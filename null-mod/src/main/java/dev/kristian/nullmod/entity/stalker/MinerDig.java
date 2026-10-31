package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.world.BuildProtection;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Underground: a pickaxe striking stone somewhere in the rock, in bursts, coming nearer. Then one of:
 * <ul>
 * <li><b>It digs through</b> (Stage 2 on, when digging is allowed): the strikes reach the wall of the
 * player's tunnel and a short 1x2 tunnel really opens towards them, block by block, through natural
 * stone and deepslate only (every block through {@link SafeBlockPlacer}, so never near anything
 * built, never letting water or lava in; ores and anything else are left standing). At the far end
 * of the new tunnel stands the figure with its pickaxe, looking at them.</li>
 * <li><b>The end of the tunnel</b>: the figure is at the far end of the straight tunnel the player is
 * in, its back to them, swinging at the rock (now and then really digging a little further). Looked
 * at for a while it stops and turns round; then it is not there any more.</li>
 * <li><b>Only the sounds</b> (mostly Stage 1): the strikes come nearer and stop.</li>
 * </ul>
 */
final class MinerDig extends Encounter {
    private static final int SOUNDS = 0;
    private static final int DIG_THROUGH = 1;
    private static final int CORRIDOR = 2;

    private int mode = SOUNDS;
    private BlockPos origin = BlockPos.ZERO;
    @Nullable
    private Direction direction;
    private double sourceDistance;
    private double arriveDistance;
    private int strikesLeft;
    private int nextStrike;
    private int burstsAtArrival;

    /** The first rock cell along {@link #direction} from where the player stood. */
    @Nullable
    private BlockPos wall;
    private int wallDistance;
    private int tunnelLength;
    /** Slice being dug, from the far end of the tunnel ({@code tunnelLength - 1}) towards the player (0). */
    private int slice;
    private int blockInSlice;

    @Nullable
    private StalkerSpots.Corridor corridor;
    private int swingIn = -1;
    private int hits;
    private int dugInCorridor;
    private int lifetime;

    private int watchedTicks;
    private int staredTicks;
    private boolean noticed;
    private boolean twitched;
    private int noticedTicks;

    MinerDig(ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        super(StalkerKind.MINER, owner, boldness, forced, random);
    }

    @Override
    boolean begin(ServerPlayer player) {
        if (!forced && !LocationFinder.isUnderground(player)) {
            return false;
        }
        origin = player.blockPosition();
        direction = StalkerSpots.solidRock(level, player, random, 6, 22);
        boolean mayDig = StalkerSettings.minerDigging() && (forced || boldness >= 0.3F);
        if (showcase) {
            // The operator should see it: the figure at the end of the tunnel if there is one.
            corridor = StalkerSpots.corridor(level, player, 8, 28);
            if (corridor != null) {
                mode = CORRIDOR;
                return startCorridor(player);
            }
        }
        if (direction != null && mayDig && planTunnel()) {
            mode = DIG_THROUGH;
            arriveDistance = wallDistance + tunnelLength;
            sourceDistance = arriveDistance + (showcase ? 2 : 8 + random.nextInt(6));
        } else {
            corridor = StalkerSpots.corridor(level, player, 10, 28);
            if (corridor != null && (forced || random.nextFloat() < bold(0.2F, 0.6F))) {
                mode = CORRIDOR;
                return startCorridor(player);
            }
            if (direction == null) {
                if (!forced) {
                    return false;
                }
                direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            }
            mode = SOUNDS;
            arriveDistance = bold(16.0F, 8.0F);
            sourceDistance = arriveDistance + bold(10.0F, 14.0F);
        }
        strikesLeft = 3 + random.nextInt(2);
        nextStrike = 20 + random.nextInt(40);
        return true;
    }

    /**
     * Plans the tunnel it will dig: from inside the rock up to the wall of the space the player is
     * in, 3-7 blocks long, almost all natural stone, nothing built anywhere near, no fluids.
     */
    private boolean planTunnel() {
        Direction dir = direction;
        if (dir == null) {
            return false;
        }
        BlockPos found = StalkerSpots.wallAlong(level, origin, dir, 5);
        if (found == null) {
            return false;
        }
        int length = 3 + Math.round(boldness * 3.0F) + random.nextInt(2);
        int stone = 0;
        for (int k = 0; k < length; k++) {
            BlockPos cell = found.relative(dir, k);
            for (int up = 0; up <= 1; up++) {
                BlockPos pos = cell.above(up);
                BlockState state = LocationFinder.stateIfLoaded(level, pos);
                if (state == null || !state.getFluidState().isEmpty()) {
                    return false;
                }
                if (StalkerSpots.diggable(state)) {
                    stone++;
                } else if (k == 0 && !state.getCollisionShape(level, pos).isEmpty()) {
                    // The wall it comes out of must be stone it can dig.
                    return false;
                }
            }
        }
        if (stone < length * 2 * 0.6) {
            return false;
        }
        BlockPos far = found.relative(dir, length - 1).above();
        if (BuildProtection.isAreaProtected(level, found, far)) {
            return false;
        }
        wall = found;
        wallDistance = (int) Math.round(Math.sqrt(found.distSqr(origin)));
        tunnelLength = length;
        slice = length - 1;
        blockInSlice = 0;
        return true;
    }

