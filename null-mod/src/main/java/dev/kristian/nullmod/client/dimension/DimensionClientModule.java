package dev.kristian.nullmod.client.dimension;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.dimension.DimensionEvents;
import dev.kristian.nullmod.registry.NullDimensions;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

/**
 * Client entry point of the NULL dimension: its special effects (sky, fog, lightmap), the
 * transition sequence, the dimension's atmosphere and ambience, and the {@code dimension.*}
 * client effects.
 */
public final class DimensionClientModule {
    private static final int MAX_FAILURES = 20;
    private static int failures;
    @Nullable
    private static ClientLevel lastLevel;

    private DimensionClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(DimensionClientModule::onRegisterEffects);
        gameBus.addListener(DimensionClientModule::onClientTick);
        gameBus.addListener(DimensionClientModule::onLoggingOut);
        // Before the visual system, which darkens and tints the fog on top of NULL's own.
        gameBus.addListener(EventPriority.HIGH, NullFog::onRenderFog);
        gameBus.addListener(EventPriority.HIGH, NullFog::onFogColor);
        ClientEffects.register(DimensionEvents.EFFECT_BLACKOUT, DimensionAtmosphere::onBlackout);
        ClientEffects.register(DimensionEvents.EFFECT_PULSE, DimensionAtmosphere::onPulse);
        ClientCorruptionState.addTransitionListener(TransitionSequence::onPayload);
    }

    private static void onRegisterEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(NullDimensions.NULL_EFFECTS, new NullDimensionEffects());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (failures >= MAX_FAILURES) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level != lastLevel) {
                boolean leftNull = lastLevel != null && NullDimensions.isNull(lastLevel);
                lastLevel = level;
                DimensionAtmosphere.reset();
                if (leftNull) {
                    NullSkyRenderer.close();
                }
            }
            boolean inNull = level != null && NullDimensions.isNull(level);
            TransitionSequence.tick(mc);
            DimensionAtmosphere.tick(mc, inNull, TransitionSequence.active());
            DimensionAmbience.tick(mc, inNull);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL dimension client tick failed ({} of {})", failures, MAX_FAILURES, e);
            if (failures >= MAX_FAILURES) {
                // Never leave the screen dark or the game silent because this broke.
                TransitionSequence.clear();
                DimensionAmbience.reset();
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TransitionSequence.reset();
        DimensionAtmosphere.reset();
        DimensionAmbience.reset();
        NullSkyRenderer.close();
        lastLevel = null;
    }
}
