package dev.kristian.nullmod.client.settings;

import dev.kristian.nullmod.NullMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entry point of NULL's settings: the mod list's "Config" button opens
 * {@link NullSettingsScreen}, whose "All options..." button leads on to NeoForge's generic
 * configuration screen for the mod.
 */
public final class SettingsClientModule {
    private static ModContainer container;

    private SettingsClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus, ModContainer modContainer) {
        container = modContainer;
        IConfigScreenFactory factory = (mod, parent) -> new NullSettingsScreen(parent);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, factory);
    }

    /** NULL's mod container (for NeoForge's configuration screen), or null if it cannot be found. */
    static ModContainer container() {
        if (container == null) {
            try {
                container = ModList.get().getModContainerById(NullMod.MODID).orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return container;
    }
}
