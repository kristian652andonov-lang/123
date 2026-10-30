package dev.kristian.nullmod.client.visual;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Looks exactly like vanilla's "Connection Lost" screen (it is vanilla's screen, with the
 * main-menu background instead of the in-game one), but nothing was disconnected: it is not a
 * pause screen, the world keeps running behind it, and after a few seconds, or when the button
 * is pressed, it simply closes and the game is still there.
 */
final class FakeDisconnectScreen extends DisconnectedScreen {
    private static final Component TITLE = Component.translatable("disconnect.lost");
    private static final Component REASON = Component.literal("Internal Exception: java.io.IOException: NULL");
    private static final Component BUTTON = Component.translatable("gui.toMenu");

    private static long pendingUntil;
    private static int pendingTicks;

    private final long closeAt;
    private boolean closed;

    private FakeDisconnectScreen(int showTicks) {
        // A null parent: the button's "back" just returns to the game.
        super(null, TITLE, REASON, BUTTON);
        this.closeAt = Util.getMillis() + showTicks * 50L;
    }

    /** Requests the screen; it appears as soon as no other screen is open (within 10 s). */
    static void request(int durationTicks) {
        pendingTicks = Math.max(50, Math.min(80, durationTicks));
        pendingUntil = Util.getMillis() + 10_000L;
    }

    static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FakeDisconnectScreen screen) {
            if (Util.getMillis() >= screen.closeAt || mc.level == null) {
                screen.closeNow();
            }
            return;
        }
        if (pendingUntil == 0L) {
            return;
        }
        if (Util.getMillis() > pendingUntil || mc.level == null || mc.player == null) {
            pendingUntil = 0L;
            return;
        }
        if (mc.screen == null && VisualDistortionManager.transitionFade() <= 0.0F && !mc.player.isDeadOrDying()) {
            pendingUntil = 0L;
            VisualSounds.fakeDisconnect(pendingTicks);
            mc.setScreen(new FakeDisconnectScreen(pendingTicks));
        }
    }

    static void closeIfOpen() {
        pendingUntil = 0L;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FakeDisconnectScreen screen) {
            screen.closeNow();
        }
    }

    private void closeNow() {
        if (!closed) {
            closed = true;
            if (this.minecraft != null && this.minecraft.screen == this) {
                this.minecraft.setScreen(null);
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The real screen appears after the world is gone: panorama, blur and the menu
        // background, never the translucent in-game one.
        this.renderPanorama(graphics, partialTick);
        this.renderBlurredBackground(partialTick);
        Screen.renderMenuBackgroundTexture(graphics, Screen.MENU_BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, this.height);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (Util.getMillis() >= closeAt) {
            closeNow();
        }
    }

    @Override
    public void onClose() {
        closeNow();
    }
}
