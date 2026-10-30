package dev.kristian.nullmod.client.visitor;

import dev.kristian.nullmod.entity.visitor.VisitorEntity;
import dev.kristian.nullmod.entity.visitor.VisitorModule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client side of the Visitors: they are drawn exactly like players. */
public final class VisitorClientModule {
    private VisitorClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(VisitorClientModule::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(VisitorModule.VISITOR.get(), context -> new PlayerLikeRenderer<VisitorEntity>(context));
    }
}
