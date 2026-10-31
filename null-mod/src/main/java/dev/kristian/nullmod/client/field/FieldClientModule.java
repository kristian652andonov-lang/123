package dev.kristian.nullmod.client.field;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.network.FieldPayload;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client entry point of the field (Stage 6): its special effects (sky, light, fog), the countdown,
 * the ambience, and the cycle sent by the server ({@link FieldPayload}, through
 * {@code ClientPacketHandler}). The black-screen move into and out of the field is the NULL
 * dimension's own transition sequence, which reacts to the same transition payloads.
 */
public final class FieldClientModule {
    private static final int MAX_FAILURES = 20;
    private static int failures;
    @Nullable
    private static ClientLevel lastLevel;

    private FieldClientModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(FieldClientModule::onRegisterEffects);
        modBus.addListener(FieldClientModule::onRegisterGuiLayers);
        gameBus.addListener(FieldClientModule::onClientTick);
        gameBus.addListener(FieldClientModule::onLoggingOut);
        // The fog's distance after the visual system, which may already have pulled it in (and
        // cancelled the event to apply that): the field's own fog is laid over what it left, so
        // both count. Its colour before the visual system, which darkens and tints it on top.
        gameBus.addListener(EventPriority.LOW, true, FieldFog::onRenderFog);
        gameBus.addListener(EventPriority.HIGH, FieldFog::onFogColor);
        gameBus.addListener(FieldAmbience::onSelectMusic);
    }

    /** A {@link FieldPayload} arrived (render thread). */
    public static void onPayload(FieldPayload payload) {
        FieldClientState.accept(payload);
    }

    private static void onRegisterEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(FieldDimension.FIELD_EFFECTS, new FieldDimensionEffects());
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, FieldHud.ID, FieldHud::render);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (failures >= MAX_FAILURES) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level != lastLevel) {
                boolean leftField = lastLevel != null && FieldDimension.isField(lastLevel);
                lastLevel = level;
                if (leftField) {
                    FieldSkyRenderer.close();
                }
            }
            FieldClientState.tick(mc);
            FieldAmbience.tick(mc);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("Field client tick failed ({} of {})", failures, MAX_FAILURES, e);
            if (failures >= MAX_FAILURES) {
                FieldAmbience.reset();
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FieldClientState.reset();
        FieldAmbience.reset();
        FieldSkyRenderer.close();
        lastLevel = null;
    }
}
