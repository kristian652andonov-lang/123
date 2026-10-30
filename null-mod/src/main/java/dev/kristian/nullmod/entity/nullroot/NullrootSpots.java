package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.BuildProtection;
import dev.kristian.nullmod.world.ChunkCorruptionManager;
import dev.kristian.nullmod.world.CorruptionPalette;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds places for NULLROOT to grow around a player. Natural growth wants corrupted ground, or
 * natural ground in the dark (night, shade, caves): floors on the surface, and floors, ceilings
 * and walls underground, with the cluster variant deep down. Nothing ever grows in or near a
 * player build ({@link BuildProtection}), in an unloaded chunk, in fluid, inside blocks, right
 * next to a player or on top of another organism. Forced spots (from {@code /null event}) relax
 * the darkness and terrain rules, never the build protection.
 * <p>
 * Candidates are scored with cheap block reads first; the expensive build-protection scan only
 * runs for the best few.
 */
final class NullrootSpots {
    /** A place to grow: the block it grows out of, the face, and what grows there. */
    record Spot(BlockPos root, Direction face, NullrootVariant variant, int score) {
        Vec3 anchor() {
            return NullrootEntity.anchoredPosition(root, face, variant);
        }
    }

    private static final int SURFACE_ATTEMPTS = 14;
    private static final int CAVE_ATTEMPTS = 24;
    private static final int PROTECTION_CHECKS = 5;
    private static final double MIN_SPACING = 3.5;
    private static final double CENTRAL_SPACING = 6.0;
    private static final double PLAYER_SPACING = 5.0;
    /** How far colony members and residue spread from the centre. */
    static final int COLONY_RADIUS = 7;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private NullrootSpots() {
    }

    // ------------------------------------------------------------------ single organisms

    /** A spot for one organism near the player, or empty. */
    static Optional<Spot> sprout(ServerLevel level, ServerPlayer player, RandomSource random, CorruptionStage stage, boolean forced) {
        boolean underground = LocationFinder.isUnderground(player);
        boolean surfaceWorld = LocationFinder.canHaveSurface(level);
        Optional<Spot> spot = underground || !surfaceWorld ? cave(level, player, random, forced) : surface(level, player, random, stage, forced);
        if (spot.isEmpty() && forced) {
            spot = underground || !surfaceWorld ? surface(level, player, random, stage, true) : cave(level, player, random, true);
        }
        return spot;
    }

