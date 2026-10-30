package dev.kristian.nullmod.dimension;

import com.mojang.serialization.MapCodec;
import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry entries of the NULL dimension. The dimension, its type and its biome are datapack
 * JSON in {@code data/null/}; only the chunk generator type ({@code null:null_geometry}) is
 * registered from code.
 */
public final class NullWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, NullMod.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<NullChunkGenerator>> NULL_GEOMETRY =
            CHUNK_GENERATORS.register("null_geometry", () -> NullChunkGenerator.CODEC);

    /** The only biome of the NULL dimension ({@code data/null/worldgen/biome/the_null.json}). */
    public static final ResourceKey<Biome> THE_NULL = ResourceKey.create(Registries.BIOME, NullMod.id("the_null"));

    private NullWorldgen() {
    }
}
