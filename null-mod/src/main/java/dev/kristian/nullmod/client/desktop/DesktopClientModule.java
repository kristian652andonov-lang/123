package dev.kristian.nullmod.client.desktop;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.config.NullClientSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * Client entry point of desktop interference (see {@link DesktopInterferenceManager}). It is on
 * by default; the player can switch it off in NULL's settings at any time.
 * <ul>
 *   <li>first client tick: settings saved while it was still off by default get it switched on
 *       once ({@link NullClientSettings#applyDesktopDefaults()});</li>
 *   <li>client tick: scheduling, availability, closing windows when the feature is switched off
 *       or the game goes fullscreen;</li>
 *   <li>stage changes: a window shortly after the stage advances (Stage 3 and later);</li>
 *   <li>Esc anywhere in the game closes every NULL window (the windows themselves never take
 *       keyboard focus, so this is how Esc reaches them);</li>
 *   <li>logout and game shutdown close everything; a JVM shutdown hook does the same if the game
 *       ends any other way;</li>
 *   <li>client effect {@code desktop.message} for server events that want a window.</li>
 * </ul>
 * Nothing here touches AWT until the feature is used; only the headless property is switched early
 * (see {@link DesktopProbe#prepareEarly()}).
 */
public final class DesktopClientModule {
    private static boolean defaultsApplied;

    private DesktopClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        DesktopProbe.prepareEarly();
        gameBus.addListener(DesktopClientModule::onClientTick);
        gameBus.addListener(DesktopClientModule::onLoggingOut);
        gameBus.addListener(DesktopClientModule::onGameShuttingDown);
        gameBus.addListener(DesktopClientModule::onKey);
        gameBus.addListener(DesktopClientModule::onScreenKey);
        ClientCorruptionState.addStageListener(DesktopInterferenceManager::onStageChanged);
        ClientEffects.register(DesktopInterferenceManager.effectId(), DesktopInterferenceManager::onEffect);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!defaultsApplied) {
            defaultsApplied = true;
            NullClientSettings.applyDesktopDefaults();
        }
        DesktopInterferenceManager.tick();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DesktopInterferenceManager.onLoggingOut();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        DesktopInterferenceManager.onShuttingDown();
    }

    /** Esc while playing (no screen open). */
    private static void onKey(InputEvent.Key event) {
        if (event.getKey() == InputConstants.KEY_ESCAPE && event.getAction() == InputConstants.PRESS) {
            DesktopInterferenceManager.onEscape();
        }
    }

    /** Esc while a screen is open (the screen usually consumes it before {@link InputEvent.Key}). */
    private static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() == InputConstants.KEY_ESCAPE) {
            DesktopInterferenceManager.onEscape();
        }
    }
}
