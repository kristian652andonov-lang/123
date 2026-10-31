package dev.kristian.nullmod.client.it;

import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.entity.it.ItAnimation;
import dev.kristian.nullmod.entity.it.ItClientHook;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.entity.it.ItSounds;
import dev.kristian.nullmod.entity.it.ItTuning;
import dev.kristian.nullmod.network.ClientEffectPayload;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The client side of every IT, once per client tick: measures how fast it really moves and turns
 * that into gait (upright lope or gallop) and phase, bends it down to windows, lets its head move
 * in jerks and its joints snap, and plays what it sounds like: enormous footfalls exactly when a
 * foot or hand hits the ground (felt as a tremor close by), wet breathing and clicking bones near
 * it, and the loud moments the server announces. Every sound goes through
 * {@link AudioCorruptionManager} on the creature channel, so the player's audio settings apply;
 * every shake through {@link VisualDistortionManager}, so theirs do too.
 */
final class ItClientHooks implements ItClientHook {
    private static final RandomSource RANDOM = RandomSource.create();
    /** The howl carries across the whole field. */
    private static final float HOWL_RANGE = 420.0F;

    @Override
    public void tick(ItEntity it) {
        ItAnimation a = it.animation();
        if (!a.started) {
            start(it, a);
        }
        a.age++;
        a.speedO = a.speed;
        a.runO = a.run;
        a.phaseO = a.phase;
        a.crouchO = a.crouch;
        a.screamO = a.scream;
        a.scrapeO = a.scrape;
        a.scrapePhaseO = a.scrapePhase;
        a.headYawO = a.headYaw;
        a.headPitchO = a.headPitch;

        int phase = it.phase();
        int action = it.action();
        boolean killing = it.killStage() != ItEntity.KILL_NONE;
        double dx = it.getX() - a.lastX;
        double dz = it.getZ() - a.lastZ;
        a.lastX = it.getX();
        a.lastZ = it.getZ();
        float measured = killing ? 0.0F : (float) Math.min(1.2, Math.sqrt(dx * dx + dz * dz));
        a.speed += (measured - a.speed) * 0.35F;
        boolean still = killing || action == ItEntity.ACTION_WINDOW || action == ItEntity.ACTION_SCRAPE;
        float runTarget = still ? 0.0F : smooth((a.speed - 0.14F) / 0.3F);
        a.run += (runTarget - a.run) * 0.12F;
        a.phase += a.speed / ItGait.cycleLength(a.speed);
        if (a.phase > 4096.0F) {
            a.phase -= 4096.0F;
            a.phaseO -= 4096.0F;
        }

        float crouchTarget = action == ItEntity.ACTION_WINDOW ? 1.0F : action == ItEntity.ACTION_SCRAPE ? 0.35F : 0.0F;
        a.crouch += (crouchTarget - a.crouch) * 0.08F;
        Optional<BlockPos> focus = it.focus();
        if (action == ItEntity.ACTION_WINDOW && focus.isPresent()) {
            a.peerDrop = 3.2F - (float) (focus.get().getY() + 0.5 - it.getY());
        }
        if (phase == ItEntity.PHASE_ARRIVING && a.age > 4 && a.age < 58) {
            // It throws its head back and howls as it arrives.
            a.screamTicks = Math.max(a.screamTicks, 2);
        }
        if (a.screamTicks > 0) {
            a.screamTicks--;
            a.scream += (1.0F - a.scream) * 0.35F;
        } else {
            a.scream *= 0.86F;
        }
        if (action == ItEntity.ACTION_SCRAPE) {
            a.scrape += (1.0F - a.scrape) * 0.15F;
            a.scrapePhase += 1.0F / ItTuning.SCRAPE_INTERVAL;
            if (a.scrapePhase >= 1.0F) {
                a.scrapePhase -= 1.0F;
                a.scrapePhaseO -= 1.0F;
            }
        } else {
            a.scrape *= 0.8F;
        }

        // The head holds still, then snaps to where it is looking.
        float yawTarget = Mth.clamp(Mth.wrapDegrees(it.getYHeadRot() - it.yBodyRot), -80.0F, 80.0F) * Mth.DEG_TO_RAD;
        float pitchTarget = Mth.clamp(it.getXRot(), -60.0F, 70.0F) * Mth.DEG_TO_RAD;
        if (a.headHold > 0) {
            a.headHold--;
        }
        if (a.headHold == 0 || Math.abs(yawTarget - a.headYaw) > 0.35F) {
            a.headYaw += (yawTarget - a.headYaw) * 0.8F;
            a.headPitch += (pitchTarget - a.headPitch) * 0.8F;
            a.headHold = 5 + RANDOM.nextInt(18);
        }
        a.jolt *= 0.85F;

        // Something in it snaps out of place for a moment.
        Minecraft mc = Minecraft.getInstance();
        if (a.twitchTicks > 0) {
            a.twitchTicks--;
        } else if (a.age >= a.nextTwitch) {
            a.twitchJoint = RANDOM.nextInt(ItModel.TWITCH_JOINTS);
            a.twitchLength = 2 + RANDOM.nextInt(4);
            a.twitchTicks = a.twitchLength;
            a.twitchX = (RANDOM.nextFloat() - 0.5F) * (killing ? 0.5F : 1.0F);
            a.twitchZ = (RANDOM.nextFloat() - 0.5F) * (killing ? 0.5F : 1.0F);
            a.nextTwitch = a.age + 25 + RANDOM.nextInt(90);
            if (mc.player != null && !it.isSilent() && mc.player.distanceToSqr(it) < 24.0 * 24.0) {
                play(ItSounds.CLICK.get(), body(it), 0.55F, 0.9F + RANDOM.nextFloat() * 0.25F, 28.0F);
            }
        }

        if (mc.player != null && !it.isSilent()) {
            footfalls(it, a, mc);
            breathing(it, a, mc, phase, killing);
        }
    }

