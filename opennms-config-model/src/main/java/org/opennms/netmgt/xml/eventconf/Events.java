/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.xml.eventconf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;

import org.opennms.core.xml.JaxbUtils;
import org.opennms.core.xml.ValidateUsing;
import org.opennms.netmgt.config.utils.ConfigUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectRetrievalFailureException;
import org.springframework.util.StringUtils;

@XmlRootElement(name = "events")
@XmlAccessorType(XmlAccessType.NONE)
@ValidateUsing("eventconf.xsd")
@XmlType(propOrder = {})
public class Events implements Serializable {
    private static final DefaultResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    public interface EventCallback<T> {
        public T process(T accum, Event event);
    }

    public interface EventCriteria {
        public boolean matches(Event e);
    }

    private static final long serialVersionUID = 2L;

    /**
     * Global settings for this configuration
     */
    @XmlElement(name = "global", required = false)
    private Global m_global;

    @XmlElement(name = "event", required = false)
    private List<Event> m_events = new ArrayList<>();

    @XmlElement(name = "event-file", required = false)
    private List<String> m_eventFiles = new ArrayList<>();

    @XmlTransient
    private Map<String, Events> m_loadedEventFiles = new LinkedHashMap<>();

    @XmlTransient
    private Partition m_partition;

    @XmlTransient
    private Map<String, List<Event>> m_partitionedEvents;

    @XmlTransient
    private List<Event> m_nullPartitionedEvents;

    @XmlTransient
    private Map<String, Event> m_eventsByUei = new ConcurrentSkipListMap<>();

    @XmlTransient
    private List<Event> m_wildcardEvents;

    @XmlTransient
    private EventOrdering m_ordering;

    public Global getGlobal() {
        return m_global;
    }

    public void setGlobal(final Global global) {
        m_global = global;
    }

    public List<Event> getEvents() {
        return m_events;
    }

    public void setEvents(final List<Event> events) {
        if (m_events == events) return;
        m_events.clear();
        if (events != null) m_events.addAll(events);
    }

    public void addEvent(final Event event) {
        m_events.add(event);
    }

    public boolean removeEvent(final Event event) {
        return m_events.remove(event);
    }

    public List<String> getEventFiles() {
        return m_eventFiles;
    }

    public void setEventFiles(final List<String> eventFiles) {
        if (m_eventFiles == eventFiles) return;
        m_eventFiles.clear();
        if (eventFiles != null) m_eventFiles.addAll(eventFiles);
    }

    public void addEventFile(final String eventFile) {
        m_eventFiles.add(ConfigUtils.normalizeAndInternString(eventFile));
    }

    public boolean removeEventFile(final String eventFile) {
        return m_eventFiles.remove(eventFile);
    }

    public EventOrdering getOrdering() {
        return m_ordering;
    }

    public static Resource getRelative(final Resource baseRef, final String relative) {
        try {
            if (relative.startsWith("classpath:")) {
                return RESOURCE_LOADER.getResource(relative);
            } else {
                return baseRef.createRelative(relative);
            }
        } catch (final IOException e) {
            throw new ObjectRetrievalFailureException(Resource.class, baseRef, "Resource location has a relative path, however the configResource does not reference a file, so the relative path cannot be resolved.  The location is: " + relative, null);
        }

    }

    public Map<String, Long> loadEventFiles(final Resource configResource) throws IOException {
        final Map<String, Long> lastModifiedEventFiles = new LinkedHashMap<String, Long>();
        loadEventFilesIfModified(configResource, lastModifiedEventFiles);
        return lastModifiedEventFiles;
    }

