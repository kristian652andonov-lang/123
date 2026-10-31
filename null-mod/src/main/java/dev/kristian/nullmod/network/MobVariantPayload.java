package dev.kristian.nullmod.network;

import dev.kristian.nullmod.NullMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: which variant a mob is. Sent when a player starts tracking a variant mob,
 * whenever a mob's variant changes, and now and then while a parasite grows.
 *
 * @param entityId the mob's network id in the receiving player's level
 * @param variant  {@link dev.kristian.nullmod.entity.variant.MobVariant#id()}; NONE makes the mob ordinary again
 * @param seed     per-mob seed for its look (texture corruption, tentacle layout)
 * @param growth   how far a parasite has grown, 0 to 1 (tentacle length); 1 for corrupted mobs
 */
public record MobVariantPayload(int entityId, int variant, long seed, float growth) implements CustomPacketPayload {
    public static final Type<MobVariantPayload> TYPE = new Type<>(NullMod.id("mob_variant"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MobVariantPayload> STREAM_CODEC =
            StreamCodec.ofMember(MobVariantPayload::write, MobVariantPayload::read);

    private static MobVariantPayload read(RegistryFriendlyByteBuf buf) {
        return new MobVariantPayload(buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readFloat());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(variant);
        buf.writeLong(seed);
        buf.writeFloat(growth);
    }

    @Override
    public Type<MobVariantPayload> type() {
        return TYPE;
    }
}
