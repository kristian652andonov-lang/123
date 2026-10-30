package dev.kristian.nullmod.network;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers NULL's payloads and sends them. All payloads are server to client. */
public final class NullNetworking {
    private static final String PROTOCOL = "1";
    private static volatile ClientPacketSink clientSink = ClientPacketSink.NONE;

    private NullNetworking() {
    }

    /** Installed by the client during construction. */
    public static void setClientSink(ClientPacketSink sink) {
        clientSink = sink;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        registrar.playToClient(StageSyncPayload.TYPE, StageSyncPayload.STREAM_CODEC,
                (payload, context) -> clientSink.stageSync(payload));
        registrar.playToClient(ClientEffectPayload.TYPE, ClientEffectPayload.STREAM_CODEC,
                (payload, context) -> clientSink.effect(payload));
        registrar.playToClient(FakePlayersPayload.TYPE, FakePlayersPayload.STREAM_CODEC,
                (payload, context) -> clientSink.fakePlayers(payload));
        registrar.playToClient(TransitionPayload.TYPE, TransitionPayload.STREAM_CODEC,
                (payload, context) -> clientSink.transition(payload));
    }

    /**
     * Sends a payload to one player. Players whose client does not have NULL's channels
     * (GameTest mock players, or anything else without the mod) are skipped, because
     * NeoForge refuses to send them unknown payloads.
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || !player.connection.hasChannel(payload)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Starts a client effect for one player only. */
    public static void sendEffect(ServerPlayer player, String effect, long seed, float intensity, int duration) {
        sendToPlayer(player, new ClientEffectPayload(effect, seed, intensity, duration));
    }

    public static void sendEffect(ServerPlayer player, String effect, long seed, float intensity, int duration, BlockPos pos) {
        sendToPlayer(player, new ClientEffectPayload(effect, seed, intensity, duration, pos));
    }

    public static void sendEffect(ServerPlayer player, String effect, long seed, float intensity, int duration,
                                  Optional<BlockPos> pos, int data, String text) {
        sendToPlayer(player, new ClientEffectPayload(effect, seed, intensity, duration, pos, data, text));
    }
}
