package dev.kristian.nullmod.client.desktop;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.desktop.DesktopInterferenceManager.Availability;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Finds out once whether desktop windows can work on this computer. Runs on its own daemon
 * thread (never the render thread) and only after the player turned desktop interference on,
 * so players who never use the feature never get AWT initialised at all.
 * <ul>
 *   <li>macOS: unsupported (AWT and GLFW both want the main thread there).</li>
 *   <li>No display (no {@code DISPLAY} on Linux/BSD, or AWT reports headless): unsupported.</li>
 *   <li>Minecraft starts with {@code java.awt.headless=true}; as long as AWT has not read the
 *       property yet it is switched to {@code false} here, and put back when the check fails.</li>
 *   <li>Any error at all: unsupported. Nothing here ever propagates to the game.</li>
 * </ul>
 */
final class DesktopProbe {
    private static final String HEADLESS_PROPERTY = "java.awt.headless";

    private DesktopProbe() {
    }

    static Availability run() {
        if (DesktopInterferenceManager.isMac()) {
            return Availability.UNSUPPORTED_OS;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows")) {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isBlank()) {
                return Availability.HEADLESS;
            }
        }
        String before = System.getProperty(HEADLESS_PROPERTY);
        boolean changed = false;
        try {
            if (before != null && Boolean.parseBoolean(before.trim())) {
                System.setProperty(HEADLESS_PROPERTY, "false");
                changed = true;
            }
            if (GraphicsEnvironment.isHeadless()) {
                restore(before, changed);
                return Availability.HEADLESS;
            }
            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsConfiguration configuration = environment.getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            if (bounds.width < 480 || bounds.height < 320) {
                restore(before, changed);
                return Availability.FAILED;
            }
            // initialises the toolkit here, off the render thread
            Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            CountDownLatch eventThreadAlive = new CountDownLatch(1);
            EventQueue.invokeLater(eventThreadAlive::countDown);
            if (!eventThreadAlive.await(5, TimeUnit.SECONDS)) {
                NullMod.LOGGER.warn("NULL desktop interference: the Swing event thread did not respond; keeping it off");
                return Availability.FAILED;
            }
            return Availability.AVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Availability.FAILED;
        } catch (Throwable t) {
            restore(before, changed);
            NullMod.LOGGER.warn("NULL desktop interference is not available on this computer: {}", t.toString());
            return Availability.FAILED;
        }
    }

    private static void restore(String before, boolean changed) {
        if (!changed) {
            return;
        }
        try {
            System.setProperty(HEADLESS_PROPERTY, before);
        } catch (RuntimeException ignored) {
            // the property stays as it is
        }
    }
}
