package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.entity.nullroot.NullrootAnimation;
import dev.kristian.nullmod.entity.nullroot.NullrootClientHook;
import dev.kristian.nullmod.entity.nullroot.NullrootEntity;
import dev.kristian.nullmod.entity.nullroot.NullrootState;
import dev.kristian.nullmod.entity.nullroot.NullrootVariant;
import dev.kristian.nullmod.registry.NullSounds;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The client side of every organism, once per client tick: smooths the synced growth, reach and
 * capture progress, turns it slowly toward whoever it watches, makes it twitch into a new pose
 * while nobody on this client is looking at it, plays its sounds through the audio corruption
 * system (so every audio setting applies), and tells {@link NullrootCaptureEffects} when it is
 * holding the local player.
 */
final class NullrootClientHooks implements NullrootClientHook {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float WATCH_TURN = (float) Math.toRadians(2.0);
    private static final float HUNT_TURN = (float) Math.toRadians(4.5);
    /** Organisms further than this from the listener make no sound. */
    private static final double HEARING = 40.0;

    @Override
    public void tick(NullrootEntity organism) {
        Minecraft minecraft = Minecraft.getInstance();
        NullrootAnimation anim = organism.animation();
        NullrootVariant variant = organism.variant();
        NullrootState state = organism.state();
        float growthTarget = organism.growth();
        float reachTarget = organism.reach() * variant.maxReach();
        float captureTarget = organism.captureProgress();
        if (!anim.started) {
            start(organism, anim, growthTarget, reachTarget, captureTarget);
        }
        anim.shift();
        anim.age++;
        anim.growth = approach(anim.growth, growthTarget, 0.3F);
        anim.reach = approach(anim.reach, reachTarget, state == NullrootState.RECOIL ? 0.6F : 0.35F);
        anim.capture = approach(anim.capture, captureTarget, 0.25F);
        anim.jolt *= 0.84F;
        anim.pulse *= 0.9F;

        Direction face = organism.face();
        Vec3 attach = organism.position().add(NullrootEntity.attachOffset(face, variant));
        Entity focus = organism.focus();
        if (focus != null) {
            Vec3 eye = focus.getEyePosition();
            float yaw = NullrootGeometry.yawToward(face, eye.x - attach.x, eye.y - attach.y, eye.z - attach.z);
            if (!Float.isNaN(yaw)) {
                anim.targetYaw = yaw;
            }
            double off = variant.rootOffset();
            Vec3 legs = NullrootEntity.legsOf(focus);
            double dx = legs.x - (attach.x + face.getStepX() * off);
            double dy = legs.y - (attach.y + face.getStepY() * off);
            double dz = legs.z - (attach.z + face.getStepZ() * off);
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length > 1.0E-3) {
                anim.reachDirX = dx / length;
                anim.reachDirY = dy / length;
                anim.reachDirZ = dz / length;
            }
            if (state.tendrilOut()) {
                anim.lastReach = Math.max(anim.reach, state == NullrootState.GRABBING ? (float) length : 0.0F);
            }
        }
        boolean hunting = state == NullrootState.REACHING || state.holding();
        anim.yaw = NullrootGeometry.approachAngle(anim.yaw, anim.targetYaw, hunting ? HUNT_TURN : WATCH_TURN);

        if (watched(minecraft, organism)) {
            anim.unseenTicks = 0;
            // Seen, it settles slowly back into its usual stance.
            anim.leanX *= 0.97F;
            anim.leanZ *= 0.97F;
        } else {
            anim.unseenTicks++;
            if (anim.unseenTicks > 30 && anim.age >= anim.nextTwitch && !hunting && organism.deathTime == 0 && !organism.isWithering()) {
                twitch(anim);
                anim.nextTwitch = anim.age + 50 + RANDOM.nextInt(160);
            }
        }

