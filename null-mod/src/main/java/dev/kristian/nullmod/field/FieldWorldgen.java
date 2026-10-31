package dev.kristian.nullmod.field;

import com.mojang.serialization.MapCodec;
import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry entries of the field. The dimension, its type and its biome are datapack JSON in
 * {@code data/null/}; only the chunk generator type ({@code null:field}) is registered from code.
 */
public final class FieldWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, NullMod.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<FieldChunkGenerator>> FIELD =
            CHUNK_GENERATORS.register("field", () -> FieldChunkGenerator.CODEC);

    private FieldWorldgen() {
    }
}
