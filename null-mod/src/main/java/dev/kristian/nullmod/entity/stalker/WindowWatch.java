package dev.kristian.nullmod.entity.stalker;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * At night, with the player inside their house, the figure stands outside one of its windows with
 * its face to the glass, looking in (breathing against it, on the client). It appears while the
 * player is not looking at that window. Staring back does not send it away; later in the stages a
 * long stare makes it raise a hand to the glass and tap. It is gone when somebody comes to the
 * window (it darts aside out of the frame), when the player leaves the house, at dawn, or after a
 * while.
 */
final class WindowWatch extends Encounter {
    /** Somebody this close (horizontally) to the window has come to it. */
    private static final double AT_THE_WINDOW = 3.2;
    private static final int MAX_WAIT = 20 * 20;

    @Nullable
    private StalkerSpots.Window spot;
    private int lifetime;
    private int waited;
    private int staredTicks;
    private boolean handRaised;
    private boolean twitched;
    private int tapsLeft;
    private int dartTicks = -1;

    WindowWatch(ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        super(StalkerKind.WINDOW_WATCHER, owner, boldness, forced, random);
    }

    @Override
    boolean begin(ServerPlayer player) {
        if (!forced && (!level.isNight() || !StalkerSpots.indoors(level, player))) {
            return false;
        }
        spot = StalkerSpots.window(level, player, random, forced);
        if (spot == null) {
            return false;
        }
        lifetime = boldTicks(20.0F, 75.0F) + random.nextInt(300);
        // It is there when the player turns round, never appearing in front of their eyes.
        return (!forced && StalkerSight.inCone(player, face(), StalkerSight.ON_SCREEN_DOT)) || appear(player);
    }

    private Vec3 face() {
        return Vec3.atBottomCenterOf(spot.feet()).add(0.0, StalkerEntity.VISUAL_EYE_HEIGHT, 0.0);
    }

    private boolean appear(ServerPlayer player) {
        StalkerEntity created = spawnFigure(Vec3.atBottomCenterOf(spot.feet()), spot.yaw(), player);
        if (created == null) {
            return false;
        }
        created.setAct(StalkerEntity.ACT_WINDOW);
        created.lookAtEyes(player);
        return true;
    }

    @Override
    boolean tick(@Nullable ServerPlayer player) {
        if (player == null || spot == null) {
            return false;
        }
        if (figure == null) {
            // Waiting for the player to look somewhere else.
            if (++waited > MAX_WAIT) {
                return false;
            }
            return StalkerSight.inCone(player, face(), StalkerSight.ON_SCREEN_DOT) || appear(player);
        }
        if (figureGone()) {
            return false;
        }
        StalkerEntity body = figure;
        drive();
        age++;
        if (dartTicks >= 0) {
            return --dartTicks > 0;
        }
        body.lookAtEyes(player);
        if (StalkerSight.nearest(level, Vec3.atCenterOf(spot.glass()), AT_THE_WINDOW) != null) {
            dart(body);
            return true;
        }
        boolean seen = StalkerSight.seer(body) != null;
        if (player.distanceToSqr(Vec3.atCenterOf(spot.glass())) > 24.0 * 24.0) {
            return false;
        }
        if (!seen && ((!forced && !level.isNight()) || age >= lifetime)) {
            return false;
        }
        if (age >= lifetime + 20 * 15) {
            // Even watched, it does not stay for ever.
            return false;
        }
        if (age % 2 == 0 && StalkerSight.starer(body) != null) {
            staredTicks += 2;
            if (!twitched && staredTicks >= 24) {
                // Stared back at for a moment: its head twitches once.
                twitched = true;
                twitch();
            }
        }
        if (!handRaised && boldness >= 0.55F && staredTicks >= 50) {
            handRaised = true;
            body.setFlag(StalkerEntity.FLAG_HAND_ON_GLASS, true);
            tapsLeft = boldness >= 0.8F ? 2 + random.nextInt(2) : 1 + random.nextInt(2);
        }
        if (handRaised && tapsLeft > 0 && age % 24 == 0) {
            body.signal(StalkerEntity.EVENT_TAP);
            tapsLeft--;
        }
        return true;
    }

    /** Somebody came to the window: it darts aside along the wall, out of the frame, and is gone. */
    private void dart(StalkerEntity body) {
        body.setFlag(StalkerEntity.FLAG_HAND_ON_GLASS, false);
        Direction along = random.nextBoolean() ? spot.outward().getClockWise() : spot.outward().getCounterClockWise();
        BlockPos aside = StalkerSpots.groundNear(level, spot.feet().relative(along, 2));
        if (aside == null) {
            aside = StalkerSpots.groundNear(level, spot.feet().relative(spot.outward()));
        }
        if (aside != null) {
            body.moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, body.getYRot(), 0.0F);
            dartTicks = 4;
        } else {
            dartTicks = 0;
        }
    }
}