        if (minecraft.player != null) {
            if (growthTarget >= 1.0F && organism.deathTime == 0 && RANDOM.nextInt(420) == 0
                    && minecraft.player.distanceToSqr(organism) < 16.0 * 16.0) {
                play(organism, NullSounds.NULLROOT_IDLE.get(), attach, 0.45F, 0.0F);
            }
            if (state.holding() && organism.focusId() == minecraft.player.getId() && !organism.isRemoved()) {
                NullrootCaptureEffects.report(captureTarget);
            }
        }
    }

    private static void start(NullrootEntity organism, NullrootAnimation anim, float growth, float reach, float capture) {
        anim.started = true;
        anim.growth = growth;
        anim.reach = reach;
        anim.capture = capture;
        anim.yaw = (float) ((NullrootGeometry.mix(anim.seed) & 1023L) / 1023.0 * Math.PI * 2.0 - Math.PI);
        anim.targetYaw = anim.yaw;
        anim.swayShift = (anim.seed & 255L) / 40.0F;
        anim.nextTwitch = 60 + RANDOM.nextInt(120);
        if (growth < 0.9F && !organism.isWithering() && !anim.grewAudible) {
            anim.grewAudible = true;
            play(organism, NullSounds.NULLROOT_GROW.get(), organism.position(), 0.6F, 0.0F);
        }
    }

    /** While nobody looks it is somewhere else when they look back: a new stance, a new turn, a new sway. */
    private static void twitch(NullrootAnimation anim) {
        anim.yaw = NullrootGeometry.wrap(anim.yaw + (RANDOM.nextFloat() - 0.5F) * 2.2F);
        anim.yawO = anim.yaw;
        anim.leanX = (RANDOM.nextFloat() - 0.5F) * 0.36F;
        anim.leanZ = (RANDOM.nextFloat() - 0.5F) * 0.36F;
        anim.leanXO = anim.leanX;
        anim.leanZO = anim.leanZ;
        anim.swayShift += 0.8F + RANDOM.nextFloat() * 2.5F;
    }

    /** True if this client's camera points roughly at it (about a sixty degree cone). */
    private static boolean watched(Minecraft minecraft, NullrootEntity organism) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return true;
        }
        Vec3 from = camera.getPosition();
        Vec3 center = organism.getBoundingBox().getCenter();
        double dx = center.x - from.x;
        double dy = center.y - from.y;
        double dz = center.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-3) {
            return true;
        }
        if (length > 96.0) {
            return false;
        }
        Vector3f look = camera.getLookVector();
        return (look.x * dx + look.y * dy + look.z * dz) / length > 0.5;
    }

    @Override
    public void handleEvent(NullrootEntity organism, byte event) {
        NullrootAnimation anim = organism.animation();
        Vec3 attach = organism.position().add(NullrootEntity.attachOffset(organism.face(), organism.variant()));
        switch (event) {
            case NullrootEntity.EVENT_REACH -> {
                anim.jolt = Math.max(anim.jolt, 0.35F);
                play(organism, NullSounds.NULLROOT_REACH.get(), attach, 0.7F, 0.0F);
            }
            case NullrootEntity.EVENT_GRAB -> {
                anim.pulse = 1.0F;
                anim.jolt = Math.max(anim.jolt, 0.6F);
                Entity focus = organism.focus();
                play(organism, NullSounds.NULLROOT_GRAB.get(), focus != null ? focus.position() : attach, 1.0F, 0.0F);
            }
            case NullrootEntity.EVENT_RECOIL -> anim.jolt = 1.0F;
            case NullrootEntity.EVENT_PULSE -> {
                anim.pulse = 1.0F;
                play(organism, NullSounds.NULLROOT_PULSE.get(), attach, organism.variant() == NullrootVariant.CENTRAL ? 0.85F : 0.4F, 0.0F);
            }
            case NullrootEntity.EVENT_CONSUME -> {
                anim.pulse = 1.0F;
                anim.jolt = Math.max(anim.jolt, 0.7F);
                play(organism, NullSounds.NULLROOT_CONSUME.get(), attach, 1.0F, 0.0F);
            }
            case NullrootEntity.EVENT_RELEASE -> anim.jolt = Math.max(anim.jolt, 0.45F);
            case NullrootEntity.EVENT_HURT -> {
                anim.jolt = 1.0F;
                play(organism, NullSounds.NULLROOT_HURT.get(), attach, 0.85F, 0.0F);
            }
            case NullrootEntity.EVENT_DEATH -> play(organism, NullSounds.NULLROOT_DEATH.get(), attach, 0.9F, 0.0F);
            default -> {
            }
        }
    }

    @Override
    public void removed(NullrootEntity organism) {
        // Its animation state lives and dies with the entity; the capture effects notice on their own.
    }

    /** Plays one of its sounds where it is, through the player's audio settings. */
    private static void play(NullrootEntity organism, SoundEvent sound, Vec3 pos, float volume, float pitchShift) {
        Minecraft minecraft = Minecraft.getInstance();
        if (organism.isSilent() || minecraft.player == null || minecraft.player.distanceToSqr(pos) > HEARING * HEARING) {
            return;
        }
        float pitch = basePitch(organism.variant()) + pitchShift + (RANDOM.nextFloat() - 0.5F) * 0.16F;
        AudioCorruptionManager.playAt(sound, AudioCorruptionManager.Channel.CREATURE, pos, volume, Mth.clamp(pitch, 0.5F, 2.0F), 26.0F);
    }

    private static float basePitch(NullrootVariant variant) {
        return switch (variant) {
            case SMALL -> 1.15F;
            case LARGE, UNDERGROUND -> 0.85F;
            case CENTRAL -> 0.72F;
            default -> 1.0F;
        };
    }

    private static float approach(float current, float target, float factor) {
        float next = current + (target - current) * factor;
        return Math.abs(target - next) < 0.002F ? target : next;
    }
}
