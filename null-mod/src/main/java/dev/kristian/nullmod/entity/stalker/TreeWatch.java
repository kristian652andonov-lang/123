package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.world.LocationFinder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Behind a tree 20-40 blocks away the figure stands hidden by the trunk, then leans out and looks
 * at the player: the head, one shoulder, long fingers around the bark. When the player looks
 * straight at it, it slides back out of sight (at once in Stage 1; later it holds their gaze for a
 * moment first) and may peek again from the other side. Coming closer makes it be gone; it is
 * never seen walking away. Late in Stage 4 it sometimes ends standing beside the tree in the open
 * when the player looks back, until they look at it properly.
 */
final class TreeWatch extends Encounter {
    private static final int HIDDEN = 0;
    private static final int PEEKING = 1;
    private static final int HIDING = 2;
    private static final int STEPPED_OUT = 3;
    private static final int LEAVING = 4;

    @Nullable
    private StalkerSpots.Trunk spot;
    private int phase = HIDDEN;
    private int phaseTicks;
    private int peeksLeft;
    private int staredTicks;
    private int lifetime;
    private boolean stepOutAtEnd;
    private int reactTicks;
    private double approachDistance;

    TreeWatch(ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        super(StalkerKind.TREE_WATCHER, owner, boldness, forced, random);
    }

    @Override
    boolean begin(ServerPlayer player) {
        if (showcase) {
            // In front of the operator, near enough to see it clearly.
            spot = StalkerSpots.treeTrunk(level, player, random, 12.0, 26.0, 64);
        } else {
            spot = StalkerSpots.treeTrunk(level, player, random, bold(30.0F, 18.0F), bold(42.0F, 32.0F), 48);
        }
        if (spot == null && forced) {
            spot = StalkerSpots.treeTrunk(level, player, random, 10.0, 48.0, 64);
        }
        lifetime = boldTicks(40.0F, 110.0F) + random.nextInt(200);
        reactTicks = Math.round(bold(3.0F, 26.0F));
        approachDistance = bold(17.0F, 9.0F);
        if (spot == null) {
            return forced && beginInTheOpen(player);
        }
        StalkerEntity created = spawnFigure(spot.hide(), spot.yaw(), player);
        if (created == null) {
            return false;
        }
        created.setAct(StalkerEntity.ACT_HIDE);
        created.setPeekSide(sideFor(created, spot.side()));
        created.lookAtEyes(player);
        peeksLeft = 1 + Math.round(boldness * 2.0F);
        stepOutAtEnd = boldness > 0.7F && random.nextFloat() < 0.35F;
        phase = HIDDEN;
        phaseTicks = showcase ? 10 : 15 + random.nextInt(40);
        return true;
    }

    /** Forced with no tree around: it stands in the open at the edge of the player's view. */
    private boolean beginInTheOpen(ServerPlayer player) {
        BlockPos ground = LocationFinder.findGround(level, player, random, 22.0, 34.0, LocationFinder.View.EDGE, 24,
                pos -> StalkerSpots.standable(level, pos.above()));
        if (ground == null) {
            return false;
        }
        Vec3 feet = Vec3.atBottomCenterOf(ground.above());
        StalkerEntity created = spawnFigure(feet, StalkerSight.yawTowards(feet, player.position()), player);
        if (created == null) {
            return false;
        }
        created.setAct(StalkerEntity.ACT_STAND);
        created.lookAtEyes(player);
        phase = STEPPED_OUT;
        return true;
    }

    /** +1 if the world direction {@code side} is to the figure's left, -1 if to its right. */
    private static int sideFor(StalkerEntity figure, Vec3 side) {
        float yaw = figure.getYRot() * Mth.DEG_TO_RAD;
        // Facing (-sin, cos); its left is (cos, sin).
        double left = side.x * Mth.cos(yaw) + side.z * Mth.sin(yaw);
        return left >= 0.0 ? 1 : -1;
    }

    @Override
    boolean tick(@Nullable ServerPlayer player) {
        if (player == null || figureGone()) {
            return false;
        }
        StalkerEntity body = figure;
        drive();
        age++;
        body.lookAtEyes(player);
        ServerPlayer close = StalkerSight.nearest(level, body.position(), approachDistance);
        if (close != null && phase != LEAVING) {
            // Somebody is coming: it is behind the trunk, and then it is not there at all.
            if (phase != STEPPED_OUT) {
                body.setAct(StalkerEntity.ACT_HIDE);
            }
            phase = LEAVING;
            phaseTicks = close.distanceToSqr(body) < 6.0 * 6.0 ? 0 : 30;
        }
        boolean stared = age % 2 == 0 ? StalkerSight.starer(body) != null : staredTicks > 0;
        switch (phase) {
            case HIDDEN -> {
                if (--phaseTicks <= 0) {
                    body.setAct(StalkerEntity.ACT_PEEK);
                    phase = PEEKING;
                    staredTicks = 0;
                }
            }
            case PEEKING -> {
                staredTicks = stared ? staredTicks + 1 : Math.max(0, staredTicks - 1);
                if (staredTicks >= reactTicks) {
                    // Looked at: it slides back behind the trunk.
                    body.setAct(StalkerEntity.ACT_HIDE);
                    peeksLeft--;
                    phase = HIDING;
                    phaseTicks = 40 + random.nextInt(80);
                    if (boldness > 0.45F && random.nextFloat() < 0.4F) {
                        soundAt(StalkerSounds.FOOTSTEPS.get(), body.blockPosition(), 0.55F, pitch(0.06F), 48.0);
                    }
                } else if (age >= lifetime) {
                    body.setAct(StalkerEntity.ACT_HIDE);
                    phase = LEAVING;
                    phaseTicks = 40;
                }
            }
            case HIDING -> {
                if (StalkerSight.seer(body) == null) {
                    phaseTicks--;
                }
                if (phaseTicks <= 0) {
                    if (peeksLeft > 0 && age < lifetime) {
                        if (random.nextBoolean()) {
                            body.setPeekSide(-body.peekSide());
                        }
                        body.setAct(StalkerEntity.ACT_PEEK);
                        phase = PEEKING;
                        staredTicks = 0;
                    } else if (stepOutAtEnd && stepOut(body)) {
                        phase = STEPPED_OUT;
                        staredTicks = 0;
                    } else {
                        phase = LEAVING;
                        phaseTicks = 60;
                    }
                }
            }
            case STEPPED_OUT -> {
                staredTicks = stared ? staredTicks + 1 : staredTicks;
                if (staredTicks >= 16 || age >= lifetime + 400) {
                    phase = LEAVING;
                    phaseTicks = 80;
                }
            }
            default -> {
                // LEAVING: gone the moment nobody sees it (or soon anyway).
                if (StalkerSight.seer(body) == null || --phaseTicks <= 0) {
                    return false;
                }
            }
        }
        return age < lifetime + 1200;
    }

    /** While nobody looks, it moves out from behind the trunk and stands beside it in the open. */
    private boolean stepOut(StalkerEntity body) {
        if (spot == null || StalkerSight.seer(body) != null) {
            return false;
        }
        Vec3 beside = spot.hide().add(spot.side().scale(1.15));
        BlockPos feet = StalkerSpots.groundNear(level, BlockPos.containing(beside));
        if (feet == null) {
            return false;
        }
        body.moveTo(beside.x, feet.getY(), beside.z, body.getYRot(), 0.0F);
        body.faceYaw(body.getYRot());
        body.setAct(StalkerEntity.ACT_STAND);
        return true;
    }
}
