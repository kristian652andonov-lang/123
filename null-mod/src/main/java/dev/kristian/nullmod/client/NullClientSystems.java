package dev.kristian.nullmod.client;

import dev.kristian.nullmod.client.audio.AudioClientModule;
import dev.kristian.nullmod.client.autotest.ClientAutoTest;
import dev.kristian.nullmod.client.fakeplayer.FakePlayerClientModule;
import dev.kristian.nullmod.client.moon.MoonClientModule;
import dev.kristian.nullmod.client.visitor.VisitorClientModule;
import dev.kristian.nullmod.client.visual.VisualClientModule;
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
        AudioClientModule.init(modBus, gameBus);
        MoonClientModule.init(modBus, gameBus);
        VisitorClientModule.init(modBus, gameBus);
        FakePlayerClientModule.init(modBus, gameBus);
        // Last, so its overlay layer sits above every other HUD layer.
        VisualClientModule.init(modBus, gameBus);
        ClientAutoTest.init(gameBus);
    }
}
