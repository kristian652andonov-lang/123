package dev.kristian.nullmod.core;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The five stages of NULL: STATIC, FRACTURE, INFESTATION, VISITATION and NULL. Stages only
 * ever move forward on their own; an administrator can move them in either direction with
 * {@code /null stage}. The constant names and ids are the original ones and stay as they are,
 * because saved worlds and configs refer to them.
 */
public enum CorruptionStage {
    START(1, "start", "STATIC"),
    UNDER_MIDDLE(2, "under_middle", "FRACTURE"),
    MIDDLE(3, "middle", "INFESTATION"),
    ABOVE_MIDDLE(4, "above_middle", "VISITATION"),
    NULL(5, "null", "NULL");

    private static final CorruptionStage[] VALUES = values();

    private final int number;
    private final String id;
    private final String title;

    CorruptionStage(int number, String id, String title) {
        this.number = number;
        this.id = id;
        this.title = title;
    }

    public static CorruptionStage byNumber(int number) {
        return VALUES[Mth.clamp(number, 1, VALUES.length) - 1];
    }

    public int number() {
        return number;
    }

    public String id() {
        return id;
    }

    /** Upper-case name, e.g. "FRACTURE". */
    public String title() {
        return title;
    }

    public Component displayName() {
        return Component.translatable("null.stage." + id);
    }

    public boolean atLeast(CorruptionStage other) {
        return number >= other.number;
    }

    public boolean atMost(CorruptionStage other) {
        return number <= other.number;
    }

    public boolean isBetween(CorruptionStage min, CorruptionStage max) {
        return number >= min.number && number <= max.number;
    }

    public boolean isFinal() {
        return this == NULL;
    }

    public CorruptionStage next() {
        return byNumber(number + 1);
    }

    public CorruptionStage previous() {
        return byNumber(number - 1);
    }
}
