package dev.kristian.nullmod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-player settings ({@code config/null-client.toml}). These are saved between sessions
 * and edited from the NULL settings screen. Anything here can only make effects weaker
 * than the server's {@link NullConfig} limits, never stronger.
 */
public final class NullClientSettings {
    public static final ModConfigSpec SPEC;

    // Visual
    public static final ModConfigSpec.DoubleValue SCREEN_SHAKE_INTENSITY;
    public static final ModConfigSpec.BooleanValue REDUCE_SCREEN_SHAKE;
    public static final ModConfigSpec.DoubleValue FLASHING_INTENSITY;
    public static final ModConfigSpec.BooleanValue DISABLE_INTENSE_EFFECTS;
    public static final ModConfigSpec.IntValue MAX_VISUAL_EFFECTS;
    public static final ModConfigSpec.BooleanValue SHADER_EFFECTS;

    // Audio
    public static final ModConfigSpec.BooleanValue HORROR_SOUNDS;
    public static final ModConfigSpec.DoubleValue AUDIO_INTENSITY;
    public static final ModConfigSpec.BooleanValue STATIC_NOISE;
    public static final ModConfigSpec.BooleanValue WHISPERS;
    public static final ModConfigSpec.BooleanValue PHANTOM_FOOTSTEPS;
    public static final ModConfigSpec.BooleanValue MUSIC_DISTORTION;
    public static final ModConfigSpec.BooleanValue PITCH_DISTORTION;
    public static final ModConfigSpec.BooleanValue SUDDEN_SILENCE;

    // Main menu
    public static final ModConfigSpec.BooleanValue CUSTOM_MAIN_MENU;
    public static final ModConfigSpec.BooleanValue ANIMATED_BACKGROUND;
    public static final ModConfigSpec.DoubleValue BACKGROUND_ANIMATION_INTENSITY;
    public static final ModConfigSpec.DoubleValue MENU_GLITCH_INTENSITY;
    public static final ModConfigSpec.BooleanValue MENU_SOUNDS;

    // Desktop interference (on by default; the player can switch it off at any time)
    public static final ModConfigSpec.BooleanValue DESKTOP_INTERFERENCE;
    public static final ModConfigSpec.IntValue DESKTOP_DEFAULTS_VERSION;
    public static final ModConfigSpec.BooleanValue DESKTOP_NOTIFICATIONS;
    public static final ModConfigSpec.BooleanValue DESKTOP_ERROR_WINDOWS;
    public static final ModConfigSpec.DoubleValue DESKTOP_FREQUENCY;
    public static final ModConfigSpec.IntValue DESKTOP_COOLDOWN;
    public static final ModConfigSpec.DoubleValue DESKTOP_CHANCE_NOTIFICATION;
    public static final ModConfigSpec.DoubleValue DESKTOP_CHANCE_ERROR;
    public static final ModConfigSpec.DoubleValue DESKTOP_CHANCE_DISTORTED;
    public static final ModConfigSpec.DoubleValue DESKTOP_CHANCE_REPORT;
    public static final ModConfigSpec.DoubleValue DESKTOP_CHANCE_FINAL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Visual distortion and accessibility.").push("visual");
        SCREEN_SHAKE_INTENSITY = b.comment("Strength of screen shaking (0 = none).")
                .translation("null.settings.screen_shake_intensity")
                .defineInRange("screenShakeIntensity", 1.0, 0.0, 1.0);
        REDUCE_SCREEN_SHAKE = b.comment("Cuts all screen shaking and camera movement to a fraction.")
                .translation("null.settings.reduce_screen_shake")
                .define("reduceScreenShake", false);
        FLASHING_INTENSITY = b.comment("Strength of flashes, flicker and blackouts (0 = replaced by slow fades).")
                .translation("null.settings.flashing_intensity")
                .defineInRange("flashingIntensity", 1.0, 0.0, 1.0);
        DISABLE_INTENSE_EFFECTS = b.comment("Turns off freezes, strobing, RGB storms and other intense visual effects.")
                .translation("null.settings.disable_intense_effects")
                .define("disableIntenseEffects", false);
        MAX_VISUAL_EFFECTS = b.comment("Most distortion effects that may run at the same time.")
                .translation("null.settings.max_visual_effects")
                .defineInRange("maxVisualEffects", 6, 0, 32);
        SHADER_EFFECTS = b.comment("Use NULL's post-processing shader (RGB separation, colour shifts, glitches).")
                .translation("null.settings.shader_effects")
                .define("shaderEffects", true);
        b.pop();

        b.comment("Audio corruption.").push("audio");
        HORROR_SOUNDS = b.comment("Master switch for NULL's horror sound effects.")
                .translation("null.settings.horror_sounds")
                .define("horrorSounds", true);
        AUDIO_INTENSITY = b.comment("Strength of audio corruption.")
                .translation("null.settings.audio_intensity")
                .defineInRange("audioIntensity", 1.0, 0.0, 1.0);
        STATIC_NOISE = b.comment("Static, interference and digital noise.")
                .translation("null.settings.static_noise")
                .define("staticNoise", true);
        WHISPERS = b.comment("Whispers and distant voices.")
                .translation("null.settings.whispers")
                .define("whispers", true);
        PHANTOM_FOOTSTEPS = b.comment("Footsteps that are not yours.")
                .translation("null.settings.phantom_footsteps")
                .define("phantomFootsteps", true);
        MUSIC_DISTORTION = b.comment("Distorted and replaced music.")
                .translation("null.settings.music_distortion")
                .define("musicDistortion", true);
        PITCH_DISTORTION = b.comment("Game sounds played at the wrong pitch.")
                .translation("null.settings.pitch_distortion")
                .define("pitchDistortion", true);
        SUDDEN_SILENCE = b.comment("Moments where every sound stops.")
                .translation("null.settings.sudden_silence")
                .define("suddenSilence", true);
        b.pop();

