package dev.kristian.nullmod.entity.variant;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Who looks at what: the checks behind stares, unseen turns and hosts that only move when nobody watches. */
final class VariantSight {
    private VariantSight() {
    }

    /** True if the target's middle is inside a cone of {@code halfAngleDegrees} around where the viewer looks. */
    static boolean inView(LivingEntity viewer, Entity target, double halfAngleDegrees) {
        Vec3 eye = viewer.getEyePosition();
        double dx = target.getX() - eye.x;
        double dy = target.getY(0.5) - eye.y;
        double dz = target.getZ() - eye.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-3) {
            return true;
        }
        Vec3 look = viewer.getViewVector(1.0F);
        return (look.x * dx + look.y * dy + look.z * dz) / length >= Math.cos(Math.toRadians(halfAngleDegrees));
    }

    /** In view and nothing solid in between. */
    static boolean sees(LivingEntity viewer, Entity target, double halfAngleDegrees) {
        return inView(viewer, target, halfAngleDegrees) && viewer.hasLineOfSight(target);
    }

    /** A player NULL's mobs may turn to: alive, in the game (not spectating). */
    static boolean watcher(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator();
    }

    /** The nearest watcher in the entity's level within {@code range} blocks, or null. */
    @Nullable
    static ServerPlayer nearestWatcher(Entity entity, double range) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        ServerPlayer best = null;
        double bestDistance = range * range;
        for (ServerPlayer player : level.players()) {
            if (!watcher(player)) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The player with this network id if it is a watcher in the entity's level within range, else null. */
    @Nullable
    static ServerPlayer watcherById(Entity entity, int id, double range) {
        if (id < 0 || !(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        Entity found = level.getEntity(id);
        if (found instanceof ServerPlayer player && watcher(player) && player.distanceToSqr(entity) <= range * range) {
            return player;
        }
        return null;
    }
}
