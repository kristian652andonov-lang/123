package dev.kristian.nullmod.client.desktop;

import dev.kristian.nullmod.NullMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * NULL's desktop windows while Minecraft runs in full screen. A full-screen game covers every
 * other window, so the same window (same text, look, placement and lifetime) is drawn inside the
 * game instead: above the HUD, and above any open screen, where a click on it closes it. Render
 * thread only.
 */
final class InGameDesktopWindows {
    private static final int MAX_OPEN = 3;
    private static final int PAD = 6;
    private static final int GAP = 3;
    private static final int BG = 0xEC050505;
    private static final int BORDER = 0xFF4A4A4A;
    private static final int BORDER_RED = 0xFF7A1010;
    private static final int WHITE = 0xFFE6E6E6;
    private static final int DIM = 0xFF8C8C8C;
    private static final int RED = 0xFFD42A2A;
    private static final Random RANDOM = new Random();

    private static final List<Open> OPEN = new ArrayList<>();

    private InGameDesktopWindows() {
    }

    static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener((RegisterGuiLayersEvent event) ->
                event.registerAboveAll(NullMod.id("desktop_in_game"), InGameDesktopWindows::renderHud));
        gameBus.addListener(InGameDesktopWindows::onScreenRender);
        gameBus.addListener(InGameDesktopWindows::onScreenClick);
    }

    /** True while the game covers the whole screen, so desktop windows could not be seen. */
    static boolean fullscreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getWindow() != null && mc.getWindow().isFullscreen();
    }

    /** Shows a window inside the game (from any thread; it is handed to the render thread). */
    static void show(DesktopWindowSpec spec) {
        Minecraft.getInstance().execute(() -> {
            if (OPEN.size() >= MAX_OPEN) {
                OPEN.remove(0);
            }
            OPEN.add(new Open(spec, Util.getMillis()));
        });
    }

    /** Closes every in-game window at once (settings switched off, logout, test). */
    static void closeAll() {
        Minecraft.getInstance().execute(OPEN::clear);
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker delta) {
        if (Minecraft.getInstance().screen == null) {
            render(graphics);
        }
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        render(event.getGuiGraphics());
    }

    private static void onScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        for (int i = OPEN.size() - 1; i >= 0; i--) {
            Open w = OPEN.get(i);
            if (w.width > 0 && event.getMouseX() >= w.x && event.getMouseX() < w.x + w.width
                    && event.getMouseY() >= w.y && event.getMouseY() < w.y + w.height) {
                OPEN.remove(i);
                event.setCanceled(true);
                return;
            }
        }
    }

    private static void render(GuiGraphics graphics) {
        if (OPEN.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        OPEN.removeIf(w -> now - w.start > w.spec.lifetimeMs());
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 450.0F);
        for (Open w : OPEN) {
            draw(graphics, font, w, now);
        }
        graphics.pose().popPose();
    }

    private static void draw(GuiGraphics graphics, Font font, Open w, long now) {
        DesktopWindowSpec spec = w.spec;
        boolean big = spec.layout() == DesktopWindowSpec.Layout.BIG_TEXT;
        float scale = big ? 2.0F : 1.0F;
        int lineH = (int) (font.lineHeight * scale) + GAP;
        int labelW = 0;
        int textW = 0;
        for (DesktopWindowSpec.Line line : spec.lines()) {
            labelW = Math.max(labelW, font.width(line.label()));
            textW = Math.max(textW, (int) (font.width(line.text()) * scale));
        }
        int column = labelW > 0 ? labelW + 10 : 0;
        int contentW = Math.max(column + textW, Math.max(font.width(spec.header()), font.width(spec.footer())));
        w.width = Math.min(graphics.guiWidth() - 8, contentW + PAD * 2);
        int headerH = spec.header().isEmpty() ? 0 : font.lineHeight + GAP + 4;
        int footerH = spec.footer().isEmpty() ? 0 : font.lineHeight + GAP + 2;
        w.height = PAD * 2 + headerH + spec.lines().size() * lineH + footerH;
        if (w.x < 0) {
            place(w, graphics.guiWidth(), graphics.guiHeight());
        }

        // Fades in and out like the real window (slowly when flashing is turned down).
        long age = now - w.start;
        long fade = spec.slowFades() ? 900L : 250L;
        float alpha = Math.min(1.0F, Math.min(age / (float) fade, (spec.lifetimeMs() - age) / (float) fade));
        if (!spec.slowFades() && spec.flicker() > 0.0F && RANDOM.nextFloat() < spec.flicker() * 0.05F) {
            alpha *= 0.35F;
        }
        if (alpha <= 0.02F) {
            return;
        }
        int x = w.x;
        int y = w.y;
        graphics.fill(x, y, x + w.width, y + w.height, withAlpha(BG, alpha));
        graphics.renderOutline(x, y, w.width, w.height, withAlpha(spec.redAccent() ? BORDER_RED : BORDER, alpha));
        int cy = y + PAD;
        if (headerH > 0) {
            graphics.drawString(font, spec.header(), x + PAD, cy, withAlpha(WHITE, alpha), false);
            graphics.drawString(font, spec.header(), x + PAD + 1, cy, withAlpha(WHITE, alpha), false);
            int ruleY = cy + font.lineHeight + 2;
            for (int dx = x + PAD; dx < x + w.width - PAD; dx += 4) {
                graphics.fill(dx, ruleY, Math.min(dx + 2, x + w.width - PAD), ruleY + 1, withAlpha(DIM, alpha));
            }
            cy += headerH;
        }
        for (DesktopWindowSpec.Line line : spec.lines()) {
            if (!line.label().isEmpty()) {
                graphics.drawString(font, line.label(), x + PAD, cy, withAlpha(DIM, alpha), false);
            }
            int color = switch (line.tone()) {
                case WARNING -> RED;
                case DIM -> DIM;
                default -> WHITE;
            };
            graphics.pose().pushPose();
            graphics.pose().translate(x + PAD + column, cy, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, line.text(), 0, 0, withAlpha(color, alpha), false);
            graphics.pose().popPose();
            cy += lineH;
        }
        if (footerH > 0) {
            graphics.drawString(font, spec.footer(), x + PAD, cy + 2, withAlpha(DIM, alpha), false);
        }
    }

    /** Where it appears, the way the desktop window would on the screen. */
    private static void place(Open w, int screenW, int screenH) {
        int maxX = Math.max(4, screenW - w.width - 4);
        int maxY = Math.max(4, screenH - w.height - 4);
        switch (w.spec.placement()) {
            case CORNER -> {
                boolean right = RANDOM.nextBoolean();
                boolean bottom = RANDOM.nextBoolean();
                w.x = right ? maxX : 4;
                w.y = bottom ? maxY : 4;
            }
            case CENTRE -> {
                w.x = Math.max(4, Math.min(maxX, (screenW - w.width) / 2 + RANDOM.nextInt(41) - 20));
                w.y = Math.max(4, Math.min(maxY, (screenH - w.height) / 2 + RANDOM.nextInt(31) - 15));
            }
            default -> {
                w.x = 4 + RANDOM.nextInt(Math.max(1, maxX - 3));
                w.y = 4 + RANDOM.nextInt(Math.max(1, maxY - 3));
            }
        }
    }

    private static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static final class Open {
        final DesktopWindowSpec spec;
        final long start;
        int x = -1;
        int y = -1;
        int width;
        int height;

        Open(DesktopWindowSpec spec, long start) {
            this.spec = spec;
            this.start = start;
        }
    }
}
