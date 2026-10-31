package dev.kristian.nullmod.entity.it;

/**
 * Every number that decides how IT moves, hunts, kills and leaves. These are the candidates for
 * the server config; until they move there they live here, in one place.
 */
public final class ItTuning {
    // ------------------------------------------------------------------ movement

    /** Ground speed while it runs at its target, blocks per tick (a sprinting player manages 0.28). */
    public static final double CHASE_SPEED = 0.78;
    /**
     * It eases off as it closes in, but always runs at least this much faster than whoever it runs
     * down (blocks per tick), so a player sprinting straight away is caught like one standing still.
     */
    public static final double CLOSING_SPEED = 0.15;
    /** How fast it gets up to speed, blocks per tick per tick. */
    public static final double ACCELERATION = 0.06;
    /** The slow lope while it circles a house. */
    public static final double PROWL_SPEED = 0.17;
    /** Leaving into the dark. */
    public static final double LEAVE_SPEED = 0.42;
    /** Degrees per tick it can turn at full speed (it is heavy) and when slow. */
    public static final float TURN_FAST = 9.0F;
    public static final float TURN_SLOW = 26.0F;
    /** Upward speed of a leap over something it cannot step over (about three blocks high). */
    public static final double JUMP_VELOCITY = 0.75;
    /** It steps over fences, hay bales and anything else this high without slowing down. */
    public static final float STEP_HEIGHT = 1.6F;
    /** Seconds without real progress while it wants to move before it gives up on that path. */
    public static final int STUCK_SECONDS = 3;

    // ------------------------------------------------------------------ the hunt

    /** How long a hunt lasts when nobody says (summon commands): 100 seconds. */
    public static final int DEFAULT_HUNT_TICKS = 20 * 100;
    /** It stands at the horizon and howls this long before it comes. */
    public static final int ARRIVE_TICKS = 60;
    /** How often it reconsiders who to hunt, ticks. */
    public static final int RECHECK_TICKS = 10;
    /** How long a shelter test of a player standing still is reused, ticks. */
    public static final int SHELTER_CACHE_TICKS = 10;
    /** Horizontal distance from its centre at which it seizes a player. */
    public static final double GRAB_REACH = 3.2;
    /** It reaches this far up for a player, and this far down into a pit. */
    public static final double GRAB_RISE = 6.5;
    public static final double GRAB_DROP = 5.0;
    /** How much higher it reaches with a leap, for somebody just out of reach above it (a leap clears about 3.5 blocks). */
    public static final double LEAP_RISE = 3.2;
    /** Players are left alone this long after they join, respawn or arrive in its world. */
    public static final int JOIN_GRACE_TICKS = 20 * 5;
    public static final int RESPAWN_GRACE_TICKS = 20 * 10;
    /** A player who lived through being seized (a totem) is left alone this long. */
    public static final int RELEASE_GRACE_TICKS = 20 * 8;
    /** Damage the blow through the chest does on its own (never lethal: the end of the kill is). */
    public static final float PIERCE_DAMAGE = 6.0F;

    // ------------------------------------------------------------------ prowling a house

    /** Gap between the walls of the house it circles and its body, blocks. */
    public static final double PROWL_GAP = 2.2;
    /** Ticks it keeps its face to a window, at least and at most. */
    public static final int WINDOW_MIN_TICKS = 70;
    public static final int WINDOW_MAX_TICKS = 130;
    /** Ticks between two scrapes of its blades over a wall. */
    public static final int SCRAPE_INTERVAL = 18;

    // ------------------------------------------------------------------ leaving

    /** Once this far from every player while leaving, it is gone. */
    public static final double LEAVE_DISTANCE = 80.0;
    /** And gone after this long whatever happens. */
    public static final int LEAVE_TIMEOUT_TICKS = 20 * 40;

    // ------------------------------------------------------------------ shelter test

    /** A room counts as shelter only if it fits in this many blocks around the player... */
    public static final int SHELTER_RADIUS = 12;
    public static final int SHELTER_UP = 10;
    public static final int SHELTER_DOWN = 6;
    /** ...and has at most this many air cells. */
    public static final int SHELTER_MAX_CELLS = 2400;

    private ItTuning() {
    }
}
