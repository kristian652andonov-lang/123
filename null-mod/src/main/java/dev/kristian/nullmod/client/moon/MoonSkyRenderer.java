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
import dev.kristian.nullmod.moon.MoonArt;
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
 * about X, moon quad 40 units across at distance 100, the phase cell stretched over it), and every
 * position goes through {@link MoonArt}'s disc-to-cell mapping for the phase the sky shows, so
 * everything sits on the moon's real body (vanilla's moon is the middle quarter of its quad).
 * <p>
 * Fragments are cut from the moon's body alone in the current phase, so the pieces that drift
 * away are literally the parts missing from the moon, and like the sun and moon they are drawn
 * additively (or with a screen blend where pieces overlap): black is always invisible.
 */
final class MoonSkyRenderer {
    private static final ResourceLocation DEBRIS = NullMod.id("textures/environment/moon_debris.png");
    private static final ResourceLocation GLOW = NullMod.id("textures/environment/moon_glow.png");
    private static final ResourceLocation RING = NullMod.id("textures/environment/moon_ring.png");
    private static final float HALF = 20.0F;
    private static final float DEPTH = -100.0F;
    private static final int STRIPS = 28;
    private static final int MAX_FAILURES = 5;
    /** Debris sprites and rim chips, in moon diameters. */
    private static final float DEBRIS_SIZE = 0.024F;
    private static final float DEBRIS_SIZE_RANGE = 0.034F;
    private static final float CHIP_SIZE = 0.055F;
    /** The final disappearance: flash and shock ring sizes, in moon diameters. */
    private static final float GONE_GLOW_START = 0.9F;
    private static final float GONE_GLOW_GROWTH = 4.6F;
    private static final float GONE_RING_START = 0.7F;
    private static final float GONE_RING_GROWTH = 9.0F;

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
        if (level == null || art == null || !art.found() || !MoonTextureManager.active() || level.dimension() != Level.OVERWORLD
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
                drawDisappearance(art, phase, progress, seconds, rain, now);
            } else if (!gone) {
                drawWarp(art, phase, rain, now);
                drawFragments(art, phase, progress, seconds, rain, now);
                drawDebris(art, phase, progress, seconds, rain, now);
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

    private static void drawFragments(MoonArt art, int phase, float progress, float seconds, float rain, float now) {
        int texture = MoonTextureManager.pristineId();
        if (texture < 0 || progress < MoonGeometry.PIECES - 0.01F) {
            return;
        }
        MoonGeometry geometry = art.geometry();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int s = 0; s < geometry.shardCount(); s++) {
            MoonGeometry.Shard shard = geometry.shard(s);
            if (!art.shardVisible(s)) {
                continue;
            }
            // A shard that is part of a bigger piece moves with that piece.
            MoonGeometry.Shard piece = piece(geometry, shard);
            float animation = MoonEffects.pieceAnimation(piece.index(), now);
            if (!MoonFragments.place(piece, progress, seconds, animation, PLACE)) {
                continue;
            }
            float light = PLACE[MoonFragments.BRIGHTNESS] * rain;
            shard(builder, art, phase, shard, piece, PLACE[MoonFragments.OFFSET_U], PLACE[MoonFragments.OFFSET_V],
                    PLACE[MoonFragments.ANGLE], PLACE[MoonFragments.SCALE], light);
        }
        draw(builder, texture, true);
    }

