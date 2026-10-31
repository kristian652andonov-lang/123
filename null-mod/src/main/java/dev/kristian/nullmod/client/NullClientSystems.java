package dev.kristian.nullmod.client;

import dev.kristian.nullmod.client.audio.AudioClientModule;
import dev.kristian.nullmod.client.autotest.ClientAutoTest;
import dev.kristian.nullmod.client.desktop.DesktopClientModule;
import dev.kristian.nullmod.client.dimension.DimensionClientModule;
import dev.kristian.nullmod.client.fakeplayer.FakePlayerClientModule;
import dev.kristian.nullmod.client.field.FieldClientModule;
import dev.kristian.nullmod.client.it.ItClientModule;
import dev.kristian.nullmod.client.menu.MenuClientModule;
import dev.kristian.nullmod.client.moon.MoonClientModule;
import dev.kristian.nullmod.client.nullroot.NullrootClientModule;
import dev.kristian.nullmod.client.settings.SettingsClientModule;
import dev.kristian.nullmod.client.visitor.VisitorClientModule;
import dev.kristian.nullmod.client.visual.VisualClientModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** The list of NULL's client systems and where they are hooked up. */
final class NullClientSystems {
    private NullClientSystems() {
    }

    static void init(IEventBus modBus, IEventBus gameBus, ModContainer container) {
        SettingsClientModule.init(modBus, gameBus, container);
        MenuClientModule.init(modBus, gameBus);
        DesktopClientModule.init(modBus, gameBus);
        AudioClientModule.init(modBus, gameBus);
        MoonClientModule.init(modBus, gameBus);
        VisitorClientModule.init(modBus, gameBus);
        FakePlayerClientModule.init(modBus, gameBus);
        NullrootClientModule.init(modBus, gameBus);
        DimensionClientModule.init(modBus, gameBus);
        FieldClientModule.init(modBus, gameBus);
        ItClientModule.init(modBus, gameBus);
        // Last, so its overlay layer sits above every other HUD layer.
        VisualClientModule.init(modBus, gameBus);
        ClientAutoTest.init(gameBus);
    }
}
