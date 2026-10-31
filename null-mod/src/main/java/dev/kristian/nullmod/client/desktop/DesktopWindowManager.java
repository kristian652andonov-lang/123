package dev.kristian.nullmod.client.desktop;

import dev.kristian.nullmod.NullMod;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Timer;

/**
 * Owns the open desktop windows. Callers on any thread only post requests; every AWT/Swing call
 * happens on the Swing event thread. At most {@link #MAX_WINDOWS} windows exist at a time, each
 * on the primary screen and never larger than 45% of it, and all of them are disposed when the
 * player leaves the world, turns the feature off, the game shuts down, or the JVM exits.
 * <p>
 * Only used after {@link DesktopProbe} reported the desktop as available. No Minecraft classes
 * apart from the mod logger.
 */
final class DesktopWindowManager {
    static final int MAX_WINDOWS = 3;
    private static final int FRAME_MS = 33;
    private static final int MARGIN = 18;
    private static final float MAX_SCREEN_FRACTION = 0.45F;

    // event thread only
    private static final List<NullDesktopWindow> WINDOWS = new ArrayList<>(MAX_WINDOWS);
    private static final Random RANDOM = new Random();
    private static Timer timer;
    private static Boolean translucency;
    private static boolean loggedTickFailure;

    // any thread
    private static final AtomicInteger OPEN = new AtomicInteger();
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static volatile boolean shutDown;
    private static volatile boolean broken;
    private static boolean hookInstalled;

    private DesktopWindowManager() {
    }

    /** Windows open or about to open. */
    static int openCount() {
        return Math.max(0, OPEN.get());
    }

    /** True once opening a window failed; nothing will be shown again this session. */
    static boolean broken() {
        return broken;
    }

    /**
     * Opens a window for {@code spec} soon (on the event thread). With {@code replaceOldest}
     * the oldest window makes room when the limit is reached; otherwise the request is dropped.
     */
    static void show(DesktopWindowSpec spec, boolean replaceOldest) {
        if (shutDown) {
            return;
        }
        if (InGameDesktopWindows.fullscreen()) {
            // A full-screen game hides every other window: show it inside the game instead.
            InGameDesktopWindows.show(spec);
            return;
        }
        if (broken) {
            return;
        }
        installShutdownHook();
        int epoch = EPOCH.get();
        OPEN.incrementAndGet();
        try {
            EventQueue.invokeLater(() -> showOnEventThread(spec, replaceOldest, epoch));
        } catch (Throwable t) {
            OPEN.decrementAndGet();
            fail(t);
        }
    }

    /** Closes every window at once; requests still on their way are dropped too. */
    static void closeAll() {
        InGameDesktopWindows.closeAll();
        EPOCH.incrementAndGet();
        if (OPEN.get() <= 0) {
            return;
        }
        try {
            EventQueue.invokeLater(DesktopWindowManager::closeAllOnEventThread);
        } catch (Throwable t) {
            NullMod.LOGGER.warn("NULL could not close its desktop windows: {}", t.toString());
        }
    }

    /**
     * The game is closing: close everything, never open anything again, and stop the animation
     * timer so the Swing event thread can end. Waits briefly (at most 250 ms) for the windows.
     */
    static void shutdown() {
        shutDown = true;
        EPOCH.incrementAndGet();
        closeAndWait(250L);
    }

