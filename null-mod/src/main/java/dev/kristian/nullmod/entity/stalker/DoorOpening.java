package dev.kristian.nullmod.entity.stalker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * At night near the player's base: heavy footsteps outside, then the wooden doors, trapdoors and
 * gates around them creak open one after another, the furthest first (one in Stage 1, up to four
 * in Stage 4). Sometimes the figure is glimpsed standing just beyond the last doorway, looking in;
 * looked at, approached or after a few seconds, it is gone. Only the "open" state of the doors
 * changes; {@link TemporaryChanges} closes them again a few minutes later when nobody is looking.
 */
final class DoorOpening extends Encounter {
    private final List<BlockPos> doors = new ArrayList<>();
    private int next;
    private int wait;
    @Nullable
    private BlockPos lastOpened;
    private boolean glimpse;
    private boolean glimpseTried;
    private int glimpseTicks;
    private int staredTicks;
    private int lingering;
    private final List<BlockPos> steps = new ArrayList<>();

    DoorOpening(ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        super(StalkerKind.DOOR_OPENER, owner, boldness, forced, random);
    }

    @Override
    boolean begin(ServerPlayer player) {
        if (!StalkerSettings.doorOpening() || (!forced && !level.isNight())) {
            return false;
        }
        List<BlockPos> found = StalkerSpots.doors(level, player.blockPosition(), 12, 3);
        // Not the door they are standing in.
        found.removeIf(pos -> pos.distToCenterSqr(player.position()) < 2.5 * 2.5);
        if (found.isEmpty()) {
            return false;
        }
        if (showcase) {
            // The ones the operator can see first.
            found.sort(Comparator.comparing((BlockPos pos) -> !StalkerSight.sees(player, Vec3.atCenterOf(pos))));
        }
        int count = Math.min(found.size(), 1 + Math.round(boldness * 2.4F) + (boldness > 0.5F ? random.nextInt(2) : 0));
        doors.addAll(found.subList(0, count));
        glimpse = showcase || random.nextFloat() < bold(0.3F, 0.75F);
        wait = showcase ? 20 : 50 + random.nextInt(40);
        if (boldness >= 0.3F) {
            planFootsteps(player, doors.get(0));
        }
        return true;
    }

    /** Three heavy steps outside, coming up to the first door. */
    private void planFootsteps(ServerPlayer player, BlockPos door) {
        Direction away = awayFrom(player, door, level.getBlockState(door));
        for (int k = 4; k >= 2; k--) {
            steps.add(door.relative(away, k));
        }
    }

    @Override
    boolean tick(@Nullable ServerPlayer player) {
        if (player == null) {
            // The doors close again on their own.
            return false;
        }
        age++;
        if (figure != null) {
            return glimpseTick(player);
        }
        if (!steps.isEmpty() && age % 18 == 0) {
            soundAt(StalkerSounds.FOOTSTEPS.get(), steps.remove(0), 0.85F, pitch(0.05F), 32.0);
            return true;
        }
        if (next < doors.size()) {
            if (--wait > 0) {
                return true;
            }
            BlockPos door = doors.get(next++);
            if (open(door, player)) {
                lastOpened = door;
            }
            wait = Math.round(bold(110.0F, 55.0F)) + random.nextInt(50);
            return true;
        }
        if (glimpse && !glimpseTried && lastOpened != null) {
            glimpseTried = true;
            if (appearBeyond(player, lastOpened)) {
                return true;
            }
        }
        return ++lingering < 60;
    }

    private boolean open(BlockPos door, ServerPlayer player) {
        BlockState state = level.getBlockState(door);
        if (!TemporaryChanges.isOpenableDoor(state)) {
            return false;
        }
        int min = 20 * 90 + random.nextInt(20 * 90);
        if (!TemporaryChanges.INSTANCE.openDoor(level, door, figure, player, min, 20 * 60 * 6)) {
            return false;
        }
        soundAt(StalkerSounds.DOOR_CREAK.get(), door, 0.9F, pitch(0.08F), 24.0);
        return true;
    }

    /** The side of a doorway away from the player (null for trapdoors, which lie flat). */
    @Nullable
    private static Direction awayFrom(ServerPlayer player, BlockPos door, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) {
            Vec3 to = Vec3.atCenterOf(door).subtract(player.position());
            return Direction.getNearest(to.x, 0.0, to.z);
        }
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 to = Vec3.atCenterOf(door).subtract(player.position());
        return to.x * facing.getStepX() + to.z * facing.getStepZ() >= 0.0 ? facing : facing.getOpposite();
    }

    /** The figure stands one or two blocks beyond the doorway, framed by it, looking in. */
    private boolean appearBeyond(ServerPlayer player, BlockPos door) {
        BlockState state = level.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof FenceGateBlock)) {
            return false;
        }
        Direction away = awayFrom(player, door, state);
        for (int k = 2; k >= 1; k--) {
            BlockPos feet = StalkerSpots.groundNear(level, door.relative(away, k));
            if (feet == null || !StalkerSpots.passable(level, feet.above(2))) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(feet);
            if (!StalkerSight.clear(level, player, player.getEyePosition(), at.add(0.0, StalkerEntity.VISUAL_EYE_HEIGHT - 0.2, 0.0))) {
                continue;
            }
            StalkerEntity created = spawnFigure(at, StalkerSight.yawTowards(at, player.position()), player);
            if (created != null) {
                created.setAct(StalkerEntity.ACT_STAND);
                created.lookAtEyes(player);
                return true;
            }
        }
        return false;
    }

    private boolean glimpseTick(ServerPlayer player) {
        if (figureGone()) {
            return false;
        }
        StalkerEntity body = figure;
        drive();
        body.lookAtEyes(player);
        glimpseTicks++;
        if (StalkerSight.nearest(level, body.position(), 4.5) != null) {
            return false;
        }
        if (age % 2 == 0 && StalkerSight.starer(body) != null) {
            staredTicks += 2;
        }
        boolean seen = StalkerSight.seer(body) != null;
        if (staredTicks >= Math.round(bold(8.0F, 24.0F)) && !seen) {
            return false;
        }
        if (staredTicks >= 40 || glimpseTicks > 20 * 14) {
            return false;
        }
        return seen || glimpseTicks < 20 * 8;
    }
}
