package dev.kristian.nullmod.entity.it;

/**
 * Client-side callbacks of {@link ItEntity}. Common code only ever sees this interface; the
 * physical client installs its implementation from {@code client.it} during start-up, so a
 * dedicated server never loads a client class. Called on the client thread only.
 */
public interface ItClientHook {
    ItClientHook NONE = new ItClientHook() {
    };

    /** Once per client tick for every IT the client knows about. */
    default void tick(ItEntity it) {
    }

    /** One of the {@code ItEntity.EVENT_*} entity events arrived from the server. */
    default void handleEvent(ItEntity it, byte event) {
    }

    /** IT left the client world (removed, out of range, logout). */
    default void removed(ItEntity it) {
    }
}
