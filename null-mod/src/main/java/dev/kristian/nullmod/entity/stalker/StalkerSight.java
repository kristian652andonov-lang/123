package dev.kristian.nullmod.entity.stalker;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Who can see what. "Visual" lines of sight: glass, panes and iron bars do not block them (their
 * visual shape is empty), leaves and everything solid do.
 */
final class StalkerSight {
    /** Cosine of roughly half the screen: inside it, a point is on screen. */
    static final double ON_SCREEN_DOT = 0.62;
    /** Players further away than this never count as seeing anything. */
    static final double MAX_SIGHT = 96.0;

    private StalkerSight() {
    }

    static boolean candidate(Player player) {
        return player.isAlive() && !player.isSpectator();
    }

    /** True if the point is inside the player's view cone (walls ignored). */
    static boolean inCone(Player player, Vec3 point, double coneDot) {
        Vec3 to = point.subtract(player.getEyePosition());
        double length = to.length();
        if (length < 1.0E-3) {
            return true;
        }
        return player.getViewVector(1.0F).dot(to) / length >= coneDot;
    }

    /** True if nothing that blocks sight lies between the two points. */
    static boolean clear(Level level, Player player, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 0.04;
    }

    /** On screen and not hidden behind anything. */
    static boolean sees(Player player, Vec3 point) {
        return inCone(player, point, ON_SCREEN_DOT) && clear(player.level(), player, player.getEyePosition(), point);
    }

    /** Looking straight at the point (a cone that narrows with distance), with a clear view. */
    static boolean staresAt(Player player, Vec3 point) {
        Vec3 to = point.subtract(player.getEyePosition());
        double distance = Math.max(1.0, to.length());
        double threshold = Math.max(0.975, 1.0 - 0.09 / distance);
        return to.lengthSqr() > 1.0E-6 && player.getViewVector(1.0F).dot(to.normalize()) > threshold
                && clear(player.level(), player, player.getEyePosition(), point);
    }

    /** Somebody (alive, not spectating, close enough) sees the figure's head or body. */
    static boolean anyoneSees(StalkerEntity figure) {
        return seer(figure) != null;
    }

    /** A player who sees the figure right now, preferring its owner, or null. */
    @Nullable
    static ServerPlayer seer(StalkerEntity figure) {
        if (!(figure.level() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 head = figure.visualEyes();
        Vec3 body = new Vec3(figure.getX(), figure.getY() + 1.1, figure.getZ());
        ServerPlayer found = null;
        for (ServerPlayer player : level.players()) {
            if (!candidate(player) || player.distanceToSqr(figure) > MAX_SIGHT * MAX_SIGHT) {
                continue;
            }
            if (sees(player, head) || sees(player, body)) {
                if (player.getUUID().equals(figure.ownerId())) {
                    return player;
                }
                if (found == null) {
                    found = player;
                }
            }
        }
        return found;
    }

    /** A player staring straight at the figure's head or body, preferring its owner, or null. */
    @Nullable
    static ServerPlayer starer(StalkerEntity figure) {
        if (!(figure.level() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 head = figure.visualEyes();
        Vec3 body = new Vec3(figure.getX(), figure.getY() + 1.3, figure.getZ());
        ServerPlayer found = null;
        for (ServerPlayer player : level.players()) {
            if (!candidate(player) || player.distanceToSqr(figure) > MAX_SIGHT * MAX_SIGHT) {
                continue;
            }
            if (staresAt(player, head) || staresAt(player, body)) {
                if (player.getUUID().equals(figure.ownerId())) {
                    return player;
                }
                if (found == null) {
                    found = player;
                }
            }
        }
        return found;
    }

    /** The closest player (alive, not spectating) within the distance of a point, or null. */
    @Nullable
    static ServerPlayer nearest(ServerLevel level, Vec3 point, double maxDistance) {
        ServerPlayer best = null;
        double bestSqr = maxDistance * maxDistance;
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            if (!candidate(player)) {
                continue;
            }
            double d = player.distanceToSqr(point);
            if (d < bestSqr) {
                bestSqr = d;
                best = player;
            }
        }
        return best;
    }

    /**
     * True if any player (within the distance) has the block on screen with a clear view of it: the
     * line to its centre ends in that block itself (a door) or reaches the centre (an empty hole).
     */
    static boolean anyoneSeesBlock(ServerLevel level, BlockPos pos, double maxDistance) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (ServerPlayer player : level.players()) {
            if (!candidate(player) || player.distanceToSqr(center) > maxDistance * maxDistance
                    || !inCone(player, center, ON_SCREEN_DOT)) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), center, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)
                    || hit.getLocation().distanceToSqr(center) < 0.36) {
                return true;
            }
        }
        return false;
    }

    /** Yaw (degrees, Minecraft convention) of something at {@code from} facing {@code to}. */
    static float yawTowards(Vec3 from, Vec3 to) {
        return (float) (Math.atan2(to.z - from.z, to.x - from.x) * (180.0 / Math.PI)) - 90.0F;
    }
}