    public void loadEventFilesIfModified(final Resource configResource, final Map<String, Long> lastModifiedEventFiles) throws IOException {
        // Remove any event files that we're previously loaded, and no
        // longer appear in the list of event files
        for (Iterator<Map.Entry<String, Events>> it = m_loadedEventFiles.entrySet().iterator(); it.hasNext(); ) {
            final String eventFile = it.next().getKey();
            if (!m_eventFiles.contains(eventFile)) {
                // The event file was previously loaded and has been removed
                // from the list of event files
                it.remove();
            }
        }

        // Conditionally load or reload the event files
        for (final String eventFile : m_eventFiles) {
            final Resource eventResource = getRelative(configResource, eventFile);
            final long lastModified = eventResource.lastModified();

            // Determine whether or not the file should be loaded
            boolean shouldLoadFile = true;
            if (lastModifiedEventFiles.containsKey(eventFile)
                    && lastModifiedEventFiles.get(eventFile) == lastModified) {
                shouldLoadFile = false;
                // If we opt out to load a particular file, it must
                // be already loaded
                assert (m_loadedEventFiles.containsKey(eventFile));
            }

            // Skip any files that don't need to be loaded
            if (!shouldLoadFile) {
                continue;
            }

            lastModifiedEventFiles.put(eventFile, lastModified);

            final Events events = JaxbUtils.unmarshal(Events.class, eventResource);
            if (events.getEvents().isEmpty()) {
                throw new IllegalStateException("Uh oh! An event file " + eventResource.getFile() + " with no events has been loaded!");
            }
            if (events.getGlobal() != null) {
                throw new ObjectRetrievalFailureException(Resource.class, eventResource, "The event resource " + eventResource + " included from the root event configuration file cannot have a 'global' element", null);
            }
            if (!events.getEventFiles().isEmpty()) {
                throw new ObjectRetrievalFailureException(Resource.class, eventResource, "The event resource " + eventResource + " included from the root event configuration file cannot include other configuration files: " + StringUtils.collectionToCommaDelimitedString(events.getEventFiles()), null);
            }

            m_loadedEventFiles.put(eventFile, events);
        }

        // Re-order the loaded event files to match the order specified in the root configuration
        final Map<String, Events> orderedAndLoadedEventFiles = new LinkedHashMap<>();
        for (String eventFile : m_eventFiles) {
            final Events loadedEvents = m_loadedEventFiles.get(eventFile);
            if (loadedEvents != null) {
                orderedAndLoadedEventFiles.put(eventFile, loadedEvents);
            }
        }
        m_loadedEventFiles = orderedAndLoadedEventFiles;
    }

    public boolean isSecureTag(final String tag) {
        return m_global != null && m_global.isSecureTag(tag);
    }

    private void partitionEvents(final Partition partition) {
        m_partition = partition;

        m_partitionedEvents = new LinkedHashMap<>();
        m_nullPartitionedEvents = new ArrayList<>();

        for (final Event event : m_events) {
            final List<String> keys = partition.group(event);
            if (keys == null) {
                m_nullPartitionedEvents.add(event);
            } else {
                for (final String key : keys) {
                    List<Event> events = m_partitionedEvents.computeIfAbsent(key, k -> new ArrayList<Event>());
                    events.add(event);
                }
            }
        }

        // Place all the partitioned and nullPartitioned event definitions in priority order.
        m_partitionedEvents.values().forEach(Collections::sort);
        Collections.sort(m_nullPartitionedEvents);
    }


    public Event findFirstMatchingEvent(final org.opennms.netmgt.xml.event.Event matchingEvent) {
        // Atempt to match the event definition by UEI
        final String ueiToMatch = matchingEvent.getUei();
        if (ueiToMatch != null) {
            final Event matchedEvent = m_eventsByUei.get(ueiToMatch);
            if (matchedEvent != null) {
                return matchedEvent;
            }
        }

        // If the UEI match failed, fallback to searching with the matchers through the partitions
        final String key = m_partition.group(matchingEvent);
        Collection<Event> potentialMatches = m_nullPartitionedEvents;
        if (key != null) {
            final List<Event> events = m_partitionedEvents.get(key);
            if (events != null) {
                potentialMatches = new TreeSet<Event>(m_nullPartitionedEvents);
                potentialMatches.addAll(events);
            }
        }

        for (final Event event : potentialMatches) {
            if (event.matches(matchingEvent).matched()) {
                return event;
            }
        }

        for (Events subEvents : m_loadedEventFiles.values()) {
            final Event event = subEvents.findFirstMatchingEvent(matchingEvent);
            if (event != null) {
                return event;
            }
        }

        return null;
    }

