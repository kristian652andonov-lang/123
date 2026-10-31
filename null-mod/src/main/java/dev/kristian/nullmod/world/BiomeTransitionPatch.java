package dev.kristian.nullmod.world;

import dev.kristian.nullmod.core.WorldMemory;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.Tags;

/**
 * An incorrect biome transition: a patch of ground belongs to the wrong biome, cut off along a
 * hard straight edge the way biomes never meet. Snow and ice in a desert, sand and cactus in a
 * forest, flowers and green grass in the snow, red sand on a mountain. The ground itself is only
 * ever replaced where it is natural; the things growing or lying on top (snow layers, cacti,
 * flowers) go into air and only appear while structures are allowed.
 */
public final class BiomeTransitionPatch {
    enum Kind { SNOW, DESERT, BLOOM, BADLANDS }

    private BiomeTransitionPatch() {
    }

    public static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        int attempts = req.attempts(4);
        MinecraftServer server = req.server();
        UUID who = req.player().getUUID();
        for (int i = 0; i < attempts; i++) {
            double min = req.minDistance(30.0, 12.0);
            BlockPos center = LocationFinder.findGround(level, req.player(), random, min, req.maxDistance(90.0, min),
                    req.view(LocationFinder.View.EDGE), 4, pos -> true);
            if (center == null) {
                continue;
            }
            Kind kind = kindFor(level.getBlockState(center), level.getBiome(center), center);
            PlacementJob job = plan(level, center, kind, random, req.intensity());
            if (job == null) {
                continue;
            }
            String detail = switch (kind) {
                case SNOW -> "snow where it never snows";
                case DESERT -> "sand where it should be green";
                case BLOOM -> "flowers in the snow";
                case BADLANDS -> "red sand on the mountain";
            };
            job.onComplete(result -> {
                if (result.anyPlaced() && !result.aborted()) {
                    WorldMemory.remember(server, "structure:wrong_biome", who, detail);
                }
            });
            if (SafeBlockPlacer.INSTANCE.submit(job)) {
                return true;
            }
        }
        return false;
    }

    /** What the ground here should NOT look like. */
    static Kind kindFor(BlockState top, Holder<Biome> biome, BlockPos pos) {
        Block block = top.getBlock();
        if (top.is(BlockTags.SAND) || block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE
                || biome.is(Tags.Biomes.IS_DESERT) || biome.is(BiomeTags.IS_BEACH) || biome.is(BiomeTags.IS_BADLANDS)) {
            return Kind.SNOW;
        }
        if (block == Blocks.SNOW_BLOCK || (block == Blocks.GRASS_BLOCK && top.getValue(BlockStateProperties.SNOWY))
                || biome.value().coldEnoughToSnow(pos)) {
            return Kind.BLOOM;
        }
        if (top.is(BlockTags.BASE_STONE_OVERWORLD) || block == Blocks.GRAVEL || block == Blocks.CALCITE) {
            return Kind.BADLANDS;
        }
        return Kind.DESERT;
    }

    @Nullable
    static PlacementJob plan(ServerLevel level, BlockPos center, Kind kind, RandomSource random, float intensity) {
        int radius = 5 + random.nextInt(5) + Math.round(intensity * 3.0F);
        int cx = center.getX();
        int cz = center.getZ();
        if (!LocationFinder.areaLoaded(level, cx - radius - 2, cz - radius - 2, cx + radius + 2, cz + radius + 2)) {
            return null;
        }
        // The border is a straight line through (or near) the middle: on one side the wrong biome.
        double angle = random.nextDouble() * Math.PI * 2.0;
        double nx = Math.cos(angle);
        double nz = Math.sin(angle);
        double offset = (random.nextDouble() - 0.5) * radius * 0.6;
        long seed = random.nextLong();

        PlacementJob job = new PlacementJob(level, "wrong_biome_patch");
        PlacementJob decor = new PlacementJob(level, "wrong_biome_decor");
        int cells = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double rim = radius * (0.8 + 0.35 * WorldNoise.value(seed, (cx + dx) * 0.2, (cz + dz) * 0.2));
                if (dist > rim || dx * nx + dz * nz < offset) {
                    continue;
                }
                BlockPos ground = LocationFinder.naturalGround(level, cx + dx, cz + dz);
                if (ground == null || Math.abs(ground.getY() - center.getY()) > 6) {
                    continue;
                }
                cells++;
                switch (kind) {
                    case SNOW -> snow(level, job, decor, ground, random);
                    case DESERT -> desert(level, job, decor, ground, random, false);
                    case BADLANDS -> desert(level, job, decor, ground, random, true);
                    case BLOOM -> bloom(level, job, decor, ground, random);
                }
            }
        }
        if (cells < 6) {
            return null;
        }
        // Ground first, then what lies on it.
        for (PlacementJob.Change change : decor.changes()) {
            job.add(change.pos(), change.state(), change.mode(), change.support(), 0, null);
        }
        return job;
    }

    private static void snow(ServerLevel level, PlacementJob job, PlacementJob decor, BlockPos ground, RandomSource random) {
        float roll = random.nextFloat();
        BlockState surface;
        if (roll < 0.55F) {
            surface = Blocks.SNOW_BLOCK.defaultBlockState();
        } else if (roll < 0.68F) {
            surface = Blocks.PACKED_ICE.defaultBlockState();
        } else if (roll < 0.78F) {
            surface = Blocks.ICE.defaultBlockState();
        } else {
            surface = level.getBlockState(ground);
        }
        if (surface != level.getBlockState(ground)) {
            job.replace(ground, surface);
        }
        clearPlant(level, decor, ground.above());
        boolean icy = surface.is(Blocks.ICE) || surface.is(Blocks.PACKED_ICE);
        if (!icy && random.nextFloat() < 0.65F) {
            int layers = 1 + random.nextInt(3);
            decor.add(ground.above(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers),
                    SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SURVIVE);
        }
    }

    private static void desert(ServerLevel level, PlacementJob job, PlacementJob decor, BlockPos ground, RandomSource random, boolean red) {
        BlockState sand = (red ? Blocks.RED_SAND : Blocks.SAND).defaultBlockState();
        BlockState stone = (red ? Blocks.RED_SANDSTONE : Blocks.SANDSTONE).defaultBlockState();
        BlockPos below = ground.below();
        BlockState under = level.getBlockState(below);
        // Sand only where something solid holds it up, so nothing ever falls.
        if (LocationFinder.isGroundBlock(under)) {
            BlockPos deeper = below.below();
            if (LocationFinder.isGroundBlock(level.getBlockState(deeper))) {
                job.replace(deeper, stone);
            }
            job.add(below, sand, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.SOLID_BELOW);
        }
        job.add(ground, sand, SafeBlockPlacer.Mode.REPLACE_NATURAL, SafeBlockPlacer.Support.SOLID_BELOW);
        clearPlant(level, decor, ground.above());
        float roll = random.nextFloat();
        if (!red && roll < 0.07F) {
            int height = 1 + random.nextInt(3);
            for (int k = 1; k <= height; k++) {
                decor.add(ground.above(k), Blocks.CACTUS.defaultBlockState(), SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SURVIVE);
            }
        } else if (roll < 0.13F) {
            decor.add(ground.above(), Blocks.DEAD_BUSH.defaultBlockState(), SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SURVIVE);
        }
    }

    private static void bloom(ServerLevel level, PlacementJob job, PlacementJob decor, BlockPos ground, RandomSource random) {
        BlockState top = level.getBlockState(ground);
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        if (top != grass && (top.is(Blocks.SNOW_BLOCK) || top.is(BlockTags.DIRT) || top.is(Blocks.GRASS_BLOCK))) {
            job.replace(ground, grass);
        } else if (!top.is(Blocks.GRASS_BLOCK)) {
            return;
        }
        clearPlant(level, decor, ground.above());
        float roll = random.nextFloat();
        BlockState plant = null;
        if (roll < 0.10F) {
            plant = Blocks.POPPY.defaultBlockState();
        } else if (roll < 0.18F) {
            plant = Blocks.DANDELION.defaultBlockState();
        } else if (roll < 0.23F) {
            plant = Blocks.AZURE_BLUET.defaultBlockState();
        } else if (roll < 0.27F) {
            plant = Blocks.OXEYE_DAISY.defaultBlockState();
        } else if (roll < 0.50F) {
            plant = Blocks.SHORT_GRASS.defaultBlockState();
        }
        if (plant != null) {
            decor.add(ground.above(), plant, SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SURVIVE);
        }
    }

    /** Takes away the plant or snow lying on a column (and both halves of tall plants). */
    private static void clearPlant(ServerLevel level, PlacementJob decor, BlockPos above) {
        BlockState state = level.getBlockState(above);
        if (state.isAir() || !BuildProtection.canPlaceInto(state)) {
            return;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        if (state.getBlock() instanceof DoublePlantBlock) {
            BlockPos top = above.above();
            if (level.getBlockState(top).getBlock() instanceof DoublePlantBlock) {
                decor.place(top, air);
            }
        }
        decor.place(above, air);
    }
}
