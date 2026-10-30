package dev.kristian.nullmod.event;

import dev.kristian.nullmod.core.CorruptionStage;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.sounds.SoundEvent;

/**
 * One random event. Every event has an id, a stage range, a weight (its probability
 * relative to the other eligible events), a per-player cooldown, an optional world-wide
 * cooldown and a duration, plus any of: a server-side world action, a client effect and a
 * sound. When the event fires the action runs first; if it reports failure nothing else
 * happens and the scheduler tries another event.
 */
public final class NullEventDefinition {
    /** Common weights so event lists read consistently. */
    public static final int COMMON = 100;
    public static final int UNCOMMON = 45;
    public static final int RARE = 14;
    public static final int VERY_RARE = 4;
    public static final int EXTREMELY_RARE = 1;

    private final String id;
    private final CorruptionStage minStage;
    private final CorruptionStage maxStage;
    private final int weight;
    private final int[] stageWeights;
    private final int cooldown;
    private final int globalCooldown;
    private final int duration;
    private final Set<EventTag> tags;
    private final EventPlace place;
    @Nullable
    private final EventAction action;
    @Nullable
    private final String clientEffect;
    @Nullable
    private final Supplier<? extends SoundEvent> sound;
    private final float soundVolume;
    private final float soundPitch;

    private NullEventDefinition(Builder b) {
        this.id = b.id;
        this.minStage = b.minStage;
        this.maxStage = b.maxStage;
        this.weight = b.weight;
        this.stageWeights = b.stageWeights.clone();
        this.cooldown = b.cooldown;
        this.globalCooldown = b.globalCooldown;
        this.duration = b.duration;
        this.tags = b.tags.isEmpty() ? EnumSet.noneOf(EventTag.class) : EnumSet.copyOf(b.tags);
        this.place = b.place;
        this.action = b.action;
        this.clientEffect = b.clientEffect;
        this.sound = b.sound;
        this.soundVolume = b.soundVolume;
        this.soundPitch = b.soundPitch;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public CorruptionStage minStage() {
        return minStage;
    }

    public CorruptionStage maxStage() {
        return maxStage;
    }

    public int weight() {
        return weight;
    }

    /** Weight at a stage, or 0 when the stage is outside the event's range. */
    public int weightAt(CorruptionStage stage) {
        if (!stage.isBetween(minStage, maxStage)) {
            return 0;
        }
        return weight * stageWeights[stage.number() - 1] / 100;
    }

    public int cooldown() {
        return cooldown;
    }

    public int globalCooldown() {
        return globalCooldown;
    }

    public int duration() {
        return duration;
    }

    public Set<EventTag> tags() {
        return tags;
    }

    public boolean has(EventTag tag) {
        return tags.contains(tag);
    }

    public EventPlace place() {
        return place;
    }

    @Nullable
    public EventAction action() {
        return action;
    }

    @Nullable
    public String clientEffect() {
        return clientEffect;
    }

    @Nullable
    public Supplier<? extends SoundEvent> sound() {
        return sound;
    }

    public float soundVolume() {
        return soundVolume;
    }

    public float soundPitch() {
        return soundPitch;
    }

    @Override
    public String toString() {
        return "NullEvent[" + id + " " + minStage.number() + "-" + maxStage.number() + " w" + weight + "]";
    }

    public static final class Builder {
        private final String id;
        private CorruptionStage minStage = CorruptionStage.START;
        private CorruptionStage maxStage = CorruptionStage.NULL;
        private int weight = COMMON;
        private final int[] stageWeights = {100, 100, 100, 100, 100};
        private int cooldown = 20 * 60;
        private int globalCooldown;
        private int duration = 40;
        private final Set<EventTag> tags = EnumSet.noneOf(EventTag.class);
        private EventPlace place = EventPlace.ANYWHERE;
        @Nullable
        private EventAction action;
        @Nullable
        private String clientEffect;
        @Nullable
        private Supplier<? extends SoundEvent> sound;
        private float soundVolume = 1.0F;
        private float soundPitch = 1.0F;

        private Builder(String id) {
            if (!id.matches("[a-z0-9_.]+")) {
                throw new IllegalArgumentException("Bad NULL event id: " + id);
            }
            this.id = id;
        }

        public Builder stages(CorruptionStage min, CorruptionStage max) {
            if (min.number() > max.number()) {
                throw new IllegalArgumentException(id + ": min stage after max stage");
            }
            this.minStage = min;
            this.maxStage = max;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = Math.max(0, weight);
            return this;
        }

        /**
         * Per-stage weight percentages for stages 1..5 (100 = unchanged), for events that
         * should become more or less common as the world decays.
         */
        public Builder stageWeights(int s1, int s2, int s3, int s4, int s5) {
            stageWeights[0] = s1;
            stageWeights[1] = s2;
            stageWeights[2] = s3;
            stageWeights[3] = s4;
            stageWeights[4] = s5;
            return this;
        }

        /** Per-player cooldown in ticks. */
        public Builder cooldown(int ticks) {
            this.cooldown = Math.max(0, ticks);
            return this;
        }

        /** Cooldown in ticks shared by all players (for world-changing events). */
        public Builder globalCooldown(int ticks) {
            this.globalCooldown = Math.max(0, ticks);
            return this;
        }

        public Builder duration(int ticks) {
            this.duration = Math.max(1, ticks);
            return this;
        }

        public Builder tags(EventTag... tags) {
            this.tags.addAll(Set.of(tags));
            return this;
        }

        public Builder place(EventPlace place) {
            this.place = Objects.requireNonNull(place);
            return this;
        }

        public Builder action(EventAction action) {
            this.action = action;
            return this;
        }

        /** Client effect id sent to the player when the event fires. */
        public Builder clientEffect(String effectId) {
            this.clientEffect = effectId;
            return this;
        }

        public Builder sound(Supplier<? extends SoundEvent> sound, float volume, float pitch) {
            this.sound = sound;
            this.soundVolume = volume;
            this.soundPitch = pitch;
            return this;
        }

        public NullEventDefinition build() {
            if (action == null && clientEffect == null && sound == null) {
                throw new IllegalStateException(id + ": an event needs an action, a client effect or a sound");
            }
            return new NullEventDefinition(this);
        }

        /** Builds and adds the event to {@link NullEventRegistry}. */
        public NullEventDefinition register() {
            return NullEventRegistry.register(build());
        }
    }
}