    private static void drawDebris(MoonArt art, int phase, float progress, float seconds, float rain, float now) {
        MoonGeometry geometry = art.geometry();
        long seed = geometry.seed();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        float shatter = MoonGeometry.shatter(progress);
        for (int s = 0; s < geometry.shardCount(); s++) {
            MoonGeometry.Shard shard = geometry.shard(s);
            if (!art.shardVisible(s)) {
                continue;
            }
            MoonGeometry.Shard piece = piece(geometry, shard);
            float animation = MoonEffects.pieceAnimation(piece.index(), now);
            if (!MoonFragments.place(piece, progress, seconds, animation, PLACE)) {
                continue;
            }
            // Around the visible part of the fragment, wherever its drift and spin have taken it.
            float rx = art.shardCenterX(s) - piece.centroidX();
            float ry = art.shardCenterY(s) - piece.centroidY();
            float cos = Mth.cos(PLACE[MoonFragments.ANGLE]) * PLACE[MoonFragments.SCALE];
            float sin = Mth.sin(PLACE[MoonFragments.ANGLE]) * PLACE[MoonFragments.SCALE];
            float cx = piece.centroidX() + PLACE[MoonFragments.OFFSET_U] + rx * cos - ry * sin;
            float cy = piece.centroidY() + PLACE[MoonFragments.OFFSET_V] + rx * sin + ry * cos;
            int count = 2 + (shatter > 0.0F ? 1 : 0);
            for (int d = 0; d < count; d++) {
                float direction = MoonFragments.hash(seed, s, d) > 0.5F ? 1.0F : -1.0F;
                float angle = MoonFragments.hash(seed, s, d + 10) * (float) (Math.PI * 2.0) + seconds * 0.012F * direction;
                float radius = (0.05F + MoonFragments.hash(seed, s, d + 20) * 0.12F) * animation;
                float size = DEBRIS_SIZE + MoonFragments.hash(seed, s, d + 30) * DEBRIS_SIZE_RANGE;
                int sprite = (int) (MoonFragments.hash(seed, s, d + 40) * 16.0F) & 15;
                float light = (0.45F + 0.3F * MoonFragments.hash(seed, s, d + 50)) * rain * animation;
                sprite(builder, art, phase, cx + Mth.cos(angle) * radius, cy + Mth.sin(angle) * radius, size, angle * 2.0F, sprite, light);
            }
        }
        for (int c = 0; c < MoonEffects.chipCount(); c++) {
            float age = MoonEffects.chipAge(c, now);
            float angle = MoonEffects.chipAngle(c);
            // From just inside the rim outwards.
            float distance = 0.44F + 1.1F * (1.0F - (1.0F - age) * (1.0F - age));
            float light = (1.0F - age) * (1.0F - age) * 0.9F * rain;
            sprite(builder, art, phase, 0.5F + Mth.cos(angle) * distance, 0.5F + Mth.sin(angle) * distance, CHIP_SIZE,
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
        // The glow texture is one phase cell, lit only on the moon's lit body: stretched over the
        // moon quad exactly like the cell itself.
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
     * jumps and faint red/cyan ghosts. While this runs the real moon is blanked out (unless it
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
        int cell = MoonArt.cellOf(phase);
        int column = cell % MoonArt.COLUMNS;
        int row = cell / MoonArt.COLUMNS;
        float u0 = column / (float) MoonArt.COLUMNS;
        float u1 = (column + 1) / (float) MoonArt.COLUMNS;
        float v0 = row / (float) MoonArt.ROWS;
        float v1 = (row + 1) / (float) MoonArt.ROWS;
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
    private static void drawDisappearance(MoonArt art, int phase, float progress, float seconds, float rain, float now) {
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
                if (!art.shardVisible(s)) {
                    continue;
                }
                MoonGeometry.Shard piece = piece(geometry, shard);
                float u = 0.0F;
                float v = 0.0F;
                float angle = 0.0F;
                float scale = 1.0F;
                if (MoonFragments.place(piece, before, seconds, 1.0F, PLACE)) {
                    u = PLACE[MoonFragments.OFFSET_U];
                    v = PLACE[MoonFragments.OFFSET_V];
                    angle = PLACE[MoonFragments.ANGLE];
                    scale = PLACE[MoonFragments.SCALE];
                }
                float push = burst * (1.6F + 1.4F * piece.depth());
                u += piece.driftX() * push;
                v += piece.driftY() * push;
                angle += piece.spin() * burst * 4.0F;
                float light = fade * (1.0F + 0.6F * Math.max(0.0F, 1.0F - t / 6.0F)) * rain;
                shard(builder, art, phase, shard, piece, u, v, angle, scale * (1.0F - 0.3F * burst), Math.min(1.0F, light));
            }
            draw(builder, texture, true);
        }
        float flashing = 0.35F + 0.65F * ClientLimits.flashing();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float glowAge = t / 24.0F;
        if (glowAge < 1.0F) {
            float size = GONE_GLOW_START + GONE_GLOW_GROWTH * (1.0F - (1.0F - glowAge) * (1.0F - glowAge));
            float alpha = (float) Math.pow(1.0F - glowAge, 1.5) * flashing * rain;
            billboard(builder, art, phase, 0.5F, 0.5F, size, alpha);
        }
        draw(builder, GLOW, false);
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float ringAge = t / 50.0F;
        if (ringAge < 1.0F) {
            float size = GONE_RING_START + GONE_RING_GROWTH * (1.0F - (1.0F - ringAge) * (1.0F - ringAge));
            billboard(builder, art, phase, 0.5F, 0.5F, size, 0.8F * (1.0F - ringAge) * flashing * rain);
        }
        draw(builder, RING, false);
    }

    // ------------------------------------------------------------------ geometry helpers

    /** The piece a shard breaks away with: itself, or the leader it is part of. */
    private static MoonGeometry.Shard piece(MoonGeometry geometry, MoonGeometry.Shard shard) {
        return shard.leader() >= 0 ? geometry.shard(shard.leader()) : shard;
    }

    /**
     * One shard as a triangle fan around its centre, textured with where it used to be in this
     * phase, moved like its piece: turned by {@code angle} and scaled about the piece's centre, then
     * shifted by the offset.
     */
    private static void shard(BufferBuilder builder, MoonArt art, int phase, MoonGeometry.Shard shard, MoonGeometry.Shard piece,
                              float offsetU, float offsetV, float angle, float scale, float light) {
        if (light <= 0.003F) {
            return;
        }
        float pivotX = piece.centroidX();
        float pivotY = piece.centroidY();
        float px = pivotX + offsetU;
        float py = pivotY + offsetV;
        float cos = Mth.cos(angle) * scale;
        float sin = Mth.sin(angle) * scale;
        float cx = shard.centroidX();
        float cy = shard.centroidY();
        float fanX = px + (cx - pivotX) * cos - (cy - pivotY) * sin;
        float fanY = py + (cx - pivotX) * sin + (cy - pivotY) * cos;
        float[] xs = shard.x();
        float[] ys = shard.y();
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            vertex(builder, art, phase, fanX, fanY, cx, cy, light);
            float ax = xs[i] - pivotX;
            float ay = ys[i] - pivotY;
            vertex(builder, art, phase, px + ax * cos - ay * sin, py + ax * sin + ay * cos, xs[i], ys[i], light);
            float bx = xs[j] - pivotX;
            float by = ys[j] - pivotY;
            vertex(builder, art, phase, px + bx * cos - by * sin, py + bx * sin + by * cos, xs[j], ys[j], light);
        }
    }

    /** A vertex at disc position (du, dv) on the sky, sampling the body texture at disc position (su, sv). */
    private static void vertex(BufferBuilder builder, MoonArt art, int phase, float du, float dv, float su, float sv, float light) {
        builder.addVertex(MATRIX, skyX(art, phase, du), DEPTH, skyZ(art, phase, dv))
                .setUv(art.sheetU(phase, su), art.sheetV(phase, sv))
                .setColor(light, light, light, 1.0F);
    }

    /** A small debris sprite (two triangles) at a disc position. */
    private static void sprite(BufferBuilder builder, MoonArt art, int phase, float du, float dv, float size, float angle, int sprite,
                               float light) {
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
            builder.addVertex(MATRIX, skyX(art, phase, x), DEPTH, skyZ(art, phase, y))
                    .setUv(cx < 0.0F ? su0 : su0 + 0.25F, cy < 0.0F ? sv0 : sv0 + 0.25F)
                    .setColor(light, light, light, 1.0F);
        }
    }