        b.comment("The corrupted main menu.").push("menu");
        CUSTOM_MAIN_MENU = b.comment("Replace the title screen with NULL's corrupted menu. Turn off to get the vanilla menu back.")
                .translation("null.settings.custom_main_menu")
                .define("customMainMenu", true);
        ANIMATED_BACKGROUND = b.comment("Animate the menu background.")
                .translation("null.settings.animated_background")
                .define("animatedBackground", true);
        BACKGROUND_ANIMATION_INTENSITY = b.comment("How much the menu background moves.")
                .translation("null.settings.background_animation_intensity")
                .defineInRange("backgroundAnimationIntensity", 0.8, 0.0, 1.0);
        MENU_GLITCH_INTENSITY = b.comment("How often and how strongly the menu glitches.")
                .translation("null.settings.menu_glitch_intensity")
                .defineInRange("menuGlitchIntensity", 0.7, 0.0, 1.0);
        MENU_SOUNDS = b.comment("Menu ambience and interface sounds.")
                .translation("null.settings.menu_sounds")
                .define("menuSounds", true);
        b.pop();

        b.comment("Desktop interference: small windows, clearly labelled as NULL's, that NULL shows outside the game window",
                "while you play (never in fullscreen, never after the game has closed). On by default; set desktopInterference",
                "to false (or switch it off in NULL's settings) to turn it off and close every window at once.").push("desktop");
        DESKTOP_INTERFERENCE = b.comment("Master switch for desktop interference.")
                .translation("null.settings.desktop_interference")
                .define("desktopInterference", true);
        DESKTOP_DEFAULTS_VERSION = b.comment("Used by NULL to switch desktop interference on once in settings saved before it was on by default.",
                        "Do not change it.")
                .translation("null.settings.desktop_defaults_version")
                .defineInRange("defaultsVersion", 0, 0, 100);
        DESKTOP_NOTIFICATIONS = b.comment("Allow small corner notifications.")
                .translation("null.settings.desktop_notifications")
                .define("desktopNotifications", true);
        DESKTOP_ERROR_WINDOWS = b.comment("Allow the larger NULL error windows.")
                .translation("null.settings.desktop_error_windows")
                .define("desktopErrorWindows", true);
        DESKTOP_FREQUENCY = b.comment("How often desktop interference happens.")
                .translation("null.settings.desktop_frequency")
                .defineInRange("desktopFrequency", 1.0, 0.0, 3.0);
        DESKTOP_COOLDOWN = b.comment("Shortest time between two desktop windows, in seconds.")
                .translation("null.settings.desktop_cooldown")
                .defineInRange("desktopCooldownSeconds", 45, 15, 1800);
        b.comment("How often each kind of window appears (1 = normal, 2 = twice as often, 0 = never).")
                .push("chances");
        DESKTOP_CHANCE_NOTIFICATION = b.comment("Type A: small corner notifications.")
                .translation("null.settings.desktop_chance_notification")
                .defineInRange("notification", 1.0, 0.0, 3.0);
        DESKTOP_CHANCE_ERROR = b.comment("Type B: small error windows.")
                .translation("null.settings.desktop_chance_error")
                .defineInRange("errorWindow", 1.0, 0.0, 3.0);
        DESKTOP_CHANCE_DISTORTED = b.comment("Type C: distorted messages that flicker and disappear.")
                .translation("null.settings.desktop_chance_distorted")
                .defineInRange("distortedMessage", 1.0, 0.0, 3.0);
        DESKTOP_CHANCE_REPORT = b.comment("Type D: larger NULL reports.")
                .translation("null.settings.desktop_chance_report")
                .defineInRange("report", 1.0, 0.0, 3.0);
        DESKTOP_CHANCE_FINAL = b.comment("Type E: Stage 5 final interference.")
                .translation("null.settings.desktop_chance_final")
                .defineInRange("finalInterference", 1.0, 0.0, 3.0);
        b.pop();
        b.pop();

        SPEC = b.build();
    }

    private NullClientSettings() {
    }

    public static boolean get(ModConfigSpec.BooleanValue value) {
        return SPEC.isLoaded() ? value.getAsBoolean() : value.getDefault();
    }

    public static double get(ModConfigSpec.DoubleValue value) {
        return SPEC.isLoaded() ? value.getAsDouble() : value.getDefault();
    }

    public static int get(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.getAsInt() : value.getDefault();
    }

    /** Stores a value and writes the file. Safe to call from the render thread. */
    public static <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        if (!SPEC.isLoaded()) {
            return;
        }
        value.set(newValue);
        value.save();
    }

    /** Desktop interference may run: it is on by default and stays on until the player switches it off. */
    public static boolean desktopAllowed() {
        return get(DESKTOP_INTERFERENCE);
    }

    /** {@link #DESKTOP_DEFAULTS_VERSION} once desktop interference is on by default. */
    private static final int DESKTOP_ON_BY_DEFAULT = 1;

    /**
     * Once per settings file: settings saved by a version in which desktop interference was off by
     * default (it only came on after an explanation screen) get it switched on, since it is on by
     * default now. Switching it off afterwards sticks. Does nothing until the settings are loaded.
     */
    public static void applyDesktopDefaults() {
        if (!SPEC.isLoaded() || DESKTOP_DEFAULTS_VERSION.getAsInt() >= DESKTOP_ON_BY_DEFAULT) {
            return;
        }
        DESKTOP_INTERFERENCE.set(true);
        DESKTOP_DEFAULTS_VERSION.set(DESKTOP_ON_BY_DEFAULT);
        DESKTOP_DEFAULTS_VERSION.save();
    }
}
