package dev.kristian.nullmod.field;

import com.mojang.serialization.MapCodec;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Chunk generator {@code null:field}: writes {@link FieldGeneration} straight into the chunk
 * sections. No noise router, no surface rules, no carvers, no features, no structures and no mobs.
 * Thread-safe: the only state is the seed.
 */
public final class FieldChunkGenerator extends ChunkGenerator {
    public static final MapCodec<FieldChunkGenerator> CODEC = BiomeSource.CODEC.fieldOf("biome_source")
            .xmap(FieldChunkGenerator::new, ChunkGenerator::getBiomeSource);

    /** Mixed into the world seed so the field is unrelated to anything else in the world. */
    private static final long SEED_SALT = 0x4649454C44484152L;

    private volatile long seed = seedFor(0L);

    public FieldChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    /** The field seed for a world seed. */
    public static long seedFor(long worldSeed) {
        return FieldGeneration.mix(worldSeed ^ SEED_SALT);
    }

    /** The seed a level's field was generated with. */
    public static long seedOf(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof FieldChunkGenerator generator) {
            return generator.seed();
        }
        return seedFor(level.getSeed());
    }

    public long seed() {
        return seed;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /** Called once by the level's chunk map with the world seed, before anything is generated. */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long levelSeed) {
        this.seed = seedFor(levelSeed);
        return ChunkGeneratorStructureState.createForFlat(randomState, levelSeed, this.biomeSource, Stream.empty());
    }

    // ------------------------------------------------------------------ generation

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager,
                                                        ChunkAccess chunk) {
        fill(chunk, seed);
        return CompletableFuture.completedFuture(chunk);
    }

    /** Writes one chunk of the field into its sections, then its world-generation heightmaps. */
    static void fill(ChunkAccess chunk, long seed) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int fromY = Math.max(chunk.getMinBuildHeight(), FieldGeneration.MIN_Y);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, FieldGeneration.MAX_Y);
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            section.acquire();
        }
        try {
            FieldGeneration.fill(seed, minX, minZ, minX + 15, minZ + 15, new FieldGeneration.Sink() {
                @Override
                public BlockState get(int x, int y, int z) {
                    if (y < fromY || y > toY || (x - minX & ~15) != 0 || (z - minZ & ~15) != 0) {
                        return FieldBlocks.AIR;
                    }
                    return sections[chunk.getSectionIndex(y)].getBlockState(x & 15, y & 15, z & 15);
                }

                @Override
                public void set(int x, int y, int z, BlockState state) {
                    if (y < fromY || y > toY || (x - minX & ~15) != 0 || (z - minZ & ~15) != 0) {
                        return;
                    }
                    sections[chunk.getSectionIndex(y)].setBlockState(x & 15, y & 15, z & 15, state, false);
                }
            });
        } finally {
            for (LevelChunkSection section : sections) {
                section.release();
            }
        }
        // Wheat does not block motion, so the two heightmaps differ: let vanilla scan the columns.
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
    }

    /** No features at all, not even ones other mods add to every biome. */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    /** Nothing lives in the field. */
    @Override
    public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> biome, StructureManager structureManager,
                                                                     MobCategory category, BlockPos pos) {
        return WeightedRandomList.create();
    }

    // ------------------------------------------------------------------ queries

    @Override
    public int getGenDepth() {
        return FieldGeneration.HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return FieldGeneration.BASE_Y - 1;
    }

    @Override
    public int getMinY() {
        return FieldGeneration.MIN_Y;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return FieldGeneration.BASE_Y + 1;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] column = FieldGeneration.column(seed, x, z);
        int minY = Math.max(level.getMinBuildHeight(), FieldGeneration.MIN_Y);
        int top = Math.min(level.getMaxBuildHeight() - 1, FieldGeneration.MAX_Y);
        for (int y = top; y >= minY; y--) {
            if (type.isOpaque().test(column[y - FieldGeneration.MIN_Y])) {
                return y + 1;
            }
        }
        return level.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        int minY = level.getMinBuildHeight();
        BlockState[] states = new BlockState[level.getHeight()];
        BlockState[] column = FieldGeneration.column(seed, x, z);
        for (int i = 0; i < states.length; i++) {
            int y = minY + i;
            states[i] = y >= FieldGeneration.MIN_Y && y <= FieldGeneration.MAX_Y ? column[y - FieldGeneration.MIN_Y] : FieldBlocks.AIR;
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        FieldHouse house = FieldGeneration.nearestHouse(seed, pos.getX(), pos.getZ());
        info.add("Field: ground " + FieldGeneration.groundY(seed, pos.getX(), pos.getZ()) + ", nearest "
                + house.type().name().toLowerCase(java.util.Locale.ROOT) + " at " + house.x() + " " + house.floor() + " " + house.z());
    }
}
