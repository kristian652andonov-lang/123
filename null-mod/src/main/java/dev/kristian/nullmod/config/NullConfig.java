package dev.kristian.nullmod.config;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * World/server settings. This is a SERVER config: every world gets its own copy in
 * {@code serverconfig/null-server.toml}, NeoForge syncs it to connecting clients, and
 * defaults for new worlds can be placed in {@code defaultconfigs/}.
 */
public final class NullConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue PROGRESSION_ENABLED;
    public static final ModConfigSpec.DoubleValue PROGRESSION_SPEED;
    public static final ModConfigSpec.DoubleValue STAGE_2_MINUTES;
    public static final ModConfigSpec.DoubleValue STAGE_3_MINUTES;
    public static final ModConfigSpec.DoubleValue STAGE_4_MINUTES;
    public static final ModConfigSpec.DoubleValue STAGE_5_MINUTES;
    public static final ModConfigSpec.DoubleValue NULL_SATURATION_MINUTES;
    public static final ModConfigSpec.BooleanValue COUNT_WITHOUT_PLAYERS;

    public static final ModConfigSpec.DoubleValue EVENT_FREQUENCY;
    public static final ModConfigSpec.DoubleValue EVENT_INTENSITY;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_EVENTS;

    public static final ModConfigSpec.DoubleValue VISITOR_FREQUENCY;
    public static final ModConfigSpec.IntValue MAX_VISITORS_PER_PLAYER;
    public static final ModConfigSpec.DoubleValue NULLROOT_FREQUENCY;
    public static final ModConfigSpec.IntValue MAX_NULLROOTS_PER_PLAYER;
    public static final ModConfigSpec.BooleanValue NULLROOT_CAPTURE_LETHAL;

    public static final ModConfigSpec.DoubleValue CHAT_FREQUENCY;
    public static final ModConfigSpec.IntValue FAKE_PLAYER_COUNT;
    public static final ModConfigSpec.IntValue FAKE_PLAYER_BODIES;

    public static final ModConfigSpec.DoubleValue MOON_SPEED;

    public static final ModConfigSpec.BooleanValue WORLD_MODIFICATION;
    public static final ModConfigSpec.BooleanValue REPLACE_NATURAL_BLOCKS;
    public static final ModConfigSpec.BooleanValue GENERATE_STRUCTURES;
    public static final ModConfigSpec.DoubleValue WORLD_CORRUPTION_INTENSITY;
    public static final ModConfigSpec.IntValue BUILD_PROTECTION_RADIUS;

    public static final ModConfigSpec.DoubleValue MAX_SCREEN_SHAKE;
    public static final ModConfigSpec.DoubleValue MAX_AUDIO_CORRUPTION;
    public static final ModConfigSpec.IntValue MAX_VISUAL_EFFECTS;

    public static final ModConfigSpec.BooleanValue AUTO_NULL_TRANSITION;
    public static final ModConfigSpec.BooleanValue NULL_DIMENSION_DECAY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("How the world moves through the five stages.").push("progression");
        PROGRESSION_ENABLED = b.comment("If false the stage never advances on its own; /null stage still works.")
                .translation("null.config.progression_enabled")
                .define("enabled", true);
        PROGRESSION_SPEED = b.comment("Multiplier for how fast corruption time passes. Combined with /null speed.")
                .translation("null.config.progression_speed")
                .defineInRange("speed", 1.0, 0.0, 100.0);
        STAGE_2_MINUTES = b.comment("Minutes of corruption time before Stage 2 (UNDER MIDDLE).")
                .translation("null.config.stage_2_minutes")
                .defineInRange("stage2Minutes", 30.0, 0.5, 100000.0);
        STAGE_3_MINUTES = b.comment("Minutes of corruption time before Stage 3 (MIDDLE).")
                .translation("null.config.stage_3_minutes")
                .defineInRange("stage3Minutes", 90.0, 1.0, 100000.0);
        STAGE_4_MINUTES = b.comment("Minutes of corruption time before Stage 4 (ABOVE MIDDLE).")
                .translation("null.config.stage_4_minutes")
                .defineInRange("stage4Minutes", 180.0, 1.5, 100000.0);
        STAGE_5_MINUTES = b.comment("Minutes of corruption time before Stage 5 (NULL).")
                .translation("null.config.stage_5_minutes")
                .defineInRange("stage5Minutes", 300.0, 2.0, 100000.0);
        NULL_SATURATION_MINUTES = b.comment("Minutes after reaching NULL until it is at full strength (full player list, constant chatter).")
                .translation("null.config.null_saturation_minutes")
                .defineInRange("nullSaturationMinutes", 60.0, 1.0, 100000.0);
        COUNT_WITHOUT_PLAYERS = b.comment("Keep counting corruption time while no player is online (dedicated servers).")
                .translation("null.config.count_without_players")
                .define("countWithoutPlayers", false);
        b.pop();

        b.comment("The random event manager.").push("events");
        EVENT_FREQUENCY = b.comment("Multiplier for how often random events happen.")
                .translation("null.config.event_frequency")
                .defineInRange("frequency", 1.0, 0.0, 10.0);
        EVENT_INTENSITY = b.comment("Multiplier for how strong random events are.")
                .translation("null.config.event_intensity")
                .defineInRange("intensity", 1.0, 0.0, 2.0);
        DISABLED_EVENTS = b.comment("Event ids that must never run (see /null events).")
                .translation("null.config.disabled_events")
                .defineListAllowEmpty("disabled", List.of(), () -> "", o -> o instanceof String);
        b.pop();

        b.comment("Things that watch and things that grow.").push("entities");
        VISITOR_FREQUENCY = b.comment("Multiplier for how often Visitors appear (Stage 4+).")
                .translation("null.config.visitor_frequency")
                .defineInRange("visitorFrequency", 1.0, 0.0, 10.0);
        MAX_VISITORS_PER_PLAYER = b.comment("Most Visitors that can exist around one player at a time.")
                .translation("null.config.max_visitors")
                .defineInRange("maxVisitorsPerPlayer", 3, 0, 16);
        NULLROOT_FREQUENCY = b.comment("Multiplier for how often NULLROOT parasites grow (Stage 3+).")
                .translation("null.config.nullroot_frequency")
                .defineInRange("nullrootFrequency", 1.0, 0.0, 10.0);
        MAX_NULLROOTS_PER_PLAYER = b.comment("Most NULLROOT organisms that can grow around one player.")
                .translation("null.config.max_nullroots")
                .defineInRange("maxNullrootsPerPlayer", 8, 0, 64);
        NULLROOT_CAPTURE_LETHAL = b.comment("If false a finished capture releases the player instead of killing them.")
                .translation("null.config.nullroot_capture_lethal")
                .define("nullrootCaptureLethal", true);
        b.pop();

        b.comment("The simulated players of the final stage and the corrupted chat.").push("simulation");
        CHAT_FREQUENCY = b.comment("Multiplier for how often corrupted chat messages appear.")
                .translation("null.config.chat_frequency")
                .defineInRange("chatFrequency", 1.0, 0.0, 10.0);
        FAKE_PLAYER_COUNT = b.comment("How many simulated players the player list fills up to in NULL.")
                .translation("null.config.fake_player_count")
                .defineInRange("fakePlayerCount", 79, 0, 200);
        FAKE_PLAYER_BODIES = b.comment("Most simulated player bodies standing around one player.")
                .translation("null.config.fake_player_bodies")
                .defineInRange("fakePlayerBodies", 10, 0, 40);
        b.pop();

        b.comment("The Shattering Moon.").push("moon");
        MOON_SPEED = b.comment("Multiplier for how fast the moon cracks and shatters.")
                .translation("null.config.moon_speed")
                .defineInRange("moonSpeed", 1.0, 0.0, 10.0);
        b.pop();

        b.comment("Changes NULL makes to the world itself.").push("world");
        WORLD_MODIFICATION = b.comment("Master switch. If false NULL never places or replaces a block outside the NULL dimension; all corruption becomes visual only.")
                .translation("null.config.world_modification")
                .define("allowWorldModification", true);
        REPLACE_NATURAL_BLOCKS = b.comment("If true corruption may replace natural terrain (stone, dirt, grass, sand...). Player builds are never replaced either way.")
                .translation("null.config.replace_natural_blocks")
                .define("replaceNaturalBlocks", true);
        GENERATE_STRUCTURES = b.comment("If true NULL builds corrupted structures, terrain fragments and sky bedrock (only into air or natural terrain).")
                .translation("null.config.generate_structures")
                .define("generateStructures", true);
        WORLD_CORRUPTION_INTENSITY = b.comment("Multiplier for how much terrain corruption spreads.")
                .translation("null.config.world_corruption_intensity")
                .defineInRange("worldCorruptionIntensity", 1.0, 0.0, 4.0);
        BUILD_PROTECTION_RADIUS = b.comment("Blocks around anything a player placed (or that looks built) that corruption leaves alone.")
                .translation("null.config.build_protection_radius")
                .defineInRange("buildProtectionRadius", 8, 2, 64);
        b.pop();

        b.comment("Upper limits for client effects. Each player's own settings can only lower these.").push("client_limits");
        MAX_SCREEN_SHAKE = b.comment("Strongest screen shake any client will show (0 disables shaking).")
                .translation("null.config.max_screen_shake")
                .defineInRange("maxScreenShake", 1.0, 0.0, 1.0);
        MAX_AUDIO_CORRUPTION = b.comment("Strongest audio corruption any client will play (0 disables it).")
                .translation("null.config.max_audio_corruption")
                .defineInRange("maxAudioCorruption", 1.0, 0.0, 1.0);
        MAX_VISUAL_EFFECTS = b.comment("Most visual distortion effects a client runs at the same time.")
                .translation("null.config.max_visual_effects")
                .defineInRange("maxVisualEffects", 6, 0, 32);
        b.pop();

        b.comment("The final stage.").push("null_dimension");
        AUTO_NULL_TRANSITION = b.comment("Move players into the NULL dimension automatically when Stage 5 begins.")
                .translation("null.config.auto_null_transition")
                .define("automaticTransition", true);
        NULL_DIMENSION_DECAY = b.comment("Let sections of the NULL dimension disappear and regenerate around players.")
                .translation("null.config.null_dimension_decay")
                .define("dimensionDecay", true);
        b.pop();

        SPEC = b.build();
    }

    private NullConfig() {
    }

    public static boolean loaded() {
        return SPEC.isLoaded();
    }

    /** Reads a value, falling back to its default before the world's config has loaded. */
    public static boolean get(ModConfigSpec.BooleanValue value) {
        return SPEC.isLoaded() ? value.getAsBoolean() : value.getDefault();
    }

    public static double get(ModConfigSpec.DoubleValue value) {
        return SPEC.isLoaded() ? value.getAsDouble() : value.getDefault();
    }

    public static int get(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.getAsInt() : value.getDefault();
    }

    public static boolean eventDisabled(String id) {
        return SPEC.isLoaded() && DISABLED_EVENTS.get().contains(id);
    }

    /** World modification is on and natural terrain may be replaced. */
    public static boolean mayReplaceNatural() {
        return get(WORLD_MODIFICATION) && get(REPLACE_NATURAL_BLOCKS);
    }

    /** World modification is on and structures may be placed. */
    public static boolean mayBuildStructures() {
        return get(WORLD_MODIFICATION) && get(GENERATE_STRUCTURES);
    }
}
