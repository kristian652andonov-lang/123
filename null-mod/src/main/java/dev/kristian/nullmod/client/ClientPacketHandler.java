package dev.kristian.nullmod.client;

import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.client.variant.ClientMobVariants;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.network.ClientPacketSink;
import dev.kristian.nullmod.network.FakePlayersPayload;
import dev.kristian.nullmod.network.MobVariantPayload;
import dev.kristian.nullmod.network.StageSyncPayload;
import dev.kristian.nullmod.network.TransitionPayload;

/** Receives NULL's payloads on the client (already on the render thread). */
final class ClientPacketHandler implements ClientPacketSink {
    @Override
    public void stageSync(StageSyncPayload payload) {
        ClientCorruptionState.apply(payload);
    }

    @Override
    public void effect(ClientEffectPayload payload) {
        ClientEffects.dispatch(payload);
    }

    @Override
    public void fakePlayers(FakePlayersPayload payload) {
        ClientCorruptionState.applyFakePlayers(payload);
    }

    @Override
    public void transition(TransitionPayload payload) {
        ClientCorruptionState.applyTransition(payload);
    }

    @Override
    public void mobVariant(MobVariantPayload payload) {
        ClientMobVariants.accept(payload);
    }
}
