package dev.kristian.nullmod.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Places sounds where the player cannot tell exactly where they came from. */
final class AudioPositions {
    /** A placement and its coordinates (absolute for WORLD, offsets for FOLLOW/HEAD). */
    record Spot(NullSoundInstance.Placement placement, double x, double y, double z) {
    }

    private static final Spot NOWHERE = new Spot(NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0);

    private AudioPositions() {
    }

    /**
     * Somewhere 6-20 blocks away at a random bearing; sometimes right behind the player's
     * head, sometimes below their feet or above them, occasionally nowhere at all.
     */
    static Spot ambiguous(RandomSource random) {
        Vec3 listener = AudioCorruptionManager.listener();
        if (listener == null) {
            return NOWHERE;
        }
        float roll = random.nextFloat();
        if (roll < 0.5F) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 6.0 + random.nextDouble() * 14.0;
            return new Spot(NullSoundInstance.Placement.WORLD, listener.x + Math.cos(angle) * distance,
                    listener.y - 2.0 + random.nextDouble() * 5.0, listener.z + Math.sin(angle) * distance);
        }
        if (roll < 0.68F) {
            return new Spot(NullSoundInstance.Placement.HEAD, (random.nextDouble() - 0.5) * 1.2,
                    -0.2 + random.nextDouble() * 0.5, 2.0 + random.nextDouble() * 2.0);
        }
        if (roll < 0.8F) {
            return new Spot(NullSoundInstance.Placement.WORLD, listener.x + (random.nextDouble() - 0.5) * 8.0,
                    listener.y - 6.0 - random.nextDouble() * 8.0, listener.z + (random.nextDouble() - 0.5) * 8.0);
        }
        if (roll < 0.9F) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = 2.0 + random.nextDouble() * 4.0;
            return new Spot(NullSoundInstance.Placement.WORLD, listener.x + Math.cos(angle) * distance,
                    listener.y + 4.0 + random.nextDouble() * 5.0, listener.z + Math.sin(angle) * distance);
        }
        return NOWHERE;
    }

    /** A point on the ground some distance away at a random bearing (for distant events). */
    static Vec3 around(RandomSource random, double minDistance, double maxDistance) {
        Vec3 listener = AudioCorruptionManager.listener();
        if (listener == null) {
            return Vec3.ZERO;
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        return new Vec3(listener.x + Math.cos(angle) * distance, listener.y - 1.0, listener.z + Math.sin(angle) * distance);
    }

    /** Horizontal unit vector the player is looking along. */
    static Vec3 lookFlat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0, Mth.cos(yaw));
    }
}
