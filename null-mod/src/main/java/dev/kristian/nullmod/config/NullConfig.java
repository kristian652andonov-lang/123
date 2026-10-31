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
    public static final ModConfigSpec.DoubleValue STAGE_2_NIGHTS;
    public static final ModConfigSpec.DoubleValue STAGE_3_NIGHTS;
    public static final ModConfigSpec.DoubleValue STAGE_4_NIGHTS;
    public static final ModConfigSpec.DoubleValue STAGE_5_NIGHTS;
    public static final ModConfigSpec.DoubleValue HARVEST_SURVIVAL_MINUTES;
    public static final ModConfigSpec.DoubleValue NULL_FULL_STRENGTH_MINUTES;
    public static final ModConfigSpec.BooleanValue COUNT_WITHOUT_PLAYERS;

    public static final ModConfigSpec.DoubleValue EVENT_FREQUENCY;
    public static final ModConfigSpec.DoubleValue EVENT_INTENSITY;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_EVENTS;
    public static final ModConfigSpec.DoubleValue EVENT_MAX_SPEED_BOOST;

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

    // ---- "field" and "it": Stage 6 (HARVEST) ----
    public static final ModConfigSpec.IntValue FIELD_DAY_SECONDS;
    public static final ModConfigSpec.IntValue FIELD_COUNTDOWN_SECONDS;
    public static final ModConfigSpec.IntValue FIELD_HUNT_SECONDS;
    public static final ModConfigSpec.IntValue FIELD_IT_DISTANCE;
    public static final ModConfigSpec.DoubleValue IT_CHASE_SPEED;
    public static final ModConfigSpec.DoubleValue IT_CLOSING_SPEED;
    public static final ModConfigSpec.DoubleValue IT_PIERCE_DAMAGE;
    // ---- end of "field" and "it" ----

    // ---- "mobs": corrupted and parasite versions of mobs (entity.variant) ----
    public static final ModConfigSpec.BooleanValue MOB_VARIANTS_ENABLED;
    public static final ModConfigSpec.DoubleValue MOB_CORRUPTED_FREQUENCY;
    public static final ModConfigSpec.DoubleValue MOB_PARASITE_FREQUENCY;
    public static final ModConfigSpec.BooleanValue MOB_PARASITE_SPREADING;
    public static final ModConfigSpec.IntValue MOB_MAX_PER_PLAYER_AREA;
    public static final ModConfigSpec.IntValue MOB_MAX_PER_WORLD;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOB_DENYLIST;
    // ---- end of "mobs" ----

    // ---- "stalkers": the figures of Stages 1-4 (entity.stalker) ----
    public static final ModConfigSpec.BooleanValue STALKERS_ENABLED;
    public static final ModConfigSpec.DoubleValue STALKER_FREQUENCY;
    public static final ModConfigSpec.BooleanValue STALKER_DOOR_OPENING;
    public static final ModConfigSpec.BooleanValue STALKER_WINDOW_BREAKING;
    public static final ModConfigSpec.BooleanValue STALKER_MINER_DIGGING;
    // ---- end of "stalkers" ----

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("How the world moves through the six stages. Stages 2-5 begin after in-game nights, counted from when NULL",
                "began in the world: a night has passed when dawn comes, and sleeping through it counts. Stage 6 (HARVEST)",
                "begins when a player survives long enough in the NULL dimension. Operators can change these with /null pace.").push("progression");
        PROGRESSION_ENABLED = b.comment("If false the stage never advances on its own; /null stage still works.")
                .translation("null.config.progression_enabled")
                .define("enabled", true);
        PROGRESSION_SPEED = b.comment("Multiplier for how fast NULL counts in-game time (the nights and the survival time in NULL). Combined with /null speed.")
                .translation("null.config.progression_speed")
                .defineInRange("speed", 1.0, 0.0, 100.0);
        STAGE_2_NIGHTS = b.comment("In-game nights until Stage 2 (FRACTURE). Each stage must begin later than the one before.")
                .translation("null.config.stage_2_nights")
                .defineInRange("stage2Nights", 3.0, 0.1, 10000.0);
        STAGE_3_NIGHTS = b.comment("In-game nights until Stage 3 (INFESTATION).")
                .translation("null.config.stage_3_nights")
                .defineInRange("stage3Nights", 6.0, 0.1, 10000.0);
        STAGE_4_NIGHTS = b.comment("In-game nights until Stage 4 (VISITATION).")
                .translation("null.config.stage_4_nights")
                .defineInRange("stage4Nights", 10.0, 0.1, 10000.0);
        STAGE_5_NIGHTS = b.comment("In-game nights until Stage 5 (NULL), when everyone is taken into the NULL dimension.")
                .translation("null.config.stage_5_nights")
                .defineInRange("stage5Nights", 15.0, 0.1, 10000.0);
        HARVEST_SURVIVAL_MINUTES = b.comment("Minutes a player has to survive in the NULL dimension, without dying or leaving it, for Stage 6 (HARVEST) to begin.",
                        "The time only runs while progression runs, times the speed settings.")
                .translation("null.config.harvest_survival_minutes")
                .defineInRange("harvestSurvivalMinutes", 5.0, 0.25, 1440.0);
        NULL_FULL_STRENGTH_MINUTES = b.comment("Minutes of in-game time after Stage 5 (NULL) began until it is at full strength (full player list, constant chatter).")
                .translation("null.config.null_full_strength_minutes")
                .defineInRange("nullFullStrengthMinutes", 5.0, 0.5, 1440.0);
        COUNT_WITHOUT_PLAYERS = b.comment("Keep counting in-game time while no player is online (dedicated servers).")
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
        EVENT_MAX_SPEED_BOOST = b.comment("When /null speed and progression.speed make corruption time pass faster, random events,",
                        "world corruption, NULLROOT, Visitors and the corrupted chat come more often by the same factor, but at most",
                        "this many times as often (1 = never faster; slower speeds keep the normal pace).")
                .translation("null.config.event_max_speed_boost")
                .defineInRange("maxSpeedBoost", 6.0, 1.0, 6.0);
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

        b.comment("Stage 5: the NULL dimension.").push("null_dimension");
        AUTO_NULL_TRANSITION = b.comment("Move players into the NULL dimension automatically when Stage 5 begins.")
                .translation("null.config.auto_null_transition")
                .define("automaticTransition", true);
        NULL_DIMENSION_DECAY = b.comment("Let sections of the NULL dimension disappear and regenerate around players.")
                .translation("null.config.null_dimension_decay")
                .define("dimensionDecay", true);
        b.pop();

        // ---- "field" and "it": Stage 6 (HARVEST) ----
        b.comment("Stage 6 (HARVEST): the endless wheat field. Everyone in it shares one day and night.").push("field");
        FIELD_DAY_SECONDS = b.comment("Seconds of day in the field while the sun slowly cracks, until it shatters.")
                .translation("null.config.field_day_seconds")
                .defineInRange("daySeconds", 210, 30, 3600);
        FIELD_COUNTDOWN_SECONDS = b.comment("Seconds of the countdown \"TIME UNTIL \"IT\" COMES\" once the sun has shattered.")
                .translation("null.config.field_countdown_seconds")
                .defineInRange("countdownSeconds", 75, 10, 900);
        FIELD_HUNT_SECONDS = b.comment("Seconds IT hunts each night before it leaves and dawn comes.")
                .translation("null.config.field_hunt_seconds")
                .defineInRange("huntSeconds", 100, 20, 900);
        FIELD_IT_DISTANCE = b.comment("How far away IT appears, in blocks, in the direction of the sun (closer if the server simulates fewer chunks).")
                .translation("null.config.field_it_distance")
                .defineInRange("itDistance", 120, 48, 512);
        b.pop();

        b.comment("IT, the creature of the field.").push("it");
        IT_CHASE_SPEED = b.comment("IT's top speed in blocks per second (a sprinting player runs about 5.6).")
                .translation("null.config.it_chase_speed")
                .defineInRange("chaseSpeed", 15.6, 4.0, 40.0);
        IT_CLOSING_SPEED = b.comment("Blocks per second IT always runs faster than the player it chases, so nobody outruns it in the open.")
                .translation("null.config.it_closing_speed")
                .defineInRange("closingSpeed", 3.0, 0.0, 20.0);
        IT_PIERCE_DAMAGE = b.comment("Damage of IT's blow through the chest during its kill, in health points (2 = one heart).",
                        "It never kills on its own: the end of the kill does.")
                .translation("null.config.it_pierce_damage")
                .defineInRange("pierceDamage", 6.0, 0.0, 19.0);
        b.pop();
        // ---- end of "field" and "it" ----

        // ---- "mobs": corrupted and parasite versions of mobs (entity.variant) ----
        b.comment("Corrupted mobs (from Stage 2) and parasite hosts with tentacles (from Stage 3). Any mob can become one,",
                "vanilla or modded; they keep their vanilla health, damage and loot.").push("mobs");
        MOB_VARIANTS_ENABLED = b.comment("If false no mob becomes corrupted or a parasite host, and the ones that are turn back to normal.")
                .translation("null.config.mobs_enabled")
                .define("enabled", true);
        MOB_CORRUPTED_FREQUENCY = b.comment("Multiplier for how often mobs turn corrupted (0 = never on their own; /null mob still works).")
                .translation("null.config.mobs_corrupted_frequency")
                .defineInRange("corruptedFrequency", 1.0, 0.0, 10.0);
        MOB_PARASITE_FREQUENCY = b.comment("Multiplier for how often mobs become parasite hosts (0 = never on their own; /null mob still works).")
                .translation("null.config.mobs_parasite_frequency")
                .defineInRange("parasiteFrequency", 1.0, 0.0, 10.0);
        MOB_PARASITE_SPREADING = b.comment("Parasites may leave a dying host for a mob nearby and slowly infest the mobs around them (never players).")
                .translation("null.config.mobs_parasite_spreading")
                .define("parasiteSpreading", true);
        MOB_MAX_PER_PLAYER_AREA = b.comment("Most variant mobs within 64 blocks of a player (fewer in the early stages).")
                .translation("null.config.mobs_max_per_area")
                .defineInRange("maxPerPlayerArea", 8, 0, 64);
        MOB_MAX_PER_WORLD = b.comment("Most variant mobs loaded at the same time in the whole world (all dimensions).")
                .translation("null.config.mobs_max_per_world")
                .defineInRange("maxPerWorld", 96, 0, 1024);
        MOB_DENYLIST = b.comment("Entity ids that never become variants, e.g. \"minecraft:cow\", or \"somemod:*\" for every mob of a mod.",
                        "Players, NULL's own creatures, bosses and the #null:variant_excluded tag are always left alone.")
                .translation("null.config.mobs_denylist")
                .defineListAllowEmpty("denylist", List.of(), () -> "", o -> o instanceof String);
        b.pop();
        // ---- end of "mobs" ----

        // ---- "stalkers": the figures of Stages 1-4 (entity.stalker) ----
        b.comment("The figure of Stages 1-4: it watches from behind trees and through windows, opens doors at night,",
                "shatters a window (always put back) and digs in the rock near players underground.").push("stalkers");
        STALKERS_ENABLED = b.comment("If false the figure never appears (not even through /null event).")
                .translation("null.config.stalkers_enabled")
                .define("enabled", true);
        STALKER_FREQUENCY = b.comment("Multiplier for how often the figure appears (0 = never on its own; /null event still works).")
                .translation("null.config.stalker_frequency")
                .defineInRange("frequency", 1.0, 0.0, 10.0);
        STALKER_DOOR_OPENING = b.comment("Let it open wooden doors, trapdoors and fence gates near a player at night. They close again",
                        "on their own a few minutes later. Needs allowWorldModification.")
                .translation("null.config.stalker_door_opening")
                .define("doorOpening", true);
        STALKER_WINDOW_BREAKING = b.comment("Let a window pane near a player shatter. It is always put back exactly as it was: within a",
                        "minute, at once when the player leaves, before its chunk unloads and when the server stops. Needs allowWorldModification.")
                .translation("null.config.stalker_window_breaking")
                .define("windowBreaking", true);
        STALKER_MINER_DIGGING = b.comment("Let it really dig a short tunnel near a player underground, through natural stone and deepslate",
                        "only (never near anything built; ores are left standing). Needs allowWorldModification and replaceNaturalBlocks.")
                .translation("null.config.stalker_miner_digging")
                .define("minerDigging", true);
        b.pop();
        // ---- end of "stalkers" ----

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
