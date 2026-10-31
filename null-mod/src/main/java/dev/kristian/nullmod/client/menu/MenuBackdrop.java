package dev.kristian.nullmod.client.menu;

import java.util.Random;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/**
 * NULL's darkness behind every screen outside a world other than the corrupted main menu itself
 * (Select World, Create World, Multiplayer, Options, the mod list, NULL's settings, connecting and
 * loading screens...): the same fog, faces, eyes, vignette and grain as the main menu, matching
 * the highest stage this computer has seen, but quieter and kept to the edges of the screen so
 * lists and text stay easy to read. One shared composition carries on from screen to screen; it
 * starts afresh after it has not been shown for a while.
 * <p>
 * Render thread only. Nothing is allocated per frame.
 */
final class MenuBackdrop {
    /** Not drawn for this long (a world was played, say): the next screen starts a new composition. */
    private static final long IDLE_RESET_MS = 15_000L;
    /** How often the settings and the remembered stage are read again while screens are open. */
    private static final long MOOD_CHECK_MS = 1_000L;
    /** The grain over other screens is a little lighter than over the main menu. */
    private static final float GRAIN_SHARE = 0.8F;

    private static final Random RANDOM = new Random();
    private static final SpriteBatch BATCH = new SpriteBatch();
    private static final MainMenuAnimationManager ANIMATION =
            new MainMenuAnimationManager(RANDOM, MainMenuAnimationManager.Layout.BACKDROP);
    private static final MainMenuBackgroundRenderer RENDERER = new MainMenuBackgroundRenderer();

    private static MenuMood mood;
    private static MenuArt art = MenuArt.EMPTY;
    private static long lastFrameMs;
    private static long moodCheckedMs;
    private static float time;

    private MenuBackdrop() {
    }

    /** Draws the backdrop over the whole screen; mouse coordinates in GUI units (eyes follow the cursor). */
    static void render(GuiGraphics graphics, int width, int height, int mouseX, int mouseY) {
        long now = Util.getMillis();
        MenuArt current = MenuArt.get();
        if (mood == null || current != art || now - lastFrameMs > IDLE_RESET_MS) {
            art = current;
            mood = MenuMood.read();
            moodCheckedMs = now;
            ANIMATION.reset(mood, art);
            lastFrameMs = now;
        } else if (now - moodCheckedMs >= MOOD_CHECK_MS) {
            moodCheckedMs = now;
            mood = MenuMood.read();
            // carries on if only the strengths changed, starts afresh for another stage or animation setting
            ANIMATION.resume(mood, art);
        }
        float dt = MenuMath.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;
        time += dt;
        ANIMATION.resize(width, height);
        if (mouseX != Integer.MAX_VALUE && mouseY != Integer.MAX_VALUE) {
            ANIMATION.lookAt(mouseX / (float) Math.max(1, width), mouseY / (float) Math.max(1, height));
        }
        ANIMATION.update(dt);
        RENDERER.renderBackdrop(graphics, BATCH, art, ANIMATION, mood, width, height, time);
    }

    /** The film grain over everything the screen drew (after its widgets). */
    static void renderGrain(GuiGraphics graphics, int width, int height) {
        if (mood == null) {
            return;
        }
        MainMenuBackgroundRenderer.drawGrain(graphics, BATCH, art, mood.grainAlpha * GRAIN_SHARE, mood.animated, width, height, time);
    }

    /** Forgets the composition (a world was joined, resources were reloaded). */
    static void forget() {
        mood = null;
        art = MenuArt.EMPTY;
    }

    /** Drawing failed part way: leaves no half-built batch behind. */
    static void abort() {
        BATCH.abort();
    }
}