    private static void start(ItEntity it, ItAnimation a) {
        a.started = true;
        a.lastX = it.getX();
        a.lastZ = it.getZ();
        a.nextTwitch = 30 + RANDOM.nextInt(60);
        a.nextBreath = 20 + RANDOM.nextInt(60);
        a.nextClick = 15 + RANDOM.nextInt(40);
        a.headYaw = Mth.clamp(Mth.wrapDegrees(it.getYHeadRot() - it.yBodyRot), -80.0F, 80.0F) * Mth.DEG_TO_RAD;
        a.headYawO = a.headYaw;
    }

    /** A footfall for every foot and hand that lands this tick, where it lands. */
    private static void footfalls(ItEntity it, ItAnimation a, Minecraft mc) {
        float duty = ItGait.duty(a.run);
        int planted = 0;
        for (int s = 0; s < 2; s++) {
            if (ItGait.planted(a.phase, ItGait.legOffset(s, a.run), duty)) {
                planted |= 1 << s;
            }
            if (a.run > 0.35F && ItGait.planted(a.phase, ItGait.ARM_RUN[s], duty)) {
                planted |= 4 << s;
            }
        }
        int landed = planted & ~a.planted;
        a.planted = planted;
        if (a.speed < 0.03F || landed == 0) {
            return;
        }
        for (int bit = 0; bit < 4; bit++) {
            if ((landed & (1 << bit)) == 0) {
                continue;
            }
            boolean hand = bit >= 2;
            float side = (bit % 2 == 0) ? -1.0F : 1.0F;
            Vec3 at = it.toWorld(new Vec3(side * (hand ? 1.0 : 0.45), 0.1, hand ? 2.2 : 0.1));
            float weight = 0.45F + 0.55F * a.run;
            play(ItSounds.STEP.get(), at, (hand ? 0.8F : 1.0F) * weight, (hand ? 1.1F : 0.85F) + RANDOM.nextFloat() * 0.15F,
                    56.0F + 64.0F * a.run);
            double distance = Math.sqrt(mc.player.distanceToSqr(at));
            if (distance < 32.0 && mc.player.getVehicle() != it) {
                // The ground shakes under it.
                float near = (float) (1.0 - distance / 32.0);
                VisualDistortionManager.shake(near * near * (0.06F + 0.2F * a.run), 6);
            }
        }
    }

    /** Wet breathing and clicking bones, only close by. */
    private static void breathing(ItEntity it, ItAnimation a, Minecraft mc, int phase, boolean killing) {
        double distance = mc.player.distanceToSqr(it);
        boolean slow = phase == ItEntity.PHASE_PROWLING || phase == ItEntity.PHASE_ARRIVING || killing || a.speed < 0.2F;
        if (a.age >= a.nextBreath) {
            a.nextBreath = a.age + 50 + RANDOM.nextInt(70);
            if (slow && distance < 26.0 * 26.0) {
                play(ItSounds.BREATH.get(), face(it, a), 0.75F, 0.85F + RANDOM.nextFloat() * 0.2F, 30.0F);
            }
        }
        if (a.age >= a.nextClick) {
            a.nextClick = a.age + 30 + RANDOM.nextInt(90);
            if (distance < 30.0 * 30.0) {
                play(ItSounds.CLICK.get(), body(it), 0.5F, 0.85F + RANDOM.nextFloat() * 0.3F, 30.0F);
            }
        }
    }

