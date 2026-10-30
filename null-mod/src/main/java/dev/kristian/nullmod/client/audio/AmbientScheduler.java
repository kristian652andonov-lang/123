package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The background of each stage, played on the client between the server's events:
 * <ul>
 * <li>Stage 1: very faint static and strange ambience, minutes apart.</li>
 * <li>Stage 2: more static, reversed sounds, short interruptions.</li>
 * <li>Stage 3: organic, parasitic noises from random directions, a heartbeat, the ground.</li>
 * <li>Stage 4: distant voices, footsteps, sounds of someone else playing, breathing behind.</li>
 * <li>Stage 5: audio that never settles: a drifting drone (the dimension's own hum inside NULL),
 * corrupted music, bursts of wrong pitches, frequent interruptions.</li>
 * </ul>
 * From Stage 3 on, every sound in the game occasionally stops at the same moment.
 */
final class AmbientScheduler {
    private static int nextAmbient = 600;
    private static int nextInterruption = 2400;
    private static int nextSilence = 9000;
    private static int nextChaos = 2400;
    private static int nextMusic = 6000;
    @Nullable
    private static NullSoundInstance drone;
    private static boolean droneInNull;
    private static float droneDrift = 1.0F;
    private static float droneTarget = 1.0F;
    private static int droneRetarget;
    private static int droneRetry;

    private static final SoundEvent[] PLAYER_LIKE = {
            SoundEvents.WOODEN_DOOR_OPEN, SoundEvents.WOODEN_DOOR_CLOSE, SoundEvents.CHEST_OPEN, SoundEvents.CHEST_CLOSE,
            SoundEvents.STONE_BREAK, SoundEvents.WOOD_BREAK, SoundEvents.GENERIC_EAT, SoundEvents.STONE_PLACE,
            SoundEvents.WOOD_PLACE, SoundEvents.PLAYER_ATTACK_WEAK
    };

    private AmbientScheduler() {
    }

    static void onStageChanged(CorruptionStage from, CorruptionStage to) {
        RandomSource random = AudioCorruptionManager.random();
        nextAmbient = 200 + random.nextInt(400);
        nextInterruption = 1200 + random.nextInt(2400);
        nextSilence = 3000 + random.nextInt(6000);
        if (to.number() < CorruptionStage.NULL.number()) {
            stopLoops(80);
        }
    }

    static void tick(Minecraft mc, RandomSource random) {
        boolean active = mc.player != null && mc.level != null && ClientCorruptionState.synced() && !ClientCorruptionState.paused()
                && !mc.isPaused() && ClientLimits.audio() > 0.0F && !AudioCorruptionManager.isSilenced();
        if (!active) {
            if (drone != null && (AudioCorruptionManager.isTransitionSilent() || !ClientCorruptionState.synced()
                    || ClientCorruptionState.paused() || ClientLimits.audio() <= 0.0F)) {
                stopLoops(40);
            }
            return;
        }
        int stage = ClientCorruptionState.stage().number();
        // A lower audio intensity makes everything quieter (channel gain) and rarer (longer waits).
        float rarity = 1.0F / Math.max(0.3F, Math.min(1.0F, ClientLimits.audio()));
        if (--nextAmbient <= 0) {
            ambient(stage, mc, random);
            nextAmbient = scaled(interval(stage, random), rarity);
        }
        tickDrone(stage, mc, random);
        if (NullClientSettings.get(NullClientSettings.SUDDEN_SILENCE)) {
            if (stage >= 2 && --nextInterruption <= 0) {
                // A dropout: everything cuts out for a fraction of a second.
                AudioCorruptionManager.silence(3 + random.nextInt(stage >= 5 ? 10 : 6));
                nextInterruption = scaled(stage >= 5 ? 400 + random.nextInt(900)
                        : stage >= 3 ? 1800 + random.nextInt(2400) : 2400 + random.nextInt(3600), rarity);
            }
            if (stage >= 3 && --nextSilence <= 0) {
                AudioCorruptionManager.silence(40 + random.nextInt(80));
                nextSilence = scaled(stage >= 5 ? 3000 + random.nextInt(3600) : 9000 + random.nextInt(9000), rarity);
            }
        }
        if (stage >= 5) {
            if (--nextChaos <= 0) {
                PitchCorruption.startChaos(100 + random.nextInt(200), 0.3F + 0.4F * random.nextFloat());
                nextChaos = scaled(1800 + random.nextInt(3000), rarity);
            }
            if (--nextMusic <= 0) {
                if (!MusicCorruption.playing() && random.nextFloat() < 0.5F) {
                    MusicCorruption.playCorrupted(random);
                }
                nextMusic = 6000 + random.nextInt(9000);
            }
        }
    }

    static void stopLoops(int fade) {
        NullSoundInstance loop = drone;
        drone = null;
        if (loop != null) {
            if (fade <= 0) {
                loop.stopNow();
            } else {
                loop.fadeOut(fade);
            }
        }
    }

    static void reset() {
        stopLoops(0);
        droneRetry = 0;
        nextAmbient = 600;
        nextInterruption = 2400;
        nextSilence = 9000;
        nextChaos = 2400;
        nextMusic = 6000;
    }

    private static int scaled(int ticks, float rarity) {
        return Math.round(ticks * rarity);
    }

    private static int interval(int stage, RandomSource random) {
        return switch (stage) {
            case 1 -> 2400 + random.nextInt(3600);
            case 2 -> 1300 + random.nextInt(2000);
            case 3 -> 700 + random.nextInt(1100);
            case 4 -> 400 + random.nextInt(700);
            default -> 140 + random.nextInt(320);
        };
    }

    // ------------------------------------------------------------------ the sounds

    private static void ambient(int stage, Minecraft mc, RandomSource random) {
        float pick = random.nextFloat();
        switch (stage) {
            case 1 -> stageOne(random);
            case 2 -> {
                if (pick < 0.4F) {
                    stageOne(random);
                } else {
                    stageTwo(random, 1.0F);
                }
            }
            case 3 -> {
                if (pick < 0.25F) {
                    stageTwo(random, 1.0F);
                } else {
                    stageThree(random, 1.0F);
                }
            }
            case 4 -> {
                if (pick < 0.15F) {
                    stageTwo(random, 1.0F);
                } else if (pick < 0.4F) {
                    stageThree(random, 1.0F);
                } else {
                    stageFour(mc, random, 1.0F);
                }
            }
            default -> stageFive(mc, random);
        }
    }

    private static void stageOne(RandomSource random) {
        float pick = random.nextFloat();
        if (pick < 0.35F) {
            play(NullSounds.STATIC_FAINT.get(), AudioCorruptionManager.Channel.STATIC, 0.16F, 0.9F + random.nextFloat() * 0.2F);
        } else if (pick < 0.6F) {
            play(NullSounds.WRONG_WIND.get(), AudioCorruptionManager.Channel.AMBIENT, 0.2F, 0.9F + random.nextFloat() * 0.1F);
        } else if (pick < 0.85F) {
            // A cave sound where there is no cave, a little too slow.
            play(SoundEvents.AMBIENT_CAVE.value(), AudioCorruptionManager.Channel.AMBIENT, 0.3F, 0.55F + random.nextFloat() * 0.15F);
        } else {
            loop(NullSounds.LOW_HUM.get(), AudioCorruptionManager.Channel.AMBIENT, 0.14F, 0.8F + random.nextFloat() * 0.2F,
                    60 + random.nextInt(60), random);
        }
    }

    private static void stageTwo(RandomSource random, float pitch) {
        float pick = random.nextFloat();
        if (pick < 0.3F) {
            play(NullSounds.STATIC_BURST.get(), AudioCorruptionManager.Channel.STATIC, 0.22F, pitch * (0.9F + random.nextFloat() * 0.2F));
        } else if (pick < 0.6F) {
            play(NullSounds.REVERSED.get(), AudioCorruptionManager.Channel.AMBIENT, 0.35F, pitch * (0.9F + random.nextFloat() * 0.15F));
        } else if (pick < 0.85F) {
            play(NullSounds.DIGITAL_INTERFERENCE.get(), AudioCorruptionManager.Channel.STATIC, 0.25F, pitch);
        } else {
            play(SoundEvents.AMBIENT_CAVE.value(), AudioCorruptionManager.Channel.AMBIENT, 0.3F, 0.5F * pitch);
        }
    }

    private static void stageThree(RandomSource random, float pitch) {
        float pick = random.nextFloat();
        if (pick < 0.3F) {
            play(NullSounds.NULLROOT_IDLE.get(), AudioCorruptionManager.Channel.CREATURE, 0.4F, pitch * (0.85F + random.nextFloat() * 0.3F));
        } else if (pick < 0.45F) {
            playAt(NullSounds.NULLROOT_PULSE.get(), AudioCorruptionManager.Channel.CREATURE, below(random), 0.45F, pitch, 40.0F);
        } else if (pick < 0.6F) {
            play(NullSounds.NULLROOT_GROW.get(), AudioCorruptionManager.Channel.CREATURE, 0.35F, pitch * (0.85F + random.nextFloat() * 0.3F));
        } else if (pick < 0.75F) {
            playAt(NullSounds.UNDERGROUND.get(), AudioCorruptionManager.Channel.AMBIENT, below(random), 0.4F, pitch, 40.0F);
        } else if (pick < 0.9F) {
            loop(NullSounds.HEARTBEAT.get(), AudioCorruptionManager.Channel.AMBIENT, 0.35F, pitch, 50 + random.nextInt(50), random);
        } else {
            play(NullSounds.WHISPER.get(), AudioCorruptionManager.Channel.WHISPER, 0.25F, pitch);
        }
    }

    private static void stageFour(Minecraft mc, RandomSource random, float pitch) {
        float pick = random.nextFloat();
        if (pick < 0.2F) {
            playAt(NullSounds.VOICES_DISTANT.get(), AudioCorruptionManager.Channel.WHISPER, AudioPositions.around(random, 12.0, 26.0),
                    0.45F, pitch * (0.9F + random.nextFloat() * 0.15F), 60.0F);
        } else if (pick < 0.35F) {
            if (AudioCorruptionManager.canStart(AudioCorruptionManager.Channel.WHISPER)) {
                AudioCorruptionManager.playBehind(NullSounds.WHISPER.get(), AudioCorruptionManager.Channel.WHISPER,
                        1.4F + random.nextFloat(), 0.35F, pitch);
            }
        } else if (pick < 0.55F) {
            PhantomWalkers.distant(random, 0.5F, false);
        } else if (pick < 0.75F) {
            playerLike(random, pitch);
        } else if (pick < 0.85F) {
            AudioCorruptionManager.playBehind(NullSounds.BREATH_BEHIND.get(), AudioCorruptionManager.Channel.CREATURE,
                    0.7F + random.nextFloat() * 0.3F, 0.4F, pitch);
        } else if (pick < 0.95F) {
            playAt(NullSounds.KNOCK.get(), AudioCorruptionManager.Channel.EVENT, AudioPositions.around(random, 5.0, 11.0),
                    0.45F, pitch, 40.0F);
        } else {
            PhantomWalkers.behind(random, 0.5F, false);
        }
    }

    private static void stageFive(Minecraft mc, RandomSource random) {
        // Nothing stays at the right pitch any more.
        float pitch = 0.7F + random.nextFloat() * 0.6F;
        float pick = random.nextFloat();
        if (pick < 0.15F) {
            play(NullSounds.GLITCH.get(), AudioCorruptionManager.Channel.STATIC, 0.3F, pitch);
        } else if (pick < 0.3F) {
            AudioCorruptionManager.playBehind(NullSounds.FAKE_PLAYER_WHISPER.get(), AudioCorruptionManager.Channel.WHISPER,
                    1.0F + random.nextFloat(), 0.35F, pitch);
        } else if (pick < 0.42F) {
            playAt(NullSounds.VOICES_DISTANT.get(), AudioCorruptionManager.Channel.WHISPER, AudioPositions.around(random, 10.0, 24.0),
                    0.45F, pitch, 60.0F);
        } else if (pick < 0.52F) {
            play(NullSounds.STATIC_BURST.get(), AudioCorruptionManager.Channel.STATIC, 0.3F, pitch);
        } else if (pick < 0.6F) {
            AudioCorruptionManager.playGlobal(NullSounds.RUMBLE.get(), AudioCorruptionManager.Channel.AMBIENT, 0.35F, pitch);
        } else if (pick < 0.7F) {
            playerLike(random, pitch);
        } else if (pick < 0.8F) {
            PhantomWalkers.distant(random, 0.6F, true);
        } else if (pick < 0.9F) {
            stageThree(random, pitch);
        } else {
            stageFour(mc, random, pitch);
        }
    }

    /** Sounds of someone else playing nearby: a door, a chest, a block breaking, eating. */
    private static void playerLike(RandomSource random, float pitch) {
        SoundEvent sound = PLAYER_LIKE[random.nextInt(PLAYER_LIKE.length)];
        playAt(sound, AudioCorruptionManager.Channel.EVENT, AudioPositions.around(random, 10.0, 24.0), 0.5F,
                pitch * (0.85F + random.nextFloat() * 0.15F), 48.0F);
    }

    private static void tickDrone(int stage, Minecraft mc, RandomSource random) {
        if (stage < 5 || mc.level == null) {
            if (drone != null) {
                stopLoops(80);
            }
            return;
        }
        boolean inNull = NullDimensions.isNull(mc.level);
        NullSoundInstance loop = drone;
        if (droneRetry > 0) {
            droneRetry--;
        }
        // isPlaying also notices a drone the game cut off (sound engine reload, /stopsound).
        if (!AudioCorruptionManager.isPlaying(loop) || loop.fading() || droneInNull != inNull) {
            if (loop != null) {
                loop.fadeOut(80);
            }
            drone = null;
            if (droneRetry > 0) {
                return;
            }
            droneRetry = 40;
            // Inside NULL the dimension already hums; ours sits far below it, beating against it.
            SoundEvent sound = inNull ? NullSounds.DIMENSION_HUM.get() : NullSounds.LOW_HUM.get();
            drone = AudioCorruptionManager.start(new NullSoundInstance(sound, AudioCorruptionManager.Channel.AMBIENT,
                    NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0, inNull ? 0.3F : 0.35F, inNull ? 0.6F : 0.85F,
                    true, false, random).fades(100, 80));
            droneInNull = inNull;
            droneDrift = 1.0F;
            droneTarget = 1.0F;
            loop = drone;
            if (loop == null) {
                return;
            }
        }
        if (--droneRetarget <= 0) {
            droneTarget = 0.82F + random.nextFloat() * 0.3F;
            droneRetarget = 60 + random.nextInt(200);
        }
        droneDrift += (droneTarget - droneDrift) * 0.01F;
        float level = Mth.clamp(0.6F + (droneDrift - 0.82F) * 1.3F, 0.4F, 1.0F);
        loop.modulate(droneDrift, level);
    }

    // ------------------------------------------------------------------ helpers

    private static void play(SoundEvent sound, AudioCorruptionManager.Channel channel, float volume, float pitch) {
        AudioCorruptionManager.play(sound, channel, null, volume, pitch);
    }

    private static void playAt(SoundEvent sound, AudioCorruptionManager.Channel channel, Vec3 pos, float volume, float pitch, float range) {
        AudioCorruptionManager.playAt(sound, channel, pos, volume, pitch, range);
    }

    /** A short loop (hum, heartbeat) that fades in and out on its own. */
    private static void loop(SoundEvent sound, AudioCorruptionManager.Channel channel, float volume, float pitch, int ticks, RandomSource random) {
        if (!AudioCorruptionManager.canStart(channel)) {
            return;
        }
        AudioCorruptionManager.start(new NullSoundInstance(sound, channel, NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0,
                volume, pitch, true, false, random).fades(20, 30).lifetime(ticks));
    }

    private static Vec3 below(RandomSource random) {
        Vec3 listener = AudioCorruptionManager.listener();
        if (listener == null) {
            return Vec3.ZERO;
        }
        return listener.add((random.nextDouble() - 0.5) * 8.0, -8.0 - random.nextDouble() * 8.0, (random.nextDouble() - 0.5) * 8.0);
    }
}
