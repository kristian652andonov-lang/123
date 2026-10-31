package dev.kristian.nullmod.client.desktop;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullSounds;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

/**
 * Desktop interference: small NULL windows outside the game window.
 * <p>
 * Safety first. Nothing is ever shown unless {@link NullClientSettings#desktopAllowed()} (the
 * player switched it on <em>and</em> accepted the explanation on NULL's consent screen). Every
 * window says it comes from the NULL Minecraft mod, closes on a click, on Esc in the game, and by
 * itself after a few seconds; it never takes focus, never grabs input, never covers the screen.
 * Nothing on the computer is changed and nothing is collected or sent. All windows close when the
 * player leaves the world, switches the feature off, or the game shuts down.
 * <p>
 * Pacing: only while in a world. Stage 1 only extremely rare, subtle notes; Stage 2 occasional
 * small notifications; Stage 3 stranger notes and occasional error windows; Stage 4 reports,
 * distorted text and warnings; Stage 5 more often and in every style. Rates scale with
 * {@code DESKTOP_FREQUENCY} and each kind's chance setting; at least the player's cooldown
 * ({@code DESKTOP_COOLDOWN}, 45 s by default, never under 15 s) between two windows, at most three
 * open at once, nothing during the first minute and a half of a session, and nothing while the
 * game runs in exclusive fullscreen.
 * <p>
 * Render thread only, except where noted. AWT is only touched after the availability check (on
 * its own daemon thread) succeeded.
 */
public final class DesktopInterferenceManager {
    /** Whether desktop windows can work on this computer. */
    public enum Availability {
        /** Not checked yet (checked once, when the feature is first switched on). */
        UNKNOWN,
        /** The check is running. */
        CHECKING,
        AVAILABLE,
        /** macOS: AWT windows cannot run next to Minecraft's window there. */
        UNSUPPORTED_OS,
        /** No desktop display is available to this game. */
        HEADLESS,
        /** Something failed; the feature stays off for this session. */
        FAILED
    }

    /** What happened when the settings screen's test button was pressed. */
    public enum TestResult {
        SHOWN,
        /** The availability check had to run first; the window appears when it succeeds. */
        CHECKING,
        NOT_ALLOWED,
        UNAVAILABLE,
        FULLSCREEN
    }

    private static final long JOIN_GRACE_MS = 90_000L;
    private static final long ROLL_INTERVAL_MS = 1_000L;
    private static final long STAGE_WINDOW_DEADLINE_MS = 120_000L;
    private static final String EFFECT_ID = "desktop.message";

    private static final Random RANDOM = new Random();
    private static volatile Availability availability = Availability.UNKNOWN;
    /** The probe succeeded once, so the AWT classes load and {@link DesktopWindowManager} may be used. */
    private static volatile boolean awtReady;

    private static long lastShownMs = Long.MIN_VALUE / 4L;
    private static long nextRollMs;
    private static long sessionStartMs = -1L;
    private static CorruptionStage knownStage;
    private static long stageWindowAtMs = -1L;
    private static long stageWindowDeadlineMs;
    private static int testCount;
    private static boolean pendingTest;
    private static boolean loggedBroken;
    private static String accountCode;
    /** The game is closing: nothing opens any more, even if the availability check finishes now. */
    private static volatile boolean shuttingDown;

    private DesktopInterferenceManager() {
    }

    static String effectId() {
        return EFFECT_ID;
    }

    /**
     * An anonymous code for the signed-in Minecraft account (a name-based UUID made from its profile
     * id): the consent is stored with it, so only the account that accepted the explanation gets
     * desktop windows. It stays in the local config file and is never sent anywhere.
     */
    static String accountCode() {
        String code = accountCode;
        if (code == null) {
            try {
                UUID profile = Minecraft.getInstance().getUser().getProfileId();
                if (profile == null) {
                    return "";
                }
                code = UUID.nameUUIDFromBytes(("NULL desktop consent " + profile).getBytes(StandardCharsets.UTF_8)).toString();
            } catch (RuntimeException e) {
                return "";
            }
            accountCode = code;
        }
        return code;
    }

    // ------------------------------------------------------------------------------------------
    // availability

    /** Current availability; on macOS this is known at once, without touching AWT. */
    public static Availability availability() {
        Availability current = availability;
        if (current == Availability.UNKNOWN && isMac()) {
            return Availability.UNSUPPORTED_OS;
        }
        return current;
    }

    /** False on operating systems where desktop interference can never work (macOS). */
    public static boolean osSupported() {
        return !isMac();
    }

