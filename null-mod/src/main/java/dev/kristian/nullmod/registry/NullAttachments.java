package dev.kristian.nullmod.registry;

import com.mojang.serialization.Codec;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.variant.VariantData;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data NULL attaches to chunks and players. */
public final class NullAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, NullMod.MODID);

    /**
     * How many blocks players have placed in a chunk (saturating). Corruption never changes
     * blocks near a chunk that has any. Stored with the chunk; absent (0) for untouched land.
     */
    public static final Supplier<AttachmentType<Integer>> PLAYER_BLOCKS = ATTACHMENT_TYPES.register("player_blocks",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT, count -> count > 0).build());

    /**
     * Whether a mob is a corrupted or parasite variant, with its look seed, growth and timers
     * ({@code entity.variant}). Saved with the mob; ordinary mobs carry none. Not copied on
     * conversions by NeoForge: {@code MobVariantManager} carries it over itself.
     */
    public static final Supplier<AttachmentType<VariantData>> MOB_VARIANT = ATTACHMENT_TYPES.register("mob_variant",
            () -> AttachmentType.builder(() -> new VariantData()).serialize(VariantData.SERIALIZER).build());

    private NullAttachments() {
    }
}