    @Override
    public void handleEvent(ItEntity it, byte event) {
        ItAnimation a = it.animation();
        Minecraft mc = Minecraft.getInstance();
        Entity held = it.getFirstPassenger();
        Vec3 victim = held != null ? held.position().add(0.0, held.getBbHeight() * 0.6, 0.0) : face(it, a);
        boolean silent = it.isSilent();
        switch (event) {
            case ItEntity.EVENT_SCREAM -> {
                a.screamTicks = 45;
                a.jolt = 1.0F;
                if (!silent) {
                    play(ItSounds.SCREAM.get(), face(it, a), 1.0F, 0.9F + RANDOM.nextFloat() * 0.12F, 110.0F);
                }
                shakeNear(mc, it, 18.0, 0.4F, 24);
            }
            case ItEntity.EVENT_SCRAPE -> {
                if (!silent) {
                    Vec3 at = it.focus().map(Vec3::atCenterOf).orElse(body(it));
                    play(ItSounds.SCRAPE.get(), at, 0.9F, 0.9F + RANDOM.nextFloat() * 0.2F, 36.0F);
                }
                shakeNear(mc, it, 10.0, 0.12F, 8);
            }
            case ItEntity.EVENT_GRAB -> {
                a.jolt = 1.0F;
                if (!silent) {
                    play(ItSounds.GRAB.get(), victim, 1.0F, 0.95F + RANDOM.nextFloat() * 0.1F, 48.0F);
                }
            }
            case ItEntity.EVENT_PIERCE -> {
                a.jolt = 1.0F;
                if (!silent) {
                    play(ItSounds.PIERCE.get(), victim, 1.0F, 1.0F, 48.0F);
                }
            }
            case ItEntity.EVENT_BITE -> {
                a.jolt = Math.max(a.jolt, 0.8F);
                if (!silent) {
                    play(ItSounds.PIERCE.get(), victim, 0.9F, 0.78F, 40.0F);
                }
            }
            case ItEntity.EVENT_TEAR -> {
                a.jolt = Math.max(a.jolt, 0.6F);
                if (!silent) {
                    play(ItSounds.TEAR.get(), victim, 1.0F, 0.95F + RANDOM.nextFloat() * 0.1F, 40.0F);
                }
            }
            case ItEntity.EVENT_SWALLOW -> {
                if (!silent) {
                    play(ItSounds.SWALLOW.get(), face(it, a), 1.0F, 0.9F, 40.0F);
                }
                ItBodies.swallowed(held);
            }
            case ItEntity.EVENT_RETREAT -> {
                if (!silent) {
                    play(ItSounds.RETREAT.get(), body(it), 1.0F, 1.0F, 160.0F);
                }
            }
            case ItEntity.EVENT_SNAP -> {
                a.headHold = 0;
                a.jolt = Math.max(a.jolt, 0.6F);
                if (!silent) {
                    play(ItSounds.CLICK.get(), face(it, a), 0.9F, 0.75F, 44.0F);
                }
            }
            case ItEntity.EVENT_SNIFF -> {
                a.jolt = Math.max(a.jolt, 0.2F);
                if (!silent) {
                    play(ItSounds.BREATH.get(), face(it, a), 0.8F, 1.5F + RANDOM.nextFloat() * 0.25F, 28.0F);
                }
            }
            case ItEntity.EVENT_BLINK -> {
                // It is somewhere else now: no gait to carry over.
                a.started = false;
                a.speed = 0.0F;
                a.speedO = 0.0F;
                a.faceKnown = false;
            }
            default -> {
            }
        }
        ItKillView.onEvent(it, event);
    }

    @Override
    public void removed(ItEntity it) {
        ItKillView.onRemoved(it);
    }

    /** The howl as it arrives: everyone in the level hears it, from where it stands. */
    static void onHowl(ClientEffectPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || payload.pos().isEmpty()) {
            return;
        }
        Vec3 at = Vec3.atCenterOf(payload.pos().get()).add(0.0, 5.0, 0.0);
        AudioCorruptionManager.playAt(ItSounds.HOWL.get(), AudioCorruptionManager.Channel.CREATURE, at,
                Mth.clamp(payload.intensity(), 0.2F, 1.0F), 0.92F + RANDOM.nextFloat() * 0.1F, HOWL_RANGE);
    }

    private static void shakeNear(Minecraft mc, ItEntity it, double radius, float strength, int ticks) {
        if (mc.player == null || mc.player.getVehicle() == it) {
            return;
        }
        double distance = Math.sqrt(mc.player.distanceToSqr(it));
        if (distance < radius) {
            VisualDistortionManager.shake(strength * (float) (1.0 - distance / radius), ticks);
        }
    }

    private static Vec3 face(ItEntity it, ItAnimation a) {
        if (a.faceKnown && it.position().distanceToSqr(a.faceX, a.faceY, a.faceZ) < 8.0 * 8.0) {
            return new Vec3(a.faceX, a.faceY, a.faceZ);
        }
        return it.toWorld(new Vec3(0.0, 6.1, 2.4));
    }

    private static Vec3 body(ItEntity it) {
        return it.position().add(0.0, it.getBbHeight() * 0.6, 0.0);
    }

    private static void play(SoundEvent sound, Vec3 pos, float volume, float pitch, float range) {
        AudioCorruptionManager.playAt(sound, AudioCorruptionManager.Channel.CREATURE, pos, volume, Mth.clamp(pitch, 0.5F, 2.0F), range);
    }

    private static float smooth(float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        return c * c * (3.0F - 2.0F * c);
    }
}
