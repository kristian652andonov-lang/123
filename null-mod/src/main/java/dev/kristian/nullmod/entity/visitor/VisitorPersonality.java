package dev.kristian.nullmod.entity.visitor;

import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.entity.SkinSet;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * How a Visitor behaves. Every Visitor stands completely still while it is being watched and
 * always faces the player it came for; the personality decides what it does otherwise and
 * what makes it go away.
 */
public enum VisitorPersonality {
    /** Stands still and watches, head and body turned to the player. Some stay for minutes. */
    OBSERVER(14.0F, 22.0F, 120, 420, new int[] {30, 8, 22, 40}),
    /** Keeps 20-35 blocks away and follows, but only while nobody is looking at it. */
    FOLLOWER(10.0F, 13.0F, 180, 480, new int[] {22, 8, 30, 40}),
    /** Gone the moment the player comes within ~16 blocks or looks straight at it. */
    DISAPPEARING(13.0F, 17.0F, 40, 240, new int[] {30, 12, 26, 32}),
    /** Appears behind the player, creeps closer while unobserved, vanishes when seen up close. */
    STALKER(2.0F, 2.6F, 60, 180, new int[] {50, 8, 27, 15}),
    /** Waits in caves and underground structures; walks off into the dark when watched too long. */
    UNDERGROUND(8.0F, 12.0F, 60, 240, new int[] {48, 10, 27, 15});

    private final float minVanishDistance;
    private final float maxVanishDistance;
    private final int minLifeSeconds;
    private final int maxLifeSeconds;
    /** Relative skin category weights: BLACK, MISSING, DISTORTED, OLD. */
    private final int[] skinWeights;

    VisitorPersonality(float minVanishDistance, float maxVanishDistance, int minLifeSeconds, int maxLifeSeconds, int[] skinWeights) {
        this.minVanishDistance = minVanishDistance;
        this.maxVanishDistance = maxVanishDistance;
        this.minLifeSeconds = minLifeSeconds;
        this.maxLifeSeconds = maxLifeSeconds;
        this.skinWeights = skinWeights;
    }

    public float rollVanishDistance(RandomSource random) {
        return Mth.lerp(random.nextFloat(), minVanishDistance, maxVanishDistance);
    }

    /** Lifetime in ticks. */
    public int rollLifetime(RandomSource random) {
        return Ticks.seconds(minLifeSeconds + random.nextInt(maxLifeSeconds - minLifeSeconds + 1));
    }

    /**
     * A skin for this personality. In the final stage fewer of them look like ordinary
     * players and more look broken.
     */
    public int rollSkin(RandomSource random, boolean finalStage) {
        int black = skinWeights[0];
        int missing = skinWeights[1];
        int distorted = skinWeights[2];
        int old = skinWeights[3];
        if (finalStage) {
            missing += 6;
            distorted += 12;
            old = Math.max(5, old - 15);
        }
        return SkinSet.weighted(random, black, missing, distorted, old);
    }

    public static VisitorPersonality byName(String name, VisitorPersonality fallback) {
        for (VisitorPersonality value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }
}
