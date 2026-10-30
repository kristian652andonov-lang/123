package dev.kristian.nullmod.client.moon;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.moon.MoonGeometry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Draws what the moon texture alone cannot: the drifting fragments and debris, crack flashes,
 * the warping moon and the final disappearance. Rendered right after the sky with exactly
 * the transform vanilla uses for the moon (rotated -90 degrees about Y, then by the time of day
 * about X, moon quad 20 units across at distance 100), so everything sits on the moon itself.
 * <p>
 * Fragments use the untouched moon texture, so the pieces that drift away are literally the
 * parts missing from the moon, and like the sun and moon they are drawn additively (or with a
 * screen blend where pieces overlap): black is always invisible.
 */
final class MoonSkyRenderer {
    private static final ResourceLocation DEBRIS = NullMod.id("textures/environment/moon_debris.png");
    private static final ResourceLocation GLOW = NullMod.id("textures/environment/moon_glow.png");
    private static final ResourceLocation RING = NullMod.id("textures/environment/moon_ring.png");
    private static final float HALF = 20.0F;
    private static final float DEPTH = -100.0F;
    private static final int STRIPS = 28;
    private static final int MAX_FAILURES = 5;

    private static final Matrix4f MATRIX = new Matrix4f();
    private static final float[] PLACE = new float[MoonFragments.SIZE];
    private static final float[] CORNER_X = {-1.0F, 1.0F, 1.0F, -1.0F};
    private static final float[] CORNER_Y = {-1.0F, -1.0F, 1.0F, 1.0F};
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static int failures;

