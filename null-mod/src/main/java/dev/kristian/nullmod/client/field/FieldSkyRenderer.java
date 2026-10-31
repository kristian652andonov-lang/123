package dev.kristian.nullmod.client.field;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldDimension;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

/**
 * The sky of the field: a washed-out, hazy dome and a large, pale sun hanging low and motionless in
 * the north, the way everyone arrives facing.
 * <ul>
 * <li>Through the day the sun cracks: a jagged network spreads over it from one point of impact
 * ({@link FieldSunGeometry}), fine fractures branch off, light leaks out of the cracks and escapes
 * in thin rays at the rim, and its glow fails.</li>
 * <li>When it shatters the core flares (as far as the flashing setting allows), the shards drift
 * apart, turn and sink, darken to embers and are gone; the sky goes black with the light.</li>
 * <li>At dawn the shards drift back together and the glow returns.</li>
 * </ul>
 * All geometry lives in static vertex buffers built once per day's crack pattern; animation is
 * done with shader colours and one matrix per shard, never by rebuilding geometry per frame.
 */
final class FieldSkyRenderer {
    private static final ResourceLocation SUN = NullMod.id("textures/environment/field/sun.png");
    private static final ResourceLocation GLOW = NullMod.id("textures/environment/field/sun_glow.png");
    private static final ResourceLocation LINE = NullMod.id("textures/environment/field/crack_line.png");
    /** Inside the far plane even at the smallest render distance. */
    private static final float SUN_DISTANCE = 90.0F;
    /** Angular radius 9.5 degrees: a big sun. */
    private static final float SUN_RADIUS = (float) (SUN_DISTANCE * Math.tan(Math.toRadians(9.5)));
    private static final float ELEVATION = (float) Math.toRadians(FieldDimension.SUN_ELEVATION);
    private static final float DOME_RADIUS = 100.0F;
    private static final int GROUPS = 24;
    private static final int MAX_FAILURES = 5;
    private static final float[] PALE = {1.0F, 0.975F, 0.92F};
    private static final float[] EMBER = {0.42F, 0.15F, 0.09F};

    @Nullable
    private static VertexBuffer dome;
    @Nullable
    private static VertexBuffer quad;
    @Nullable
    private static VertexBuffer disc;
    private static VertexBuffer[] pieces = new VertexBuffer[0];
    private static FieldSunGeometry.Piece[] pieceData = new FieldSunGeometry.Piece[0];
    private static final VertexBuffer[] CRACKS = new VertexBuffer[GROUPS];
    private static final VertexBuffer[] LEAKS = new VertexBuffer[GROUPS];
    private static final VertexBuffer[] GLINTS = new VertexBuffer[GROUPS];
    private static final VertexBuffer[] RAYS = new VertexBuffer[GROUPS];
    private static boolean sunBuilt;
    private static long builtSeed;
    private static int failures;
    private static final Matrix4f SUN_MATRIX = new Matrix4f();
    private static final Matrix4f MATRIX = new Matrix4f();

    private FieldSkyRenderer() {
    }

    // ================================================================== drawing

