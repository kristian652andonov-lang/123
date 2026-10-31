package dev.kristian.nullmod.client.field;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.field.FieldCycle;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The countdown at the top of the screen during the field's night:
 * <pre>
 *     TIME UNTIL "IT" COMES
 *           01:15
 * </pre>
 * Heavy, blood-dark letters on a smear of dried blood, never quite still: now and then a letter
 * slips by a pixel, and a dark double of the text shivers beside it. In the last ten seconds it
 * throbs like a pulse; with flashing turned down the throb is a slow swell and the colour never
 * jumps. It makes way for a screen that would cover it (not for the chat, nor for an inventory that
 * opens below it) and for a hidden HUD (F1); only in the field.
 */
final class FieldHud {
    static final ResourceLocation ID = NullMod.id("field_countdown");
    private static final ResourceLocation BACKDROP = NullMod.id("textures/environment/field/countdown.png");
    private static final int BLOOD = 0x9C1111;
    private static final int BLOOD_BRIGHT = 0xD01C1C;
    private static final int OUTLINE = 0x0E0202;
    private static final int GHOST = 0x4A0606;

    private FieldHud() {
    }

    static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!FieldClientState.active() || FieldClientState.phase() != FieldCycle.Phase.NIGHT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float elapsed = FieldClientState.elapsed(partial);
        float seconds = FieldClientState.countdownSeconds(partial);
        float alpha = Mth.clamp(elapsed / 20.0F, 0.0F, 1.0F) * Mth.clamp(seconds * 2.0F, 0.0F, 1.0F);
        if (alpha < 0.02F) {
            return;
        }
        int total = Mth.ceil(seconds);
        String time = String.format("%02d:%02d", total / 60, total % 60);
        String title = Component.translatable("field.hud.countdown").getString();
        Font font = mc.font;
        float width = graphics.guiWidth();
        float titleScale = width >= 420.0F ? 2.0F : 1.5F;
        float timeScale = titleScale * 1.7F;
        float titleY = 8.0F;
        float timeY = titleY + font.lineHeight * titleScale + 3.0F;
        float centre = width / 2.0F;
        int smearW = (int) Math.min(width * 0.9F, (font.width(title) + 30) * titleScale);
        int smearH = (int) (smearW / 4.0F);
        float smearY = titleY - smearH * 0.22F;
        // Lowest point it reaches (the smear, or the time at the height of its throb).
        float bottom = Math.max(smearY + smearH, timeY + font.lineHeight * timeScale * 1.07F);
        if (mc.screen != null && covers(mc.screen, bottom)) {
            return;
        }

        // The last ten seconds throb: a double beat with flashing allowed, a slow swell without.
        float flashing = Mth.clamp(ClientLimits.flashing(), 0.0F, 1.0F);
        double clock = FieldClientState.time(partial);
        float urgency = seconds < 10.0F ? 1.0F - seconds / 10.0F : 0.0F;
        float beat = flashing >= 0.5F ? heartbeat(clock, 0.9F + urgency) : 0.5F + 0.5F * Mth.sin((float) (clock * 0.12));
        float throb = urgency * beat;
        int color = lerpColor(BLOOD, BLOOD_BRIGHT, throb * Math.max(0.35F, flashing));
        float pulseScale = 1.0F + 0.07F * throb;

        // The smear behind it.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha * 0.9F);
        graphics.blit(BACKDROP, (int) (centre - smearW / 2.0F), (int) smearY, smearW, smearH, 0.0F, 0.0F, 512, 128, 512, 128);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();

        long seed = FieldClientState.seed() ^ FieldClientState.cycle();
        // Every glyph is drawn several times over: batched into one draw instead of a flush per string.
        graphics.drawManaged(() -> {
            text(graphics, font, title, centre, titleY, titleScale, color, alpha, clock, seed, flashing);
            text(graphics, font, time, centre, timeY, timeScale * pulseScale, color, alpha, clock, seed ^ 0x7417L, flashing);
        });
    }

    /**
     * True if an open screen would cover the countdown, which then makes way: any screen but the
     * chat (it leaves the top free) and inventories whose window starts below it (the creative one
     * has its tabs above its window, so it always counts as covering).
     */
    private static boolean covers(Screen screen, float bottom) {
        if (screen instanceof ChatScreen) {
            return false;
        }
        return !(screen instanceof AbstractContainerScreen<?> container) || screen instanceof CreativeModeInventoryScreen
                || container.getGuiTop() < bottom;
    }

    /**
     * Heavy text centred on {@code centre}: a dark outline, a shivering dark double, then the letters,
     * each of which now and then sits a pixel off.
     */
    private static void text(GuiGraphics graphics, Font font, String text, float centre, float y, float scale, int color, float alpha,
                             double clock, long seed, float flashing) {
        float textWidth = font.width(text);
        graphics.pose().pushPose();
        graphics.pose().translate(centre, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        float x0 = -textWidth / 2.0F;
        int a = Mth.clamp((int) (alpha * 255.0F), 4, 255) << 24;
        long step = (long) Math.floor(clock / 5.0);
        // The double: a dark copy a pixel aside, there one moment and gone the next.
        if (FieldClientState.hash01(seed ^ 0x6C6FL, step) < 0.35F + 0.4F * flashing) {
            float gx = FieldClientState.hash01(seed ^ 0x6C70L, step) < 0.5F ? -1.0F : 1.0F;
            int ga = Mth.clamp((int) (alpha * 150.0F), 4, 255) << 24;
            graphics.drawString(font, text, x0 + gx, 0.6F, ga | GHOST, false);
        }
        float x = x0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            float advance = font.width(glyph);
            float jitter = 0.0F;
            if (FieldClientState.hash01(seed + i * 31L, step) < 0.08F) {
                jitter = FieldClientState.hash01(seed - i * 17L, step) < 0.5F ? -1.0F : 1.0F;
            }
            int outline = a | OUTLINE;
            graphics.drawString(font, glyph, x - 1.0F, jitter, outline, false);
            graphics.drawString(font, glyph, x + 1.0F, jitter, outline, false);
            graphics.drawString(font, glyph, x, jitter - 1.0F, outline, false);
            graphics.drawString(font, glyph, x, jitter + 1.0F, outline, false);
            graphics.drawString(font, glyph, x + 1.0F, jitter + 1.0F, outline, false);
            // Drawn twice, a hair apart, so the strokes are thick and heavy.
            graphics.drawString(font, glyph, x, jitter, a | color, false);
            graphics.drawString(font, glyph, x + 0.5F, jitter, a | color, false);
            x += advance;
            i += Character.charCount(codePoint);
        }
        graphics.pose().popPose();
    }

    /** A double heartbeat (lub-dub), {@code rate} beats a second, as 0..1. */
    private static float heartbeat(double clock, float rate) {
        double phase = (clock / 20.0 * rate) % 1.0;
        double lub = Math.exp(-Math.pow((phase - 0.08) / 0.05, 2.0));
        double dub = 0.7 * Math.exp(-Math.pow((phase - 0.3) / 0.06, 2.0));
        return (float) Math.min(1.0, lub + dub);
    }

    private static int lerpColor(int from, int to, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = (int) Mth.lerp(t, from >> 16 & 255, to >> 16 & 255);
        int g = (int) Mth.lerp(t, from >> 8 & 255, to >> 8 & 255);
        int b = (int) Mth.lerp(t, from & 255, to & 255);
        return r << 16 | g << 8 | b;
    }
}
