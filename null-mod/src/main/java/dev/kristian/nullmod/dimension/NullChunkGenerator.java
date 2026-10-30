package dev.kristian.nullmod.dimension;

import com.mojang.serialization.MapCodec;
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
 * Chunk generator {@code null:null_geometry}: writes {@link NullWorldGeneration} straight into the
 * chunk sections, one column at a time. No noise, no surface rules, no carvers, no features, no
 * structures and no mobs; nothing but the geometry. Thread-safe: the only state is the seed.
 */
public final class NullChunkGenerator extends ChunkGenerator {
    public static final MapCodec<NullChunkGenerator> CODEC = BiomeSource.CODEC.fieldOf("biome_source")
            .xmap(NullChunkGenerator::new, ChunkGenerator::getBiomeSource);

    /** Mixed into the world seed so NULL's layout is unrelated to anything in the overworld. */
    private static final long SEED_SALT = 0x4E554C4C47454F4DL;

    private volatile long seed = SEED_SALT;

    public NullChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    /** The geometry seed for a world seed. */
    public static long seedFor(long worldSeed) {
        return worldSeed ^ SEED_SALT;
    }

    /** The geometry seed a level's chunks were generated with. */
    public static long seedOf(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof NullChunkGenerator generator) {
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

    /**
     * Called once by the level's chunk map with the world seed, before anything is generated.
     * NULL has no structures, so the structure state is empty.
     */
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

    /** Writes the geometry of one chunk directly into its sections and world-gen heightmaps. */
    static void fill(ChunkAccess chunk, long seed) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int fromY = Math.max(chunk.getMinBuildHeight(), NullWorldGeneration.MIN_Y);
        int toY = Math.min(chunk.getMaxBuildHeight() - 1, NullWorldGeneration.MAX_Y);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            section.acquire();
        }
        try {
            NullWorldGeneration.Column column = new NullWorldGeneration.Column();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    NullWorldGeneration.column(seed, minX + lx, minZ + lz, column);
                    boolean top = true;
                    for (int y = toY; y >= fromY; y--) {
                        int id = NullWorldGeneration.idAt(column, y);
                        if (id == NullWorldGeneration.AIR) {
                            continue;
                        }
                        BlockState state = NullWorldGeneration.state(id);
                        sections[chunk.getSectionIndex(y)].setBlockState(lx, y & 15, lz, state, false);
                        if (top) {
                            // Every block of NULL is a full, motion-blocking cube, so the top block is the
                            // surface of both world-gen heightmaps.
                            oceanFloor.update(lx, y, lz, state);
                            worldSurface.update(lx, y, lz, state);
                            top = false;
                        }
                    }
                }
            }
        } finally {
            for (LevelChunkSection section : sections) {
                section.release();
            }
        }
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

    /** Nothing spawns in NULL on its own. */
    @Override
    public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> biome, StructureManager structureManager,
                                                                     MobCategory category, BlockPos pos) {
        return WeightedRandomList.create();
    }

    // ------------------------------------------------------------------ queries

    @Override
    public int getGenDepth() {
        return NullWorldGeneration.HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return NullWorldGeneration.MIN_Y;
    }

    @Override
    public int getMinY() {
        return NullWorldGeneration.MIN_Y;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return NullWorldGeneration.SPAWN_Y;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        int surface = NullWorldGeneration.surfaceY(seed, x, z);
        return Math.max(level.getMinBuildHeight(), Math.min(surface, level.getMaxBuildHeight()));
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        int minY = level.getMinBuildHeight();
        BlockState[] states = new BlockState[level.getHeight()];
        NullWorldGeneration.Column column = new NullWorldGeneration.Column();
        NullWorldGeneration.column(seed, x, z, column);
        for (int i = 0; i < states.length; i++) {
            states[i] = NullWorldGeneration.state(NullWorldGeneration.idAt(column, minY + i));
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add(NullWorldGeneration.describe(seed, pos.getX(), pos.getZ()));
    }
}
