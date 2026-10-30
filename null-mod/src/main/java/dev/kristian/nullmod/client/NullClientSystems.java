package dev.kristian.nullmod.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** The list of NULL's client systems and where they are hooked up. */
final class NullClientSystems {
    private NullClientSystems() {
    }

    static void init(IEventBus modBus, IEventBus gameBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
