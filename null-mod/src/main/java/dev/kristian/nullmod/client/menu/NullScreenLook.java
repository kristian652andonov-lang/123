package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.core.CorruptionStage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * NULL's look on every menu of the game (the user: "the custom menu should be on all menus in the
 * game, even the inventory should have a blood grafity in the middle saying "Leave while you can""):
 * <ul>
 *   <li>Outside a world, every screen that draws Minecraft's usual background (the panorama, its
 *       blur and the menu texture) gets NULL's dark {@link MenuBackdrop} on top of it instead:
 *       Select World, Create World and its tabs, Multiplayer, Add/Edit Server, Options and its
 *       sub-screens, the mod list, language, credits, connecting and world-loading screens...
 *       Lists keep their own darker backgrounds and separators, so they stay readable.</li>
 *   <li>Inside a world, menus such as the pause screen and the options keep the game behind them
 *       but get NULL's darkening, vignette and grain, as strong as the world's stage.</li>
 *   <li>The inventory and every other container screen get the blood graffiti
 *       ({@link InventoryGraffiti}).</li>
 * </ul>
 * It hooks NeoForge's {@code ScreenEvent.BackgroundRendered} (fired at the end of
 * {@code Screen#renderBackground}), {@code ScreenEvent.Render.Post} (grain over the finished
 * screen) and {@code ContainerScreenEvent.Render.Background} (between a container's panel and its
 * items). Screens that draw their own background without the vanilla one (the death screen, chat,
 * books, signs, the "Loading terrain" and "Saving world" messages) keep it.
 * <p>
 * The corrupted main menu setting turns all of it off together and gives the vanilla look back.
 * Inside a world NULL's look only appears when the server runs NULL (a synced corruption state).
 */
public final class NullScreenLook {
    /** Darkening, vignette and grain of menus inside a world, per stage of that world (STATIC to HARVEST). */
    private static final float[] IN_WORLD_DARKEN = {0.08F, 0.12F, 0.16F, 0.20F, 0.24F, 0.28F};
    private static final float[] IN_WORLD_VIGNETTE = {0.45F, 0.55F, 0.65F, 0.75F, 0.85F, 0.95F};
    private static final float[] IN_WORLD_GRAIN = {0.030F, 0.035F, 0.040F, 0.050F, 0.060F, 0.070F};

    private static final int GRAIN_NONE = 0;
    private static final int GRAIN_BACKDROP = 1;
    private static final int GRAIN_IN_WORLD = 2;

    private static final SpriteBatch BATCH = new SpriteBatch();
    /** The screen whose background NULL drew this frame; it gets the grain once it has finished drawing. */
    private static Screen grainScreen;
    private static int grainKind = GRAIN_NONE;
    private static int mouseX = Integer.MAX_VALUE;
    private static int mouseY = Integer.MAX_VALUE;
    /** Something failed while drawing: NULL's look stays off for the rest of the session. */
    private static boolean broken;

    private NullScreenLook() {
    }

    static void init(IEventBus gameBus) {
        gameBus.addListener(NullScreenLook::onRenderPre);
        // first, so anything other mods draw over the background stays visible
        gameBus.addListener(EventPriority.HIGHEST, NullScreenLook::onBackgroundRendered);
        gameBus.addListener(EventPriority.LOWEST, NullScreenLook::onRenderPost);
        gameBus.addListener(EventPriority.HIGHEST, NullScreenLook::onContainerBackground);
        gameBus.addListener(NullScreenLook::onLoggingIn);
        gameBus.addListener(NullScreenLook::onLoggingOut);
    }

    /** True while NULL's look should replace the vanilla one (the corrupted main menu setting). */
    public static boolean enabled() {
        return !broken && CorruptedMainMenu.enabled();
    }

