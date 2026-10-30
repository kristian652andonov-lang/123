package dev.kristian.nullmod.entity.nullroot;

/** What an organism is doing right now. Synced to clients, which animate and voice it. */
public enum NullrootState {
    /** Nobody near. Swaying. */
    IDLE,
    /** A player is near; it turns toward them. */
    WATCHING,
    /** A tendril is slowly growing toward the target. */
    REACHING,
    /** It holds the target. */
    GRABBING,
    /** The tendril pulls back in. */
    RETRACTING,
    /** It was hurt: the tendril snaps back and it twitches. */
    RECOIL,
    /** A colony centre that a captured player is being pulled into. */
    ANCHORING,
    /** Shrinking back into the ground, then gone. */
    WITHERING;

    private static final NullrootState[] VALUES = values();

    public static NullrootState byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : IDLE;
    }

    /** True while a reaching tendril is out (drawn by clients). */
    public boolean tendrilOut() {
        return this == REACHING || this == GRABBING || this == RETRACTING || this == RECOIL;
    }

    /** True while it holds or anchors a captured player. */
    public boolean holding() {
        return this == GRABBING || this == ANCHORING;
    }
}
