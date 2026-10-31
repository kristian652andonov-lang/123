package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.NullMod;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How variant mobs are moved and turned. Works for mobs driven by goals and for mobs driven by a
 * brain (axolotls, goats, piglins...): holding still is a temporary speed modifier (never saved,
 * so a mob can never be left frozen by a crash or an unload), looking and walking also go
 * through the brain's memories when the mob has them.
 */
final class VariantMotion {
    /** Holding still to stare (or while a player watches a drifting host). */
    static final ResourceLocation HOLD = NullMod.id("variant_hold");
    /** The few ticks of a stutter, when the mob stops as if frames were dropped. */
    static final ResourceLocation HITCH = NullMod.id("variant_hitch");

    private VariantMotion() {
    }

    /**
     * Takes away (or gives back) the mob's walking and flying speed under one of the two ids. Its
     * path is kept: a stutter skips ahead along it afterwards; a stare stops it separately.
     */
    static void hold(Mob mob, ResourceLocation id, boolean held) {
        apply(mob.getAttribute(Attributes.MOVEMENT_SPEED), id, held);
        apply(mob.getAttribute(Attributes.FLYING_SPEED), id, held);
    }

    static boolean held(Mob mob, ResourceLocation id) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.hasModifier(id);
    }

    /** Takes every modifier of NULL's away (the mob turned back to normal). */
    static void release(Mob mob) {
        hold(mob, HOLD, false);
        hold(mob, HITCH, false);
    }

    private static void apply(@Nullable AttributeInstance attribute, ResourceLocation id, boolean held) {
        if (attribute == null) {
            return;
        }
        boolean has = attribute.hasModifier(id);
        if (held && !has) {
            attribute.addTransientModifier(new AttributeModifier(id, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!held && has) {
            attribute.removeModifier(id);
        }
    }

    /** True if the mob's brain (not its goals) decides where it walks. */
    static boolean brainDriven(Mob mob) {
        return mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED);
    }

    /** Turns the head toward the target, at most {@code degreesPerTick} a tick. */
    static void lookAt(Mob mob, Entity target, float degreesPerTick) {
        mob.getLookControl().setLookAt(target, degreesPerTick, mob.getMaxHeadXRot());
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
        }
    }

    /** Body and head face the target at once (an unseen snap, or every mob of an event together). */
    static void face(Mob mob, Entity target) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        double dy = target.getEyeY() - mob.getEyeY();
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        mob.setYRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setXRot(Mth.clamp(pitch, -mob.getMaxHeadXRot(), mob.getMaxHeadXRot()));
    }

    /** Walks toward the target (navigation, or the brain's walk target). */
    static void walkTo(Mob mob, Entity target, double speed) {
        if (brainDriven(mob)) {
            mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, (float) speed, 2));
        } else {
            mob.getNavigation().moveTo(target, speed);
        }
    }

    /** Stops walking (and forgets a brain walk target and look target NULL set). */
    static void stopWalking(Mob mob) {
        mob.getNavigation().stop();
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    static void forgetLook(Mob mob) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
        }
    }

    /** True if the mob is walking along a path on the ground (when a stutter can show). */
    static boolean walking(Mob mob) {
        return mob.onGround() && !mob.isInWater() && !mob.isInLava() && !mob.isPassenger() && !mob.isVehicle()
                && mob.getNavigation().isInProgress();
    }

    /**
     * Skips the mob ahead along its path by up to {@code distance} blocks, as if frames had been
     * dropped. Never into a wall, a fluid, past the next path point or off a ledge.
     *
     * @return true if it moved
     */
    static boolean skipAhead(Mob mob, double distance) {
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        Vec3 next = path.getNextEntityPos(mob);
        double dx = next.x - mob.getX();
        double dz = next.z - mob.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.35) {
            return false;
        }
        double step = Math.min(distance, length - 0.1);
        double ox = dx / length * step;
        double oz = dz / length * step;
        Level level = mob.level();
        AABB moved = mob.getBoundingBox().move(ox, 0.0, oz);
        if (!level.noCollision(mob, moved) || level.containsAnyLiquid(moved)) {
            return false;
        }
        AABB below = moved.move(0.0, -0.6, 0.0);
        if (level.noCollision(mob, below) || level.containsAnyLiquid(below)) {
            return false;
        }
        mob.moveTo(mob.getX() + ox, mob.getY(), mob.getZ() + oz, mob.getYRot(), mob.getXRot());
        return true;
    }

    /** A host's sudden jerk: the head snaps aside, sometimes the body, sometimes a small hop. */
    static void twitch(Mob mob, RandomSource random, float strength) {
        float jerk = (random.nextBoolean() ? 1.0F : -1.0F) * (25.0F + random.nextFloat() * 55.0F * strength);
        mob.setYHeadRot(mob.getYHeadRot() + jerk);
        mob.setXRot(Mth.clamp(mob.getXRot() + (random.nextFloat() - 0.5F) * 50.0F * strength, -mob.getMaxHeadXRot(), mob.getMaxHeadXRot()));
        if (random.nextFloat() < 0.35F * strength) {
            float yaw = mob.getYRot() + jerk * 0.5F;
            mob.setYRot(yaw);
            mob.setYBodyRot(yaw);
        }
        if (mob.onGround() && !mob.isInWater() && !mob.isPassenger() && !mob.isVehicle() && random.nextFloat() < 0.25F * strength) {
            Vec3 motion = mob.getDeltaMovement();
            mob.setDeltaMovement(motion.x, 0.2 + 0.1 * strength, motion.z);
            mob.hasImpulse = true;
        }
    }

    /** A hostile host's sudden short lunge at its target (no extra damage: its own attack still decides). */
    static void lunge(Mob mob, LivingEntity target, double strength) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) {
            return;
        }
        mob.setDeltaMovement(dx / length * strength, 0.3, dz / length * strength);
        mob.hasImpulse = true;
        face(mob, target);
    }
}