    static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * Starts the one-time availability check on a daemon thread, if it has not run yet. Never
     * blocks. Called when the feature is switched on (and from the tick while it is on).
     */
    public static synchronized void requestAvailabilityCheck() {
        if (availability != Availability.UNKNOWN) {
            return;
        }
        if (isMac()) {
            availability = Availability.UNSUPPORTED_OS;
            NullMod.LOGGER.info("NULL desktop interference is not supported on macOS");
            return;
        }
        availability = Availability.CHECKING;
        Thread thread = new Thread(DesktopInterferenceManager::runCheck, "NULL desktop check");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            availability = Availability.FAILED;
        }
    }

    /** Daemon thread. */
    private static void runCheck() {
        Availability result;
        try {
            result = DesktopProbe.run();
        } catch (Throwable t) {
            NullMod.LOGGER.warn("NULL desktop interference check failed: {}", t.toString());
            result = Availability.FAILED;
        }
        awtReady = result == Availability.AVAILABLE;
        availability = result;
        NullMod.LOGGER.info("NULL desktop interference: {}", result.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    // ------------------------------------------------------------------------------------------
    // ticking and scheduling

    static void tick() {
        if (!NullClientSettings.desktopAllowed()) {
            pendingTest = false;
            stageWindowAtMs = -1L;
            closeEverything();
            return;
        }
        Availability current = availability;
        if (current == Availability.UNKNOWN) {
            requestAvailabilityCheck();
            return;
        }
        if (!awtReady) {
            if (current != Availability.CHECKING) {
                // the check failed: a test request waiting for it can never be shown
                pendingTest = false;
            }
            return;
        }
        if (DesktopWindowManager.broken()) {
            if (!loggedBroken) {
                loggedBroken = true;
                availability = Availability.FAILED;
            }
            return;
        }
        if (current != Availability.AVAILABLE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow().isFullscreen()) {
            // in exclusive fullscreen a window could minimise the game: nothing, and close what is open
            pendingTest = false;
            closeEverything();
            return;
        }
        if (pendingTest) {
            pendingTest = false;
            showTest();
        }
        if (!ClientCorruptionState.synced() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = Util.getMillis();
        if (sessionStartMs < 0L) {
            sessionStartMs = now;
        }
        if (now - sessionStartMs < JOIN_GRACE_MS) {
            return;
        }
        CorruptionStage stage = ClientCorruptionState.stage();

        // a window shortly after the stage advanced (not when the player chose "Never")
        if (stageWindowAtMs >= 0L && now >= stageWindowAtMs) {
            if (now > stageWindowDeadlineMs || pace(stage) <= 0.0) {
                stageWindowAtMs = -1L;
            } else if (canShowNow(now)) {
                stageWindowAtMs = -1L;
                showForStage(stage, true, now);
                return;
            }
        }

        if (now < nextRollMs) {
            return;
        }
        nextRollMs = now + ROLL_INTERVAL_MS;
        double pace = pace(stage);
        if (pace <= 0.0 || !canShowNow(now)) {
            return;
        }
        double mean = meanIntervalSeconds(stage, ClientCorruptionState.stageProgress()) / pace;
        if (RANDOM.nextDouble() * mean >= 1.0) {
            return;
        }
        showForStage(stage, false, now);
    }

    /**
     * Average seconds between two windows at frequency 1. Stage 1 is "extremely rare"; within a
     * stage the rate rises a little with the stage's progress.
     */
    static double meanIntervalSeconds(CorruptionStage stage, float stageProgress) {
        double base = switch (stage) {
            case START -> 5400.0;
            case UNDER_MIDDLE -> 1500.0;
            case MIDDLE -> 720.0;
            case ABOVE_MIDDLE -> 420.0;
            case NULL, HARVEST -> 200.0;
        };
        float progress = Math.max(0.0F, Math.min(1.0F, stageProgress));
        return base * (1.15 - 0.3 * progress);
    }

    /**
     * The player's pace for the stage: {@code DESKTOP_FREQUENCY} times the chance settings of the
     * kinds the stage uses. 1 is the normal pace; 0 means no window at all (frequency "Never", or
     * every fitting kind switched off).
     */
    private static double pace(CorruptionStage stage) {
        return Math.max(0.0, NullClientSettings.get(NullClientSettings.DESKTOP_FREQUENCY))
                * DesktopNotificationManager.paceFactor(stage);
    }

    private static boolean canShowNow(long now) {
        long gapMs = NullClientSettings.get(NullClientSettings.DESKTOP_COOLDOWN) * 1000L;
        return now - lastShownMs >= gapMs && DesktopWindowManager.openCount() < DesktopWindowManager.MAX_WINDOWS;
    }

    private static void showForStage(CorruptionStage stage, boolean stageChange, long now) {
        DesktopWindowType type = DesktopNotificationManager.chooseType(stage, RANDOM, stageChange);
        if (type == null) {
            return;
        }
        show(DesktopNotificationManager.build(type, stage.number(), RANDOM), now);
    }

    private static void show(DesktopWindowSpec spec, long now) {
        if (shuttingDown) {
            return;
        }
        DesktopWindowManager.show(spec, false);
        lastShownMs = now;
        // a faint crackle in the game as the window appears (follows every audio setting)
        AudioCorruptionManager.playGlobal(NullSounds.STATIC_FAINT.get(), AudioCorruptionManager.Channel.STATIC,
                0.3F, 0.85F + RANDOM.nextFloat() * 0.3F);
    }

    private static void closeEverything() {
        if (awtReady && DesktopWindowManager.openCount() > 0) {
            DesktopWindowManager.closeAll();
        }
    }

    // ------------------------------------------------------------------------------------------
    // game events (wired by DesktopClientModule)

    static void onStageChanged(CorruptionStage from, CorruptionStage to) {
        if (!ClientCorruptionState.synced()) {
            // logout reset
            knownStage = null;
            stageWindowAtMs = -1L;
            return;
        }
        CorruptionStage previous = knownStage;
        knownStage = to;
        if (previous == null || to.number() <= previous.number() || !to.atLeast(CorruptionStage.MIDDLE)) {
            // first sync of this session, a stage going back, or too early for a reaction
            return;
        }
        long delay = to.isFinal() ? 25_000L + RANDOM.nextInt(20_000) : 8_000L + RANDOM.nextInt(17_000);
        stageWindowAtMs = Util.getMillis() + delay;
        stageWindowDeadlineMs = stageWindowAtMs + STAGE_WINDOW_DEADLINE_MS;
    }

    static void onLoggingOut() {
        sessionStartMs = -1L;
        stageWindowAtMs = -1L;
        knownStage = null;
        closeEverything();
    }

    /** Esc pressed in the game: every NULL window closes. */
    static void onEscape() {
        closeEverything();
    }

    static void onShuttingDown() {
        shuttingDown = true;
        pendingTest = false;
        if (awtReady) {
            DesktopWindowManager.shutdown();
        }
    }

    /**
     * Client effect {@code desktop.message}: lets a server event ask for a window. {@code data}
     * selects the kind (0..4 = notification, error, distorted, report, final; anything else lets
     * the stage decide). The server never supplies the text: only NULL's own messages appear on
     * the desktop. Every rule of the scheduler (switches, gap, limit, fullscreen) still applies.
     */
    static void onEffect(ClientEffectPayload effect) {
        if (!NullClientSettings.desktopAllowed()) {
            return;
        }
        if (availability == Availability.UNKNOWN) {
            requestAvailabilityCheck();
            return;
        }
        if (!awtReady || availability != Availability.AVAILABLE || DesktopWindowManager.broken()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = Util.getMillis();
        if (minecraft.getWindow().isFullscreen() || !ClientCorruptionState.synced() || !canShowNow(now)) {
            return;
        }
        CorruptionStage stage = ClientCorruptionState.stage();
        double pace = pace(stage);
        if (pace <= 0.0 || RANDOM.nextDouble() >= pace) {
            // frequency "Never", every fitting kind off, or chances set below normal
            return;
        }
        DesktopWindowType type = DesktopWindowType.byIndex(effect.data());
        if (type == null || !DesktopNotificationManager.allowed(type)) {
            type = DesktopNotificationManager.chooseType(stage, RANDOM, false);
        }
        if (type == null) {
            return;
        }
        show(DesktopNotificationManager.build(type, stage.number(), RANDOM), now);
    }

    // ------------------------------------------------------------------------------------------
    // settings screen

    /** The feature was switched off: every window closes at once, nothing more is scheduled. */
    public static void disableNow() {
        pendingTest = false;
        stageWindowAtMs = -1L;
        if (awtReady) {
            DesktopWindowManager.closeAll();
        }
    }

    /** NULL desktop windows open right now (0 before the feature was ever used). */
    public static int openWindows() {
        return awtReady ? DesktopWindowManager.openCount() : 0;
    }

    /** The test button can do something useful right now. */
    public static boolean canTest() {
        Availability current = availability();
        return NullClientSettings.desktopAllowed()
                && (current == Availability.AVAILABLE || current == Availability.UNKNOWN || current == Availability.CHECKING)
                && !(awtReady && DesktopWindowManager.broken());
    }

    /**
     * Shows one window right away (each press shows the next allowed kind), from the settings
     * screen, even outside a world. Still requires the switch, the consent and a working
     * desktop; never in exclusive fullscreen.
     */
    public static TestResult showTestMessage() {
        if (!NullClientSettings.desktopAllowed()) {
            return TestResult.NOT_ALLOWED;
        }
        Availability current = availability();
        if (current == Availability.UNKNOWN || current == Availability.CHECKING) {
            requestAvailabilityCheck();
            pendingTest = true;
            return TestResult.CHECKING;
        }
        if (!awtReady || current != Availability.AVAILABLE || DesktopWindowManager.broken()) {
            return TestResult.UNAVAILABLE;
        }
        if (Minecraft.getInstance().getWindow().isFullscreen()) {
            return TestResult.FULLSCREEN;
        }
        showTest();
        return TestResult.SHOWN;
    }

    private static void showTest() {
        if (shuttingDown || !NullClientSettings.desktopAllowed() || !awtReady || availability != Availability.AVAILABLE) {
            return;
        }
        DesktopWindowType type = DesktopNotificationManager.testType(testCount++);
        DesktopWindowManager.show(DesktopNotificationManager.buildTest(type, RANDOM), true);
        // no natural window right after the test
        lastShownMs = Util.getMillis();
    }
}
