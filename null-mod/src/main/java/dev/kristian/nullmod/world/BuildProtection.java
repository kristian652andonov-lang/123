package dev.kristian.nullmod.world;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.registry.NullAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Keeps corruption away from anything players built. Two independent checks guard every
 * block NULL changes outside its own dimension:
 * <ol>
 * <li>Chunks where a player has placed blocks are remembered (a counter attached to the
 * chunk), and nothing within the protection radius of such a chunk is touched.</li>
 * <li>Before changing an area, the surroundings are scanned for blocks that only appear in
 * built things ({@code #null:build_indicators}: planks, glass, doors, beds, chests, torches...)
 * and for block entities. This also covers worlds that existed before NULL was installed,
 * villages and other generated structures.</li>
 * </ol>
 * Only blocks in {@code #null:corruptible} (natural terrain) may ever be replaced, and
 * structures only ever go into air or replaceable plants.
 */
public final class BuildProtection {
    public static final TagKey<Block> CORRUPTIBLE = TagKey.create(Registries.BLOCK, NullMod.id("corruptible"));
    public static final TagKey<Block> BUILD_INDICATORS = TagKey.create(Registries.BLOCK, NullMod.id("build_indicators"));

    private static final int SCAN_RADIUS_CAP = 6;

    private BuildProtection() {
    }

    public static void init(IEventBus gameBus) {
        // EntityMultiPlaceEvent (beds, doors) is a subclass, so this sees those too.
        gameBus.addListener(BuildProtection::onPlace);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level) {
            markPlayerBlock(level, event.getPos());
        }
    }

    /** Records that a player placed a block in this chunk. */
    public static void markPlayerBlock(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        int count = chunk.getData(NullAttachments.PLAYER_BLOCKS);
        if (count < Integer.MAX_VALUE) {
            chunk.setData(NullAttachments.PLAYER_BLOCKS, count + 1);
            chunk.setUnsaved(true);
        }
    }

    /** Number of player-placed blocks recorded for the chunk containing {@code pos} (0 if it is not loaded). */
    public static int playerBlocks(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? 0 : chunk.getData(NullAttachments.PLAYER_BLOCKS);
    }

    /**
     * True if natural terrain at this state may be replaced by corruption. Leaves a player placed
     * (persistent) and anything holding water are never natural, even when the block is tagged.
     */
    public static boolean isNatural(BlockState state) {
        return state.is(CORRUPTIBLE) && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !(state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT));
    }

    /** True if a structure block may be placed into this state (air, grass, flowers, snow...). */
    public static boolean canPlaceInto(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        return state.canBeReplaced() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !state.is(BUILD_INDICATORS);
    }

    /** True if corruption must leave {@code pos} alone. */
    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        return isAreaProtected(level, pos, pos);
    }

    /**
     * True if anything in the box {@code min..max} (inclusive), grown by the protection
     * radius, belongs to a player build, or if any of it is not loaded. Unloaded chunks
     * always count as protected so NULL never loads chunks to change them.
     */
    public static boolean isAreaProtected(ServerLevel level, BlockPos min, BlockPos max) {
        int radius = NullConfig.get(NullConfig.BUILD_PROTECTION_RADIUS);
        int minX = Math.min(min.getX(), max.getX()) - radius;
        int maxX = Math.max(min.getX(), max.getX()) + radius;
        int minZ = Math.min(min.getZ(), max.getZ()) - radius;
        int maxZ = Math.max(min.getZ(), max.getZ()) + radius;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                // Only chunks that are fully loaded right now count; one still loading or
                // generating is never waited for (that would stall the server thread).
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || chunk.getData(NullAttachments.PLAYER_BLOCKS) > 0) {
                    return true;
                }
            }
        }
        int scan = Math.min(radius, SCAN_RADIUS_CAP);
        int minY = Math.max(level.getMinBuildHeight(), Math.min(min.getY(), max.getY()) - scan);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(min.getY(), max.getY()) + scan);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int sx0 = Math.min(min.getX(), max.getX()) - scan;
        int sx1 = Math.max(min.getX(), max.getX()) + scan;
        int sz0 = Math.min(min.getZ(), max.getZ()) - scan;
        int sz1 = Math.max(min.getZ(), max.getZ()) + scan;
        // Large boxes are sampled on a 2-block lattice; anything built is far bigger than that.
        int step = (sx1 - sx0) * (sz1 - sz0) * (maxY - minY) > 40_000 ? 2 : 1;
        for (int x = sx0; x <= sx1; x += step) {
            for (int z = sz0; z <= sz1; z += step) {
                for (int y = minY; y <= maxY; y += step) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.is(BUILD_INDICATORS) || state.hasBlockEntity()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
