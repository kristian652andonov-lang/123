package dev.kristian.nullmod.entity.stalker;

/**
 * Client-side animation state of one figure: smoothed values the model reads (previous and
 * current tick, so it can interpolate by the partial tick) and the clocks of its sounds. Plain
 * data in common code so the entity can own it; only {@code client.stalker} writes or reads it.
 */
public final class StalkerAnimation {
    /** -1..1: how far it leans out from behind something, to its right (-) or left (+). */
    public float peek;
    public float peekO;
    /** 0..1: one hand raised flat against a window. */
    public float glass;
    public float glassO;
    /** 0..1: holding the pickaxe ready. */
    public float dig;
    public float digO;
    /** Ticks since the current pickaxe swing started, or -1 between swings. */
    public int swingTicks = -1;
    /** 0..1: bent under a ceiling too low for it. */
    public float hunch;
    public float hunchO;
    /** 0..1: a sudden twitch of the head, dying away. */
    public float twitch;
    public float twitchO;
    /** Distance walked since its last footfall sound. */
    public float stepDistance;
    /** Client ticks until it breathes against the glass again. */
    public int breathCooldown = 30;

    /** Copies every current value into its previous one; called at the start of each client tick. */
    public void rollOver() {
        peekO = peek;
        glassO = glass;
        digO = dig;
        hunchO = hunch;
        twitchO = twitch;
    }
}
