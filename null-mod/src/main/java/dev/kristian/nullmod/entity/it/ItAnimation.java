package dev.kristian.nullmod.entity.it;

/**
 * Client-side animation state of one IT: plain numbers the client hook advances every tick and
 * the model reads every frame (values with an {@code O} suffix are last tick's, for smooth
 * interpolation). Lives on the entity so it goes away with it; never touched on the server.
 */
public final class ItAnimation {
    public boolean started;
    public long seed;
    public int age;

    /** Smoothed horizontal speed, blocks per tick. */
    public float speed;
    public float speedO;
    /** 0 upright .. 1 galloping on all fours. */
    public float run;
    public float runO;
    /** Gait cycles, ever growing. */
    public float phase;
    public float phaseO;
    /** 0..1 bent down (at a window, seizing). */
    public float crouch;
    public float crouchO;
    /** 0..1 screaming. */
    public float scream;
    public float screamO;
    public int screamTicks;
    /** 0..1 a blade arm dragging down a wall, and where in the stroke it is. */
    public float scrape;
    public float scrapeO;
    public float scrapePhase;
    public float scrapePhaseO;
    /** How far below its usual height its head must go to reach a window (blocks). */
    public float peerDrop;
    /** 0..1 shudder after a blow, a bite, a snap. */
    public float jolt;
    /** Displayed head turn (radians, relative to the body) and pitch; they move in jerks. */
    public float headYaw;
    public float headYawO;
    public float headPitch;
    public float headPitchO;
    public int headHold;

    /** A joint briefly snapped out of place: which (index into the model's twitch list), how far, how long. */
    public int twitchJoint = -1;
    public float twitchX;
    public float twitchZ;
    public int twitchTicks;
    public int twitchLength;
    public int nextTwitch;

    /** Which limbs were on the ground last tick (bits: left foot, right foot, left hand, right hand). */
    public int planted;
    public int nextBreath;
    public int nextClick;
    public double lastX;
    public double lastZ;
    /** Where its face was when it was last drawn (world), for the kill camera and its voice. */
    public double faceX;
    public double faceY;
    public double faceZ;
    public boolean faceKnown;
    /**
     * Where its eyes were when it was last drawn (world): the point between them (0..2), the unit
     * vector from one to the other (3..5) and the unit vector they look along (6..8). Read by the
     * glow that is seen from far away.
     */
    public final double[] eye = new double[9];
    public boolean eyeKnown;

    ItAnimation() {
    }
}
