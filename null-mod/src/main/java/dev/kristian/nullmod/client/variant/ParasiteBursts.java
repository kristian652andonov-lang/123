package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * {@code variant.parasite_burst}: when a parasite leaves its dead host, a knot of tentacles
 * whips out of the spot (overshooting, thrashing), hangs there for a moment and pulls back into
 * the ground, about a second and a half in all, with a wet burst and a slithering retreat
 * (through {@link AudioCorruptionManager}, so the player's audio settings apply). Payload: the
 * position, a seed, the intensity (the parasite's growth), the duration in ticks and, in
 * {@code data}, the host's size in hundredths of a block so the tentacles fit the host.
 * Drawn after the entities from its own buffer, so vanilla's batches are never flushed early.
 */
final class ParasiteBursts {
    static final String EFFECT = "variant.parasite_burst";
    private static final int MAX_BURSTS = 8;
    private static final int MAX_TENTACLES = 9;
    private static final double VIEW_RANGE = 96.0;
    private static final Burst[] BURSTS = new Burst[MAX_BURSTS];
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();
    @Nullable
    private static MultiBufferSource.BufferSource buffers;
    private static int live;

    static {
        for (int i = 0; i < MAX_BURSTS; i++) {
            BURSTS[i] = new Burst();
        }
    }

    private ParasiteBursts() {
    }

    private static final class Burst {
        boolean active;
        @Nullable
        ClientLevel level;
        double x;
        double y;
        double z;
        int age;
        int duration;
        int count;
        float size;
        float intensity;
        boolean retreatHeard;
        final Tentacles.Spec[] specs = new Tentacles.Spec[MAX_TENTACLES];
        final float[] dirX = new float[MAX_TENTACLES];
        final float[] dirY = new float[MAX_TENTACLES];
        final float[] dirZ = new float[MAX_TENTACLES];

        Burst() {
            for (int k = 0; k < MAX_TENTACLES; k++) {
                specs[k] = new Tentacles.Spec();
            }
        }
    }

    static void onEffect(ClientEffectPayload effect) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || effect.pos().isEmpty()) {
            return;
        }
        BlockPos pos = effect.pos().get();
        Burst burst = null;
        for (Burst candidate : BURSTS) {
            if (!candidate.active) {
                burst = candidate;
                break;
            }
            if (burst == null || candidate.age > burst.age) {
                burst = candidate;
            }
        }
        float intensity = effect.intensity();
        intensity = intensity >= 0.0F ? Mth.clamp(intensity, 0.2F, 1.0F) : 1.0F;
        float host = effect.data() > 0 ? Mth.clamp(effect.data() / 100.0F, 0.3F, 4.0F) : 1.0F;
        long seed = effect.seed();
        burst.active = true;
        burst.level = level;
        burst.x = pos.getX() + 0.5;
        burst.y = pos.getY() + 0.1;
        burst.z = pos.getZ() + 0.5;
        burst.age = 0;
        burst.duration = effect.duration() > 0 ? Mth.clamp(effect.duration(), 20, 60) : 30;
        burst.intensity = intensity;
        burst.size = Math.max(0.8F, host) * (0.75F + 0.25F * intensity);
        burst.retreatHeard = false;
        int count = 6 + (int) (Tentacles.mix(seed) & 3L);
        if (!ClientLimits.intenseAllowed()) {
            count = Math.min(count, 5);
        }
        burst.count = count;
        for (int k = 0; k < count; k++) {
            Tentacles.Spec spec = burst.specs[k];
            spec.roll(seed, burst.size, k, count);
            spec.droop *= 0.5F;
            // One gaping mouth among them; the rest end in points and hooks.
            if (k == 0) {
                spec.tip = Tentacles.TIP_MOUTH;
            } else if (spec.tip == Tentacles.TIP_MOUTH) {
                spec.tip = Tentacles.TIP_POINT;
            }
            long h = Tentacles.mix(seed + k * 0x9E3779B97F4A7C15L);
            float yaw = Mth.TWO_PI * (k + Tentacles.unit(h)) / count;
            float pitch = 0.25F + 0.95F * Tentacles.unit(h >>> 11);
            burst.dirX[k] = Mth.cos(pitch) * Mth.sin(yaw);
            burst.dirY[k] = Mth.sin(pitch);
            burst.dirZ[k] = Mth.cos(pitch) * Mth.cos(yaw);
        }
        recount();
        float pitch = Mth.clamp(1.15F - 0.15F * host, 0.7F, 1.15F);
        AudioCorruptionManager.playAt(NullSounds.NULLROOT_GRAB.get(), AudioCorruptionManager.Channel.CREATURE,
                new Vec3(burst.x, burst.y + 0.3, burst.z), 0.6F + 0.4F * intensity, pitch, 24.0F);
    }

    static void clientTick() {
        if (live == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        boolean paused = mc.isPaused();
        for (Burst burst : BURSTS) {
            if (!burst.active) {
                continue;
            }
            if (burst.level != level || level == null) {
                burst.active = false;
                continue;
            }
            if (paused) {
                continue;
            }
            burst.age++;
            if (!burst.retreatHeard && burst.age >= burst.duration / 2) {
                burst.retreatHeard = true;
                AudioCorruptionManager.playAt(NullSounds.NULLROOT_REACH.get(), AudioCorruptionManager.Channel.CREATURE,
                        new Vec3(burst.x, burst.y + 0.2, burst.z), 0.45F + 0.3F * burst.intensity, 0.7F, 20.0F);
            }
            if (burst.age >= burst.duration) {
                burst.active = false;
            }
        }
        recount();
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (live == 0 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
        }
        RenderType type = RenderType.entityCutoutNoCull(VariantTextures.TENTACLE);
        VertexConsumer out = buffers.getBuffer(type);
        boolean calm = !ClientLimits.intenseAllowed();
        for (Burst burst : BURSTS) {
            if (!burst.active || burst.level != level) {
                continue;
            }
            double dx = burst.x - cam.x;
            double dy = burst.y - cam.y;
            double dz = burst.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > VIEW_RANGE * VIEW_RANGE) {
                continue;
            }
            float t = burst.age + partial;
            int light = LevelRenderer.getLightColor(level, LIGHT_POS.set(burst.x, burst.y + 0.5, burst.z));
            float agitation = 2.2F - 1.2F * Math.min(1.0F, t / burst.duration);
            if (calm) {
                agitation = Math.min(agitation, 1.6F);
            }
            for (int k = 0; k < burst.count; k++) {
                float extent = extent(t, k, burst.duration);
                if (extent <= 0.01F) {
                    continue;
                }
                Tentacles.draw(out, (float) dx, (float) dy, (float) dz, burst.dirX[k], burst.dirY[k], burst.dirZ[k], burst.specs[k],
                        1.0F, extent, 0.7F + 0.3F * extent, t, agitation, 10, 6, true, light, OverlayTexture.NO_OVERLAY);
            }
        }
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        buffers.endBatch(type);
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** How far tentacle k is out: whipping out with an overshoot, holding, then pulling back. */
    static float extent(float tick, int k, int duration) {
        float t = (tick - (k % 3) * 0.6F) * 30.0F / duration;
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t < 5.0F) {
            float x = t / 5.0F - 1.0F;
            return 1.0F + 2.70158F * x * x * x + 1.70158F * x * x;
        }
        if (t < 14.0F) {
            return 1.0F;
        }
        if (t < 30.0F) {
            float x = (t - 14.0F) / 16.0F;
            return Math.max(0.0F, 1.0F - x * x * (3.0F - 2.0F * x));
        }
        return 0.0F;
    }

    private static void recount() {
        int n = 0;
        for (Burst burst : BURSTS) {
            if (burst.active) {
                n++;
            }
        }
        live = n;
    }

    static void reset() {
        for (Burst burst : BURSTS) {
            burst.active = false;
            burst.level = null;
        }
        live = 0;
    }
}