    @Override
    boolean tick(@Nullable ServerPlayer player) {
        if (player == null || player.distanceToSqr(Vec3.atCenterOf(origin)) > 40.0 * 40.0) {
            return false;
        }
        age++;
        if (mode == CORRIDOR) {
            return corridorTick(player);
        }
        if (figure != null) {
            return revealTick(player);
        }
        if (age > 20 * 180) {
            return false;
        }
        if (--nextStrike > 0) {
            return true;
        }
        if (mode == DIG_THROUGH && sourceDistance <= arriveDistance) {
            return digStrike(player);
        }
        // Still in the rock, somewhere: a strike, then the next, then a pause, then nearer.
        double wobble = (random.nextDouble() - 0.5) * 2.0;
        Direction dir = direction;
        BlockPos at = origin.above().relative(dir, (int) Math.round(sourceDistance))
                .relative(dir.getClockWise(), (int) Math.round(wobble));
        soundAt(StalkerSounds.DIG.get(), at, 1.0F, pitch(0.06F), 48.0);
        if (--strikesLeft > 0) {
            nextStrike = StalkerEntity.SWING_TICKS + random.nextInt(8);
            return true;
        }
        strikesLeft = 3 + random.nextInt(2);
        nextStrike = 50 + random.nextInt(70);
        if (sourceDistance > arriveDistance) {
            sourceDistance = Math.max(arriveDistance, sourceDistance - (1.5 + random.nextDouble()));
            return true;
        }
        // Arrived (only the sounds): one more burst, and then nothing.
        return mode != SOUNDS || ++burstsAtArrival < 2;
    }

    // ------------------------------------------------------------------ digging through

    private boolean digStrike(ServerPlayer player) {
        Direction dir = direction;
        BlockPos target = wall.relative(dir, slice).above(blockInSlice == 0 ? 1 : 0);
        soundAt(StalkerSounds.DIG.get(), target, 1.0F, pitch(0.05F), 40.0);
        nextStrike = StalkerEntity.SWING_TICKS + random.nextInt(8);
        if (!dig(level, target)) {
            // It could not go on (something changed): the strikes simply stop.
            return false;
        }
        if (++blockInSlice <= 1) {
            return true;
        }
        blockInSlice = 0;
        if (--slice >= 0) {
            return true;
        }
        // The last slice was the wall of the player's tunnel: it is open.
        BlockPos opening = wall;
        if (!StalkerSpots.passable(level, opening) && !StalkerSpots.passable(level, opening.above())) {
            return false;
        }
        return reveal(player);
    }

    /**
     * Digs one block if it is natural stone or deepslate (true), leaves anything else standing (also
     * true: ores stay, the tunnel goes round them), and fails (false) only when the safe placer
     * refuses a block it should have dug.
     */
    static boolean dig(ServerLevel level, BlockPos pos) {
        BlockState state = LocationFinder.stateIfLoaded(level, pos);
        if (state == null) {
            return false;
        }
        if (!StalkerSpots.diggable(state)) {
            return true;
        }
        PlacementJob job = new PlacementJob(level, "stalker_dig").atomic();
        job.replace(pos, Blocks.AIR.defaultBlockState());
        if (SafeBlockPlacer.INSTANCE.runNow(level, job).placed() <= 0) {
            return false;
        }
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        return true;
    }