    /** Draws the sky. Called from {@link FieldDimensionEffects#renderSky} on the render thread. */
    static void render(float partial, Matrix4f modelView, Matrix4f projection, Camera camera, Runnable setupFog) {
        setupFog.run();
        if (failures >= MAX_FAILURES || !skyVisible(camera)) {
            return;
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        try {
            if (dome == null) {
                buildStatic();
            }
            long sunSeed = FieldClientState.seed() + FieldClientState.cycle() * 0x9E3779B97F4A7C15L;
            if (!sunBuilt || builtSeed != sunSeed) {
                buildSun(sunSeed);
            }
            float day = FieldClientState.daylight(partial);
            float flare = FieldAtmosphere.flare(partial);
            float redness = FieldClientState.active() && FieldClientState.phase() == FieldCycle.Phase.SHATTER
                    ? 1.0F - FieldClientState.progress(partial) : 0.0F;
            float sky = Math.min(1.0F, day + 0.35F * flare);
            if (sky > 0.002F) {
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                draw(dome, modelView, projection, sky, sky * (1.0F - 0.12F * redness), sky * (1.0F - 0.22F * redness), 1.0F);
            }
            SUN_MATRIX.set(modelView).rotateX(ELEVATION).translate(0.0F, 0.0F, -SUN_DISTANCE).scale(SUN_RADIUS, SUN_RADIUS, SUN_RADIUS);
            drawSun(partial, projection, flare);
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("Field sky rendering failed ({} of {})", failures, MAX_FAILURES, e);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        }
    }

    private static void drawSun(float partial, Matrix4f projection, float flare) {
        // Until the server has said where the day stands, the sun is simply whole.
        FieldCycle.Phase phase = FieldClientState.active() ? FieldClientState.phase() : FieldCycle.Phase.DAY;
        if (phase == FieldCycle.Phase.NIGHT || phase == FieldCycle.Phase.HUNT) {
            // Nothing is left up there.
            return;
        }
        float progress = FieldClientState.progress(partial);
        float glow = FieldAtmosphere.glow(partial);
        float crack = FieldClientState.crack(partial);
        float apart = switch (phase) {
            case SHATTER -> progress;
            case DAWN -> 1.0F - FieldClientState.reform(partial);
            default -> 0.0F;
        };
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        // The halo, failing with the cracks.
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, GLOW);
        if (glow > 0.003F) {
            drawQuad(projection, 2.8F, 1.0F, 0.95F, 0.82F, glow * 0.8F);
        }
        // The core light, seen through the gaps as the shards come apart.
        if (phase == FieldCycle.Phase.SHATTER) {
            float core = 1.0F - FieldClientState.smooth(0.0F, 0.7F, progress);
            drawQuad(projection, 1.25F, 1.0F, 0.88F, 0.68F, core * 0.9F);
        }

        // The disc itself, whole or in pieces.
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderTexture(0, SUN);
        if (apart <= 0.0005F) {
            float bright = 0.86F + 0.14F * Math.min(1.0F, glow);
            draw(disc, SUN_MATRIX, projection, PALE[0] * bright, PALE[1] * bright, PALE[2] * bright, 1.0F);
        } else {
            float ember = FieldClientState.smooth(0.0F, 0.55F, apart);
            float dark = 1.0F - 0.6F * FieldClientState.smooth(0.5F, 1.0F, apart);
            float alpha = 1.0F - FieldClientState.smooth(0.45F, 1.0F, apart);
            float r = Mth.lerp(ember, PALE[0], EMBER[0]) * dark;
            float g = Mth.lerp(ember, PALE[1], EMBER[1]) * dark;
            float b = Mth.lerp(ember, PALE[2], EMBER[2]) * dark;
            if (alpha > 0.003F) {
                for (int i = 0; i < pieces.length; i++) {
                    pieceMatrix(pieceData[i], apart);
                    draw(pieces[i], MATRIX, projection, r, g, b, alpha);
                }
            }
        }

        // The cracks, and the light leaking out of them, while the sun is still whole.
        float fade = phase == FieldCycle.Phase.DAY ? 1.0F
                : phase == FieldCycle.Phase.SHATTER ? 1.0F - FieldClientState.smooth(0.0F, 0.08F, progress) : 0.0F;
        if (fade > 0.0F && crack > 0.0F) {
            RenderSystem.setShaderTexture(0, LINE);
            for (int g = 0; g < GROUPS; g++) {
                float a = groupAlpha(g, crack) * fade;
                if (a > 0.003F) {
                    draw(CRACKS[g], SUN_MATRIX, projection, 0.17F, 0.13F, 0.1F, a * 0.92F);
                }
            }
            float leak = FieldClientState.smooth(0.2F, 1.0F, crack) * fade;
            if (leak > 0.003F) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                double time = FieldClientState.time(partial);
                for (int g = 0; g < GROUPS; g++) {
                    float a = groupAlpha(g, crack) * leak;
                    if (a <= 0.003F) {
                        continue;
                    }
                    // A slow shimmer, different in every group (never a flash).
                    float shimmer = 0.72F + 0.28F * Mth.sin((float) (time * 0.07) + g * 1.7F);
                    draw(LEAKS[g], SUN_MATRIX, projection, 1.0F, 0.93F, 0.78F, a * 0.55F * shimmer);
                    draw(RAYS[g], SUN_MATRIX, projection, 1.0F, 0.95F, 0.82F, a * 0.42F * shimmer);
                }
                RenderSystem.setShaderTexture(0, GLOW);
                for (int g = 0; g < GROUPS; g++) {
                    float a = groupAlpha(g, crack) * leak;
                    if (a > 0.003F) {
                        float twinkle = 0.6F + 0.4F * Mth.sin((float) (time * 0.11) + g * 2.3F);
                        draw(GLINTS[g], SUN_MATRIX, projection, 1.0F, 0.94F, 0.8F, a * 0.8F * twinkle);
                    }
                }
            }
        }

