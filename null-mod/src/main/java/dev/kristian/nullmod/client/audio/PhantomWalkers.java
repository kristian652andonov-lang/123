package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Footsteps of something that is not there: a sequence of real step sounds (taken from the
 * block under each step, so it sounds like a player on that ground) walking past in the
 * distance, or approaching from behind and stopping just short of the player.
 */
final class PhantomWalkers {
    private static final int MAX_WALKERS = 3;
    private static final List<Walker> WALKERS = new ArrayList<>();

    private static final class Walker {
        double x;
        double y;
        double z;
        final double stepX;
        final double stepZ;
        int steps;
        final int interval;
        int timer;
        final float volume;
        final float pitch;
        final boolean distorted;
        boolean layered;

        Walker(double x, double y, double z, double stepX, double stepZ, int steps, int interval, float volume, float pitch, boolean distorted) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stepX = stepX;
            this.stepZ = stepZ;
            this.steps = steps;
            this.interval = interval;
            this.timer = 1;
            this.volume = volume;
            this.pitch = pitch;
            this.distorted = distorted;
        }
    }

    private PhantomWalkers() {
    }

    /** Someone walks past (or towards the player) 14-22 blocks away. */
    static void distant(RandomSource random, float intensity, boolean distorted) {
        Vec3 listener = AudioCorruptionManager.listener();
        if (listener == null || WALKERS.size() >= MAX_WALKERS) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = 14.0 + random.nextDouble() * 8.0;
        double x = listener.x + Math.cos(angle) * distance;
        double z = listener.z + Math.sin(angle) * distance;
        // Mostly walking across the player's field of hearing, sometimes towards them.
        double heading = random.nextFloat() < 0.3F
                ? angle + Math.PI + (random.nextDouble() - 0.5) * 0.6
                : angle + Math.PI / 2.0 * (random.nextBoolean() ? 1.0 : -1.0) + (random.nextDouble() - 0.5) * 0.8;
        double stride = 0.65 + random.nextDouble() * 0.15;
        WALKERS.add(new Walker(x, listener.y, z, Math.cos(heading) * stride, Math.sin(heading) * stride,
                8 + random.nextInt(9), 8 + random.nextInt(3), 0.3F + 0.2F * intensity, 0.9F + random.nextFloat() * 0.15F, distorted));
    }

    /** Steps approaching from behind, stopping about a block and a half away. */
    static void behind(RandomSource random, float intensity, boolean distorted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || WALKERS.size() >= MAX_WALKERS) {
            return;
        }
        Vec3 look = AudioPositions.lookFlat();
        int steps = 4 + random.nextInt(3);
        double stride = 0.7;
        double start = 1.5 + steps * stride;
        double side = (random.nextDouble() - 0.5) * 1.2;
        double x = mc.player.getX() - look.x * start - look.z * side;
        double z = mc.player.getZ() - look.z * start + look.x * side;
        WALKERS.add(new Walker(x, mc.player.getY(), z, look.x * stride, look.z * stride, steps, 7 + random.nextInt(3),
                0.22F + 0.16F * intensity, 0.88F + random.nextFloat() * 0.1F, distorted));
    }

    static void tick(Minecraft mc) {
        if (WALKERS.isEmpty()) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null || AudioCorruptionManager.isSilenced()) {
            WALKERS.clear();
            return;
        }
        if (mc.isPaused()) {
            // New sounds would play over the pause menu: the walk resumes with the game.
            return;
        }
        RandomSource random = AudioCorruptionManager.random();
        for (Iterator<Walker> it = WALKERS.iterator(); it.hasNext(); ) {
            Walker walker = it.next();
            if (--walker.timer > 0) {
                continue;
            }
            step(level, walker, random);
            walker.timer = walker.interval + random.nextInt(2);
            if (--walker.steps <= 0) {
                it.remove();
            }
        }
    }

    static void reset() {
        WALKERS.clear();
    }

    private static void step(ClientLevel level, Walker walker, RandomSource random) {
        walker.x += walker.stepX;
        walker.z += walker.stepZ;
        // Find the ground under this step (a few blocks up or down).
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseY = (int) Math.floor(walker.y);
        BlockState ground = null;
        for (int dy = 2; dy >= -4; dy--) {
            pos.set((int) Math.floor(walker.x), baseY + dy - 1, (int) Math.floor(walker.z));
            BlockState below = level.getBlockState(pos);
            pos.set((int) Math.floor(walker.x), baseY + dy, (int) Math.floor(walker.z));
            BlockState at = level.getBlockState(pos);
            if (!below.isAir() && at.isAir()) {
                ground = below;
                walker.y = baseY + dy;
                break;
            }
        }
        SoundEvent sound = ground == null ? SoundEvents.STONE_STEP : ground.getSoundType().getStepSound();
        Vec3 at = new Vec3(walker.x, walker.y + 0.1, walker.z);
        AudioCorruptionManager.play(sound, AudioCorruptionManager.Channel.FOOTSTEPS, at,
                walker.volume, walker.pitch * (0.97F + random.nextFloat() * 0.06F));
        if (walker.distorted && !walker.layered) {
            // Under the real steps, once: NULL's own recording of footsteps that are not quite right.
            walker.layered = true;
            AudioCorruptionManager.play(NullSounds.FOOTSTEPS_DISTORTED.get(), AudioCorruptionManager.Channel.FOOTSTEPS, at,
                    walker.volume * 0.8F, walker.pitch);
        }
    }
}
