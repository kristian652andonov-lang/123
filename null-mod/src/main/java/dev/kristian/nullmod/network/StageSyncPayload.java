package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the world's corruption state. Sent on join, on every change and every 20 seconds. */
public record StageSyncPayload(
        int stage,
        int highestStage,
        float stageProgress,
        float overallProgress,
        float moonProgress,
        boolean moonGone,
        boolean paused,
        long seed) implements CustomPacketPayload {

    public static final Type<StageSyncPayload> TYPE = new Type<>(NullMod.id("stage_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StageSyncPayload> STREAM_CODEC =
            StreamCodec.ofMember(StageSyncPayload::write, StageSyncPayload::read);

    private static StageSyncPayload read(RegistryFriendlyByteBuf buf) {
        return new StageSyncPayload(
                buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readBoolean(), buf.readBoolean(), buf.readLong());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(stage);
        buf.writeVarInt(highestStage);
        buf.writeFloat(stageProgress);
        buf.writeFloat(overallProgress);
        buf.writeFloat(moonProgress);
        buf.writeBoolean(moonGone);
        buf.writeBoolean(paused);
        buf.writeLong(seed);
    }

    @Override
    public Type<StageSyncPayload> type() {
        return TYPE;
    }
}