        // The flare of the breaking sun, in front of everything.
        if (flare > 0.003F) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.setShaderTexture(0, GLOW);
            drawQuad(projection, 4.5F, 1.0F, 0.93F, 0.78F, flare);
        }
    }

    /** Group g holds the cracks that appear while the crack level passes from g/GROUPS to (g+1)/GROUPS. */
    private static float groupAlpha(int group, float crack) {
        return Mth.clamp((crack - group / (float) GROUPS) * GROUPS, 0.0F, 1.0F);
    }

    /** A shard flying apart (t from 0, whole, to 1, gone): it drifts outwards, sinks, turns and recedes. */
    private static void pieceMatrix(FieldSunGeometry.Piece piece, float t) {
        float drift = piece.speed() * (0.06F * t + 1.05F * t * t);
        float ox = piece.dirX() * drift;
        float oy = piece.dirY() * drift - piece.fall() * t * t * 0.7F;
        float angle = piece.spin() * t * 1.3F;
        float scale = 1.0F - 0.3F * t;
        MATRIX.set(SUN_MATRIX)
                .translate(piece.cx() + ox, piece.cy() + oy, 0.0F)
                .rotateZ(angle)
                .scale(scale, scale, 1.0F)
                .translate(-piece.cx(), -piece.cy(), 0.0F);
    }

    /** The unit glow quad around the sun's centre, {@code size} sun radii across each way. */
    private static void drawQuad(Matrix4f projection, float size, float r, float g, float b, float a) {
        MATRIX.set(SUN_MATRIX).scale(size, size, 1.0F);
        draw(quad, MATRIX, projection, r, g, b, a);
    }

    private static void draw(@Nullable VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, float r, float g, float b, float a) {
        if (buffer == null || a <= 0.002F) {
            return;
        }
        RenderSystem.setShaderColor(r, g, b, Math.min(1.0F, a));
        buffer.bind();
        buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
    }

    /** Vanilla skips the sky in lava, powder snow, blindness and darkness; so does the field. */
    private static boolean skyVisible(Camera camera) {
        FogType fog = camera.getFluidInCamera();
        if (fog == FogType.POWDER_SNOW || fog == FogType.LAVA) {
            return false;
        }
        return !(camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    // ================================================================== building

    /** Frees every buffer (logout, leaving the field); rebuilt on the next frame in the field. */
    static void close() {
        if (dome != null) {
            dome.close();
            dome = null;
        }
        if (quad != null) {
            quad.close();
            quad = null;
        }
        closeSun();
    }

    private static void closeSun() {
        if (disc != null) {
            disc.close();
            disc = null;
        }
        for (VertexBuffer buffer : pieces) {
            if (buffer != null) {
                buffer.close();
            }
        }
        pieces = new VertexBuffer[0];
        pieceData = new FieldSunGeometry.Piece[0];
        for (VertexBuffer[] group : new VertexBuffer[][]{CRACKS, LEAKS, GLINTS, RAYS}) {
            for (int i = 0; i < GROUPS; i++) {
                if (group[i] != null) {
                    group[i].close();
                    group[i] = null;
                }
            }
        }
        sunBuilt = false;
    }

    /** The sky dome (colours baked in, brightness from the shader colour) and the glow quad. */
    private static void buildStatic() {
        if (dome != null) {
            dome.close();
        }
        if (quad != null) {
            quad.close();
        }
        float sunY = Mth.sin(ELEVATION);
        float sunZ = -Mth.cos(ELEVATION);
        int lat = 16;
        int lon = 32;
        try (Mesh mesh = new Mesh(DefaultVertexFormat.POSITION_COLOR)) {
            for (int i = 0; i < lat; i++) {
                float e0 = -Mth.HALF_PI + Mth.PI * i / lat;
                float e1 = -Mth.HALF_PI + Mth.PI * (i + 1) / lat;
                for (int j = 0; j < lon; j++) {
                    float a0 = Mth.TWO_PI * j / lon;
                    float a1 = Mth.TWO_PI * (j + 1) / lon;
                    domeVertex(mesh, e0, a0, sunY, sunZ);
                    domeVertex(mesh, e0, a1, sunY, sunZ);
                    domeVertex(mesh, e1, a1, sunY, sunZ);
                    domeVertex(mesh, e1, a0, sunY, sunZ);
                }
            }
            dome = mesh.upload();
        }
        try (Mesh mesh = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR)) {
            mesh.textured(-1.0F, -1.0F, 0.0F, 1.0F, 1.0F);
            mesh.textured(1.0F, -1.0F, 1.0F, 1.0F, 1.0F);
            mesh.textured(1.0F, 1.0F, 1.0F, 0.0F, 1.0F);
            mesh.textured(-1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
            quad = mesh.upload();
        }
    }

    /** Pale haze at the horizon, a washed-out grey-blue above, a warm brightening around the sun. */
    private static void domeVertex(Mesh mesh, float elevation, float azimuth, float sunY, float sunZ) {
        float x = Mth.cos(elevation) * Mth.sin(azimuth);
        float y = Mth.sin(elevation);
        float z = -Mth.cos(elevation) * Mth.cos(azimuth);
        float up = (float) Math.pow(Math.max(0.0F, y), 0.55);
        float[] horizon = FieldAtmosphere.DAY_FOG;
        float r = Mth.lerp(up, horizon[0], 0.47F);
        float g = Mth.lerp(up, horizon[1], 0.505F);
        float b = Mth.lerp(up, horizon[2], 0.53F);
        float cos = Mth.clamp(y * sunY + z * sunZ, -1.0F, 1.0F);
        float angle = (float) Math.acos(cos);
        float near = (float) Math.exp(-angle / 0.32F) * 0.26F + (float) Math.exp(-angle / 1.1F) * 0.1F;
        r = Math.min(1.0F, r + near * 1.0F);
        g = Math.min(1.0F, g + near * 0.95F);
        b = Math.min(1.0F, b + near * 0.82F);
        mesh.colored(x * DOME_RADIUS, y * DOME_RADIUS, z * DOME_RADIUS, r, g, b);
    }

    /** Everything of one day's sun: its shards, its cracks and its light. */
    private static void buildSun(long seed) {
        closeSun();
        FieldSunGeometry geometry = FieldSunGeometry.build(seed);
        try (Mesh mesh = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR)) {
            for (FieldSunGeometry.Piece piece : geometry.pieces) {
                polygon(mesh, piece);
            }
            disc = mesh.upload();
        }
        pieces = new VertexBuffer[geometry.pieces.size()];
        pieceData = geometry.pieces.toArray(new FieldSunGeometry.Piece[0]);
        for (int i = 0; i < pieces.length; i++) {
            try (Mesh mesh = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR)) {
                polygon(mesh, pieceData[i]);
                pieces[i] = mesh.upload();
            }
        }
        Mesh[] cracks = new Mesh[GROUPS];
        Mesh[] leaks = new Mesh[GROUPS];
        Mesh[] glints = new Mesh[GROUPS];
        Mesh[] rays = new Mesh[GROUPS];
        try {
            for (int i = 0; i < GROUPS; i++) {
                cracks[i] = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR);
                leaks[i] = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR);
                glints[i] = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR);
                rays[i] = new Mesh(DefaultVertexFormat.POSITION_TEX_COLOR);
            }
            for (FieldSunGeometry.Segment segment : geometry.segments) {
                int g = group(segment.time());
                ribbon(cracks[g], segment.ax(), segment.ay(), segment.bx(), segment.by(), segment.width(), segment.width(), 1.0F, 1.0F);
                if (segment.main()) {
                    ribbon(leaks[g], segment.ax(), segment.ay(), segment.bx(), segment.by(), segment.width() * 0.45F,
                            segment.width() * 0.45F, 1.0F, 1.0F);
                }
            }
            for (FieldSunGeometry.Glint glint : geometry.glints) {
                Mesh mesh = glints[group(glint.time())];
                float s = glint.size();
                mesh.textured(glint.x() - s, glint.y() - s, 0.0F, 1.0F, 1.0F);
                mesh.textured(glint.x() + s, glint.y() - s, 1.0F, 1.0F, 1.0F);
                mesh.textured(glint.x() + s, glint.y() + s, 1.0F, 0.0F, 1.0F);
                mesh.textured(glint.x() - s, glint.y() + s, 0.0F, 0.0F, 1.0F);
            }
            for (FieldSunGeometry.Ray ray : geometry.rays) {
                float bx = ray.x() + ray.dirX() * ray.length();
                float by = ray.y() + ray.dirY() * ray.length();
                ribbon(rays[group(ray.time())], ray.x(), ray.y(), bx, by, 0.05F, 0.006F, 1.0F, 0.0F);
            }
            for (int i = 0; i < GROUPS; i++) {
                CRACKS[i] = cracks[i].upload();
                LEAKS[i] = leaks[i].upload();
                GLINTS[i] = glints[i].upload();
                RAYS[i] = rays[i].upload();
            }
        } finally {
            for (Mesh[] meshes : new Mesh[][]{cracks, leaks, glints, rays}) {
                for (Mesh mesh : meshes) {
                    if (mesh != null) {
                        mesh.close();
                    }
                }
            }
        }
        builtSeed = seed;
        sunBuilt = true;
    }

    private static int group(float time) {
        return Mth.clamp((int) (time * GROUPS), 0, GROUPS - 1);
    }

    /** A convex shard as a fan of triangles (each written as a quad with its last corner twice). */
    private static void polygon(Mesh mesh, FieldSunGeometry.Piece piece) {
        float[] xs = piece.xs();
        float[] ys = piece.ys();
        for (int k = 1; k + 1 < xs.length; k++) {
            sunVertex(mesh, xs[0], ys[0]);
            sunVertex(mesh, xs[k], ys[k]);
            sunVertex(mesh, xs[k + 1], ys[k + 1]);
            sunVertex(mesh, xs[k + 1], ys[k + 1]);
        }
    }

    private static void sunVertex(Mesh mesh, float x, float y) {
        mesh.textured(x, y, 0.5F + 0.5F * x, 0.5F - 0.5F * y, 1.0F);
    }

    /**
     * A ribbon from a to b in the sun's plane, {@code widthA}/{@code widthB} wide at its ends, with
     * the line texture across it; slightly lengthened so consecutive pieces of a crack overlap.
     */
    private static void ribbon(Mesh mesh, float ax, float ay, float bx, float by, float widthA, float widthB, float alphaA, float alphaB) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-6F) {
            return;
        }
        float ux = dx / length;
        float uy = dy / length;
        float extend = Math.min(widthA, widthB) * 0.5F;
        ax -= ux * extend;
        ay -= uy * extend;
        bx += ux * extend;
        by += uy * extend;
        float nxA = -uy * widthA * 0.5F;
        float nyA = ux * widthA * 0.5F;
        float nxB = -uy * widthB * 0.5F;
        float nyB = ux * widthB * 0.5F;
        mesh.textured(ax - nxA, ay - nyA, 0.0F, 0.0F, alphaA);
        mesh.textured(ax + nxA, ay + nyA, 0.0F, 1.0F, alphaA);
        mesh.textured(bx + nxB, by + nyB, 1.0F, 1.0F, alphaB);
        mesh.textured(bx - nxB, by - nyB, 1.0F, 0.0F, alphaB);
    }

    /** One vertex buffer under construction (quads). */
    private static final class Mesh implements AutoCloseable {
        private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 14);
        private final BufferBuilder builder;

        Mesh(VertexFormat format) {
            builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
        }

        /** A vertex in the sun's plane (z = 0) with texture coordinates and an alpha (white). */
        void textured(float x, float y, float u, float v, float alpha) {
            builder.addVertex(x, y, 0.0F).setUv(u, v).setColor(1.0F, 1.0F, 1.0F, Mth.clamp(alpha, 0.0F, 1.0F));
        }

        void colored(float x, float y, float z, float r, float g, float b) {
            builder.addVertex(x, y, z).setColor(r, g, b, 1.0F);
        }

        @Nullable
        VertexBuffer upload() {
            MeshData data = builder.build();
            if (data == null) {
                return null;
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(data);
            VertexBuffer.unbind();
            return buffer;
        }

        @Override
        public void close() {
            bytes.close();
        }
    }
}
