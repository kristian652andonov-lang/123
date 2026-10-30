package dev.kristian.nullmod.event;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** All random events, keyed by id. Filled once during mod construction. */
public final class NullEventRegistry {
    private static final Map<String, NullEventDefinition> EVENTS = new LinkedHashMap<>();

    private NullEventRegistry() {
    }

    public static synchronized NullEventDefinition register(NullEventDefinition event) {
        if (EVENTS.putIfAbsent(event.id(), event) != null) {
            throw new IllegalStateException("Duplicate NULL event id " + event.id());
        }
        return event;
    }

    public static Optional<NullEventDefinition> get(String id) {
        return Optional.ofNullable(EVENTS.get(id));
    }

    public static Collection<NullEventDefinition> all() {
        return Collections.unmodifiableCollection(EVENTS.values());
    }

    public static int size() {
        return EVENTS.size();
    }
}
