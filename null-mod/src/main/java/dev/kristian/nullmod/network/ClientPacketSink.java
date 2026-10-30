package dev.kristian.nullmod.network;

/**
 * Where client-bound payloads end up. The common code only knows this interface; the client
 * installs the real implementation during client setup, so dedicated servers never load any
 * client class.
 */
public interface ClientPacketSink {
    ClientPacketSink NONE = new ClientPacketSink() {
    };

    default void stageSync(StageSyncPayload payload) {
    }

    default void effect(ClientEffectPayload payload) {
    }

    default void fakePlayers(FakePlayersPayload payload) {
    }

    default void transition(TransitionPayload payload) {
    }
}
