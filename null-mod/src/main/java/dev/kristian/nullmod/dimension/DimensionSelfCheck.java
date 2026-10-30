package dev.kristian.nullmod.dimension;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.registry.NullDimensions;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Checks of the NULL dimension that need the dimension itself, run once on a real server when the
 * JVM has {@code -Dnull.autotest} (the CI smoke tests, singleplayer and dedicated). The GameTest
 * server cannot run them: vanilla builds its world from the flat preset alone and never loads
 * datapack dimensions there. Generated chunks must match {@link NullWorldGeneration} exactly, the
 * arrival platform must be safe, and a decayed region must regenerate block for block. Results are
 * logged as {@code NULL-AUTOTEST} lines, which the CI scripts judge. Never registered in normal play.
 */
public final class DimensionSelfCheck {
    private static final String FAIL = "NULL-AUTOTEST-FAIL: dimension self-check: ";
    /** Lets NULL's systems load their saved state first (the decay queue loads on its first tick). */
    private static final int START_DELAY_TICKS = 40;
    private static final int DECAY_TIMEOUT_TICKS = 600;
    private static final int[][] CHUNKS = {{0, 0}, {-1, -1}, {37, -23}};

    private static long startAt = -1L;
    @Nullable
    private static Decay decay;

    private DimensionSelfCheck() {
    }

