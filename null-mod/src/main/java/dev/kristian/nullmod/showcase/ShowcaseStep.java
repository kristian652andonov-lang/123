package dev.kristian.nullmod.showcase;

import dev.kristian.nullmod.core.CorruptionStage;

/**
 * One step of {@code /null showcase}.
 *
 * @param kind    what the step does
 * @param stage   the stage it belongs to
 * @param subject the event id ({@link Kind#EVENT}), the mob variant ({@link Kind#MOBS}: one of
 *                {@link ShowcasePlan#MOBS_CORRUPTED}, {@link ShowcasePlan#MOBS_PARASITE}), the
 *                field phase ({@link Kind#FIELD}: day, cracking, shatter, night, hunt, dawn), or
 *                empty
 * @param number  1-based position of the step inside its stage
 * @param count   number of steps of its stage
 */
public record ShowcaseStep(Kind kind, CorruptionStage stage, String subject, int number, int count) {
    public enum Kind {
        /** Sets the world's stage and waits until the player is where it happens (the NULL dimension, the field). */
        STAGE,
        /** Runs one random event for the player, with the showcase hint. */
        EVENT,
        /** Corrupted or parasite mobs in view (through {@code /null mob}, when that command exists). */
        MOBS,
        /** Jumps the field's shared cycle to a phase. */
        FIELD,
        /** Puts the player inside the nearest house of the field. */
        HOUSE
    }

    /** The same step with its place inside the stage. */
    ShowcaseStep numbered(int newNumber, int newCount) {
        return new ShowcaseStep(kind, stage, subject, newNumber, newCount);
    }
}