    private static void closeAndWait(long maxWaitMs) {
        if (EventQueue.isDispatchThread()) {
            closeAllOnEventThread();
            stopTimer();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        try {
            EventQueue.invokeLater(() -> {
                try {
                    closeAllOnEventThread();
                    stopTimer();
                } finally {
                    done.countDown();
                }
            });
            done.await(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // shutting down anyway
        }
    }

    private static synchronized void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(DesktopWindowManager::onJvmExit, "NULL desktop cleanup"));
        } catch (IllegalStateException | SecurityException e) {
            // the JVM is already shutting down: never open anything
            shutDown = true;
        }
    }

    private static void onJvmExit() {
        shutDown = true;
        EPOCH.incrementAndGet();
        if (OPEN.get() > 0) {
            closeAndWait(500L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // event thread

    private static void showOnEventThread(DesktopWindowSpec spec, boolean replaceOldest, int epoch) {
        if (shutDown || broken || epoch != EPOCH.get()) {
            OPEN.decrementAndGet();
            return;
        }
        boolean added = false;
        try {
            while (WINDOWS.size() >= MAX_WINDOWS) {
                if (!replaceOldest) {
                    OPEN.decrementAndGet();
                    return;
                }
                remove(WINDOWS.get(0));
            }
            GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle area = usableArea(configuration);
            if (translucency == null) {
                translucency = configuration.getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
            }
            int maxWidth = Math.max(220, Math.round(area.width * MAX_SCREEN_FRACTION));
            int maxHeight = Math.max(110, Math.round(area.height * MAX_SCREEN_FRACTION));
            NullDesktopWindow window = new NullDesktopWindow(spec, maxWidth, maxHeight, translucency,
                    DesktopWindowManager::remove);
            Rectangle target = place(spec.placement(), window.width(), window.height(), area);
            WINDOWS.add(window);
            added = true;
            window.show(target.x, target.y);
            ensureTimer();
        } catch (Throwable t) {
            if (!added) {
                OPEN.decrementAndGet();
            }
            fail(t);
            closeAllOnEventThread();
        }
    }

    /** Closes one window at once (the player clicked it, or the oldest has to make room). */
    private static void remove(NullDesktopWindow window) {
        window.dispose();
        if (WINDOWS.remove(window)) {
            OPEN.decrementAndGet();
        }
        if (WINDOWS.isEmpty()) {
            stopTimer();
        }
    }

    private static void closeAllOnEventThread() {
        for (NullDesktopWindow window : List.copyOf(WINDOWS)) {
            try {
                window.dispose();
            } catch (RuntimeException ignored) {
                // keep closing the others
            }
            if (WINDOWS.remove(window)) {
                OPEN.decrementAndGet();
            }
        }
        stopTimer();
    }

    private static void tick() {
        long now = NullDesktopWindow.nowMs();
        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            NullDesktopWindow window = WINDOWS.get(i);
            boolean alive;
            try {
                alive = window.tick(now);
            } catch (RuntimeException e) {
                if (!loggedTickFailure) {
                    loggedTickFailure = true;
                    NullMod.LOGGER.warn("A NULL desktop window failed to animate and was closed", e);
                }
                window.dispose();
                alive = false;
            }
            if (!alive) {
                WINDOWS.remove(i);
                OPEN.decrementAndGet();
            }
        }
        if (WINDOWS.isEmpty()) {
            stopTimer();
        }
    }

    private static void ensureTimer() {
        if (timer == null) {
            timer = new Timer(FRAME_MS, event -> tick());
            timer.setCoalesce(true);
            timer.setRepeats(true);
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    private static void stopTimer() {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            NullMod.LOGGER.warn("NULL could not open a desktop window; desktop interference is off for this session", t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // placement

    /** The primary screen minus task bars and docks. */
    private static Rectangle usableArea(GraphicsConfiguration configuration) {
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int width = Math.max(320, bounds.width - insets.left - insets.right);
        int height = Math.max(240, bounds.height - insets.top - insets.bottom);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top, width, height);
    }

    private static Rectangle place(DesktopWindowSpec.Placement placement, int width, int height, Rectangle area) {
        List<Rectangle> taken = new ArrayList<>(WINDOWS.size());
        for (NullDesktopWindow window : WINDOWS) {
            Rectangle bounds = window.bounds();
            bounds.grow(8, 8);
            taken.add(bounds);
        }
        int minX = area.x + MARGIN;
        int minY = area.y + MARGIN;
        int maxX = Math.max(minX, area.x + area.width - MARGIN - width);
        int maxY = Math.max(minY, area.y + area.height - MARGIN - height);

        switch (placement) {
            case CORNER -> {
                // x, y, stacking direction; bottom right first half of the time
                int[][] corners = {{maxX, maxY, -1}, {maxX, minY, 1}, {minX, maxY, -1}, {minX, minY, 1}};
                int first = RANDOM.nextFloat() < 0.5F ? 0 : RANDOM.nextInt(corners.length);
                for (int k = 0; k < corners.length; k++) {
                    int[] corner = corners[(first + k) % corners.length];
                    for (int stack = 0; stack < MAX_WINDOWS; stack++) {
                        int y = corner[1] + corner[2] * stack * (height + 10);
                        if (y < minY || y > maxY) {
                            break;
                        }
                        Rectangle candidate = new Rectangle(corner[0], y, width, height);
                        if (free(candidate, taken)) {
                            return candidate;
                        }
                    }
                }
            }
            case CENTRE -> {
                int centreX = area.x + (area.width - width) / 2;
                int centreY = area.y + (area.height - height) / 2;
                int spreadX = Math.max(1, area.width / 4);
                int spreadY = Math.max(1, area.height / 4);
                for (int attempt = 0; attempt < 16; attempt++) {
                    int x = clamp(centreX + RANDOM.nextInt(spreadX * 2 + 1) - spreadX, minX, maxX);
                    int y = clamp(centreY + RANDOM.nextInt(spreadY * 2 + 1) - spreadY, minY, maxY);
                    Rectangle candidate = new Rectangle(x, y, width, height);
                    if (free(candidate, taken)) {
                        return candidate;
                    }
                }
            }
            default -> {
                // RANDOM: tried below
            }
        }
        Rectangle last = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            int x = minX + RANDOM.nextInt(maxX - minX + 1);
            int y = minY + RANDOM.nextInt(maxY - minY + 1);
            last = new Rectangle(x, y, width, height);
            if (free(last, taken)) {
                return last;
            }
        }
        return last;
    }

    private static boolean free(Rectangle candidate, List<Rectangle> taken) {
        for (Rectangle other : taken) {
            if (other.intersects(candidate)) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
