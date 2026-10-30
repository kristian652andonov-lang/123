package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.TerrainShapes;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An old house in the air, 16-32 blocks above the ground: standing on a torn-off piece of
 * terrain, floating bare with nothing under its floor, or hanging upside down with its roof
 * pointing at the ground.
 */
final class FloatingHouseBuilder {
    enum Variant { ISLAND, BARE, UPSIDE_DOWN }

    private FloatingHouseBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        for (int i = 0; i < attempts; i++) {
            BlockPos ground = LocationFinder.findGround(level, req.player(), random, req.minDistance(50.0, 24.0), 110.0,
                    req.view(LocationFinder.View.HIDDEN), 4, pos -> true);
            if (ground == null) {
                continue;
            }
            float roll = random.nextFloat();
            Variant variant = roll < 0.45F ? Variant.ISLAND : roll < 0.75F ? Variant.BARE : Variant.UPSIDE_DOWN;
            PlacementJob job = plan(level, ground, level.getBlockState(ground), variant, random, req.stageNumber());
            if (job == null) {
                continue;
            }
            String detail = variant == Variant.UPSIDE_DOWN ? "an upside-down house in the sky" : "a house in the sky";
            if (CorruptedStructureGenerator.submit(req, job, "floating_house", detail, ground)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos ground, BlockState groundState, Variant variant, RandomSource random, int stage) {
        RetroHouse.Spec spec = RetroHouse.random(random);
        spec.cobwebs = 0.3F;
        spec.torch = variant != Variant.UPSIDE_DOWN && random.nextFloat() < 0.35F;
        int height = RetroHouse.height(spec);
        Blueprint house = RetroHouse.build(spec, random);
        Blueprint bp = variant == Variant.UPSIDE_DOWN ? house.flippedY(height) : house;
        if (variant == Variant.ISLAND) {
            int radius = Math.max(spec.width, spec.depth) / 2 + 2;
            TerrainShapes.island(bp, spec.width / 2, -2, spec.depth / 2, radius,
                    TerrainShapes.IslandPalette.forGround(groundState, random), random, random.nextLong(), stage >= 5 ? 0.15F : 0.0F, stage);
        }
        int floorY = ground.getY() + 16 + random.nextInt(17);
        floorY = Math.min(floorY, level.getMaxBuildHeight() - height - 4);
        if (floorY < ground.getY() + 12) {
            return null;
        }
        Rotation rotation = Rotation.getRandom(random);
        BlockPos center = Blueprint.toWorld(BlockPos.ZERO, spec.width / 2, 0, spec.depth / 2, rotation, Mirror.NONE);
        BlockPos origin = new BlockPos(ground.getX() - center.getX(), floorY, ground.getZ() - center.getZ());
        BlockPos[] bounds = bp.worldBounds(origin, rotation, Mirror.NONE);
        int blocked = LocationFinder.countBlocked(level, bounds[0].offset(-1, -1, -1), bounds[1].offset(1, 1, 1), false, 3);
        if (blocked < 0 || blocked > 2) {
            return null;
        }
        PlacementJob job = new PlacementJob(level, "floating_house").atomic();
        bp.appendTo(job, origin, rotation, Mirror.NONE);
        return job;
    }
}
