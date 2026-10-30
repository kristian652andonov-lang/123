package dev.kristian.nullmod.registry;

import com.mojang.serialization.Codec;
import dev.kristian.nullmod.NullMod;
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

    private NullAttachments() {
    }
}
