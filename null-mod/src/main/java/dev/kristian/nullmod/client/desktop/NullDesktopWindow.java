package dev.kristian.nullmod.client.desktop;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JWindow;

/**
 * One NULL desktop window: an undecorated, never-focused, always-on-top {@link JWindow} with a
 * {@link NullDesktopPanel}. It never takes keyboard or mouse focus, never grabs anything, closes
 * on any click (or Esc, should a window manager ever give it focus) and closes by itself when its
 * lifetime runs out.
 * <p>
 * Swing event thread only. No Minecraft classes.
 */
final class NullDesktopWindow {
    private final DesktopWindowSpec spec;
    private final JWindow window;
    private final NullDesktopPanel panel;
    private final long openedAtMs;
    private final boolean wholeWindowFlicker;
    private boolean translucent;
    private long closingSinceMs = -1L;
    private int fadeOutMs;
    private float lastOpacity = -1.0F;
    private boolean disposed;

    /**
     * Builds the window (not yet visible).
     *
     * @param maxWidth    widest the window may be (a fraction of the usable screen area)
     * @param maxHeight   tallest the window may be
     * @param translucent the platform supports per-window opacity (fades)
     * @param onClose     called on the event thread when the player clicks it (or presses Esc on it)
     */
    NullDesktopWindow(DesktopWindowSpec spec, int maxWidth, int maxHeight, boolean translucent, Consumer<NullDesktopWindow> onClose) {
        this.spec = spec;
        this.translucent = translucent;
        this.openedAtMs = System.currentTimeMillis();
        this.wholeWindowFlicker = spec.layout() == DesktopWindowSpec.Layout.BIG_TEXT;
        this.window = new JWindow();
        // never steal focus or keyboard input, never show up in the task bar
        window.setType(Window.Type.POPUP);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setFocusable(false);
        try {
            window.setAlwaysOnTop(true);
        } catch (SecurityException ignored) {
            // still a normal window then
        }
        window.setBackground(NullDesktopPanel.BACKGROUND);
        this.panel = new NullDesktopPanel(spec, maxWidth, maxHeight);
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        window.setContentPane(panel);
        Dimension size = panel.getPreferredSize();
        window.setSize(Math.min(size.width, maxWidth), Math.min(size.height, maxHeight));

        MouseAdapter click = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                onClose.accept(NullDesktopWindow.this);
            }
        };
        panel.addMouseListener(click);
        window.addMouseListener(click);
        window.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    onClose.accept(NullDesktopWindow.this);
                }
            }
        });
        if (translucent) {
            setOpacity(0.0F);
        }
    }

    DesktopWindowSpec spec() {
        return spec;
    }

    int width() {
        return window.getWidth();
    }

    int height() {
        return window.getHeight();
    }

    Rectangle bounds() {
        return window.getBounds();
    }

    void show(int x, int y) {
        window.setLocation(x, y);
        window.setVisible(true);
    }

    /**
     * Advances the animation. Returns false once the window is gone (lifetime over).
     */
    boolean tick(long nowMs) {
        if (disposed) {
            return false;
        }
        long age = nowMs - openedAtMs;
        int fadeInMs = spec.slowFades() ? 900 : wholeWindowFlicker ? 120 : 220;
        if (closingSinceMs < 0L) {
            int naturalFade = spec.slowFades() ? 1300 : 380;
            if (age >= spec.lifetimeMs() - naturalFade) {
                closingSinceMs = nowMs;
                fadeOutMs = naturalFade;
            }
        }
        float alpha = Math.min(1.0F, age / (float) fadeInMs);
        if (closingSinceMs >= 0L) {
            // without translucency the window simply stays solid until the fade would have ended
            float out = 1.0F - (nowMs - closingSinceMs) / (float) Math.max(1, fadeOutMs);
            if (out <= 0.0F) {
                dispose();
                return false;
            }
            alpha = Math.min(alpha, out);
        }
        panel.advance(age);
        if (translucent) {
            float flicker = wholeWindowFlicker ? panel.windowFlicker() : 1.0F;
            setOpacity(Math.max(0.0F, Math.min(1.0F, alpha * flicker)));
        }
        panel.repaint();
        return true;
    }

    /** Starts a short fade-out (or closes at once when fades are not supported). */
    void close(boolean immediately) {
        if (disposed) {
            return;
        }
        if (immediately || !translucent) {
            dispose();
            return;
        }
        if (closingSinceMs < 0L) {
            closingSinceMs = System.currentTimeMillis();
            fadeOutMs = spec.slowFades() ? 700 : 180;
        }
    }

    boolean disposed() {
        return disposed;
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        window.setVisible(false);
        window.dispose();
    }

    private void setOpacity(float opacity) {
        if (Math.abs(opacity - lastOpacity) < 0.01F) {
            return;
        }
        try {
            window.setOpacity(opacity);
            lastOpacity = opacity;
        } catch (RuntimeException e) {
            // translucency claimed but refused (some X11 setups): show the window solid from now on
            translucent = false;
            try {
                window.setOpacity(1.0F);
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
        }
    }
}
