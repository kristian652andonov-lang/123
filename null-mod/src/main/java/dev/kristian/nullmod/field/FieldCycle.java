package dev.kristian.nullmod.field;

import net.minecraft.nbt.CompoundTag;

/**
 * The day and night of the field, without any world attached (so it can be tested on its own):
 * <pre>
 * DAY (the sun cracks) -> SHATTER (it breaks, the light dies) -> NIGHT (the countdown)
 *     -> HUNT (until IT stops hunting) -> DAWN (the light returns) -> DAY ...
 * </pre>
 * Every phase lasts its {@link FieldTuning} length, except the hunt, which ends when the caller says
 * so (or, failing that, after its length plus {@link FieldTuning#HUNT_WATCHDOG_TICKS}).
 */
public final class FieldCycle {
    public enum Phase {
        DAY, SHATTER, NIGHT, HUNT, DAWN;

        private static final Phase[] VALUES = values();

        public int id() {
            return ordinal();
        }

        public static Phase byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : DAY;
        }

        /** Phases in which the sun is gone and the field is dark. */
        public boolean dark() {
            return this == SHATTER || this == NIGHT || this == HUNT;
        }
    }

    private Phase phase = Phase.DAY;
    private int elapsed;
    private int cycle;

    public Phase phase() {
        return phase;
    }

    /** Ticks spent in the current phase. */
    public int elapsed() {
        return elapsed;
    }

    /** How many full days have passed. */
    public int cycle() {
        return cycle;
    }

    /** Nominal length of a phase in ticks (for the hunt, the hunt time IT is given). */
    public static int length(Phase phase) {
        return switch (phase) {
            case DAY -> FieldTuning.DAY_TICKS;
            case SHATTER -> FieldTuning.SHATTER_TICKS;
            case NIGHT -> FieldTuning.NIGHT_TICKS;
            case HUNT -> FieldTuning.HUNT_TICKS;
            case DAWN -> FieldTuning.DAWN_TICKS;
        };
    }

    public int length() {
        return length(phase);
    }

    /** Ticks left in the current phase (0 once it is due to end). */
    public int remaining() {
        return Math.max(0, length() - elapsed);
    }

    /**
     * Advances one tick.
     *
     * @param huntOver for the hunt: true once IT has stopped hunting (or is gone)
     * @return the phase that just began, or null if the phase did not change
     */
    public Phase tick(boolean huntOver) {
        elapsed++;
        Phase next = switch (phase) {
            case DAY, SHATTER, NIGHT, DAWN -> elapsed >= length() ? following(phase) : null;
            case HUNT -> huntOver || elapsed >= FieldTuning.HUNT_TICKS + FieldTuning.HUNT_WATCHDOG_TICKS ? Phase.DAWN : null;
        };
        if (next != null) {
            if (phase == Phase.DAWN) {
                cycle++;
            }
            set(next, 0);
        }
        return next;
    }

    /** The phase that follows another one. */
    public static Phase following(Phase phase) {
        return switch (phase) {
            case DAY -> Phase.SHATTER;
            case SHATTER -> Phase.NIGHT;
            case NIGHT -> Phase.HUNT;
            case HUNT -> Phase.DAWN;
            case DAWN -> Phase.DAY;
        };
    }

    public void set(Phase newPhase, int newElapsed) {
        phase = newPhase;
        elapsed = Math.max(0, newElapsed);
    }

    /** A fresh field: the first morning. */
    public void reset() {
        phase = Phase.DAY;
        elapsed = 0;
        cycle = 0;
    }

    public void save(CompoundTag tag) {
        tag.putInt("phase", phase.id());
        tag.putInt("elapsed", elapsed);
        tag.putInt("cycle", cycle);
    }

    public void load(CompoundTag tag) {
        phase = Phase.byId(tag.getInt("phase"));
        elapsed = Math.max(0, Math.min(tag.getInt("elapsed"), length(phase) + FieldTuning.HUNT_WATCHDOG_TICKS));
        cycle = Math.max(0, tag.getInt("cycle"));
    }
}
