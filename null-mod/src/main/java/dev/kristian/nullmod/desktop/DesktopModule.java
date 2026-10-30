package dev.kristian.nullmod.desktop;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;

/**
 * Common side of desktop interference: one rare random event that asks the player's client for a
 * NULL desktop window. The client decides everything else and shows nothing unless that player
 * switched desktop interference on and accepted the explanation; the server never sends text, so
 * only NULL's own messages can appear. Windows are opened only by
 * {@code dev.kristian.nullmod.client.desktop}, never on a dedicated server.
 */
public final class DesktopModule {
    /** Client effect id; {@code data} -1 lets the client pick the kind of window for the stage. */
    public static final String EFFECT = "desktop.message";

    private DesktopModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        NullEventDefinition.builder("desktop_message")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .stageWeights(0, 50, 100, 150, 250)
                .cooldown(Ticks.minutes(15))
                .tags(EventTag.VISUAL)
                .action(context -> {
                    context.sendEffect(EFFECT, Optional.empty(), -1, "");
                    return true;
                })
                .register();
    }
}