    public Set<Event> findMatchingEvents(final EventCriteria criteria) {
        final Set<Event> results = new HashSet<>();
        for (final Event event : m_events) {
            if (criteria.matches(event)) {
                results.add(event);
            }
        }

        for (final Entry<String, Events> loadedEvents : m_loadedEventFiles.entrySet()) {
            final Events events = loadedEvents.getValue();
            final Set<Event> moreResults = events.findMatchingEvents(criteria);
            if (moreResults != null) {
                results.addAll(moreResults);
            }
        }

        return results;
    }

    public Event findFirstMatchingEvent(final EventCriteria criteria) {
        for (final Event event : m_events) {
            if (criteria.matches(event)) {
                return event;
            }
        }

        for (final Entry<String, Events> loadedEvents : m_loadedEventFiles.entrySet()) {
            final Events events = loadedEvents.getValue();
            final Event result = events.findFirstMatchingEvent(criteria);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    public <T> T forEachEvent(final T initial, final EventCallback<T> callback) {
        T result = initial;
        for (final Event event : m_events) {
            result = callback.process(result, event);
        }

        for (final Entry<String, Events> loadedEvents : m_loadedEventFiles.entrySet()) {
            final Events events = loadedEvents.getValue();
            result = events.forEachEvent(result, callback);
        }

        return result;
    }

    public void initialize(final Partition partition, final EventOrdering eventOrdering) {
        m_ordering = eventOrdering;

        for (final Event event : m_events) {
            event.initialize(m_ordering.next());
        }

        partitionEvents(partition);

        for (final Events events : m_loadedEventFiles.values()) {
            events.initialize(partition, m_ordering.subsequence());
        }

        // roll up all prioritized events and sort all events by priority
        // must be done after event.initialize() has been called to set the event.index
        List<Event> prioritizedEvents = getPrioritizedEvents();

        m_events.removeAll(prioritizedEvents); // prevent duplicates
        m_events.addAll(prioritizedEvents);
        m_events.sort(Comparator.naturalOrder());

        // Also add to unpartitioned events for first crack when not using a UEI match
        m_nullPartitionedEvents.removeAll(prioritizedEvents); // prevent duplicates
        m_nullPartitionedEvents.addAll(prioritizedEvents);
        m_nullPartitionedEvents.sort(Comparator.naturalOrder());

        indexEventsByUei();
    }

    // Recurse through the configuration and return Event Definitions with priority > 0
    private List<Event> getPrioritizedEvents() {
        List<Event> prioritizedEvents = m_events.stream().filter(e -> e.getPriority() > 0).collect(Collectors.toList());

        for (final Events eventsFile : m_loadedEventFiles.values()) {
            prioritizedEvents.addAll(eventsFile.getPrioritizedEvents());
        }

        return prioritizedEvents;
    }

    private void indexEventsByUei() {
        m_eventsByUei.clear();

        final Set<String> ueisWithManyEventDefinitions = new HashSet<>();

        // Build a map of UEI to Event definition
        forEachEvent((e) -> {
            final String uei = e.getUei();
            if (uei == null) {
                // Skip events with no UEI
                return;
            }

            if (m_eventsByUei.putIfAbsent(uei, e) != null) {
                // Keep trap of the UEIs that have many event definitions
                ueisWithManyEventDefinitions.add(uei);
            }
        });

        // Remove UEIs for which there are many event definitions
        ueisWithManyEventDefinitions.forEach(uei -> {
            m_eventsByUei.remove(uei);
            // Also do this recursively
            for (final Events events : m_loadedEventFiles.values()) {
                events.m_eventsByUei.remove(uei);
            }
        });

        // Now remove event definitions from the index if any
        // mask elements from any other event definitions match
        // the UEI.
        //
        // This allows mask elements to be used against incoming
        // event instances that already have a UEI set, provided
        // that they include a UEI match. In this case the associated
        // event definition will no longer be found in the
        // UEI to Event definition index.

        // 1) Gather the set of matchers from all event definitions
        // that are used to match a UEI
        final List<EventMatcher> matchers = new ArrayList<>();
        forEachEvent((e) -> {
            final Mask mask = e.getMask();
            if (mask != null) {
                final Maskelement ueiMask = mask.getMaskElement("uei");
                if (ueiMask != null) {
                    matchers.add(ueiMask.constructMatcher());
                }
            }
        });

        // 2) Remove event definition from the index if they are matched
        // by any of the known UEI matchers.
        if (matchers.size() >= 1) {
            events: for (Iterator<Entry<String, Event>> it = m_eventsByUei.entrySet().iterator(); it.hasNext(); ) {
                final Entry<String, Event> entry = it.next();
                for (EventMatcher matcher : matchers) {
                    // Build an event instance
                    org.opennms.netmgt.xml.event.Event eventToMatch = new org.opennms.netmgt.xml.event.Event();
                    // The UEI is the only field the matcher should check
                    eventToMatch.setUei(entry.getKey());
                    if (matcher.matches(eventToMatch).matched()) {
                        // We got a match, remove this event definition from the index
                        it.remove();
                        continue events;
                    }
                }
            }
        }
    }

    public Events getLoadEventsByFile(final String relativePath) {
        return m_loadedEventFiles.get(relativePath);
    }

    public void addLoadedEventFile(final String relativePath, final Events events) {
        if (!m_eventFiles.contains(relativePath)) {
            m_eventFiles.add(relativePath);
        }
        m_loadedEventFiles.put(relativePath, events);
    }

    public void removeLoadedEventFile(final String relativePath) {
        m_eventFiles.remove(relativePath);
        m_loadedEventFiles.remove(relativePath);
    }

    public void saveEvents(final Resource resource) {
        final StringWriter stringWriter = new StringWriter();
        JaxbUtils.marshal(this, stringWriter);

        if (stringWriter.toString() != null) {
            File file;
            try {
                file = resource.getFile();
            } catch (final IOException e) {
                throw new DataAccessResourceFailureException("Event resource '" + resource + "' is not a file resource and cannot be saved.  Nested exception: " + e, e);
            }
            try (final OutputStream fos = new FileOutputStream(file);
                 final Writer fileWriter = new OutputStreamWriter(fos, StandardCharsets.UTF_8);) {
                fileWriter.write(stringWriter.toString());
            } catch (final Exception e) {
                throw new DataAccessResourceFailureException("Event file '" + file + "' could not be opened.  Nested exception: " + e, e);
            }
        }
    }

    public void save(final Resource resource) {
        for (final Entry<String, Events> entry : m_loadedEventFiles.entrySet()) {
            final String eventFile = entry.getKey();
            final Events events = entry.getValue();

            final Resource eventResource = getRelative(resource, eventFile);
            events.save(eventResource);

        }

        saveEvents(resource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(m_global, m_events, m_eventFiles);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Events) {
            final Events that = (Events) obj;
            return Objects.equals(this.m_global, that.m_global) &&
                    Objects.equals(this.m_events, that.m_events) &&
                    Objects.equals(this.m_eventFiles, that.m_eventFiles);
        }
        return false;
    }

    private void forEachEvent(Consumer<Event> callback) {
        forEachEvent(callback, this, null);
    }

    private void forEachEvent(Consumer<Event> callback, Events eventFile, Set<String> filesProcessed) {
        for (final Event event : eventFile.m_events) {
            callback.accept(event);
        }

        if (filesProcessed == null) {
            filesProcessed = new HashSet<>();
        }

        for (final Entry<String, Events> loadedEvents : m_loadedEventFiles.entrySet()) {
            if (filesProcessed.contains(loadedEvents.getKey())) {
                // We already processed this file, don't recurse - avoid stack overflows
                continue;
            }
            filesProcessed.add(loadedEvents.getKey());
            forEachEvent(callback, loadedEvents.getValue(), filesProcessed);
        }
    }

    /**
     * Quick-look for Events by UEI with the caveat that not all  Events can be looked up this way.
     *
     * This function can be used a first pass when attempting to lookup events by UEI. The caller should
     * fall back to other means if no match is found - having no results returned by this function
     * does not necessarily mean that an Event with the given UEI does not exist.
     *
     * @param uei UEI to lookup
     * @return matching Event, or {@link null} if none was found in the index
     */
    public Event getEventByUeiOptimistic(String uei) {
        return m_eventsByUei.get(uei);
    }
}