    /**
     * For NULL's own screens (and screens imitating vanilla ones) that draw their background
     * themselves: draws NULL's backdrop over the whole screen and returns true, or returns false
     * when the vanilla look is wanted (the setting is off) and the caller should draw its own.
     */
    public static boolean drawBackdrop(Screen screen, GuiGraphics graphics) {
        if (!enabled()) {
            return false;
        }
        try {
            MenuBackdrop.render(graphics, screen.width, screen.height, mouseX, mouseY);
            grainScreen = screen;
            grainKind = GRAIN_BACKDROP;
            if (Minecraft.getInstance().level != null) {
                // drawn inside a world (a screen imitating one outside it): free the art again soon after
                MenuClientModule.scheduleRelease(MenuClientModule.RELEASE_GRACE_MS);
            }
            return true;
        } catch (RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** True while a screen outside a world shows NULL's backdrop (the menu art must stay loaded). */
    static boolean backdropShowing() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null && minecraft.screen != null && enabled();
    }

    // ---------------------------------------------------------------------------------------
    // events

    private static void onRenderPre(ScreenEvent.Render.Pre event) {
        // NeoForge passes Integer.MAX_VALUE to screens layered below the top one
        if (event.getMouseX() != Integer.MAX_VALUE) {
            mouseX = event.getMouseX();
            mouseY = event.getMouseY();
        }
    }

    // NeoForge marks this event for removal in a later version; in 21.1 it is the hook that runs
    // right after Screen#renderBackground (panorama, blur and menu texture) and before the widgets
    @SuppressWarnings({"deprecation", "removal"})
    private static void onBackgroundRendered(ScreenEvent.BackgroundRendered event) {
        if (!enabled()) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen instanceof CorruptedMainMenu) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        try {
            if (minecraft.level == null) {
                MenuBackdrop.render(graphics, screen.width, screen.height, mouseX, mouseY);
                grainScreen = screen;
                grainKind = GRAIN_BACKDROP;
            } else if (ClientCorruptionState.synced()) {
                renderInWorld(graphics, screen.width, screen.height, ClientCorruptionState.stage());
                grainScreen = screen;
                grainKind = GRAIN_IN_WORLD;
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void onRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = grainScreen;
        if (screen == null || event.getScreen() != screen) {
            return;
        }
        grainScreen = null;
        if (!enabled()) {
            return;
        }
        try {
            if (grainKind == GRAIN_BACKDROP) {
                MenuBackdrop.renderGrain(event.getGuiGraphics(), screen.width, screen.height);
            } else if (grainKind == GRAIN_IN_WORLD && ClientCorruptionState.synced()) {
                renderInWorldGrain(event.getGuiGraphics(), screen.width, screen.height, ClientCorruptionState.stage());
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void onContainerBackground(ContainerScreenEvent.Render.Background event) {
        if (!enabled() || !ClientCorruptionState.synced()) {
            return;
        }
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        try {
            InventoryGraffiti.render(event.getGuiGraphics(), screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(), screen.getYSize(),
                    ClientCorruptionState.stage());
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // the next screen outside a world starts a new composition
        MenuBackdrop.forget();
        grainScreen = null;
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        InventoryGraffiti.release();
        grainScreen = null;
    }

    // ---------------------------------------------------------------------------------------
    // inside a world

    private static void renderInWorld(GuiGraphics graphics, int width, int height, CorruptionStage stage) {
        int i = index(stage);
        graphics.fill(0, 0, width, height, MenuMath.argb(0x000000, IN_WORLD_DARKEN[i]));
        MenuArt art = MenuArt.get();
        if (art.vignette != null && BATCH.begin(graphics, art.vignette, false)) {
            BATCH.quad(0.0F, 0.0F, width, height, 0.0F, 0.0F, 1.0F, 1.0F, MenuMath.argb(0xFFFFFF, IN_WORLD_VIGNETTE[i]));
            BATCH.end();
        }
    }

    private static void renderInWorldGrain(GuiGraphics graphics, int width, int height, CorruptionStage stage) {
        float glitch = MenuMath.clamp01((float) NullClientSettings.get(NullClientSettings.MENU_GLITCH_INTENSITY));
        boolean animated = NullClientSettings.get(NullClientSettings.ANIMATED_BACKGROUND)
                && NullClientSettings.get(NullClientSettings.BACKGROUND_ANIMATION_INTENSITY) >= 0.02;
        float alpha = IN_WORLD_GRAIN[index(stage)] * (0.55F + 0.45F * glitch);
        float time = (Util.getMillis() % 3_600_000L) / 1000.0F;
        MainMenuBackgroundRenderer.drawGrain(graphics, BATCH, MenuArt.get(), alpha, animated, width, height, time);
    }

    private static int index(CorruptionStage stage) {
        return Math.max(0, Math.min(IN_WORLD_DARKEN.length - 1, stage.number() - 1));
    }

    private static void fail(RuntimeException e) {
        BATCH.abort();
        MenuBackdrop.abort();
        grainScreen = null;
        if (!broken) {
            broken = true;
            NullMod.LOGGER.error("NULL could not draw its look on a menu; menus keep the vanilla look for this session", e);
        }
    }
}