    private MoonSkyRenderer() {
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || failures >= MAX_FAILURES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        MoonArt art = MoonTextureManager.art();
        if (level == null || art == null || !MoonTextureManager.active() || level.dimension() != Level.OVERWORLD
                || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        if (!skyVisible(mc, level, event.getCamera())) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float rain = 1.0F - level.getRainLevel(partial);
        if (rain <= 0.01F) {
            return;
        }
        // Height of the moon over the horizon (-1..1). This pass runs after the whole sky, so a
        // debris field far below the horizon (daytime) would show over vanilla's dark lower sky.
        float altitude = -Mth.cos(level.getTimeOfDay(partial) * (float) (Math.PI * 2.0));
        if (altitude < -0.45F) {
            return;
        }
        MATRIX.set(event.getModelViewMatrix())
                .rotateY((float) (-Math.PI / 2.0))
                .rotateX(level.getTimeOfDay(partial) * (float) (Math.PI * 2.0));

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        try {
            float now = MoonEffects.now(partial);
            float seconds = (level.getGameTime() + partial) / 20.0F;
            float progress = ClientCorruptionState.moonProgress();
            boolean gone = MoonTextureManager.gone();
            int phase = level.getMoonPhase();
            if (MoonEffects.goneTime(now) >= 0.0F) {
                drawDisappearance(art, progress, seconds, rain, now);
            } else if (!gone) {
                drawWarp(art, phase, rain, now);
                drawFragments(art, progress, seconds, rain, now);
                drawDebris(art, progress, seconds, rain, now);
                drawFlash(rain, now);
            } else if (MoonEffects.distortion(now) > 0.0F) {
                drawWarp(art, phase, rain, now);
            }
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL moon rendering failed ({} of {})", failures, MAX_FAILURES, e);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** Vanilla skips the sun and moon in these situations; so do we. */
    private static boolean skyVisible(Minecraft mc, ClientLevel level, Camera camera) {
        FogType fog = camera.getFluidInCamera();
        if (fog == FogType.POWDER_SNOW || fog == FogType.LAVA) {
            return false;
        }
        if (camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return false;
        }
        Vec3 position = camera.getPosition();
        return !level.effects().isFoggyAt(Mth.floor(position.x), Mth.floor(position.y))
                && !mc.gui.getBossOverlay().shouldCreateWorldFog();
    }

    // ------------------------------------------------------------------ passes

    private static void drawFragments(MoonArt art, float progress, float seconds, float rain, float now) {
        int texture = MoonTextureManager.pristineId();
        if (texture < 0 || progress < MoonGeometry.PIECES - 0.01F) {
            return;
        }
        MoonGeometry geometry = art.geometry();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int s = 0; s < geometry.shardCount(); s++) {
            MoonGeometry.Shard shard = geometry.shard(s);
            float animation = MoonEffects.pieceAnimation(s, now);
            if (!MoonFragments.place(shard, progress, seconds, animation, PLACE)) {
                continue;
            }
            float light = PLACE[MoonFragments.BRIGHTNESS] * rain;
            shard(builder, art, shard, PLACE[MoonFragments.OFFSET_U], PLACE[MoonFragments.OFFSET_V],
                    PLACE[MoonFragments.ANGLE], PLACE[MoonFragments.SCALE], light);
        }
        draw(builder, texture, true);
    }

    private static void drawDebris(MoonArt art, float progress, float seconds, float rain, float now) {
        MoonGeometry geometry = art.geometry();
        long seed = geometry.seed();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        float shatter = MoonGeometry.shatter(progress);
        for (int s = 0; s < geometry.shardCount(); s++) {
            MoonGeometry.Shard shard = geometry.shard(s);
            float animation = MoonEffects.pieceAnimation(s, now);
            if (!MoonFragments.place(shard, progress, seconds, animation, PLACE)) {
                continue;
            }
            float cx = shard.centroidX() + PLACE[MoonFragments.OFFSET_U];
            float cy = shard.centroidY() + PLACE[MoonFragments.OFFSET_V];
            int count = 2 + (shatter > 0.0F ? 1 : 0);
            for (int d = 0; d < count; d++) {
                float direction = MoonFragments.hash(seed, s, d) > 0.5F ? 1.0F : -1.0F;
                float angle = MoonFragments.hash(seed, s, d + 10) * (float) (Math.PI * 2.0) + seconds * 0.012F * direction;
                float radius = (0.05F + MoonFragments.hash(seed, s, d + 20) * 0.12F) * animation;
                float size = 0.012F + MoonFragments.hash(seed, s, d + 30) * 0.018F;
                int sprite = (int) (MoonFragments.hash(seed, s, d + 40) * 16.0F) & 15;
                float light = (0.45F + 0.3F * MoonFragments.hash(seed, s, d + 50)) * rain * animation;
                sprite(builder, art, cx + Mth.cos(angle) * radius, cy + Mth.sin(angle) * radius, size, angle * 2.0F, sprite, light);
            }
        }
        for (int c = 0; c < MoonEffects.chipCount(); c++) {
            float age = MoonEffects.chipAge(c, now);
            float angle = MoonEffects.chipAngle(c);
            float distance = 0.47F + 1.1F * (1.0F - (1.0F - age) * (1.0F - age));
            float light = (1.0F - age) * (1.0F - age) * 0.9F * rain;
            sprite(builder, art, 0.5F + Mth.cos(angle) * distance, 0.5F + Mth.sin(angle) * distance, 0.03F,
                    MoonEffects.chipSpin(c) * age * 9.0F, MoonEffects.chipSprite(c), light);
        }
        draw(builder, DEBRIS, true);
    }

    private static void drawFlash(float rain, float now) {
        float strength = MoonEffects.flash(now);
        int texture = MoonTextureManager.glowId();
        if (strength <= 0.0F || texture < 0) {
            return;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float alpha = strength * rain;
        builder.addVertex(MATRIX, -HALF, DEPTH, HALF).setUv(1.0F, 1.0F).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(MATRIX, HALF, DEPTH, HALF).setUv(0.0F, 1.0F).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(MATRIX, HALF, DEPTH, -HALF).setUv(0.0F, 0.0F).setColor(1.0F, 1.0F, 1.0F, alpha);
        builder.addVertex(MATRIX, -HALF, DEPTH, -HALF).setUv(1.0F, 0.0F).setColor(1.0F, 1.0F, 1.0F, alpha);
        draw(builder, texture, false);
    }

    /**
     * The moon drawn as horizontal strips that slide sideways: a wobble with sudden glitch
     * jumps and faint red/cyan ghosts. While this runs the real moon is blacked out (unless it
     * is a ghost of a moon that no longer exists).
     */
    private static void drawWarp(MoonArt art, int phase, float rain, float now) {
        boolean ghost = MoonEffects.distortionGhost();
        if (!ghost && !MoonTextureManager.moonHidden()) {
            return;
        }
        float strength = MoonEffects.distortion(now);
        int texture = ghost ? MoonTextureManager.pristineId() : MoonTextureManager.copyId();
        if (texture < 0 || (ghost && strength <= 0.0F)) {
            return;
        }
        int column = Math.floorMod(phase, 4);
        int row = Math.floorMod(phase / 4, 2);
        float u0 = column / 4.0F;
        float u1 = (column + 1) / 4.0F;
        float v0 = row / 2.0F;
        float v1 = (row + 1) / 2.0F;
        float alpha;
        if (ghost) {
            // A ghost of the moon that is no longer there: faint and unstable.
            float flicker = 0.55F + 0.45F * Mth.sin(now * 2.3F) * Mth.sin(now * 0.71F + 1.3F);
            alpha = rain * strength * (0.18F + 0.3F * flicker);
        } else {
            alpha = rain;
        }
        long seed = art.geometry().seed();
        // Sudden jumps and colour ghosts count as intense flicker: only when the player allows it.
        boolean intense = ClientLimits.intenseAllowed();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        strips(builder, seed, now, strength, 0.0F, intense, u0, u1, v0, v1, 1.0F, 1.0F, 1.0F, alpha);
        draw(builder, texture, false);
        if (intense && strength > 0.05F) {
            float ghostAlpha = alpha * 0.35F * strength;
            float split = 0.9F * strength;
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            strips(builder, seed, now, strength, split, true, u0, u1, v0, v1, 1.0F, 0.2F, 0.2F, ghostAlpha);
            strips(builder, seed, now, strength, -split, true, u0, u1, v0, v1, 0.2F, 0.9F, 1.0F, ghostAlpha);
            draw(builder, texture, false);
        }
    }

    private static void strips(BufferBuilder builder, long seed, float now, float strength, float shift, boolean bursts,
                               float u0, float u1, float v0, float v1, float r, float g, float b, float a) {
        int burst = (int) (now / 3.0F);
        for (int i = 0; i < STRIPS; i++) {
            float t0 = (float) i / STRIPS;
            float t1 = (float) (i + 1) / STRIPS;
            float offset = strength * (1.4F * Mth.sin(now * 0.33F + i * 0.55F) + 0.6F * Mth.sin(now * 1.7F + i * 1.9F));
            if (bursts && MoonFragments.hash(seed, i, burst) < 0.12F * strength) {
                offset += (MoonFragments.hash(seed, i, burst + 7) - 0.5F) * 7.0F * strength;
            }
            offset += shift;
            float z0 = -HALF + 2.0F * HALF * t0;
            float z1 = -HALF + 2.0F * HALF * t1;
            float tv0 = v0 + (v1 - v0) * t0;
            float tv1 = v0 + (v1 - v0) * t1;
            builder.addVertex(MATRIX, -HALF + offset, DEPTH, z1).setUv(u1, tv1).setColor(r, g, b, a);
            builder.addVertex(MATRIX, HALF + offset, DEPTH, z1).setUv(u0, tv1).setColor(r, g, b, a);
            builder.addVertex(MATRIX, HALF + offset, DEPTH, z0).setUv(u0, tv0).setColor(r, g, b, a);
            builder.addVertex(MATRIX, -HALF + offset, DEPTH, z0).setUv(u1, tv0).setColor(r, g, b, a);
        }
    }

    /** The final disappearance: every piece blows outwards and fades, with a flash and a ring. */
    private static void drawDisappearance(MoonArt art, float progress, float seconds, float rain, float now) {
        float t = MoonEffects.goneTime(now);
        if (t < 0.0F || t > MoonEffects.GONE_VISIBLE_TICKS || !MoonEffects.goneWatching()) {
            return;
        }
        float tau = t / MoonEffects.GONE_VISIBLE_TICKS;
        float burst = 1.0F - (1.0F - tau) * (1.0F - tau);
        float fade = (float) Math.pow(1.0F - tau, 1.6);
        int texture = MoonTextureManager.pristineId();
        MoonGeometry geometry = art.geometry();
        if (texture >= 0) {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            float before = Math.min(progress, 0.999F);
            for (int s = 0; s < geometry.shardCount(); s++) {
                MoonGeometry.Shard shard = geometry.shard(s);
                if (shard.visibleArea() < 0.0005F) {
                    continue;
                }
                float u = 0.0F;
                float v = 0.0F;
                float angle = 0.0F;
                float scale = 1.0F;
                if (MoonFragments.place(shard, before, seconds, 1.0F, PLACE)) {
                    u = PLACE[MoonFragments.OFFSET_U];
                    v = PLACE[MoonFragments.OFFSET_V];
                    angle = PLACE[MoonFragments.ANGLE];
                    scale = PLACE[MoonFragments.SCALE];
                }
                float push = burst * (1.6F + 1.4F * shard.depth());
                u += shard.driftX() * push;
                v += shard.driftY() * push;
                angle += shard.spin() * burst * 4.0F;
                float light = fade * (1.0F + 0.6F * Math.max(0.0F, 1.0F - t / 6.0F)) * rain;
                shard(builder, art, shard, u, v, angle, scale * (1.0F - 0.3F * burst), Math.min(1.0F, light));
            }
            draw(builder, texture, true);
        }
        float flashing = 0.35F + 0.65F * ClientLimits.flashing();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float glowAge = t / 24.0F;
        if (glowAge < 1.0F) {
            float size = 0.6F + 2.8F * (1.0F - (1.0F - glowAge) * (1.0F - glowAge));
            float alpha = (float) Math.pow(1.0F - glowAge, 1.5) * flashing * rain;
            billboard(builder, art, 0.5F, 0.5F, size, alpha, 0.0F, 0.0F, 1.0F, 1.0F);
        }
        draw(builder, GLOW, false);
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float ringAge = t / 50.0F;
        if (ringAge < 1.0F) {
            float size = 0.4F + 6.0F * (1.0F - (1.0F - ringAge) * (1.0F - ringAge));
            billboard(builder, art, 0.5F, 0.5F, size, 0.8F * (1.0F - ringAge) * flashing * rain, 0.0F, 0.0F, 1.0F, 1.0F);
        }
        draw(builder, RING, false);
    }

    // ------------------------------------------------------------------ geometry helpers

    /** One shard as a triangle fan around its centre, textured with where it used to be. */
    private static void shard(BufferBuilder builder, MoonArt art, MoonGeometry.Shard shard, float offsetU, float offsetV,
                              float angle, float scale, float light) {
        if (light <= 0.003F) {
            return;
        }
        float cx = shard.centroidX();
        float cy = shard.centroidY();
        float px = cx + offsetU;
        float py = cy + offsetV;
        float cos = Mth.cos(angle) * scale;
        float sin = Mth.sin(angle) * scale;
        float[] xs = shard.x();
        float[] ys = shard.y();
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            vertex(builder, art, px, py, cx, cy, light);
            float ax = xs[i] - cx;
            float ay = ys[i] - cy;
            vertex(builder, art, px + ax * cos - ay * sin, py + ax * sin + ay * cos, xs[i], ys[i], light);
            float bx = xs[j] - cx;
            float by = ys[j] - cy;
            vertex(builder, art, px + bx * cos - by * sin, py + bx * sin + by * cos, xs[j], ys[j], light);
        }
    }

    /** A vertex at disc position (du, dv), sampling the full-moon phase of the untouched sheet at (su, sv). */
    private static void vertex(BufferBuilder builder, MoonArt art, float du, float dv, float su, float sv, float light) {
        float fx = art.discFractionX() + du * art.discFractionW();
        float fy = art.discFractionY() + dv * art.discFractionH();
        float tu = (art.discFractionX() + su * art.discFractionW()) / 4.0F;
        float tv = (art.discFractionY() + sv * art.discFractionH()) / 2.0F;
        builder.addVertex(MATRIX, HALF - 2.0F * HALF * fx, DEPTH, -HALF + 2.0F * HALF * fy)
                .setUv(tu, tv)
                .setColor(light, light, light, 1.0F);
    }

    /** A small debris sprite (two triangles) at a disc position. */
    private static void sprite(BufferBuilder builder, MoonArt art, float du, float dv, float size, float angle, int sprite, float light) {
        if (light <= 0.003F) {
            return;
        }
        float su0 = (sprite & 3) / 4.0F;
        float sv0 = ((sprite >> 2) & 3) / 4.0F;
        float cos = Mth.cos(angle) * size;
        float sin = Mth.sin(angle) * size;
        for (int k : TRIANGLES) {
            float cx = CORNER_X[k];
            float cy = CORNER_Y[k];
            float x = du + cx * cos - cy * sin;
            float y = dv + cx * sin + cy * cos;
            float fx = art.discFractionX() + x * art.discFractionW();
            float fy = art.discFractionY() + y * art.discFractionH();
            builder.addVertex(MATRIX, HALF - 2.0F * HALF * fx, DEPTH, -HALF + 2.0F * HALF * fy)
                    .setUv(cx < 0.0F ? su0 : su0 + 0.25F, cy < 0.0F ? sv0 : sv0 + 0.25F)
                    .setColor(light, light, light, 1.0F);
        }
    }

    /** A square sprite centred on a disc position, {@code size} disc diameters wide (additive). */
    private static void billboard(BufferBuilder builder, MoonArt art, float du, float dv, float size, float alpha,
                                  float u0, float v0, float u1, float v1) {
        if (alpha <= 0.003F) {
            return;
        }
        float half = size * 0.5F;
        for (int k = 0; k < 4; k++) {
            float cx = CORNER_X[k];
            float cy = CORNER_Y[k];
            float fx = art.discFractionX() + (du + cx * half) * art.discFractionW();
            float fy = art.discFractionY() + (dv + cy * half) * art.discFractionH();
            builder.addVertex(MATRIX, HALF - 2.0F * HALF * fx, DEPTH, -HALF + 2.0F * HALF * fy)
                    .setUv(cx < 0.0F ? u0 : u1, cy < 0.0F ? v0 : v1)
                    .setColor(1.0F, 1.0F, 1.0F, alpha);
        }
    }

    private static void draw(BufferBuilder builder, int texture, boolean screen) {
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.setShaderTexture(0, texture);
        blend(screen);
        BufferUploader.drawWithShader(mesh);
    }

    private static void draw(BufferBuilder builder, ResourceLocation texture, boolean screen) {
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.setShaderTexture(0, texture);
        blend(screen);
        BufferUploader.drawWithShader(mesh);
    }

    /** Screen blending for solid pieces (overlaps never blow out), additive for light. */
    private static void blend(boolean screen) {
        if (screen) {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        } else {
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        }
    }
}
