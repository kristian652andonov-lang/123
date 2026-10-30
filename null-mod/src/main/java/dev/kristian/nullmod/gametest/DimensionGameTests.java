package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.dimension.NullChunkGenerator;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.dimension.NullWorldGeneration;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.registry.NullDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The NULL dimension: its geometry function, generated chunks, the transition and decay. */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class DimensionGameTests {
    private DimensionGameTests() {
    }

    @GameTest(template = "empty", batch = "dimension")
    public static void sampleIsDeterministic(GameTestHelper helper) {
        long[] seeds = {0L, 1L, -7L, 0x123456789ABCDEFL, NullChunkGenerator.seedFor(helper.getLevel().getSeed())};
        NullWorldGeneration.Column column = new NullWorldGeneration.Column();
        RandomSource random = RandomSource.create(42L);
        for (long seed : seeds) {
            for (int i = 0; i < 4000; i++) {
                int x = random.nextInt(40000) - 20000;
                int y = NullWorldGeneration.MIN_Y + random.nextInt(NullWorldGeneration.HEIGHT);
                int z = random.nextInt(40000) - 20000;
                int first = NullWorldGeneration.sampleId(seed, x, y, z);
                int second = NullWorldGeneration.sampleId(seed, x, y, z);
                NullWorldGeneration.column(seed, x, z, column);
                if (first != second || NullWorldGeneration.idAt(column, y) != first) {
                    helper.fail("sample is not deterministic at " + x + " " + y + " " + z + " for seed " + seed);
                }
                if (NullWorldGeneration.sample(seed, x, y, z) != NullWorldGeneration.state(first)) {
                    helper.fail("sample() and sampleId() disagree at " + x + " " + y + " " + z);
                }
            }
        }

        // The spawn platform is the same for every seed and every arrival spot is safe.
        for (int x = -NullWorldGeneration.PLATFORM_RADIUS; x <= NullWorldGeneration.PLATFORM_RADIUS; x++) {
            for (int z = -NullWorldGeneration.PLATFORM_RADIUS; z <= NullWorldGeneration.PLATFORM_RADIUS; z++) {
                for (int y = NullWorldGeneration.MAIN_FLOOR - 7; y <= NullWorldGeneration.MAIN_FLOOR + 17; y++) {
                    int expected = NullWorldGeneration.sampleId(seeds[0], x, y, z);
                    for (long seed : seeds) {
                        if (NullWorldGeneration.sampleId(seed, x, y, z) != expected) {
                            helper.fail("the spawn platform depends on the seed at " + x + " " + y + " " + z);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 13; i++) {
            BlockPos spot = NullDimensionManager.platformSpot(i);
            for (long seed : seeds) {
                helper.assertTrue(NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY() - 1, spot.getZ()) != NullWorldGeneration.AIR,
                        "no floor under arrival spot " + spot);
                helper.assertTrue(NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY(), spot.getZ()) == NullWorldGeneration.AIR
                                && NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY() + 1, spot.getZ()) == NullWorldGeneration.AIR,
                        "arrival spot " + spot + " is blocked");
            }
        }

        // The main tier can be walked forever along both axes, whatever the seed.
        for (long seed : seeds) {
            for (int k = -40; k <= 40; k++) {
                int far = k * 977 + 13;
                helper.assertTrue(NullWorldGeneration.sampleId(seed, far, NullWorldGeneration.MAIN_FLOOR, 0) != NullWorldGeneration.AIR,
                        "main corridor along X is broken at x=" + far);
                helper.assertTrue(NullWorldGeneration.sampleId(seed, 0, NullWorldGeneration.MAIN_FLOOR, far) != NullWorldGeneration.AIR,
                        "main corridor along Z is broken at z=" + far);
            }
        }

        // Seeds matter outside the spawn platform, and the structure is mostly dark void.
        int differences = 0;
        int solid = 0;
        int total = 0;
        for (int i = 0; i < 20000; i++) {
            int x = random.nextInt(4000) - 2000;
            int y = NullWorldGeneration.MIN_Y + random.nextInt(NullWorldGeneration.HEIGHT);
            int z = random.nextInt(4000) - 2000;
            int a = NullWorldGeneration.sampleId(1L, x, y, z);
            if (a != NullWorldGeneration.sampleId(2L, x, y, z)) {
                differences++;
            }
            if (a != NullWorldGeneration.AIR) {
                solid++;
            }
            total++;
        }
        helper.assertTrue(differences > 120, "different seeds give the same structure (" + differences + " differences)");
        helper.assertTrue(solid > total / 400 && solid < total / 4, "implausible amount of structure: " + solid + " of " + total);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "dimension_chunks", timeoutTicks = 600)
    public static void generatedChunksMatchTheGeometry(GameTestHelper helper) {
        ServerLevel level = nullLevel(helper);
        helper.assertTrue(level.getChunkSource().getGenerator() instanceof NullChunkGenerator,
                "the NULL dimension does not use the null:null_geometry generator");
        long seed = NullChunkGenerator.seedOf(level);
        // Nothing may be decayed while the generated blocks are compared with the geometry.
        NullDimensionManager.INSTANCE.restoreAll(level.getServer());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] chunkPos : new int[][]{{0, 0}, {-1, -1}, {37, -23}}) {
            LevelChunk chunk = level.getChunk(chunkPos[0], chunkPos[1]);
            int minX = chunkPos[0] << 4;
            int minZ = chunkPos[1] << 4;
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockState actual = chunk.getBlockState(pos.set(x, y, z));
                        if (actual != NullWorldGeneration.sample(seed, x, y, z)) {
                            helper.fail("generated " + actual + " at " + x + " " + y + " " + z + " but the geometry says "
                                    + NullWorldGeneration.sample(seed, x, y, z));
                        }
                    }
                }
            }
        }

        int floor = NullWorldGeneration.MAIN_FLOOR;
        expectBlock(helper, level, new BlockPos(0, floor, 0), NullBlocks.DATA_BLOCK.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(4, floor, 0), NullBlocks.GLOW_MAGENTA.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(8, floor, 2), NullBlocks.GLOW_BLUE.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(12, floor, 12), NullBlocks.FRAME_BLOCK.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(6, floor, 6), NullBlocks.GRID_PANEL.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(12, floor + 1, 8), NullBlocks.NULL_GLASS.get().defaultBlockState());
        expectBlock(helper, level, new BlockPos(-12, floor + 1, -8), NullBlocks.NULL_GLASS.get().defaultBlockState());
        for (int i = 0; i < 13; i++) {
            BlockPos spot = NullDimensionManager.platformSpot(i);
            helper.assertTrue(level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir(),
                    "arrival spot " + spot + " is not free in the generated world");
            helper.assertFalse(level.getBlockState(spot.below()).isAir(), "arrival spot " + spot + " has no floor");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "dimension_transition", timeoutTicks = 400)
    public static void transitionMovesPlayerIntoNull(GameTestHelper helper) {
        ServerLevel nullLevel = nullLevel(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertFalse(NullDimensions.isNull(player.level()), "the mock player started inside NULL");
        long started = helper.getTick();
        NullDimensionManager.INSTANCE.beginEnter(player);
        helper.assertTrue(NullDimensionManager.INSTANCE.inTransition(player), "no transition was scheduled");
        helper.runAfterDelay(NullDimensionManager.COLLAPSE_TICKS / 2,
                () -> helper.assertFalse(NullDimensions.isNull(player.level()), "the player was moved before the collapse ended"));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NullDimensions.isNull(player.level()), "the player is not in NULL yet"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getTick() - started >= NullDimensionManager.COLLAPSE_TICKS,
                            "the player was moved after only " + (helper.getTick() - started) + " ticks");
                    BlockPos feet = player.blockPosition();
                    helper.assertTrue(feet.getY() == NullWorldGeneration.SPAWN_Y, "the player arrived at y=" + feet.getY());
                    helper.assertTrue(Math.abs(feet.getX()) <= NullWorldGeneration.PLATFORM_RADIUS
                            && Math.abs(feet.getZ()) <= NullWorldGeneration.PLATFORM_RADIUS, "the player is not on the spawn platform: " + feet);
                    helper.assertFalse(nullLevel.getBlockState(feet.below()).isAir(), "there is nothing under the player");
                    helper.assertFalse(NullDimensionManager.INSTANCE.inTransition(player), "the transition is still pending");
                })
                .thenExecute(() -> removeMockPlayer(helper, player))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "dimension_decay", timeoutTicks = 400)
    public static void decayedRegionsRegenerateExactly(GameTestHelper helper) {
        ServerLevel level = nullLevel(helper);
        long seed = NullChunkGenerator.seedOf(level);
        // A crossing of the main lattice far from the platform: a hub, four pillars and four corridors.
        int cx = 10 * NullWorldGeneration.CELL;
        BlockPos min = new BlockPos(cx - 9, NullWorldGeneration.MAIN_FLOOR - 2, -9);
        BlockPos max = new BlockPos(cx + 9, NullWorldGeneration.MAIN_FLOOR + 10, 9);
        for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
            for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                level.getChunk(chunkX, chunkZ);
            }
        }
        int removable = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!NullWorldGeneration.isProtected(pos.getX(), pos.getY(), pos.getZ())
                    && !NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ()).isAir()) {
                removable++;
            }
        }
        helper.assertTrue(removable > 50, "the test region holds only " + removable + " removable blocks");
        helper.assertTrue(NullDimensionManager.INSTANCE.vanishRegion(level, min, max, 20 * 60 * 30), "the region was refused");
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                        boolean keep = NullWorldGeneration.isProtected(pos.getX(), pos.getY(), pos.getZ());
                        BlockState expected = NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ());
                        BlockState actual = level.getBlockState(pos);
                        if (keep ? actual != expected : !actual.isAir()) {
                            helper.fail("the region has not vanished yet at " + pos);
                        }
                    }
                })
                .thenExecute(() -> {
                    NullDimensionManager.INSTANCE.restoreAll(level.getServer());
                    helper.assertTrue(NullDimensionManager.INSTANCE.damagedRegions() == 0, "a region is still damaged after restoring");
                    for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                        if (level.getBlockState(pos) != NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ())) {
                            helper.fail("the region did not regenerate exactly at " + pos.immutable());
                        }
                    }
                })
                .thenExecute(() -> {
                    for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
                        for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
                            level.setChunkForced(chunkX, chunkZ, false);
                        }
                    }
                })
                .thenSucceed();
    }

    private static ServerLevel nullLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(NullDimensions.NULL_LEVEL);
        helper.assertTrue(level != null, "the NULL dimension " + NullDimensions.NULL_ID + " does not exist");
        return level;
    }

    private static void expectBlock(GameTestHelper helper, ServerLevel level, BlockPos pos, BlockState expected) {
        BlockState actual = level.getBlockState(pos);
        helper.assertTrue(actual == expected, "expected " + expected + " at " + pos + " but found " + actual);
    }

    private static void removeMockPlayer(GameTestHelper helper, ServerPlayer player) {
        try {
            helper.getLevel().getServer().getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after the NULL transition test", e);
        }
    }
}
