package dev.kristian.nullmod.entity.stalker;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** What {@code gametest.StalkerGameTests} needs to reach inside this package. */
public final class StalkerTestAccess {
    private StalkerTestAccess() {
    }

    /** The miner's single dig: natural stone or deepslate only, through the safe placer. */
    public static boolean dig(ServerLevel level, BlockPos pos) {
        return MinerDig.dig(level, pos);
    }

    /** The doors, trapdoors and gates the door opener would find around a position. */
    public static List<BlockPos> doors(ServerLevel level, BlockPos center, int radius, int height) {
        return StalkerSpots.doors(level, center, radius, height);
    }
}
