package dev.kristian.nullmod.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * "You can't quit.": the pause menu's Save and Quit / Disconnect button and the corrupted title
 * screen's Quit Game button refuse the first two presses, with the words written in blood across the
 * screen. The third press in a row really quits, so nobody is ever trapped (a killed game can lose
 * its world). Switching the corrupted menu off in NULL's settings gives the normal buttons back.
 */
public final class QuitRefusal {
    /** Presses that are refused before the next one goes through. */
    private static final int REFUSALS = 2;
    /** How long the words stay on screen, in milliseconds. */
    private static final long SHOW_MS = 2600L;
    private static final ResourceLocation SMEAR = NullMod.id("textures/environment/field/countdown.png");
    private static final Component TEXT = Component.translatable("null.menu.cant_quit");
    private static final Component RETURN_TO_MENU = Component.translatable("menu.returnToMenu");
    private static final Component DISCONNECT = Component.translatable("menu.disconnect");
    private static final RandomSource RANDOM = RandomSource.create();

    private static int refused;
    private static long shownAt = -1L;
    private static long seed;
    private static Screen shownOn;

    private QuitRefusal() {
    }

    static void init(IEventBus gameBus) {
        gameBus.addListener(QuitRefusal::onScreenInit);
        gameBus.addListener(QuitRefusal::onScreenRender);
    }

    /** True when the refusal is in effect: the corrupted menu is switched on. */
    static boolean active() {
        return CorruptedMainMenu.enabled();
    }

    /**
     * Runs {@code quit} only after {@link #REFUSALS} refused presses on this screen; otherwise
     * shows "You can't quit." over {@code screen}.
     */
    static void attempt(Screen screen, Runnable quit) {
        if (!active() || refused >= REFUSALS) {
            refused = 0;
            shownAt = -1L;
            quit.run();
            return;
        }
        refused++;
        shownAt = Util.getMillis();
        seed = RANDOM.nextLong();
        shownOn = screen;
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(NullSounds.STATIC_BURST.get(), 0.55F + 0.1F * refused, 0.9F));
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen) || !active() || !ClientCorruptionState.synced()) {
            return;
        }
        refused = 0;
        List<GuiEventListener> listeners = List.copyOf(event.getListenersList());
        for (GuiEventListener listener : listeners) {
            if (listener instanceof Button old && (old.getMessage().equals(RETURN_TO_MENU) || old.getMessage().equals(DISCONNECT))) {
                Button replacement = Button.builder(old.getMessage(), b -> attempt(screen, old::onPress))
                        .bounds(old.getX(), old.getY(), old.getWidth(), old.getHeight())
                        .build();
                event.removeListener(old);
                event.addListener(replacement);
            }
        }
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (shownAt < 0L || event.getScreen() != shownOn) {
            return;
        }
        long age = Util.getMillis() - shownAt;
        if (age > SHOW_MS) {
            shownAt = -1L;
            return;
        }
        draw(event.getGuiGraphics(), event.getScreen(), age / (float) SHOW_MS);
    }

    /** The words in blood across the middle of the screen; t runs from 0 to 1 while they show. */
    private static void draw(GuiGraphics graphics, Screen screen, float t) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        String text = TEXT.getString();
        float alpha = t < 0.08F ? t / 0.08F : t > 0.8F ? (1.0F - t) / 0.2F : 1.0F;
        float scale = screen.width >= 420 ? 3.0F : 2.0F;
        float textWidth = font.width(text) * scale;
        float centreX = screen.width / 2.0F;
        float centreY = screen.height / 2.0F;

        // A dark veil and the dried-blood smear behind the words.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        graphics.fill(0, 0, screen.width, screen.height, (int) (alpha * 150.0F) << 24);
        int smearW = (int) Math.min(screen.width * 0.95F, textWidth + 60.0F * scale);
        int smearH = (int) Math.max(smearW / 4.0F, 42.0F * scale);
        float capsCentre = centreY;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(SMEAR, (int) (centreX - smearW / 2.0F), (int) (capsCentre - smearH * 0.352F), smearW, smearH,
                0.0F, 0.0F, 512, 128, 512, 128);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // The words, each letter a little unsteady, wet red on a black edge, with drips running down.
        RandomSource random = RandomSource.create(seed + (long) (t * 14.0F));
        int a = Math.max(4, (int) (alpha * 255.0F)) << 24;
        graphics.pose().translate(centreX - textWidth / 2.0F, centreY - 3.5F * scale, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        float x = 0.0F;
        RandomSource drips = RandomSource.create(seed);
        for (int i = 0; i < text.length(); i++) {
            String glyph = String.valueOf(text.charAt(i));
            float advance = font.width(glyph);
            float jitter = (random.nextFloat() - 0.5F) * 0.8F;
            int outline = a | 0x1A0000;
            graphics.drawString(font, glyph, x - 1.0F, jitter, outline, false);
            graphics.drawString(font, glyph, x + 1.0F, jitter, outline, false);
            graphics.drawString(font, glyph, x, jitter - 1.0F, outline, false);
            graphics.drawString(font, glyph, x, jitter + 1.0F, outline, false);
            graphics.drawString(font, glyph, x, jitter, a | 0x9A0606, false);
            if (!glyph.isBlank() && drips.nextFloat() < 0.45F) {
                int dx = (int) (x + 1.0F + drips.nextInt(Math.max(1, (int) advance - 2)));
                int length = (int) (2.0F + drips.nextFloat() * 9.0F * Math.min(1.0F, t * 2.5F));
                graphics.fill(dx, 7, dx + 1, 7 + length, a | 0x6E0303);
                graphics.fill(dx, 7 + length, dx + 1, 8 + length, a | 0x8A0404);
            }
            x += advance;
        }
        graphics.pose().popPose();
    }
}
