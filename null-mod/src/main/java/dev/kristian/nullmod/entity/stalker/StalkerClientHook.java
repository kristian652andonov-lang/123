package dev.kristian.nullmod.entity.stalker;

/**
 * Client-side callbacks of {@link StalkerEntity}. Common code only ever sees this interface; the
 * physical client installs its implementation from {@code client.stalker} during start-up, so a
 * dedicated server never loads a client class. Called on the client thread only.
 */
public interface StalkerClientHook {
    StalkerClientHook NONE = new StalkerClientHook() {
    };

    /** Once per client tick for every figure the client knows about. */
    default void tick(StalkerEntity stalker) {
    }

    /** One of the {@code StalkerEntity.EVENT_*} entity events arrived from the server. */
    default void handleEvent(StalkerEntity stalker, byte event) {
    }
}
