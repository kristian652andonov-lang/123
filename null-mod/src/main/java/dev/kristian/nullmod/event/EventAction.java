package dev.kristian.nullmod.event;

/** Server-side part of an event. Returns false if it could not happen (no valid spot, etc.). */
@FunctionalInterface
public interface EventAction {
    boolean run(EventContext context);
}