    /** A square sprite (the whole texture) centred on a disc position, {@code size} disc diameters wide (additive). */
    private static void billboard(BufferBuilder builder, MoonArt art, int phase, float du, float dv, float size, float alpha) {
        if (alpha <= 0.003F) {
            return;
        }
        float half = size * 0.5F;
        for (int k = 0; k < 4; k++) {
            float cx = CORNER_X[k];
            float cy = CORNER_Y[k];
            builder.addVertex(MATRIX, skyX(art, phase, du + cx * half), DEPTH, skyZ(art, phase, dv + cy * half))
                    .setUv(cx < 0.0F ? 0.0F : 1.0F, cy < 0.0F ? 0.0F : 1.0F)
                    .setColor(1.0F, 1.0F, 1.0F, alpha);
        }
    }

    /**
     * The moon quad's x for a disc-space x: vanilla draws the cell's left edge (u0) at +HALF and its
     * right edge (u1) at -HALF.
     */
    private static float skyX(MoonArt art, int phase, float discX) {
        return HALF - 2.0F * HALF * art.cellFractionX(phase, discX);
    }

    /** The moon quad's z for a disc-space y: the cell's top edge (v0) is at -HALF. */
    private static float skyZ(MoonArt art, int phase, float discY) {
        return -HALF + 2.0F * HALF * art.cellFractionY(phase, discY);
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
