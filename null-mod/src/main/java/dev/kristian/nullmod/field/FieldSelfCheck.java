package dev.kristian.nullmod.field;

import dev.kristian.nullmod.NullMod;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Checks of the field that need the dimension itself, run once on a real server when the JVM has
 * {@code -Dnull.autotest} (the CI smoke tests; the GameTest server never loads datapack
 * dimensions). Generated chunks around the home house and far out must match
 * {@link FieldGeneration} block for block, every spot of the home house's floor must be a shelter
 * in the real world, and the arrival spots must be safe. Results are logged as
 * {@code NULL-AUTOTEST} lines, which the CI scripts judge. Never registered in normal play.
 */
public final class FieldSelfCheck {
    private static final String FAIL = "NULL-AUTOTEST-FAIL: field self-check: ";
    private static final int START_DELAY_TICKS = 60;

    private static long startAt = -1L;

    private FieldSelfCheck() {
    }

    public static void init(IEventBus gameBus) {
        if (System.getProperty("null.autotest") == null) {
            return;
        }
        gameBus.addListener(FieldSelfCheck::onServerStarted);
        gameBus.addListener(FieldSelfCheck::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        startAt = event.getServer().getTickCount() + START_DELAY_TICKS;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (startAt < 0L || server.getTickCount() < startAt) {
            return;
        }
        startAt = -1L;
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        if (level == null) {
            NullMod.LOGGER.error(FAIL + "the field {} does not exist", FieldDimension.FIELD_ID);
            return;
        }
        String problem;
        try {
            problem = check(level);
        } catch (RuntimeException e) {
            NullMod.LOGGER.error(FAIL + "the check threw", e);
            return;
        }
        if (problem != null) {
            NullMod.LOGGER.error(FAIL + problem);
        } else {
            NullMod.LOGGER.info("NULL-AUTOTEST: field self-check passed (generation, houses are shelters, arrival spots)");
        }
    }

    @Nullable
    private static String check(ServerLevel level) {
        long seed = FieldChunkGenerator.seedOf(level);
        FieldHouse home = FieldGeneration.homeHouse(seed);
        int[][] chunks = {{home.x() >> 4, home.z() >> 4}, {(home.x() >> 4) + 1, (home.z() >> 4) + 1}, {-53, 71}};
        for (int[] chunk : chunks) {
            level.getChunk(chunk[0], chunk[1]);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = (chunk[0] << 4) + lx;
                    int z = (chunk[1] << 4) + lz;
                    BlockState[] column = FieldGeneration.column(seed, x, z);
                    for (int y = FieldGeneration.MIN_Y; y <= FieldGeneration.MAX_Y; y++) {
                        BlockState actual = level.getBlockState(new BlockPos(x, y, z));
                        BlockState expected = column[y - FieldGeneration.MIN_Y];
                        if (!actual.equals(expected)) {
                            return "block at " + x + " " + y + " " + z + " is " + actual + ", generation says " + expected;
                        }
                    }
                }
            }
        }
        // Every spot of the home house's floor is a shelter in the real world.
        for (int x = home.minX(); x <= home.maxX(); x++) {
            for (int z = home.minZ(); z <= home.maxZ(); z++) {
                if (!home.inInterior(x, z)) {
                    continue;
                }
                int y = home.floor() + 1;
                FieldShelter.Blocks blocks = (bx, by, bz) -> level.getBlockState(new BlockPos(bx, by, bz));
                if (FieldShelter.solid(blocks.get(x, y, z)) || FieldShelter.solid(blocks.get(x, y + 1, z))) {
                    continue;
                }
                if (!FieldShelter.isIndoors(blocks, x, y, z)) {
                    return "the home house at " + home.x() + " " + home.floor() + " " + home.z() + " is no shelter at " + x + " " + y + " " + z;
                }
            }
        }
        for (int[] spot : FieldGeneration.arrivalSpots(seed)) {
            if (!FieldManager.standable(level, new BlockPos(spot[0], spot[1], spot[2]))) {
                return "arrival spot " + spot[0] + " " + spot[1] + " " + spot[2] + " is not safe";
            }
        }
        return null;
    }
}
