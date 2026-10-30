package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to one client: start a client-side effect (visual, audio, fake block change...).
 * The server decides when and for whom; the client only renders. Effect ids are registered
 * on the client in {@code ClientEffects}; unknown ids are ignored.
 *
 * @param effect    effect id such as {@code "visual.blackout"}
 * @param seed      randomness for the effect, so it is reproducible
 * @param intensity 0..1 (already scaled by the server's intensity setting)
 * @param duration  length in ticks (effects may treat it as a hint)
 * @param pos       optional world position the effect is anchored to
 * @param data      small extra integer (variant, count...)
 * @param text      optional text (chat lines, glitch text); empty when unused
 */
public record ClientEffectPayload(
        String effect,
        long seed,
        float intensity,
        int duration,
        Optional<BlockPos> pos,
        int data,
        String text) implements CustomPacketPayload {

    public static final Type<ClientEffectPayload> TYPE = new Type<>(NullMod.id("client_effect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientEffectPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientEffectPayload::write, ClientEffectPayload::read);

    public ClientEffectPayload(String effect, long seed, float intensity, int duration) {
        this(effect, seed, intensity, duration, Optional.empty(), 0, "");
    }

    public ClientEffectPayload(String effect, long seed, float intensity, int duration, BlockPos pos) {
        this(effect, seed, intensity, duration, Optional.of(pos), 0, "");
    }

    private static ClientEffectPayload read(RegistryFriendlyByteBuf buf) {
        String effect = buf.readUtf(128);
        long seed = buf.readLong();
        float intensity = buf.readFloat();
        int duration = buf.readVarInt();
        Optional<BlockPos> pos = buf.readBoolean() ? Optional.of(buf.readBlockPos()) : Optional.empty();
        int data = buf.readVarInt();
        String text = buf.readUtf(1024);
        return new ClientEffectPayload(effect, seed, intensity, duration, pos, data, text);
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(effect, 128);
        buf.writeLong(seed);
        buf.writeFloat(intensity);
        buf.writeVarInt(duration);
        buf.writeBoolean(pos.isPresent());
        pos.ifPresent(buf::writeBlockPos);
        buf.writeVarInt(data);
        buf.writeUtf(text, 1024);
    }

    @Override
    public Type<ClientEffectPayload> type() {
        return TYPE;
    }
}
