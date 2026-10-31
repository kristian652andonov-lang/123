package dev.kristian.nullmod.client.menu;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionStage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * "Leave while you can", scrawled in blood across the middle of the inventory and every other
 * container screen (the user: "even the inventory should have a blood grafity in the middle").
 * <p>
 * It is painted on the panel itself: drawn right after the panel background, before the slots'
 * items, so every item stays on top of it; and depth-tested just above the panel, so the player
 * model in the inventory (drawn with the panel, but nearer to the viewer) stays in front of it too.
 * <p>
 * The texture ({@code tools/gen_graffiti.py}) holds two versions of the same scrawl: old and dried
 * (top half) and fresh (bottom half). Stage 1 shows only a faint dried trace; from Stage 3 the
 * fresh blood runs over it, bolder with every stage.
 */
final class InventoryGraffiti {
    static final ResourceLocation TEXTURE = NullMod.id("textures/gui/menu/graffiti.png");

    /** Strength of the dried and of the fresh version per stage, STATIC to HARVEST. Mirrored in gen_graffiti.py. */
    private static final float[] DRIED = {0.34F, 0.46F, 0.40F, 0.22F, 0.12F, 0.08F};
    private static final float[] FRESH = {0.0F, 0.0F, 0.32F, 0.62F, 0.76F, 0.86F};
    /** Share of the panel width the words cover (the texture is twice as wide as high). */
    private static final float WIDTH_SHARE = 0.92F;
    private static final float MAX_WIDTH = 300.0F;
    /** Panels smaller than this (odd modded screens) are left alone. */
    private static final int MIN_PANEL_WIDTH = 90;
    private static final int MIN_PANEL_HEIGHT = 60;

    private static final SpriteBatch BATCH = new SpriteBatch();
    private static MenuTexture texture;

    private InventoryGraffiti() {
    }

    /** Paints the graffiti on a container panel at (left, top), size in GUI units. */
    static void render(GuiGraphics graphics, int left, int top, int panelWidth, int panelHeight, CorruptionStage stage) {
        if (panelWidth < MIN_PANEL_WIDTH || panelHeight < MIN_PANEL_HEIGHT) {
            return;
        }
        int i = Math.max(0, Math.min(DRIED.length - 1, stage.number() - 1));
        float dried = DRIED[i];
        float fresh = FRESH[i];
        float w = Math.min(panelWidth * WIDTH_SHARE, MAX_WIDTH);
        float h = w * 0.5F;
        float x0 = left + (panelWidth - w) * 0.5F;
        float y0 = top + (panelHeight - h) * 0.5F - panelHeight * 0.02F;
        if (texture == null) {
            texture = new MenuTexture(TEXTURE, true);
        }
        graphics.flush();
        graphics.pose().pushPose();
        try {
            // just above the panel (z 0) but far behind the player model (drawn around z 50)
            graphics.pose().translate(0.0F, 0.0F, 1.0F);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GlConst.GL_LEQUAL);
            RenderSystem.depthMask(false);
            if (BATCH.begin(graphics, texture, false)) {
                if (dried > 0.0F) {
                    BATCH.quad(x0, y0, x0 + w, y0 + h, 0.0F, 0.0F, 1.0F, 0.5F, MenuMath.argb(0xFFFFFF, dried));
                }
                if (fresh > 0.0F) {
                    BATCH.quad(x0, y0, x0 + w, y0 + h, 0.0F, 0.5F, 1.0F, 1.0F, MenuMath.argb(0xFFFFFF, fresh));
                }
                BATCH.end();
            }
        } finally {
            BATCH.abort();
            RenderSystem.depthMask(true);
            RenderSystem.disableDepthTest();
            graphics.pose().popPose();
        }
    }

    /** Frees the texture (logout); it loads again when a container is next opened. */
    static void release() {
        if (texture != null) {
            texture.release();
        }
    }

    /** Drops the texture for good (resource reload); a fresh one is made on next use. */
    static void invalidate() {
        MenuTexture current = texture;
        texture = null;
        if (current != null) {
            current.close();
        }
    }
}
