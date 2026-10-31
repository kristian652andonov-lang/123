package dev.kristian.nullmod.client.it;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.it.ItAnimation;
import dev.kristian.nullmod.entity.it.ItEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * IT's eyes, seen from far away in the dark: two burning red points that fog and night cannot
 * take. Every IT the client knows is drawn here (not through the entity renderer, so culling and
 * entity distance settings do not matter) as a pair of soft round glows facing the camera, added
 * onto the picture after the entities with a shader that has no fog, out to about 120 blocks and
 * fading out beyond. They are depth-tested, so every block between still hides them, and they
 * only show from in front: from behind its head is in the way. The glow is steady (no pulse, no
 * flicker); farther away the pair spreads apart and the points grow a little so they stay a few
 * pixels wide and still read as two. Close up the model's own emissive eyes take over.
 */
final class ItEyes {
    /** Full strength out to this distance (blocks), faded out over the next {@link #FADE_RANGE}. */
    private static final double FULL_RANGE = 120.0;
    private static final double FADE_RANGE = 30.0;
    /** Fades in between these distances: nearer than the first only the model's own eyes show. */
    private static final double NEAR_FROM = 8.0;
    private static final double NEAR_TO = 26.0;
    private static final int MAX_FAILURES = 3;

    /** Concentric rings of one glow: radius (share of the whole) and colour (red, green, blue, at full strength). */
    private static final float[] RING_RADIUS = {0.0F, 0.12F, 0.3F, 0.6F, 1.0F};
    private static final float[][] RING_COLOR = {
            {1.0F, 0.45F, 0.28F}, {0.95F, 0.2F, 0.12F}, {0.55F, 0.06F, 0.04F}, {0.16F, 0.02F, 0.01F}, {0.0F, 0.0F, 0.0F}};
    private static final int SEGMENTS = 10;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];

    static {
        for (int k = 0; k <= SEGMENTS; k++) {
            COS[k] = Mth.cos(Mth.TWO_PI * k / SEGMENTS);
            SIN[k] = Mth.sin(Mth.TWO_PI * k / SEGMENTS);
        }
    }

    /** Every IT in the client level, added when it joins and dropped when it leaves. */
    private static final List<ItEntity> LIVE = new ArrayList<>();
    private static final float[] MODEL = new float[9];
    private static final double[] FALLBACK = new double[9];
    private static int failures;

    private ItEyes() {
    }

    static void add(ItEntity it) {
        if (!LIVE.contains(it)) {
            LIVE.add(it);
        }
    }

    static void remove(ItEntity it) {
        LIVE.remove(it);
    }

    static void reset() {
        LIVE.clear();
    }

    /**
     * Right after the renderer drew {@code entity}: remembers where its eyes are in the world
     * (the same turn, flip and lift the renderer applies to the model) and which way they look.
     */
    static void remember(ItEntity entity, ItModel model, float partialTick) {
        model.eyeFrame(MODEL);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float theta = (180.0F - bodyYaw) * Mth.DEG_TO_RAD;
        float cos = Mth.cos(theta);
        float sin = Mth.sin(theta);
        ItAnimation anim = entity.animation();
        double[] eye = anim.eye;
        float x = -MODEL[0] / 16.0F;
        float z = MODEL[2] / 16.0F;
        eye[0] = Mth.lerp(partialTick, entity.xo, entity.getX()) + x * cos + z * sin;
        eye[1] = Mth.lerp(partialTick, entity.yo, entity.getY()) + 1.501 - MODEL[1] / 16.0F;
        eye[2] = Mth.lerp(partialTick, entity.zo, entity.getZ()) - x * sin + z * cos;
        for (int k = 3; k < 9; k += 3) {
            float dx = -MODEL[k];
            float dz = MODEL[k + 2];
            eye[k] = dx * cos + dz * sin;
            eye[k + 1] = -MODEL[k + 1];
            eye[k + 2] = -dx * sin + dz * cos;
        }
        anim.eyeKnown = true;
    }

    /** Where the eyes are: as last drawn, or worked out from its feet and facing if it was not drawn lately. */
    private static double[] frame(ItEntity it) {
        ItAnimation anim = it.animation();
        if (anim.eyeKnown && it.position().distanceToSqr(anim.eye[0], anim.eye[1], anim.eye[2]) < 10.0 * 10.0) {
            return anim.eye;
        }
        Vec3 feet = it.position();
        Vec3 face = it.toWorld(new Vec3(0.0, 6.1, 2.4));
        Vec3 side = it.toWorld(new Vec3(1.0, 0.0, 0.0)).subtract(feet);
        Vec3 ahead = it.toWorld(new Vec3(0.0, 0.0, 1.0)).subtract(feet);
        FALLBACK[0] = face.x;
        FALLBACK[1] = face.y;
        FALLBACK[2] = face.z;
        FALLBACK[3] = side.x;
        FALLBACK[4] = side.y;
        FALLBACK[5] = side.z;
        FALLBACK[6] = ahead.x;
        FALLBACK[7] = ahead.y;
        FALLBACK[8] = ahead.z;
        return FALLBACK;
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (LIVE.isEmpty() || failures >= MAX_FAILURES || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            LIVE.clear();
            return;
        }
        try {
            draw(event, level);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("IT's far-away eyes failed to draw ({} of {})", failures, MAX_FAILURES, e);
        }
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level) {
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        // The camera's own axes: the glows are flat and always face it.
        float yaw = camera.getYRot() * Mth.DEG_TO_RAD;
        float pitch = camera.getXRot() * Mth.DEG_TO_RAD;
        float sy = Mth.sin(yaw);
        float cy = Mth.cos(yaw);
        float sp = Mth.sin(pitch);
        float cp = Mth.cos(pitch);
        float leftX = cy;
        float leftZ = sy;
        float upX = -sp * sy;
        float upY = cp;
        float upZ = sp * cy;

        BufferBuilder buffer = null;
        for (int i = LIVE.size() - 1; i >= 0; i--) {
            ItEntity it = LIVE.get(i);
            if (it.isRemoved() || it.level() != level) {
                LIVE.remove(i);
                continue;
            }
            double[] eye = frame(it);
            double dx = eye[0] - cam.x;
            double dy = eye[1] - cam.y;
            double dz = eye[2] - cam.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < NEAR_FROM || distance > FULL_RANGE + FADE_RANGE) {
                continue;
            }
            // Only from in front: the eyes face along eye[6..8].
            double facing = -(eye[6] * dx + eye[7] * dy + eye[8] * dz) / distance;
            float strength = strength(distance, facing);
            if (strength <= 0.004F) {
                continue;
            }
            if (buffer == null) {
                buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            }
            // Out in front of the face a little (along the line of sight, so nothing moves on screen),
            // so the head's own surface does not cut the glow in half.
            double push = 0.3 + 0.003 * distance;
            double px = dx - dx / distance * push;
            double py = dy - dy / distance * push;
            double pz = dz - dz / distance * push;
            // Farther away the pair spreads and the points grow, so they stay a few pixels wide and still two.
            double half = Math.min(0.6, 0.125 + 0.004 * Math.max(0.0, distance - 20.0));
            float radius = (float) (0.15 + 0.0042 * distance);
            for (int e = -1; e <= 1; e += 2) {
                glow(buffer, (float) (px + eye[3] * half * e), (float) (py + eye[4] * half * e), (float) (pz + eye[5] * half * e),
                        leftX, 0.0F, leftZ, upX, upY, upZ, radius, strength);
            }
        }
        if (buffer == null) {
            return;
        }
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return;
        }
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            // Added onto the picture, tested against the depth of everything already drawn, writing none.
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            // Position and colour only: this shader has no fog.
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** 0..1: fades in with distance, out past the far limit, and out as the camera leaves its line of sight. */
    private static float strength(double distance, double facing) {
        float far = distance <= FULL_RANGE ? 1.0F : (float) Mth.clamp((FULL_RANGE + FADE_RANGE - distance) / FADE_RANGE, 0.0, 1.0);
        float near = smooth((float) ((distance - NEAR_FROM) / (NEAR_TO - NEAR_FROM)));
        float look = smooth((float) ((facing + 0.2) / 0.6));
        return far * near * look;
    }

    private static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * One round glow around (x, y, z) (camera space), in the plane spanned by the unit vectors
     * left and up, as rings of quads whose corners carry the colour: bright and hot in the
     * middle, red around it, nothing at the rim.
     */
    private static void glow(BufferBuilder b, float x, float y, float z, float lx, float ly, float lz, float ux, float uy, float uz,
                             float radius, float strength) {
        for (int ring = 0; ring < RING_RADIUS.length - 1; ring++) {
            float r0 = RING_RADIUS[ring] * radius;
            float r1 = RING_RADIUS[ring + 1] * radius;
            float[] c0 = RING_COLOR[ring];
            float[] c1 = RING_COLOR[ring + 1];
            for (int k = 0; k < SEGMENTS; k++) {
                corner(b, x, y, z, lx, ly, lz, ux, uy, uz, COS[k] * r0, SIN[k] * r0, c0, strength);
                corner(b, x, y, z, lx, ly, lz, ux, uy, uz, COS[k] * r1, SIN[k] * r1, c1, strength);
                corner(b, x, y, z, lx, ly, lz, ux, uy, uz, COS[k + 1] * r1, SIN[k + 1] * r1, c1, strength);
                corner(b, x, y, z, lx, ly, lz, ux, uy, uz, COS[k + 1] * r0, SIN[k + 1] * r0, c0, strength);
            }
        }
    }

    private static void corner(BufferBuilder b, float x, float y, float z, float lx, float ly, float lz, float ux, float uy, float uz,
                               float a, float c, float[] color, float strength) {
        b.addVertex(x + lx * a + ux * c, y + ly * a + uy * c, z + lz * a + uz * c)
                .setColor(Math.round(255.0F * color[0] * strength), Math.round(255.0F * color[1] * strength),
                        Math.round(255.0F * color[2] * strength), 255);
    }
}
