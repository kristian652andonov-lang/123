package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.NullMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.Musics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/**
 * Client entry point of the corrupted main menu and of NULL's look on every other menu.
 * <ul>
 *   <li>Swaps the vanilla {@link TitleScreen} for {@link CorruptedMainMenu} while
 *       {@code CUSTOM_MAIN_MENU} is on, and back again when it is switched off.</li>
 *   <li>NULL's backdrop behind the other screens, darkening and grain on menus inside a world and
 *       the blood graffiti in container screens ({@link NullScreenLook}).</li>
 *   <li>Keeps the cheerful vanilla menu music away while the corrupted menu (and the screens
 *       opened from it, before a world is joined) plays its own music.</li>
 *   <li>Frees the menu's textures once no screen has shown them for a while (at once when a
 *       world is joined) and re-reads its art after a resource reload.</li>
 * </ul>
 */
public final class MenuClientModule {
    /** How long the art stays loaded after the menu closes, so sub-screens can return to it without a hitch. */
    static final long RELEASE_GRACE_MS = 30_000L;

    private static long releaseAtMs = -1L;

    private MenuClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        gameBus.addListener(MenuClientModule::onScreenOpening);
        gameBus.addListener(MenuClientModule::onSelectMusic);
        gameBus.addListener(MenuClientModule::onLoggingIn);
        gameBus.addListener(MenuClientModule::onClientTick);
        modBus.addListener(MenuClientModule::onRegisterReloadListeners);
        NullScreenLook.init(gameBus);
    }

    /** The menu was removed: free its textures after {@code delayMs} unless it comes back first. */
    static void scheduleRelease(long delayMs) {
        releaseAtMs = Util.getMillis() + Math.max(0L, delayMs);
    }

    /** The menu is showing again; keep its textures. */
    static void cancelRelease() {
        releaseAtMs = -1L;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (releaseAtMs < 0L) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof CorruptedMainMenu) {
            releaseAtMs = -1L;
        } else if (NullScreenLook.backdropShowing()) {
            // another screen outside a world still shows the faces and the fog
            releaseAtMs = Util.getMillis() + RELEASE_GRACE_MS;
        } else if (Util.getMillis() >= releaseAtMs) {
            releaseAtMs = -1L;
            MenuArt.releaseTextures();
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null) {
            return;
        }
        try {
            if (next.getClass() == TitleScreen.class) {
                if (CorruptedMainMenu.enabled()) {
                    event.setNewScreen(new CorruptedMainMenu());
                }
            } else if (next instanceof CorruptedMainMenu && !CorruptedMainMenu.enabled()) {
                // the corrupted menu was switched off while one of its sub-screens was open
                event.setNewScreen(new TitleScreen());
                MenuArt.releaseTextures();
            }
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL could not swap the title screen; keeping the vanilla one", e);
        }
    }

    private static void onSelectMusic(SelectMusicEvent event) {
        if (event.getMusic() != Musics.MENU) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.player != null) {
            return;
        }
        if (CorruptedMainMenu.enabled()) {
            // The corrupted menu has its own music, which always plays (MenuAudio).
            event.setMusic(null);
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        releaseAtMs = -1L;
        MenuArt.releaseTextures();
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            MenuArt.invalidate();
            MenuBackdrop.forget();
            InventoryGraffiti.invalidate();
        });
    }
}
