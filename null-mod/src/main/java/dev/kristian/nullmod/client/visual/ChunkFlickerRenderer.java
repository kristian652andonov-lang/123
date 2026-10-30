package dev.kristian.nullmod.client.visual;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * For a few frames a whole chunk 32-96 blocks away is covered by a solid black (or
 * missing-texture) cuboid sitting on its surface, as if it had failed to render. Drawn after the
 * translucent terrain with depth testing, so nearer terrain still hides it.
 */
final class ChunkFlickerRenderer {
    private static final ResourceLocation MISSING = NullMod.id("textures/gui/visual/missing_pattern.png");
    private static final int MAX = 3;

    private static final class Flicker {
        boolean active;
        float x0;
        float y0;
        float z0;
        float x1;
        float y1;
        float z1;
        double start;
        float duration;
        long seed;
        boolean missing;
        boolean strobe;
        float strength;
    }

    private static final Flicker[] FLICKERS = new Flicker[MAX];

    static {
        for (int i = 0; i < MAX; i++) {
            FLICKERS[i] = new Flicker();
        }
    }

    private ChunkFlickerRenderer() {
    }

    /** Picks a chunk in front of the player and starts flickering it. False if none fits. */
    static boolean start(float intensity, int duration, long seed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || ClientLimits.maxVisualEffects() <= 0) {
            return false;
        }
        Flicker slot = null;
        for (Flicker f : FLICKERS) {
            if (!f.active) {
                slot = f;
                break;
            }
        }
        if (slot == null) {
            return false;
        }
        RandomSource random = RandomSource.create(seed);
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int bestX = 0;
        int bestZ = 0;
        int bestMin = 0;
        int bestMax = 0;
        int bestRange = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 10; attempt++) {
            float yaw = (player.getYRot() + (random.nextFloat() * 2.0F - 1.0F) * 42.0F) * Mth.DEG_TO_RAD;
            double distance = 32.0 + random.nextDouble() * 64.0;
            int cx = Mth.floor(player.getX() - Mth.sin(yaw) * distance) >> 4;
            int cz = Mth.floor(player.getZ() + Mth.cos(yaw) * distance) >> 4;
            if (Math.abs(cx - playerChunkX) <= 1 && Math.abs(cz - playerChunkZ) <= 1) {
                continue;
            }
            if (!level.hasChunk(cx, cz)) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, (cx << 4) + dx, (cz << 4) + dz);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            if (max <= level.getMinBuildHeight()) {
                continue;
            }
            int range = max - min;
            if (range < bestRange) {
                bestRange = range;
                bestX = cx;
                bestZ = cz;
                bestMin = min;
                bestMax = max;
                if (range <= 6) {
                    break;
                }
            }
        }
        if (bestRange == Integer.MAX_VALUE) {
            return false;
        }
        int top = Math.min(bestMax, bestMin + 24);
        slot.active = true;
        slot.x0 = (bestX << 4) - 0.02F;
        slot.z0 = (bestZ << 4) - 0.02F;
        slot.x1 = (bestX << 4) + 16.02F;
        slot.z1 = (bestZ << 4) + 16.02F;
        slot.y0 = bestMin - 3.0F;
        slot.y1 = top + 0.03F;
        slot.start = VisualDistortionManager.time();
        slot.duration = Mth.clamp(duration, 6, 60);
        slot.seed = seed;
        slot.missing = random.nextFloat() < 0.35F;
        slot.strobe = ClientLimits.intenseAllowed() && ClientLimits.flashing() >= 0.6F;
        slot.strength = Mth.clamp(0.55F + intensity * 0.45F, 0.0F, 1.0F) * (0.35F + 0.65F * SmoothNoise.clamp01(ClientLimits.flashing()));
        if (!slot.strobe) {
            // Without strobing the cuboid fades in and out once, slowly.
            slot.duration = Math.max(slot.duration, 36.0F);
        }
        VisualSounds.chunkFlicker(new Vec3((bestX << 4) + 8.0, top, (bestZ << 4) + 8.0), intensity);
        return true;
    }

    static void clear() {
        for (Flicker f : FLICKERS) {
            f.active = false;
        }
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        boolean any = false;
        double now = VisualDistortionManager.time();
        for (Flicker f : FLICKERS) {
            if (f.active && now - f.start >= f.duration) {
                f.active = false;
            }
            any |= f.active;
        }
        if (!any) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GlConst.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            for (Flicker f : FLICKERS) {
                if (!f.active) {
                    continue;
                }
                float alpha = visibility(f, now) * f.strength;
                if (alpha <= 0.01F) {
                    continue;
                }
                drawBox(f, cam, alpha);
            }
        } finally {
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static float visibility(Flicker f, double now) {
        double age = now - f.start;
        if (f.strobe) {
            // A few hard frames on, a few off, at an irregular rhythm.
            long segment = (long) Math.floor(age / 1.5);
            return SmoothNoise.hash01(f.seed, segment) < 0.55F ? 1.0F : 0.0F;
        }
        float x = (float) (age / f.duration);
        return SmoothNoise.smoothstep(0.0F, 0.3F, x) * (1.0F - SmoothNoise.smoothstep(0.6F, 1.0F, x));
    }

    private static void drawBox(Flicker f, Vec3 cam, float alpha) {
        float x0 = (float) (f.x0 - cam.x);
        float y0 = (float) (f.y0 - cam.y);
        float z0 = (float) (f.z0 - cam.z);
        float x1 = (float) (f.x1 - cam.x);
        float y1 = (float) (f.y1 - cam.y);
        float z1 = (float) (f.z1 - cam.z);
        int a = GuiQuads.alpha(alpha);
        if (f.missing) {
            float h = (f.y1 - f.y0) / 16.0F;
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            // top
            b.addVertex(x0, y1, z0).setUv(0.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x0, y1, z1).setUv(0.0F, 1.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z1).setUv(1.0F, 1.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z0).setUv(1.0F, 0.0F).setColor(255, 255, 255, a);
            // north / south
            b.addVertex(x0, y0, z0).setUv(0.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x0, y1, z0).setUv(0.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z0).setUv(1.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y0, z0).setUv(1.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x0, y0, z1).setUv(0.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x0, y1, z1).setUv(0.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z1).setUv(1.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y0, z1).setUv(1.0F, h).setColor(255, 255, 255, a);
            // west / east
            b.addVertex(x0, y0, z0).setUv(0.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x0, y1, z0).setUv(0.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x0, y1, z1).setUv(1.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x0, y0, z1).setUv(1.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x1, y0, z0).setUv(0.0F, h).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z0).setUv(0.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y1, z1).setUv(1.0F, 0.0F).setColor(255, 255, 255, a);
            b.addVertex(x1, y0, z1).setUv(1.0F, h).setColor(255, 255, 255, a);
            MeshData mesh = b.build();
            if (mesh != null) {
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                RenderSystem.setShaderTexture(0, MISSING);
                BufferUploader.drawWithShader(mesh);
            }
        } else {
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            box(b, x0, y0, z0, x1, y1, z1, a);
            MeshData mesh = b.build();
            if (mesh != null) {
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                BufferUploader.drawWithShader(mesh);
            }
        }
    }

    private static void box(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1, int a) {
        b.addVertex(x0, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y0, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x1, y0, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y0, z1).setColor(0, 0, 0, a);
        b.addVertex(x0, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y0, z1).setColor(0, 0, 0, a);
        b.addVertex(x0, y0, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x0, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x0, y0, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y0, z0).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z0).setColor(0, 0, 0, a);
        b.addVertex(x1, y1, z1).setColor(0, 0, 0, a);
        b.addVertex(x1, y0, z1).setColor(0, 0, 0, a);
    }
}
