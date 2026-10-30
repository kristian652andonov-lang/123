package dev.kristian.nullmod.client.dimension;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The sound of the NULL dimension: its hum, and now and then the structure shifting somewhere
 * out of sight.
 * <p>
 * The hum is played here rather than as the biome's ambient loop: vanilla starts a biome loop
 * once, when the player enters the biome, and NULL has a single biome. A loop that starts during
 * the transition silence would be held back and never come back, so this one is restarted
 * whenever it is missing (after silences, the transition, respawning). It follows the horror
 * sound settings and stops completely when they are off.
 */
final class DimensionAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int RETRY_TICKS = 40;

    @Nullable
    private static HumSound hum;
    private static int retry;
    private static long ticks;
    private static long nextShift = -1L;

    private DimensionAmbience() {
    }

    static void tick(Minecraft mc, boolean inNull) {
        ticks++;
        boolean wanted = inNull && mc.player != null && !TransitionSequence.active()
                && !AudioCorruptionManager.isSilenced() && ClientLimits.audio() > 0.0F;
        if (wanted) {
            HumSound current = hum;
            if (current == null || current.isStopped() || !mc.getSoundManager().isActive(current)) {
                if (--retry <= 0) {
                    retry = RETRY_TICKS;
                    HumSound next = new HumSound(NullSounds.DIMENSION_HUM.get());
                    hum = next;
                    mc.getSoundManager().play(next);
                }
            }
        } else if (hum != null) {
            hum.fadeOut();
            hum = null;
            retry = 0;
        }
        tickShifts(mc, inNull);
    }

    /** Somewhere 40-90 blocks away, the structure moves. */
    private static void tickShifts(Minecraft mc, boolean inNull) {
        if (!inNull || TransitionSequence.active() || mc.gameRenderer == null) {
            nextShift = -1L;
            return;
        }
        if (nextShift < 0L) {
            nextShift = ticks + 600 + RANDOM.nextInt(1200);
            return;
        }
        if (ticks < nextShift) {
            return;
        }
        nextShift = ticks + 700 + RANDOM.nextInt(1700);
        Vec3 listener = mc.gameRenderer.getMainCamera().getPosition();
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = 40.0 + RANDOM.nextDouble() * 50.0;
        Vec3 pos = listener.add(Math.cos(angle) * distance, (RANDOM.nextDouble() - 0.5) * 40.0, Math.sin(angle) * distance);
        AudioCorruptionManager.playAt(NullSounds.DIMENSION_SHIFT.get(), AudioCorruptionManager.Channel.AMBIENT, pos,
                0.5F + 0.3F * RANDOM.nextFloat(), 0.55F + 0.5F * RANDOM.nextFloat(), 96.0F);
    }

    /** Logout: the hum stops at once. */
    static void reset() {
        HumSound current = hum;
        hum = null;
        retry = 0;
        nextShift = -1L;
        if (current != null) {
            current.stopNow();
            Minecraft.getInstance().getSoundManager().stop(current);
        }
    }
}
