package dev.kristian.nullmod.entity.nullroot;

/**
 * Client-side callbacks of {@link NullrootEntity}. Common code only ever sees this interface;
 * the physical client installs its implementation from {@code client.nullroot} during start-up,
 * so a dedicated server never loads a client class. Called on the client thread only.
 */
public interface NullrootClientHook {
    NullrootClientHook NONE = new NullrootClientHook() {
    };

    /** Once per client tick for every organism the client knows about. */
    default void tick(NullrootEntity organism) {
    }

    /** A {@code NullrootEntity.EVENT_*} entity event arrived from the server. */
    default void handleEvent(NullrootEntity organism, byte event) {
    }

    /** The organism left the client world (removed, out of range, logout). */
    default void removed(NullrootEntity organism) {
    }
}
