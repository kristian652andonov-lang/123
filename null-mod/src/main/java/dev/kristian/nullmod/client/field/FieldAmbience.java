package dev.kristian.nullmod.client.field;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.audio.NullMusic;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The sound of the field. Everything goes through NULL's audio system (or follows the same settings,
 * for the loops), so the horror sound switch, the audio strength and the per-channel switches apply.
 * <ul>
 * <li><b>Day</b>: wind hissing through the wheat, a low unease that grows as the sun cracks, crows
 * far off, wood creaking, a barn door banging somewhere in the distance, wind chimes, and now and
 * then the sun itself cracking, far above in the north.</li>
 * <li><b>The shattering</b>: the sun breaking apart; the wind dies with the light.</li>
 * <li><b>Night</b>: a bell tolls once when the countdown begins, then dead silence, broken by
 * enormous footsteps far out in the dark, something dragged through the wheat, breathing. When the
 * timer runs low a heartbeat grows, faster and louder.</li>
 * <li><b>Dawn</b>: the light returning, and the wind.</li>
 * </ul>
 * The field's music is {@link NullMusic}'s: the game's own never plays here, and this class only tells
 * NullMusic every tick whether it is day (the only time the field's piece plays). Render thread only.
 */
final class FieldAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int RETRY_TICKS = 40;
    /** The heartbeat starts when this many seconds are left. */
    private static final float HEARTBEAT_FROM = 25.0F;

    @Nullable
    private static FieldLoopSound wind;
    @Nullable
    private static FieldLoopSound drone;
    @Nullable
    private static FieldLoopSound night;
    private static int retry;
    private static long ticks;
    private static long nextCrow = -1L;
    private static long nextCreak = -1L;
    private static long nextBarn = -1L;
    private static long nextChimes = -1L;
    private static long nextCrack = -1L;
    private static long nextFootsteps = -1L;
    private static long nextDrag = -1L;
    private static long nextBreath = -1L;
    private static long nextBeat = -1L;

    private FieldAmbience() {
    }

    // ------------------------------------------------------------------ the moments

    /** A phase began; seen live, its moment sounds. */
    static void onPhase(FieldCycle.Phase phase, boolean live) {
        nextBeat = -1L;
        if (!live) {
            return;
        }
        switch (phase) {
            case SHATTER -> AudioCorruptionManager.playGlobal(FieldSounds.SUN_SHATTER.get(), AudioCorruptionManager.Channel.EVENT, 1.0F, 1.0F);
            case NIGHT -> {
                // A bell, very far away, in the direction the sun was.
                Vec3 pos = around(Math.PI * 1.5 + (RANDOM.nextDouble() - 0.5) * 0.6, 170.0, 10.0);
                if (pos != null) {
                    AudioCorruptionManager.playAt(FieldSounds.TOLL.get(), AudioCorruptionManager.Channel.EVENT, pos, 0.85F, 1.0F, 420.0F);
                }
            }
            case DAWN -> AudioCorruptionManager.playGlobal(FieldSounds.DAWN.get(), AudioCorruptionManager.Channel.EVENT, 0.8F, 1.0F);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ ticking

    static void tick(Minecraft mc) {
        ticks++;
        boolean inField = FieldClientState.active() && mc.player != null;
        boolean allowed = inField && !AudioCorruptionManager.isSilenced();
        float audio = AudioCorruptionManager.channelEnabled(AudioCorruptionManager.Channel.AMBIENT) ? ClientLimits.audio() : 0.0F;
        FieldCycle.Phase phase = FieldClientState.phase();
        float progress = FieldClientState.progress(0.0F);
        NullMusic.fieldDay(inField && phase == FieldCycle.Phase.DAY);

        float windLevel = switch (phase) {
            case DAY -> 0.55F;
            case SHATTER -> 0.55F * Math.max(0.0F, 1.0F - progress * 2.5F);
            case DAWN -> 0.55F * FieldClientState.smooth(0.2F, 1.0F, progress);
            default -> 0.0F;
        };
        float droneLevel = switch (phase) {
            case DAY -> 0.06F + 0.44F * FieldClientState.crack(0.0F);
            case SHATTER -> 0.5F * Math.max(0.0F, 1.0F - progress * 1.5F);
            default -> 0.0F;
        };
        float nightLevel = switch (phase) {
            case SHATTER -> 0.5F * progress;
            case NIGHT, HUNT -> 0.5F;
            case DAWN -> 0.5F * (1.0F - progress);
            default -> 0.0F;
        };
        boolean retrying = --retry > 0;
        wind = loop(mc, wind, FieldSounds.WIND.get(), allowed && audio > 0.0F && windLevel > 0.0F, windLevel * audio, retrying);
        drone = loop(mc, drone, FieldSounds.DRONE.get(), allowed && audio > 0.0F && droneLevel > 0.0F, droneLevel * audio, retrying);
        night = loop(mc, night, FieldSounds.NIGHT_AIR.get(), allowed && audio > 0.0F && nightLevel > 0.0F, nightLevel * audio, retrying);

        if (!allowed || mc.isPaused()) {
            if (!inField) {
                clearSchedules();
            }
            return;
        }
        switch (phase) {
            case DAY -> dayEvents();
            case NIGHT, HUNT -> nightEvents(phase);
            default -> {
            }
        }
    }

    /** Keeps one loop running at its level, restarting it when the sound engine dropped it. */
    @Nullable
    private static FieldLoopSound loop(Minecraft mc, @Nullable FieldLoopSound current, SoundEvent sound, boolean wanted, float level,
                                       boolean retrying) {
        if (!wanted) {
            if (current != null) {
                current.fadeOut();
            }
            return null;
        }
        if (current != null && !current.isStopped() && !current.fading() && mc.getSoundManager().isActive(current)) {
            current.setTarget(level);
            return current;
        }
        if (retrying) {
            return current != null && !current.isStopped() ? current : null;
        }
        retry = RETRY_TICKS;
        FieldLoopSound next = new FieldLoopSound(sound);
        next.setTarget(level);
        mc.getSoundManager().play(next);
        return next;
    }

    private static void dayEvents() {
        if (due(nextCrow)) {
            nextCrow = ticks + 300 + RANDOM.nextInt(600);
            at(FieldSounds.CROW.get(), AudioCorruptionManager.Channel.AMBIENT, 45.0, 95.0, 12.0, 0.55F);
        }
        if (due(nextCreak)) {
            nextCreak = ticks + 400 + RANDOM.nextInt(700);
            at(FieldSounds.CREAK.get(), AudioCorruptionManager.Channel.AMBIENT, 10.0, 30.0, 2.0, 0.45F);
        }
        if (due(nextBarn)) {
            nextBarn = ticks + 900 + RANDOM.nextInt(1500);
            at(FieldSounds.BARN_DOOR.get(), AudioCorruptionManager.Channel.AMBIENT, 55.0, 110.0, 3.0, 0.6F);
        }
        if (due(nextChimes)) {
            nextChimes = ticks + 800 + RANDOM.nextInt(1200);
            at(FieldSounds.CHIMES.get(), AudioCorruptionManager.Channel.AMBIENT, 20.0, 50.0, 3.0, 0.35F);
        }
        float crack = FieldClientState.crack(0.0F);
        if (crack > 0.02F && due(nextCrack)) {
            nextCrack = ticks + 380 + RANDOM.nextInt(420);
            // Far above, from the sun.
            Vec3 pos = around(Math.PI * 1.5, 150.0, 34.0);
            if (pos != null) {
                AudioCorruptionManager.playAt(FieldSounds.SUN_CRACK.get(), AudioCorruptionManager.Channel.EVENT, pos,
                        0.35F + 0.6F * crack, 0.9F + RANDOM.nextFloat() * 0.15F, 400.0F);
            }
        }
    }

    private static void nightEvents(FieldCycle.Phase phase) {
        if (phase == FieldCycle.Phase.NIGHT) {
            if (due(nextFootsteps)) {
                nextFootsteps = ticks + 240 + RANDOM.nextInt(300);
                at(FieldSounds.FOOTSTEPS.get(), AudioCorruptionManager.Channel.CREATURE, 70.0, 130.0, 0.0, 0.7F);
            }
            if (due(nextDrag)) {
                nextDrag = ticks + 360 + RANDOM.nextInt(480);
                at(FieldSounds.DRAG.get(), AudioCorruptionManager.Channel.CREATURE, 30.0, 60.0, 0.0, 0.5F);
            }
            if (due(nextBreath)) {
                nextBreath = ticks + 500 + RANDOM.nextInt(600);
                at(FieldSounds.BREATH.get(), AudioCorruptionManager.Channel.CREATURE, 14.0, 26.0, 0.5, 0.45F);
            }
        }
        heartbeat(phase);
    }

    /**
     * The heartbeat: from {@link #HEARTBEAT_FROM} seconds before IT comes, slow and quiet, then faster
     * and louder; it races on for the first seconds of the hunt and fades.
     */
    private static void heartbeat(FieldCycle.Phase phase) {
        float urgency;
        if (phase == FieldCycle.Phase.NIGHT) {
            float seconds = FieldClientState.countdownSeconds(0.0F);
            if (seconds > HEARTBEAT_FROM) {
                nextBeat = -1L;
                return;
            }
            urgency = 1.0F - seconds / HEARTBEAT_FROM;
        } else {
            float elapsed = FieldClientState.elapsed(0.0F);
            if (elapsed > 300.0F) {
                return;
            }
            urgency = 1.0F - elapsed / 300.0F;
        }
        if (nextBeat < 0L) {
            nextBeat = ticks;
        }
        if (ticks < nextBeat) {
            return;
        }
        // From about 55 beats a minute to about 140.
        float interval = Mth.lerp(urgency, 22.0F, 8.5F);
        nextBeat = ticks + Math.max(6, Math.round(interval));
        AudioCorruptionManager.playGlobal(FieldSounds.HEARTBEAT.get(), AudioCorruptionManager.Channel.EVENT,
                0.25F + 0.7F * urgency, 1.0F);
    }

    private static boolean due(long next) {
        return next >= 0L && ticks >= next;
    }

    /** A one-shot somewhere around the listener, {@code min}-{@code max} blocks away. */
    private static void at(SoundEvent sound, AudioCorruptionManager.Channel channel, double min, double max, double rise, float volume) {
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = min + RANDOM.nextDouble() * (max - min);
        Vec3 pos = around(angle, distance, rise * RANDOM.nextDouble());
        if (pos != null) {
            AudioCorruptionManager.playAt(sound, channel, pos, volume, 0.92F + RANDOM.nextFloat() * 0.14F, (float) distance * 1.6F + 16.0F);
        }
    }

    /** The point {@code distance} blocks from the listener in direction {@code angle} (0 = east, 1.5 pi = north). */
    @Nullable
    private static Vec3 around(double angle, double distance, double rise) {
        Vec3 listener = listener();
        if (listener == null) {
            return null;
        }
        return listener.add(Math.cos(angle) * distance, rise, Math.sin(angle) * distance);
    }

    @Nullable
    private static Vec3 listener() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        return mc.gameRenderer.getMainCamera().getPosition();
    }

    /** Plans the first sounds a little after arriving, never all at once. */
    private static void clearSchedules() {
        nextCrow = ticks + 200 + RANDOM.nextInt(300);
        nextCreak = ticks + 300 + RANDOM.nextInt(400);
        nextBarn = ticks + 600 + RANDOM.nextInt(900);
        nextChimes = ticks + 400 + RANDOM.nextInt(800);
        nextCrack = ticks + 300 + RANDOM.nextInt(300);
        nextFootsteps = ticks + 160 + RANDOM.nextInt(200);
        nextDrag = ticks + 300 + RANDOM.nextInt(300);
        nextBreath = ticks + 400 + RANDOM.nextInt(400);
        nextBeat = -1L;
    }

    /** Logout or a new world: everything stops at once. */
    static void reset() {
        for (FieldLoopSound sound : new FieldLoopSound[]{wind, drone, night}) {
            if (sound != null) {
                sound.stopNow();
                Minecraft.getInstance().getSoundManager().stop(sound);
            }
        }
        wind = null;
        drone = null;
        night = null;
        retry = 0;
        clearSchedules();
    }
}
