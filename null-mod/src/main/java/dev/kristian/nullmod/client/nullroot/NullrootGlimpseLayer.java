package dev.kristian.nullmod.client.nullroot;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;

/**
 * The nullroot.glimpse effect: for a second or two a black tendril slides in from one edge of
 * the screen, curls once and pulls back out, like something at the very edge of vision. A small
 * HUD layer of its own, drawn under the hotbar; one batched strip of quads, nothing allocated
 * per frame beyond the vertex buffer. Never flashes, so it is not limited by the flashing
 * setting, but it is skipped when the player allows no visual effects at all.
 */
final class NullrootGlimpseLayer {
    static final ResourceLocation ID = NullMod.id("nullroot_glimpse");
    private static final int SEGMENTS = 30;
    /** Centre line of the last strip drawn (offshoots start on it). */
    private static final float[] PATH_X = new float[SEGMENTS + 1];
    private static final float[] PATH_Y = new float[SEGMENTS + 1];
    private static final float[] PATH_HEADING = new float[SEGMENTS + 1];

    private static boolean active;
    private static int age;
    private static int duration;
    private static int edge;
    private static float along;
    private static float angle;
    private static float intensity;
    private static float phase;
    private static boolean flip;

    private NullrootGlimpseLayer() {
    }

    static void start(long seed, float strength, int ticks) {
        if (ClientLimits.maxVisualEffects() <= 0 || Minecraft.getInstance().level == null) {
            return;
        }
        RandomSource random = RandomSource.create(seed);
        active = true;
        age = 0;
        duration = Mth.clamp(ticks, 24, 70);
        edge = random.nextInt(4);
        along = 0.18F + random.nextFloat() * 0.64F;
        angle = (random.nextFloat() - 0.5F) * 0.8F;
        intensity = Mth.clamp(strength, 0.25F, 1.0F);
        phase = random.nextFloat() * 6.28F;
        flip = random.nextBoolean();
    }

    static void clientTick() {
        if (active && !Minecraft.getInstance().isPaused() && ++age >= duration) {
            active = false;
        }
    }

    static void reset() {
        active = false;
    }

    /** 0..1 how far it is in: out fast, a moment of writhing, back slowly. */
    private static float extension(float t) {
        if (t < 0.3F) {
            float x = t / 0.3F;
            return 1.0F - (1.0F - x) * (1.0F - x);
        }
        if (t < 0.62F) {
            return 1.0F;
        }
        float x = (t - 0.62F) / 0.38F;
        return 1.0F - x * x;
    }

    static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float time = age + delta.getGameTimeDeltaPartialTick(false);
        float t = time / duration;
        if (t >= 1.0F) {
            return;
        }
        float extend = extension(t);
        if (extend <= 0.01F) {
            return;
        }
        float width = graphics.guiWidth();
        float height = graphics.guiHeight();
        float size = Math.min(width, height);
        float baseX;
        float baseY;
        float direction;
        switch (edge) {
            case 0 -> {
                baseX = -3.0F;
                baseY = along * height;
                direction = 0.0F;
            }
            case 1 -> {
                baseX = width + 3.0F;
                baseY = along * height;
                direction = Mth.PI;
            }
            case 2 -> {
                baseX = along * width;
                baseY = -3.0F;
                direction = Mth.HALF_PI;
            }
            default -> {
                baseX = along * width;
                baseY = height + 3.0F;
                direction = -Mth.HALF_PI;
            }
        }
        direction += angle;
        float length = size * (0.38F + 0.22F * intensity) * extend;
        float thickness = size * (0.05F + 0.035F * intensity);
        float curl = (flip ? 1.0F : -1.0F) * (0.8F + 0.7F * t);

        graphics.flush();
        Matrix4f pose = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // A faint dark halo first, then the tendril itself and two thin offshoots.
        strip(buffer, pose, baseX, baseY, direction, length, thickness * 1.7F, curl, time, phase, 22, 8, 30, 70);
        strip(buffer, pose, baseX, baseY, direction, length, thickness, curl, time, phase, 4, 2, 6, 240);
        // The offshoots grow out of the tendril's actual (curled) path.
        int first = Math.round(SEGMENTS * 0.35F);
        int second = Math.round(SEGMENTS * 0.55F);
        float sideX = PATH_X[first];
        float sideY = PATH_Y[first];
        float sideHeading = PATH_HEADING[first];
        float side2X = PATH_X[second];
        float side2Y = PATH_Y[second];
        float side2Heading = PATH_HEADING[second];
        strip(buffer, pose, sideX, sideY, sideHeading + Math.copySign(0.9F, curl), length * 0.38F, thickness * 0.35F, -curl, time * 1.3F,
                phase + 2.1F, 4, 2, 6, 235);
        strip(buffer, pose, side2X, side2Y, side2Heading - Math.copySign(0.8F, curl), length * 0.28F, thickness * 0.25F, curl, time * 1.6F,
                phase + 4.4F, 4, 2, 6, 230);
        MeshData mesh = buffer.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** One tapering, curling strip from (x, y) in {@code direction}. */
    private static void strip(BufferBuilder buffer, Matrix4f pose, float x, float y, float direction, float length, float thickness,
                              float curl, float time, float phase, int r, int g, int b, int alpha) {
        float px = x;
        float py = y;
        float heading = direction;
        float prevLeftX = 0.0F;
        float prevLeftY = 0.0F;
        float prevRightX = 0.0F;
        float prevRightY = 0.0F;
        float step = length / SEGMENTS;
        for (int i = 0; i <= SEGMENTS; i++) {
            float s = i / (float) SEGMENTS;
            if (i > 0) {
                heading += curl * 2.2F / SEGMENTS * s + Mth.sin(time * 0.35F + s * 7.0F + phase) * 0.06F;
                px += Mth.cos(heading) * step;
                py += Mth.sin(heading) * step;
            }
            PATH_X[i] = px;
            PATH_Y[i] = py;
            PATH_HEADING[i] = heading;
            float half = 0.5F * thickness * (float) Math.pow(1.0F - s, 0.6F) + 0.4F;
            float nx = -Mth.sin(heading) * half;
            float ny = Mth.cos(heading) * half;
            float leftX = px + nx;
            float leftY = py + ny;
            float rightX = px - nx;
            float rightY = py - ny;
            if (i > 0) {
                buffer.addVertex(pose, prevLeftX, prevLeftY, 0.0F).setColor(r, g, b, alpha);
                buffer.addVertex(pose, prevRightX, prevRightY, 0.0F).setColor(r, g, b, alpha);
                buffer.addVertex(pose, rightX, rightY, 0.0F).setColor(r, g, b, alpha);
                buffer.addVertex(pose, leftX, leftY, 0.0F).setColor(r, g, b, alpha);
            }
            prevLeftX = leftX;
            prevLeftY = leftY;
            prevRightX = rightX;
            prevRightY = rightY;
        }
    }
}