    /** At the far end of its new tunnel, pickaxe in hand, looking out at the player. */
    private boolean reveal(ServerPlayer player) {
        Direction dir = direction;
        BlockPos feet = wall.relative(dir, tunnelLength - 1);
        if (!StalkerSpots.standable(level, feet)) {
            feet = wall.relative(dir, Math.max(0, tunnelLength - 2));
            if (!StalkerSpots.standable(level, feet)) {
                return false;
            }
        }
        Vec3 at = Vec3.atBottomCenterOf(feet);
        StalkerEntity created = spawnFigure(at, StalkerSight.yawTowards(at, at.subtract(dir.getStepX(), 0.0, dir.getStepZ())), player);
        if (created == null) {
            return false;
        }
        created.setAct(StalkerEntity.ACT_DIG);
        created.lookAtEyes(player);
        lifetime = 20 * 15;
        return true;
    }

    private boolean revealTick(ServerPlayer player) {
        if (figureGone()) {
            return false;
        }
        StalkerEntity body = figure;
        drive();
        body.lookAtEyes(player);
        watchedTicks++;
        if (StalkerSight.nearest(level, body.position(), 4.0) != null) {
            return false;
        }
        if (age % 2 == 0 && StalkerSight.starer(body) != null) {
            staredTicks += 2;
            if (!twitched && staredTicks >= 8) {
                twitched = true;
                twitch();
            }
        }
        boolean seen = StalkerSight.seer(body) != null;
        if (staredTicks >= Math.round(bold(14.0F, 36.0F)) && !seen) {
            return false;
        }
        if (staredTicks >= 70) {
            return false;
        }
        return watchedTicks < lifetime || (seen && watchedTicks < lifetime + 20 * 10);
    }

    // ------------------------------------------------------------------ at the end of the tunnel

    private boolean startCorridor(ServerPlayer player) {
        StalkerSpots.Corridor end = corridor;
        Vec3 at = Vec3.atBottomCenterOf(end.feet());
        Direction dir = end.direction();
        StalkerEntity created = spawnFigure(at, StalkerSight.yawTowards(at, at.add(dir.getStepX(), 0.0, dir.getStepZ())), player);
        if (created == null) {
            return false;
        }
        created.setAct(StalkerEntity.ACT_DIG);
        created.lookAtEyes(null);
        nextStrike = 10 + random.nextInt(20);
        lifetime = boldTicks(25.0F, 50.0F);
        return true;
    }

    private boolean corridorTick(ServerPlayer player) {
        if (figureGone()) {
            return false;
        }
        StalkerEntity body = figure;
        drive();
        watchedTicks++;
        if (StalkerSight.nearest(level, body.position(), 6.0) != null) {
            return false;
        }
        boolean seen = StalkerSight.seer(body) != null;
        if (age % 2 == 0 && StalkerSight.starer(body) != null) {
            staredTicks += 2;
        }
        if (!noticed && staredTicks >= Math.round(bold(12.0F, 30.0F))) {
            // It stops, its head twitches, and it turns round.
            noticed = true;
            twitch();
            body.faceYaw(StalkerSight.yawTowards(body.position(), player.position()));
            body.setAct(StalkerEntity.ACT_STAND);
        }
        if (noticed) {
            body.lookAtEyes(player);
            noticedTicks++;
            return noticedTicks < 120 && (seen || noticedTicks < 30);
        }
        if (swingIn > 0) {
            if (--swingIn == 0) {
                hitWall();
            }
        } else if (--nextStrike <= 0) {
            body.swing();
            swingIn = StalkerEntity.SWING_HIT_TICKS;
            nextStrike = StalkerEntity.SWING_TICKS + 4 + random.nextInt(12);
        }
        return watchedTicks < lifetime || (seen && watchedTicks < lifetime + 20 * 10);
    }

    /** The blow lands: the sound from the rock face, and now and then the face gives way. */
    private void hitWall() {
        StalkerSpots.Corridor end = corridor;
        BlockPos face = end.wall();
        soundAt(StalkerSounds.DIG.get(), face.above(), 1.0F, pitch(0.05F), 48.0);
        hits++;
        if (!StalkerSettings.minerDigging() || boldness < 0.3F || dugInCorridor >= 3 || hits % 3 != 0 || figure == null) {
            return;
        }
        BlockState lower = level.getBlockState(face);
        BlockState upper = level.getBlockState(face.above());
        if (!StalkerSpots.diggable(lower) || !StalkerSpots.diggable(upper)) {
            return;
        }
        if (BuildProtection.isAreaProtected(level, face, face.above()) || !dig(level, face.above()) || !dig(level, face)) {
            return;
        }
        dugInCorridor++;
        // It steps into the hole it made and goes on.
        Direction dir = end.direction();
        corridor = new StalkerSpots.Corridor(face, dir, face.relative(dir), end.length() + 1);
        figure.slideTo(Vec3.atBottomCenterOf(face), 0.06);
    }
}
