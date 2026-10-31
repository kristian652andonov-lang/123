package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.entity.variant.MobVariant;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * How variant mobs are drawn, for any living entity renderer (vanilla or modded):
 * <ul>
 * <li>before the mob is drawn ({@link #onRenderLivingPre}): a corrupted mob now and then is not
 * drawn at all for a tick or two, or jumps aside for a dropped frame; a parasite twitches and
 * swells as something moves under its skin. Where the mob ends up is remembered for the layer;</li>
 * <li>{@link MobVariantLayer} then draws over the mob, on its own model: the corrupted copy of its
 * texture, glitch frames (its texture slipping, faces torn loose), and now and then the whole mob
 * turned into a black shape with glowing eyes; or the infested copy of a parasite's texture and
 * the tentacles bursting out of its body.</li>
 * </ul>
 * Flicker scales with {@link ClientLimits#flashing()} (none at zero: the black shape fades in
 * slowly instead), everything is gentler without intense effects, and with no visual effects
 * allowed only the static looks (textures, tentacles) remain. Render thread only.
 */
final class VariantRendering {
    /** Parasites farther away than this are drawn without tentacles. */
    private static final double TENTACLE_RANGE = 64.0;
    /** Most parasites that get tentacles in one frame. */
    private static final int MAX_TENTACLE_HOSTS = 24;

    private static final float[] SHIFT = new float[3];
    private static final float[] CENTER = new float[3];
    private static final Quaternionf TURN = new Quaternionf();
    private static long budgetFrame = -1L;
    private static int budgetUsed;
    private static boolean preFailed;

    private VariantRendering() {
    }

    // ------------------------------------------------------------------ before the mob is drawn

    static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (ClientMobVariants.size() == 0 || preFailed) {
            return;
        }
        LivingEntity entity = event.getEntity();
        ClientMobVariants.State state = ClientMobVariants.get(entity);
        if (state == null) {
            return;
        }
        try {
            MobVisual visual = MobVisuals.of(entity, state);
            float time = entity.tickCount + event.getPartialTick();
            visual.updateGrowth(time);
            PoseStack poseStack = event.getPoseStack();
            Matrix4f pose = poseStack.last().pose();
            float unit = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
            visual.unit = unit > 1.0E-4F ? unit : 1.0F;
            if (!entity.isInvisible() && ClientLimits.maxVisualEffects() > 0) {
                float flashing = ClientLimits.flashing();
                boolean intense = ClientLimits.intenseAllowed();
                float pace = stagePace();
                if (state.variant() == MobVariant.CORRUPTED) {
                    float blink = intense && flashing >= 0.3F ? 0.008F * flashing * pace : 0.0F;
                    if (visual.blinkedOut(time, blink)) {
                        // Not drawn at all this tick: only its shadow is left.
                        event.setCanceled(true);
                        return;
                    }
                    if (visual.stutter(time, 0.025F * pace * (intense ? 1.0F : 0.5F), entity.getBbWidth(), SHIFT)) {
                        float scale = 0.5F + 0.5F * flashing;
                        poseStack.translate(SHIFT[0] * scale, SHIFT[1] * scale, SHIFT[2] * scale);
                    }
                } else if (state.variant() == MobVariant.PARASITE) {
                    float turn = visual.twitch(time, intense ? 0.03F : 0.015F);
                    if (turn != 0.0F) {
                        poseStack.mulPose(TURN.rotationY(turn * Mth.DEG_TO_RAD));
                    }
                    float swell = visual.swell(time) * visual.growth * (intense ? 0.045F : 0.025F);
                    if (swell > 0.001F) {
                        poseStack.scale(1.0F + swell, 1.0F - swell * 0.5F, 1.0F + swell);
                    }
                }
            }
            visual.originX = pose.m30();
            visual.originY = pose.m31();
            visual.originZ = pose.m32();
            visual.frame = VariantTextures.frame();
        } catch (RuntimeException e) {
            preFailed = true;
            NullMod.LOGGER.error("NULL's variant mob effects failed and are switched off for this session", e);
        }
    }

    // ------------------------------------------------------------------ over the mob (the layer)

    /** Draws a variant mob's look over its normal render. The pose stack is in the model's space. */
    static void render(Model model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffers, int light,
                       LivingEntity entity, ClientMobVariants.State state, float partialTick) {
        MobVisual visual = MobVisuals.of(entity, state);
        if (visual.frame != VariantTextures.frame()) {
            // Drawn without the usual event before it: assume a plain world render.
            Vec3 cam = camera().getPosition();
            visual.originX = (float) (Mth.lerp(partialTick, entity.xo, entity.getX()) - cam.x);
            visual.originY = (float) (Mth.lerp(partialTick, entity.yo, entity.getY()) - cam.y);
            visual.originZ = (float) (Mth.lerp(partialTick, entity.zo, entity.getZ()) - cam.z);
            visual.unit = 1.0F;
            visual.frame = VariantTextures.frame();
        }
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        float time = entity.tickCount + partialTick;
        if (state.variant() == MobVariant.CORRUPTED) {
            corrupted(model, texture, poseStack, buffers, light, overlay, entity, visual, time, partialTick);
        } else if (state.variant() == MobVariant.PARASITE) {
            parasite(model, texture, poseStack, buffers, light, overlay, entity, visual, time, partialTick);
        }
    }

    private static void corrupted(Model model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffers, int light,
                                  int overlay, LivingEntity entity, MobVisual visual, float time, float partialTick) {
        VariantTextures.Entry entry = VariantTextures.overlay(visual, texture);
        boolean effects = ClientLimits.maxVisualEffects() > 0;
        float flashing = ClientLimits.flashing();
        boolean intense = ClientLimits.intenseAllowed();
        float pace = stagePace();
        float black = effects ? visual.silhouette(time, 0.05F * pace * (intense ? 1.0F : 0.6F), 1.0F + 9.0F * (1.0F - flashing)) : 0.0F;
        if (black < 0.99F) {
            ResourceLocation damaged = entry == null ? null : entry.overlay();
            if (damaged != null) {
                model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(damaged)), light, overlay, -1);
            }
            ResourceLocation glow = entry == null ? null : entry.glow();
            if (glow != null) {
                // The pinprick eyes glow in the dark, slowly brightening and dimming.
                int g = Mth.clamp((int) (255.0F * (0.6F + 0.4F * Mth.sin(time * 0.08F + (visual.seed & 63L)))), 0, 255);
                model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.eyes(glow)), LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(255, g, g, g));
            }
            if (effects && flashing > 0.15F && black <= 0.0F) {
                long glitch = visual.glitchFrame(time, 0.03F * flashing * pace);
                if (glitch != 0L) {
                    GlitchConsumer consumer = GlitchConsumer.INSTANCE.begin(buffers.getBuffer(RenderType.entityCutoutNoCull(texture)),
                            glitch, 0.4F, intense ? 0.18F : 0.08F, entity.getBbWidth() * 0.35F * visual.unit);
                    try {
                        model.renderToBuffer(poseStack, consumer, light, overlay, -1);
                    } finally {
                        consumer.end();
                    }
                }
            }
        }
        if (black > 0.0F) {
            silhouette(model, texture, poseStack, buffers, light, entity, visual, black, partialTick);
        }
    }

    /** The mob as a pure black shape with two white pinprick eyes on its face. */
    private static void silhouette(Model model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffers, int light,
                                   LivingEntity entity, MobVisual visual, float amount, float partialTick) {
        if (amount >= 0.99F) {
            model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)), light, OverlayTexture.NO_OVERLAY, 0xFF000000);
        } else {
            int alpha = Mth.clamp((int) (amount * 255.0F), 0, 255);
            model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.entityTranslucent(texture)), light, OverlayTexture.NO_OVERLAY, alpha << 24);
        }
        ModelRecorder recorder = ModelRecorder.INSTANCE;
        recorder.reset();
        model.renderToBuffer(poseStack, recorder, light, OverlayTexture.NO_OVERLAY, -1);
        float yaw = entity.getViewYRot(partialTick) * Mth.DEG_TO_RAD;
        float pitch = entity.getViewXRot(partialTick) * Mth.DEG_TO_RAD;
        float lookX = -Mth.sin(yaw) * Mth.cos(pitch);
        float lookY = -Mth.sin(pitch);
        float lookZ = Mth.cos(yaw) * Mth.cos(pitch);
        float unit = visual.unit;
        float eyeY = visual.originY + entity.getEyeHeight() * unit;
        int face = recorder.faceQuad(lookX, lookY, lookZ, visual.originX, eyeY, visual.originZ, entity.getBbHeight() * 0.45F * unit);
        if (face >= 0) {
            eyes(recorder, face, buffers.getBuffer(RenderType.eyes(VariantTextures.EYE)), amount, unit);
        }
    }

    /** Two glowing points on a face quad, a little above its middle. */
    private static void eyes(ModelRecorder r, int quad, VertexConsumer out, float amount, float unit) {
        int i = quad * 4;
        float ax = r.x[i + 1] - r.x[i];
        float ay = r.y[i + 1] - r.y[i];
        float az = r.z[i + 1] - r.z[i];
        float bx = r.x[i + 3] - r.x[i];
        float by = r.y[i + 3] - r.y[i];
        float bz = r.z[i + 3] - r.z[i];
        float al = Mth.sqrt(ax * ax + ay * ay + az * az);
        float bl = Mth.sqrt(bx * bx + by * by + bz * bz);
        if (al < 1.0E-5F || bl < 1.0E-5F) {
            return;
        }
        boolean aUp = Math.abs(ay) / al > Math.abs(by) / bl;
        float hx = aUp ? bx : ax;
        float hy = aUp ? by : ay;
        float hz = aUp ? bz : az;
        float hl = aUp ? bl : al;
        float vx = aUp ? ax : bx;
        float vy = aUp ? ay : by;
        float vz = aUp ? az : bz;
        float vl = aUp ? al : bl;
        float top = vy < 0.0F ? 0.38F : 0.62F;
        float nx = r.nx[i];
        float ny = r.ny[i];
        float nz = r.nz[i];
        float nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0E-5F) {
            return;
        }
        nx /= nl;
        ny /= nl;
        nz /= nl;
        float size = Mth.clamp(0.11F * hl, 0.02F * unit, 0.08F * unit) * 2.6F;
        float hux = hx / hl * size;
        float huy = hy / hl * size;
        float huz = hz / hl * size;
        float vux = vx / vl * size;
        float vuy = vy / vl * size;
        float vuz = vz / vl * size;
        int c = Mth.clamp((int) (amount * 255.0F), 0, 255);
        int color = FastColor.ARGB32.color(255, c, c, c);
        float lift = 0.004F * unit;
        for (int e = 0; e < 2; e++) {
            float s = e == 0 ? 0.27F : 0.73F;
            float cx = r.x[i] + hx * s + vx * top + nx * lift;
            float cy = r.y[i] + hy * s + vy * top + ny * lift;
            float cz = r.z[i] + hz * s + vz * top + nz * lift;
            out.addVertex(cx - hux + vux, cy - huy + vuy, cz - huz + vuz, color, 0.0F, 0.0F, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, nx, ny, nz);
            out.addVertex(cx + hux + vux, cy + huy + vuy, cz + huz + vuz, color, 1.0F, 0.0F, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, nx, ny, nz);
            out.addVertex(cx + hux - vux, cy + huy - vuy, cz + huz - vuz, color, 1.0F, 1.0F, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, nx, ny, nz);
            out.addVertex(cx - hux - vux, cy - huy - vuy, cz - huz - vuz, color, 0.0F, 1.0F, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, nx, ny, nz);
        }
    }

    private static void parasite(Model model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffers, int light,
                                 int overlay, LivingEntity entity, MobVisual visual, float time, float partialTick) {
        float hostSize = Mth.sqrt(Math.max(0.01F, entity.getBbWidth() * entity.getBbHeight())) * 1.2F;
        visual.ensureTentacles(hostSize);
        ModelRecorder recorder = ModelRecorder.INSTANCE;
        boolean recorded = false;
        if (!visual.anchorsChosen) {
            recorder.reset();
            model.renderToBuffer(poseStack, recorder, light, overlay, -1);
            recorded = true;
            chooseAnchors(visual, recorder, time);
        }
        VariantTextures.Entry entry = VariantTextures.overlay(visual, texture);
        ResourceLocation infested = entry == null ? null : entry.overlay();
        if (infested != null) {
            model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(infested)), light, overlay, -1);
        }

        Vec3 cam = camera().getPosition();
        double distanceSqr = entity.distanceToSqr(cam.x, cam.y, cam.z);
        if (distanceSqr > TENTACLE_RANGE * TENTACLE_RANGE || !claimTentacleBudget()) {
            return;
        }
        int segments;
        int sides;
        boolean tips;
        int count = visual.tentacles;
        if (distanceSqr < 16.0 * 16.0) {
            segments = 10;
            sides = 6;
            tips = true;
        } else if (distanceSqr < 32.0 * 32.0) {
            segments = 7;
            sides = 5;
            tips = true;
        } else if (distanceSqr < 48.0 * 48.0) {
            segments = 5;
            sides = 4;
            tips = false;
        } else {
            segments = 3;
            sides = 4;
            tips = false;
            count = (count + 1) / 2;
        }
        if (!recorded) {
            recorder.reset();
            model.renderToBuffer(poseStack, recorder, light, overlay, -1);
            if (recorder.quads() != visual.anchorQuads && (time < visual.anchorsTime || time - visual.anchorsTime > 200.0F)) {
                // The model changed shape for good (parts shown or hidden): find the body again.
                chooseAnchors(visual, recorder, time);
            }
        }
        boolean onBody = recorder.quads() > 0 && recorder.quads() == visual.anchorQuads && visual.anchorCount > 0;
        if (onBody) {
            recorder.center(CENTER);
        }
        float growth = visual.growth;
        float grown = smooth(growth);
        float agitation = 1.0F + (entity.hurtTime > 0 ? 1.4F * entity.hurtTime / 10.0F : 0.0F);
        float retract = 1.0F;
        if (entity.deathTime > 0) {
            // A dying host's tentacles thrash and pull back in.
            retract = Math.max(0.0F, 1.0F - (entity.deathTime + partialTick) / 18.0F);
            agitation = 2.4F;
        }
        if (!ClientLimits.intenseAllowed()) {
            agitation = Math.min(agitation, 1.6F);
        }
        float unit = visual.unit;
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot) * Mth.DEG_TO_RAD;
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(VariantTextures.TENTACLE));
        for (int k = 0; k < count; k++) {
            // They come out one after another as the parasite grows.
            float start = 0.6F * k / visual.tentacles;
            float emerge = Mth.clamp((growth - start) / 0.25F, 0.0F, 1.0F);
            float extent = smooth(emerge) * (0.25F + 0.75F * grown) * retract;
            if (extent < 0.02F) {
                continue;
            }
            float thickness = 0.5F + 0.5F * grown;
            float bx;
            float by;
            float bz;
            float dx;
            float dy;
            float dz;
            if (onBody && k < visual.anchorCount) {
                int q = visual.anchorQuad[k];
                float s = visual.anchorS[k];
                float t = visual.anchorT[k];
                bx = recorder.pointX(q, s, t);
                by = recorder.pointY(q, s, t);
                bz = recorder.pointZ(q, s, t);
                int vi = q * 4;
                float nx = recorder.nx[vi];
                float ny = recorder.ny[vi];
                float nz = recorder.nz[vi];
                float nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1.0E-5F) {
                    nx /= nl;
                    ny /= nl;
                    nz /= nl;
                }
                float ox = bx - CENTER[0];
                float oy = by - CENTER[1];
                float oz = bz - CENTER[2];
                float ol = Mth.sqrt(ox * ox + oy * oy + oz * oz);
                if (ol > 1.0E-5F) {
                    ox /= ol;
                    oy /= ol;
                    oz /= ol;
                }
                dx = nx + ox * 0.35F;
                dy = ny + 0.45F + oy * 0.35F;
                dz = nz + oz * 0.35F;
            } else {
                // No body to read (a renderer that draws no model): around the hitbox instead.
                float angle = Mth.TWO_PI * (k + 0.5F) / visual.tentacles + (visual.seed & 1023L) / 163.0F;
                float lx = Mth.sin(angle) * entity.getBbWidth() * 0.4F;
                float lz = Mth.cos(angle) * entity.getBbWidth() * 0.4F;
                float cos = Mth.cos(bodyYaw);
                float sin = Mth.sin(bodyYaw);
                float rx = lx * cos - lz * sin;
                float rz = lx * sin + lz * cos;
                float height = entity.getBbHeight() * (0.45F + 0.4F * Tentacles.unit(Tentacles.mix(visual.seed + k)));
                bx = visual.originX + rx * unit;
                by = visual.originY + height * unit;
                bz = visual.originZ + rz * unit;
                dx = rx;
                dy = 0.7F * Math.max(0.05F, entity.getBbWidth() * 0.4F);
                dz = rz;
            }
            float dl = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl < 1.0E-5F) {
                continue;
            }
            dx /= dl;
            dy /= dl;
            dz /= dl;
            if (dy < -0.2F) {
                // Never straight down into the ground.
                dy = -0.2F;
                float hl = Mth.sqrt(dx * dx + dz * dz);
                float scale = hl > 1.0E-5F ? Mth.sqrt(1.0F - dy * dy) / hl : 0.0F;
                dx *= scale;
                dz *= scale;
            }
            Tentacles.draw(out, bx, by, bz, dx, dy, dz, visual.specs[k], unit, extent, thickness, time, agitation,
                    segments, sides, tips, light, overlay);
        }
    }

    /** Where the tentacles leave the body, chosen once per mob from its layout. */
    private static void chooseAnchors(MobVisual visual, ModelRecorder recorder, float time) {
        visual.anchorsChosen = true;
        visual.anchorsTime = time;
        visual.anchorQuads = recorder.quads();
        long layoutSeed = Tentacles.mix(0x4C41594F55545300L + visual.layout);
        int found = recorder.pickAnchors(layoutSeed, visual.tentacles, visual.anchorQuad, visual.anchorS, visual.anchorT);
        visual.anchorCount = found;
        long hash = 0xCBF29CE484222325L;
        for (int k = 0; k < found; k++) {
            float u = recorder.pointU(visual.anchorQuad[k], visual.anchorS[k], visual.anchorT[k]);
            float v = recorder.pointV(visual.anchorQuad[k], visual.anchorS[k], visual.anchorT[k]);
            visual.anchorUv[k * 2] = u;
            visual.anchorUv[k * 2 + 1] = v;
            hash = (hash ^ Float.floatToIntBits(u)) * 0x100000001B3L;
            hash = (hash ^ Float.floatToIntBits(v)) * 0x100000001B3L;
        }
        visual.anchorsHash = found == 0 ? 0L : hash | 1L;
    }

    private static boolean claimTentacleBudget() {
        long frame = VariantTextures.frame();
        if (frame != budgetFrame) {
            budgetFrame = frame;
            budgetUsed = 0;
        }
        return budgetUsed++ < MAX_TENTACLE_HOSTS;
    }

    /** Glitches come more often at later stages. */
    private static float stagePace() {
        int stage = ClientCorruptionState.stage().number();
        return 1.0F + 0.3F * Mth.clamp(stage - 2, 0, 3);
    }

    private static Camera camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    private static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