    public static void init(IEventBus gameBus) {
        if (System.getProperty("null.autotest") == null) {
            return;
        }
        gameBus.addListener(DimensionSelfCheck::onServerStarted);
        gameBus.addListener(DimensionSelfCheck::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        decay = null;
        startAt = event.getServer().getTickCount() + START_DELAY_TICKS;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (startAt >= 0L && server.getTickCount() >= startAt) {
            startAt = -1L;
            start(server);
            return;
        }
        Decay running = decay;
        if (running == null || running.level.getServer() != server) {
            return;
        }
        if (!running.vanished()) {
            if (--running.ticksLeft <= 0) {
                finish(running, "the decayed region never vanished");
            }
            return;
        }
        NullDimensionManager.INSTANCE.restoreAll(server);
        if (NullDimensionManager.INSTANCE.damagedRegions() != 0) {
            finish(running, "a region is still damaged after restoring");
        } else {
            finish(running, running.firstDifference());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level == null) {
            NullMod.LOGGER.error(FAIL + "the NULL dimension {} does not exist", NullDimensions.NULL_ID);
            return;
        }
        String problem = checkGeneration(level);
        if (problem != null) {
            NullMod.LOGGER.error(FAIL + problem);
            return;
        }
        decay = startDecay(level);
    }

    @Nullable
    private static String checkGeneration(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NullChunkGenerator)) {
            return "the NULL dimension does not use the null:null_geometry generator";
        }
        long seed = NullChunkGenerator.seedOf(level);
        // Nothing may be decayed while the generated blocks are compared with the geometry.
        NullDimensionManager.INSTANCE.restoreAll(level.getServer());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] chunkPos : CHUNKS) {
            LevelChunk chunk = level.getChunk(chunkPos[0], chunkPos[1]);
            int minX = chunkPos[0] << 4;
            int minZ = chunkPos[1] << 4;
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockState actual = chunk.getBlockState(pos.set(x, y, z));
                        BlockState expected = NullWorldGeneration.sample(seed, x, y, z);
                        if (actual != expected) {
                            return "generated " + actual + " at " + x + " " + y + " " + z + " but the geometry says " + expected;
                        }
                    }
                }
            }
        }

        int floor = NullWorldGeneration.MAIN_FLOOR;
        String wrong = firstWrong(level,
                new BlockPos(0, floor, 0), NullBlocks.DATA_BLOCK.get(),
                new BlockPos(4, floor, 0), NullBlocks.GLOW_MAGENTA.get(),
                new BlockPos(8, floor, 2), NullBlocks.GLOW_BLUE.get(),
                new BlockPos(12, floor, 12), NullBlocks.FRAME_BLOCK.get(),
                new BlockPos(6, floor, 6), NullBlocks.GRID_PANEL.get(),
                new BlockPos(12, floor + 1, 8), NullBlocks.NULL_GLASS.get(),
                new BlockPos(-12, floor + 1, -8), NullBlocks.NULL_GLASS.get());
        if (wrong != null) {
            return wrong;
        }
        for (int i = 0; i < 13; i++) {
            BlockPos spot = NullDimensionManager.platformSpot(i);
            if (!level.getBlockState(spot).isAir() || !level.getBlockState(spot.above()).isAir()) {
                return "arrival spot " + spot + " is not free in the generated world";
            }
            if (level.getBlockState(spot.below()).isAir()) {
                return "arrival spot " + spot + " has no floor";
            }
        }
        return null;
    }

    /** Pairs of position and expected block; the first mismatch, or null. */
    @Nullable
    private static String firstWrong(ServerLevel level, Object... expectations) {
        for (int i = 0; i < expectations.length; i += 2) {
            BlockPos pos = (BlockPos) expectations[i];
            BlockState expected = ((Block) expectations[i + 1]).defaultBlockState();
            BlockState actual = level.getBlockState(pos);
            if (actual != expected) {
                return "expected " + expected + " at " + pos + " but found " + actual;
            }
        }
        return null;
    }

    @Nullable
    private static Decay startDecay(ServerLevel level) {
        long seed = NullChunkGenerator.seedOf(level);
        // A crossing of the main lattice far from the platform: a hub, four pillars and four corridors.
        int cx = 10 * NullWorldGeneration.CELL;
        BlockPos min = new BlockPos(cx - 9, NullWorldGeneration.MAIN_FLOOR - 2, -9);
        BlockPos max = new BlockPos(cx + 9, NullWorldGeneration.MAIN_FLOOR + 10, 9);
        Decay check = new Decay(level, seed, min, max);
        check.forceChunks(true);
        int removable = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!NullWorldGeneration.isProtected(pos.getX(), pos.getY(), pos.getZ())
                    && !NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ()).isAir()) {
                removable++;
            }
        }
        if (removable <= 50) {
            finish(check, "the decay test region holds only " + removable + " removable blocks");
            return null;
        }
        if (!NullDimensionManager.INSTANCE.vanishRegion(level, min, max, 20 * 60 * 30)) {
            finish(check, "the decay test region was refused");
            return null;
        }
        return check;
    }

    private static void finish(Decay check, @Nullable String problem) {
        check.forceChunks(false);
        decay = null;
        if (problem == null) {
            NullMod.LOGGER.info("NULL-AUTOTEST: dimension self-check passed (generation, arrival platform, decay and regeneration)");
        } else {
            NullMod.LOGGER.error(FAIL + problem);
        }
    }

    private static final class Decay {
        final ServerLevel level;
        final long seed;
        final BlockPos min;
        final BlockPos max;
        int ticksLeft = DECAY_TIMEOUT_TICKS;

        Decay(ServerLevel level, long seed, BlockPos min, BlockPos max) {
            this.level = level;
            this.seed = seed;
            this.min = min;
            this.max = max;
        }

        void forceChunks(boolean forced) {
            for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
                for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
                    level.setChunkForced(chunkX, chunkZ, forced);
                }
            }
        }

        /** Everything unprotected in the box is gone and every protected block is untouched. */
        boolean vanished() {
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                boolean keep = NullWorldGeneration.isProtected(pos.getX(), pos.getY(), pos.getZ());
                BlockState actual = level.getBlockState(pos);
                if (keep ? actual != NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ()) : !actual.isAir()) {
                    return false;
                }
            }
            return true;
        }

        @Nullable
        String firstDifference() {
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (level.getBlockState(pos) != NullWorldGeneration.sample(seed, pos.getX(), pos.getY(), pos.getZ())) {
                    return "the decayed region did not regenerate exactly at " + pos.immutable();
                }
            }
            return null;
        }
    }
}
