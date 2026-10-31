package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, to players in the field: where its day and night stand. Sent on arrival, on
 * joining, on every phase change and every ten seconds; the client counts on from there.
 *
 * @param phase   {@code FieldCycle.Phase#id()}: DAY, SHATTER, NIGHT, HUNT, DAWN
 * @param elapsed ticks already spent in the phase
 * @param length  nominal length of the phase in ticks (the countdown for NIGHT)
 * @param cycle   how many days have passed (each day the sun cracks differently)
 * @param seed    seed of the sun's crack pattern (derived from NULL's public seed, not the world seed)
 */
public record FieldPayload(int phase, int elapsed, int length, int cycle, long seed) implements CustomPacketPayload {
    public static final Type<FieldPayload> TYPE = new Type<>(NullMod.id("field"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FieldPayload> STREAM_CODEC =
            StreamCodec.ofMember(FieldPayload::write, FieldPayload::read);

    private static FieldPayload read(RegistryFriendlyByteBuf buf) {
        return new FieldPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(phase);
        buf.writeVarInt(elapsed);
        buf.writeVarInt(length);
        buf.writeVarInt(cycle);
        buf.writeLong(seed);
    }

    @Override
    public Type<FieldPayload> type() {
        return TYPE;
    }
}
