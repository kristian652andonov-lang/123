package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: drives the cinematic move into (or out of) the NULL dimension.
 *
 * @param phase    one of the PHASE_ constants
 * @param duration ticks the phase lasts on the server
 */
public record TransitionPayload(int phase, int duration) implements CustomPacketPayload {
    /** Everything stops, all sound is cut and the screen fades to black. */
    public static final int PHASE_COLLAPSE = 0;
    /** The player has been moved; stay black, start the distorted sound, then fade back in. */
    public static final int PHASE_ARRIVE = 1;
    /** The player is being returned to the overworld (stage moved back by an administrator). */
    public static final int PHASE_RETURN = 2;
    /** Abort any running transition visuals. */
    public static final int PHASE_CLEAR = 3;

    public static final Type<TransitionPayload> TYPE = new Type<>(NullMod.id("transition"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransitionPayload> STREAM_CODEC =
            StreamCodec.ofMember(TransitionPayload::write, TransitionPayload::read);

    private static TransitionPayload read(RegistryFriendlyByteBuf buf) {
        return new TransitionPayload(buf.readVarInt(), buf.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(phase);
        buf.writeVarInt(duration);
    }

    @Override
    public Type<TransitionPayload> type() {
        return TYPE;
    }
}
