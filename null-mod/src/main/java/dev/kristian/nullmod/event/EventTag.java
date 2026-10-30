package dev.kristian.nullmod.event;

/**
 * What an event touches. Tags decide which config switches and multipliers apply, so an
 * administrator who turns off world modification never gets a block-changing event.
 */
public enum EventTag {
    /** Plays horror audio on the client. */
    AUDIO,
    /** Visual distortion on the client. */
    VISUAL,
    /** Corrupted chat; weight scales with the chat frequency setting. */
    CHAT,
    /** Replaces natural terrain blocks; needs world modification + natural block replacement. */
    WORLD,
    /** Places blocks into air (structures, fragments, sky bedrock); needs world modification + structures. */
    STRUCTURE,
    /** Spawns or moves Visitors; weight scales with the visitor frequency setting. */
    VISITOR,
    /** Grows NULLROOT organisms; weight scales with the nullroot frequency setting. */
    NULLROOT,
    /** Touches the simulated player list; weight scales with the chat frequency setting. */
    FAKE_PLAYER,
    /** Concerns the moon. */
    MOON,
    /** Concerns the NULL dimension. */
    DIMENSION,
    /** Flashes, freezes or other effects a client may soften under its accessibility settings. */
    INTENSE
}