    private static Optional<Spot> surface(ServerLevel level, ServerPlayer player, RandomSource random, CorruptionStage stage, boolean forced) {
        List<Spot> candidates = new ArrayList<>();
        double min = forced ? 3.5 : 9.0;
        double max = forced ? 14.0 : 30.0;
        for (int i = 0; i < SURFACE_ATTEMPTS; i++) {
            LocationFinder.View view = forced ? LocationFinder.View.ANY
                    : switch (i % 3) {
                        case 0 -> LocationFinder.View.HIDDEN;
                        case 1 -> LocationFinder.View.EDGE;
                        default -> LocationFinder.View.ANY;
                    };
            LocationFinder.Column column = LocationFinder.column(player, random, min, max, view);
            Spot spot = floorColumn(level, player, column.x(), column.z(), surfaceVariant(random, stage), forced);
            if (spot != null) {
                candidates.add(spot);
            }
        }
        // Where the ground already broke down it grows best.
        int spots = 0;
        for (ChunkCorruptionManager.Spot corrupted : ChunkCorruptionManager.INSTANCE.spots(level.getServer())) {
            if (spots >= 4 || !corrupted.dimension().equals(level.dimension())) {
                continue;
            }
            BlockPos center = corrupted.center();
            double dx = center.getX() + 0.5 - player.getX();
            double dz = center.getZ() + 0.5 - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < min || distance > max + 24.0) {
                continue;
            }
            spots++;
            int r = Math.max(1, corrupted.radius());
            for (int i = 0; i < 2; i++) {
                int x = center.getX() + random.nextInt(2 * r + 1) - r;
                int z = center.getZ() + random.nextInt(2 * r + 1) - r;
                Spot spot = floorColumn(level, player, x, z, surfaceVariant(random, stage), forced);
                if (spot != null) {
                    candidates.add(new Spot(spot.root(), spot.face(), spot.variant(), spot.score() + 2));
                }
            }
        }
        return pick(level, candidates, 2);
    }

    /** The top of one column as a floor spot, or null. */
    @Nullable
    private static Spot floorColumn(ServerLevel level, ServerPlayer player, int x, int z, NullrootVariant variant, boolean forced) {
        int y = LocationFinder.groundY(level, x, z);
        if (y == LocationFinder.NONE) {
            return null;
        }
        BlockPos root = new BlockPos(x, y, z);
        BlockState ground = level.getBlockState(root);
        if (!fits(level, root, Direction.UP, variant, forced)) {
            return null;
        }
        boolean corrupted = CorruptionPalette.isCorruption(ground) || corruptionNearby(level, root);
        boolean dark = level.getMaxLocalRawBrightness(root.above()) <= 7;
        if (!forced && !corrupted && !dark) {
            return null;
        }
        if (!spacedOut(level, root, Direction.UP, variant, forced)) {
            return null;
        }
        int score = 1 + (CorruptionPalette.isCorruption(ground) ? 4 : 0) + (corrupted ? 2 : 0) + (dark ? 2 : 0)
                + (LocationFinder.inView(player, root, 40.0) ? 0 : 1);
        return new Spot(root, Direction.UP, variant, score);
    }

    /** Floors, ceilings and walls of the caves around a player underground. */
    private static Optional<Spot> cave(ServerLevel level, ServerPlayer player, RandomSource random, boolean forced) {
        List<Spot> candidates = new ArrayList<>();
        BlockPos origin = player.blockPosition();
        for (int i = 0; i < CAVE_ATTEMPTS && candidates.size() < 8; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = forced ? 3.0 + random.nextDouble() * 9.0 : 6.0 + random.nextDouble() * 14.0;
            BlockPos cell = new BlockPos(origin.getX() + Mth.floor(Math.cos(angle) * distance), origin.getY() + random.nextInt(10) - 4,
                    origin.getZ() + Mth.floor(Math.sin(angle) * distance));
            Spot spot = caveSpot(level, cell, random, forced, false);
            if (spot != null) {
                candidates.add(spot);
            }
        }
        return pick(level, candidates, 2);
    }

    /**
     * Around an air cell: a floor below it, a ceiling above it or a wall beside it (in a random
     * order of preference), with the variant that grows there.
     */
    @Nullable
    private static Spot caveSpot(ServerLevel level, BlockPos cell, RandomSource random, boolean forced, boolean colonyMember) {
        BlockState at = LocationFinder.stateIfLoaded(level, cell);
        if (at == null || !at.isAir()) {
            return null;
        }
        if (!forced && !colonyMember && !darkCave(level, cell)) {
            return null;
        }
        int pick = random.nextInt(10);
        Spot spot = switch (pick) {
            case 0, 1, 2, 3 -> caveFloor(level, cell, random, forced);
            case 4, 5, 6 -> caveCeiling(level, cell, forced);
            default -> caveWall(level, cell, random, forced);
        };
        if (spot == null) {
            spot = pick < 4 ? caveCeiling(level, cell, forced) : caveFloor(level, cell, random, forced);
        }
        if (spot == null) {
            return null;
        }
        Vec3 anchor = spot.anchor();
        boolean clear = colonyMember ? awayFromOthers(level, anchor, 2.2, 2.5) : spacedOut(level, spot.root(), spot.face(), spot.variant(), forced);
        return clear ? spot : null;
    }

    @Nullable
    private static Spot caveFloor(ServerLevel level, BlockPos cell, RandomSource random, boolean forced) {
        BlockPos.MutableBlockPos cursor = cell.mutable();
        for (int i = 0; i < 6; i++) {
            cursor.move(Direction.DOWN);
            BlockState state = LocationFinder.stateIfLoaded(level, cursor);
            if (state == null) {
                return null;
            }
            if (state.isAir()) {
                continue;
            }
            BlockPos root = cursor.immutable();
            boolean deep = root.getY() < level.getSeaLevel() - 20;
            float roll = random.nextFloat();
            NullrootVariant variant = deep
                    ? (roll < 0.55F ? NullrootVariant.UNDERGROUND : roll < 0.85F ? NullrootVariant.TENDRIL : NullrootVariant.SMALL)
                    : (roll < 0.45F ? NullrootVariant.TENDRIL : roll < 0.8F ? NullrootVariant.SMALL : NullrootVariant.UNDERGROUND);
            if (!fits(level, root, Direction.UP, variant, forced)) {
                variant = NullrootVariant.SMALL;
                if (!fits(level, root, Direction.UP, variant, forced)) {
                    return null;
                }
            }
            return new Spot(root, Direction.UP, variant, deep ? 3 : 2);
        }
        return null;
    }

    @Nullable
    private static Spot caveCeiling(ServerLevel level, BlockPos cell, boolean forced) {
        BlockPos.MutableBlockPos cursor = cell.mutable();
        for (int i = 0; i < 6; i++) {
            cursor.move(Direction.UP);
            BlockState state = LocationFinder.stateIfLoaded(level, cursor);
            if (state == null) {
                return null;
            }
            if (state.isAir()) {
                continue;
            }
            BlockPos root = cursor.immutable();
            return fits(level, root, Direction.DOWN, NullrootVariant.CEILING, forced) ? new Spot(root, Direction.DOWN, NullrootVariant.CEILING, 3) : null;
        }
        return null;
    }

    @Nullable
    private static Spot caveWall(ServerLevel level, BlockPos cell, RandomSource random, boolean forced) {
        int start = random.nextInt(HORIZONTAL.length);
        for (int i = 0; i < HORIZONTAL.length; i++) {
            Direction toWall = HORIZONTAL[(start + i) % HORIZONTAL.length];
            BlockPos root = cell.relative(toWall);
            Direction face = toWall.getOpposite();
            if (fits(level, root, face, NullrootVariant.WALL, forced)) {
                return new Spot(root, face, NullrootVariant.WALL, 3);
            }
        }
        return null;
    }

    private static boolean darkCave(ServerLevel level, BlockPos cell) {
        return level.getBrightness(LightLayer.SKY, cell) <= 4 && level.getBrightness(LightLayer.BLOCK, cell) <= 6;
    }

    // ------------------------------------------------------------------ colonies

    /** A spot for a colony's central mass: room for it and its satellites, nothing built within reach. */
    static Optional<Spot> colonyCenter(ServerLevel level, ServerPlayer player, RandomSource random, boolean forced) {
        boolean underground = LocationFinder.isUnderground(player) || !LocationFinder.canHaveSurface(level);
        List<Spot> candidates = new ArrayList<>();
        if (underground) {
            BlockPos origin = player.blockPosition();
            for (int i = 0; i < CAVE_ATTEMPTS && candidates.size() < 6; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = forced ? 5.0 + random.nextDouble() * 8.0 : 10.0 + random.nextDouble() * 16.0;
                BlockPos cell = new BlockPos(origin.getX() + Mth.floor(Math.cos(angle) * distance), origin.getY() + random.nextInt(7) - 3,
                        origin.getZ() + Mth.floor(Math.sin(angle) * distance));
                BlockState at = LocationFinder.stateIfLoaded(level, cell);
                if (at == null || !at.isAir() || (!forced && !darkCave(level, cell))) {
                    continue;
                }
                BlockPos.MutableBlockPos cursor = cell.mutable();
                for (int d = 0; d < 6; d++) {
                    cursor.move(Direction.DOWN);
                    BlockState state = LocationFinder.stateIfLoaded(level, cursor);
                    if (state == null || !state.isAir()) {
                        BlockPos root = cursor.immutable();
                        if (state != null && fits(level, root, Direction.UP, NullrootVariant.CENTRAL, forced)
                                && spacedOut(level, root, Direction.UP, NullrootVariant.CENTRAL, forced)) {
                            candidates.add(new Spot(root, Direction.UP, NullrootVariant.CENTRAL, 3));
                        }
                        break;
                    }
                }
            }
        } else {
            double min = forced ? 7.0 : 14.0;
            double max = forced ? 16.0 : 34.0;
            for (int i = 0; i < SURFACE_ATTEMPTS; i++) {
                LocationFinder.Column column = LocationFinder.column(player, random, min, max,
                        forced || i % 2 == 0 ? LocationFinder.View.ANY : LocationFinder.View.HIDDEN);
                Spot spot = floorColumn(level, player, column.x(), column.z(), NullrootVariant.CENTRAL, forced);
                if (spot != null && flatAround(level, spot.root())) {
                    candidates.add(spot);
                }
            }
        }
        return pick(level, candidates, COLONY_RADIUS + 1);
    }

    /** True if most columns three blocks around the centre are ground within two blocks of its height. */
    private static boolean flatAround(ServerLevel level, BlockPos center) {
        int good = 0;
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int y = LocationFinder.groundY(level, center.getX() + dx, center.getZ() + dz);
                if (y != LocationFinder.NONE && Math.abs(y - center.getY()) <= 2) {
                    good++;
                }
            }
        }
        return good >= 5;
    }

    /** A spot for one satellite of a colony, 2.5-7 blocks from its centre, or null. */
    @Nullable
    static Spot satellite(ServerLevel level, BlockPos center, boolean underground, RandomSource random, CorruptionStage stage,
                          List<Spot> taken, boolean forced) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 2.8 + random.nextDouble() * (COLONY_RADIUS - 2.8);
            int x = center.getX() + Mth.floor(Math.cos(angle) * distance + 0.5);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * distance + 0.5);
            Spot spot;
            if (underground) {
                spot = caveSpot(level, new BlockPos(x, center.getY() + 1 + random.nextInt(4), z), random, forced, true);
            } else {
                int y = LocationFinder.groundY(level, x, z);
                if (y == LocationFinder.NONE || Math.abs(y - center.getY()) > 3) {
                    continue;
                }
                BlockPos root = new BlockPos(x, y, z);
                NullrootVariant variant = satelliteVariant(random, stage);
                spot = fits(level, root, Direction.UP, variant, forced) && awayFromOthers(level, NullrootEntity.anchoredPosition(root, Direction.UP, variant), 2.2, 2.5)
                        ? new Spot(root, Direction.UP, variant, 1) : null;
            }
            if (spot == null || tooCloseTo(spot, taken)) {
                continue;
            }
            return spot;
        }
        return null;
    }

    private static boolean tooCloseTo(Spot spot, List<Spot> taken) {
        Vec3 anchor = spot.anchor();
        for (Spot other : taken) {
            double spacing = other.variant() == NullrootVariant.CENTRAL ? 2.6 : 2.0;
            if (other.anchor().distanceToSqr(anchor) < spacing * spacing) {
                return true;
            }
        }
        return false;
    }

    /**
     * The black film around a colony: carpet only into air, only on top of natural ground, thinning
     * out with distance. The placer re-checks everything (structures allowed, build protection)
     * before each block.
     */
    static PlacementJob residue(ServerLevel level, BlockPos center, RandomSource random) {
        PlacementJob job = new PlacementJob(level, "nullroot_residue");
        BlockState carpet = NullBlocks.RESIDUE.get().defaultBlockState();
        for (int dx = -COLONY_RADIUS + 1; dx <= COLONY_RADIUS - 1; dx++) {
            for (int dz = -COLONY_RADIUS + 1; dz <= COLONY_RADIUS - 1; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > COLONY_RADIUS - 0.5 || random.nextFloat() >= 0.7F * (1.0 - distance / (COLONY_RADIUS + 0.5))) {
                    continue;
                }
                for (int dy = 3; dy >= -3; dy--) {
                    BlockPos pos = center.offset(dx, 1 + dy, dz);
                    BlockState state = LocationFinder.stateIfLoaded(level, pos);
                    BlockState below = LocationFinder.stateIfLoaded(level, pos.below());
                    if (state == null || below == null) {
                        break;
                    }
                    if (!state.isAir()) {
                        continue;
                    }
                    if (BuildProtection.isNatural(below) && below.isFaceSturdy(level, pos.below(), Direction.UP)) {
                        job.add(pos, carpet, SafeBlockPlacer.Mode.PLACE_INTO_AIR, SafeBlockPlacer.Support.SOLID_BELOW);
                        break;
                    }
                    if (!below.isAir()) {
                        break;
                    }
                }
            }
        }
        return job;
    }

    // ------------------------------------------------------------------ shared checks

    /**
     * True if an organism of this variant can grow out of that face: a solid face of natural (or
     * corrupted) terrain, open air in front of it, room for its whole body, no fluid.
     */
    static boolean fits(ServerLevel level, BlockPos root, Direction face, NullrootVariant variant, boolean relaxed) {
        BlockState state = LocationFinder.stateIfLoaded(level, root);
        if (state == null || state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
            return false;
        }
        boolean terrain = BuildProtection.isNatural(state) || CorruptionPalette.isCorruption(state);
        if (!terrain && !(relaxed && !state.is(BuildProtection.BUILD_INDICATORS))) {
            return false;
        }
        if (!state.isFaceSturdy(level, root, face)) {
            return false;
        }
        BlockPos cell = root.relative(face);
        BlockState front = LocationFinder.stateIfLoaded(level, cell);
        if (front == null || !front.getFluidState().isEmpty() || !BuildProtection.canPlaceInto(front)) {
            return false;
        }
        AABB box = NullrootEntity.boxAt(root, face, variant).deflate(0.02);
        if (!LocationFinder.areaLoaded(level, Mth.floor(box.minX), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxZ))) {
            return false;
        }
        return level.noCollision(box) && !level.containsAnyLiquid(box);
    }

    /** Not on top of another organism, not right next to a player. */
    private static boolean spacedOut(ServerLevel level, BlockPos root, Direction face, NullrootVariant variant, boolean forced) {
        Vec3 anchor = NullrootEntity.anchoredPosition(root, face, variant);
        for (NullrootEntity other : NullrootManager.INSTANCE.organisms()) {
            if (other.level() != level || other.isRemoved()) {
                continue;
            }
            double spacing = variant == NullrootVariant.CENTRAL || other.variant() == NullrootVariant.CENTRAL ? CENTRAL_SPACING : MIN_SPACING;
            if (other.position().distanceToSqr(anchor) < spacing * spacing) {
                return false;
            }
        }
        return awayFromOthers(level, anchor, 0.0, forced ? 2.5 : PLAYER_SPACING);
    }

    /** No organism within {@code organismSpacing} and no player within {@code playerSpacing} of the point. */
    private static boolean awayFromOthers(ServerLevel level, Vec3 anchor, double organismSpacing, double playerSpacing) {
        if (organismSpacing > 0.0) {
            for (NullrootEntity other : NullrootManager.INSTANCE.organisms()) {
                if (other.level() == level && !other.isRemoved() && other.position().distanceToSqr(anchor) < organismSpacing * organismSpacing) {
                    return false;
                }
            }
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.position().distanceToSqr(anchor) < playerSpacing * playerSpacing) {
                return false;
            }
        }
        return true;
    }

    /** True if a corrupted block sits right next to the ground block. */
    private static boolean corruptionNearby(ServerLevel level, BlockPos ground) {
        for (Direction side : HORIZONTAL) {
            BlockState state = LocationFinder.stateIfLoaded(level, ground.relative(side, 2));
            if (state != null && CorruptionPalette.isCorruption(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The best candidates first; the first one whose surroundings ({@code radius} blocks around
     * it) are free of anything built wins.
     */
    private static Optional<Spot> pick(ServerLevel level, List<Spot> candidates, int radius) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingInt(Spot::score).reversed());
        int checks = 0;
        for (Spot spot : candidates) {
            if (checks++ >= PROTECTION_CHECKS) {
                break;
            }
            BlockPos root = spot.root();
            if (!BuildProtection.isAreaProtected(level, root.offset(-radius, -2, -radius), root.offset(radius, 3, radius))) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    private static NullrootVariant surfaceVariant(RandomSource random, CorruptionStage stage) {
        float roll = random.nextFloat();
        if (stage.atLeast(CorruptionStage.ABOVE_MIDDLE)) {
            return roll < 0.3F ? NullrootVariant.SMALL : roll < 0.7F ? NullrootVariant.TENDRIL : NullrootVariant.LARGE;
        }
        return roll < 0.45F ? NullrootVariant.SMALL : roll < 0.8F ? NullrootVariant.TENDRIL : NullrootVariant.LARGE;
    }

    private static NullrootVariant satelliteVariant(RandomSource random, CorruptionStage stage) {
        float roll = random.nextFloat();
        float large = stage.atLeast(CorruptionStage.ABOVE_MIDDLE) ? 0.3F : 0.2F;
        return roll < 0.45F ? NullrootVariant.TENDRIL : roll < 1.0F - large ? NullrootVariant.SMALL : NullrootVariant.LARGE;
    }
}
