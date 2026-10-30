package dev.kristian.nullmod.client.dimension;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kristian.nullmod.NullMod;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

/**
 * The sky of the NULL dimension: black, with an endless grid plane far below and another far
 * above converging on the horizon, distant towers joining them, corridors of glowing frames
 * receding to the vanishing point along the axes of the world's own corridor lattice (each axis
 * with its own colours, fainter ones on the diagonals), recursive frames hanging in the void and
 * points of light.
 * <p>
 * Everything is built once into static vertex buffers (a few thousand quads, projected onto a
 * sphere of {@link #RADIUS} so it is never clipped at any render distance) and animated only with
 * the shader colour and a slowly turning matrix: the frames are split into
 * {@link #CHASE_GROUPS} phase groups, so a band of light runs outwards along every corridor of
 * frames while nothing is rebuilt per frame.
 */
final class NullSkyRenderer {
    private static final ResourceLocation LINE = NullMod.id("textures/environment/null_line.png");
    private static final ResourceLocation FLARE = NullMod.id("textures/environment/null_flare.png");
    private static final ResourceLocation FRAME = NullMod.id("textures/environment/null_frame.png");
    /** Sky geometry lies this far from the camera, inside the far plane even at render distance 2. */
    private static final float RADIUS = 80.0F;
    private static final int CHASE_GROUPS = 8;
    private static final int TWINKLE_GROUPS = 3;
    private static final int MAX_FAILURES = 5;
    private static final long LAYOUT_SEED = 0x4E554C4C534B5931L;

    private static final float FLOOR_Y = -260.0F;
    private static final float CEILING_Y = 340.0F;

    /** Frame colours along +X, +Z, -X and -Z: each axis of the lattice has its own. */
    private static final float[][][] AXIS_COLORS = {
            {{0.25F, 0.85F, 1.0F}, {0.2F, 0.45F, 1.0F}},
            {{0.3F, 1.0F, 0.55F}, {0.2F, 0.9F, 0.95F}},
            {{1.0F, 0.25F, 0.75F}, {1.0F, 0.22F, 0.3F}},
            {{1.0F, 0.85F, 0.25F}, {1.0F, 0.35F, 0.8F}}
    };
    private static final float[][] DIAGONAL_COLORS = {{0.75F, 0.85F, 1.0F}, {0.45F, 0.55F, 1.0F}};
    private static final float[][] LIGHTS = {
            {1.0F, 1.0F, 1.0F}, {0.3F, 0.9F, 1.0F}, {1.0F, 0.3F, 0.85F}, {0.35F, 0.5F, 1.0F},
            {0.4F, 1.0F, 0.6F}, {1.0F, 0.9F, 0.35F}, {1.0F, 0.3F, 0.3F}};

    @FunctionalInterface
    private interface Fade {
        float alpha(float x, float y, float z);
    }

    @Nullable
    private static VertexBuffer grids;
    @Nullable
    private static VertexBuffer towers;
    private static final VertexBuffer[] FRAMES = new VertexBuffer[CHASE_GROUPS];
    @Nullable
    private static VertexBuffer portals;
    private static final VertexBuffer[] LIGHT_BUFFERS = new VertexBuffer[TWINKLE_GROUPS];
    private static boolean built;
    private static int failures;
    private static final Matrix4f MATRIX = new Matrix4f();
    private static final float[] TINT = new float[3];

    private NullSkyRenderer() {
    }

    // ================================================================== drawing

    /** Draws the sky. Called from {@link NullDimensionEffects#renderSky} on the render thread. */
    static void render(float partial, Matrix4f modelView, Matrix4f projection, Camera camera, Runnable setupFog) {
        setupFog.run();
        if (failures >= MAX_FAILURES || !skyVisible(camera)) {
            return;
        }
        float brightness = DimensionAtmosphere.skyBrightness(partial);
        if (brightness <= 0.003F) {
            return;
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        try {
            if (!built) {
                build();
            }
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            double time = DimensionAtmosphere.time(partial);
            float wave = DimensionAtmosphere.pulseColor(partial, TINT);
            float boost = 1.0F + 1.2F * wave;
            float tr = Mth.lerp(wave * 0.8F, 1.0F, TINT[0] * 1.6F);
            float tg = Mth.lerp(wave * 0.8F, 1.0F, TINT[1] * 1.6F);
            float tb = Mth.lerp(wave * 0.8F, 1.0F, TINT[2] * 1.6F);

            RenderSystem.setShaderTexture(0, LINE);
            float breath = 0.85F + 0.15F * Mth.sin((float) (time * 0.03));
            draw(grids, modelView, projection, tr, tg, tb, brightness * breath * boost);
            draw(towers, modelView, projection, tr, tg, tb, brightness * (0.8F + 0.2F * Mth.sin((float) (time * 0.017) + 1.3F)) * boost);
            for (int group = 0; group < CHASE_GROUPS; group++) {
                float chase = chase(time, group);
                float drift = (float) (time * 0.011) + group * 0.8F;
                draw(FRAMES[group], modelView, projection,
                        tr * (0.82F + 0.18F * Mth.sin(drift)),
                        tg * (0.82F + 0.18F * Mth.sin(drift + 2.1F)),
                        tb * (0.82F + 0.18F * Mth.sin(drift + 4.2F)),
                        brightness * (0.3F + 0.7F * chase) * boost);
            }

            RenderSystem.setShaderTexture(0, FRAME);
            MATRIX.set(modelView).rotateY((float) (time * 0.0004));
            draw(portals, MATRIX, projection, tr, tg, tb, brightness * (0.55F + 0.25F * Mth.sin((float) (time * 0.02))) * boost);

            RenderSystem.setShaderTexture(0, FLARE);
            MATRIX.set(modelView).rotateY((float) (-time * 0.00025));
            for (int group = 0; group < TWINKLE_GROUPS; group++) {
                float twinkle = 0.55F + 0.45F * Mth.sin((float) (time * 0.09) + group * 2.1F);
                draw(LIGHT_BUFFERS[group], MATRIX, projection, tr, tg, tb, brightness * twinkle * boost);
            }
        } catch (RuntimeException e) {
            failures++;
            NullMod.LOGGER.error("NULL sky rendering failed ({} of {})", failures, MAX_FAILURES, e);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        }
    }

    /** A band of light running outwards along the frames: group g is lit when the band passes it. */
    private static float chase(double time, int group) {
        double phase = time * 0.025 - group / (double) CHASE_GROUPS;
        float c = 0.5F + 0.5F * (float) Math.cos(phase * Math.PI * 2.0);
        float c2 = c * c;
        float c4 = c2 * c2;
        return c4 * c4;
    }

    private static void draw(@Nullable VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, float r, float g, float b, float a) {
        if (buffer == null || a <= 0.002F) {
            return;
        }
        RenderSystem.setShaderColor(r, g, b, Math.min(1.0F, a));
        buffer.bind();
        buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
    }

    /** Vanilla skips the sky in lava, powder snow, blindness and darkness; so does NULL. */
    private static boolean skyVisible(Camera camera) {
        FogType fog = camera.getFluidInCamera();
        if (fog == FogType.POWDER_SNOW || fog == FogType.LAVA) {
            return false;
        }
        return !(camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    // ================================================================== building

    /** Frees the vertex buffers (logout, leaving NULL); they are rebuilt on the next frame in NULL. */
    static void close() {
        if (grids != null) {
            grids.close();
            grids = null;
        }
        if (towers != null) {
            towers.close();
            towers = null;
        }
        if (portals != null) {
            portals.close();
            portals = null;
        }
        for (int i = 0; i < CHASE_GROUPS; i++) {
            if (FRAMES[i] != null) {
                FRAMES[i].close();
                FRAMES[i] = null;
            }
        }
        for (int i = 0; i < TWINKLE_GROUPS; i++) {
            if (LIGHT_BUFFERS[i] != null) {
                LIGHT_BUFFERS[i].close();
                LIGHT_BUFFERS[i] = null;
            }
        }
        built = false;
    }

    private static void build() {
        close();
        RandomSource random = RandomSource.create(LAYOUT_SEED);
        try (Mesh mesh = new Mesh()) {
            buildGrids(mesh);
            grids = mesh.upload();
        }
        try (Mesh mesh = new Mesh()) {
            buildTowers(mesh, random);
            towers = mesh.upload();
        }
        Mesh[] groups = new Mesh[CHASE_GROUPS];
        try {
            for (int i = 0; i < CHASE_GROUPS; i++) {
                groups[i] = new Mesh();
            }
            buildFrames(groups);
            for (int i = 0; i < CHASE_GROUPS; i++) {
                FRAMES[i] = groups[i].upload();
            }
        } finally {
            for (Mesh mesh : groups) {
                if (mesh != null) {
                    mesh.close();
                }
            }
        }
        try (Mesh mesh = new Mesh()) {
            buildPortals(mesh, random);
            portals = mesh.upload();
        }
        Mesh[] lights = new Mesh[TWINKLE_GROUPS];
        try {
            for (int i = 0; i < TWINKLE_GROUPS; i++) {
                lights[i] = new Mesh();
            }
            buildLights(lights, random);
            for (int i = 0; i < TWINKLE_GROUPS; i++) {
                LIGHT_BUFFERS[i] = lights[i].upload();
            }
        } finally {
            for (Mesh mesh : lights) {
                if (mesh != null) {
                    mesh.close();
                }
            }
        }
        built = true;
    }

    /** An endless grid far below (cold blue) and a sparser one far above (violet). */
    private static void buildGrids(Mesh mesh) {
        float extent = 5200.0F;
        Fade floor = (x, y, z) -> 0.8F * (1.0F - smoothstep(700.0F, 3800.0F, (float) Math.sqrt(x * x + z * z)));
        float spacing = 110.0F;
        for (int k = -24; k <= 24; k++) {
            float c = k * spacing;
            strip(mesh, -extent, FLOOR_Y, c, extent, FLOOR_Y, c, 48, 2.8F, 0.15F, 0.6F, 1.0F, floor);
            strip(mesh, c, FLOOR_Y, -extent, c, FLOOR_Y, extent, 48, 2.8F, 0.15F, 0.6F, 1.0F, floor);
        }
        Fade ceiling = (x, y, z) -> 0.45F * (1.0F - smoothstep(600.0F, 3800.0F, (float) Math.sqrt(x * x + z * z)));
        float wide = 160.0F;
        for (int k = -16; k <= 16; k++) {
            float c = k * wide;
            strip(mesh, -extent, CEILING_Y, c, extent, CEILING_Y, c, 48, 3.2F, 0.7F, 0.25F, 1.0F, ceiling);
            strip(mesh, c, CEILING_Y, -extent, c, CEILING_Y, extent, 48, 3.2F, 0.7F, 0.25F, 1.0F, ceiling);
        }
    }

    /** Distant towers of light joining the two grids: structures that go on forever. */
    private static void buildTowers(Mesh mesh, RandomSource random) {
        for (int i = 0; i < 56; i++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float distance = 1200.0F + random.nextFloat() * 3200.0F;
            float x = Mth.cos(angle) * distance;
            float z = Mth.sin(angle) * distance;
            float[] c = LIGHTS[random.nextInt(LIGHTS.length)];
            float base = 0.85F * (1.0F - smoothstep(1200.0F, 4600.0F, distance));
            float width = 6.0F + random.nextFloat() * 8.0F;
            Fade fade = (px, py, pz) -> base * (0.45F + 0.55F * smoothstep(CEILING_Y, FLOOR_Y, py));
            strip(mesh, x, FLOOR_Y, z, x, CEILING_Y, z, 12, width, c[0], c[1], c[2], fade);
            int rungs = random.nextInt(4);
            float sideX = -Mth.sin(angle) * 40.0F;
            float sideZ = Mth.cos(angle) * 40.0F;
            for (int r = 0; r < rungs; r++) {
                float y = FLOOR_Y + 60.0F + random.nextFloat() * (CEILING_Y - FLOOR_Y - 120.0F);
                float a = base * 0.8F;
                line(mesh, x - sideX, y, z - sideZ, x + sideX, y, z + sideZ, width * 0.6F, c[0], c[1], c[2], a, a);
            }
        }
    }

    /**
     * Corridors of rectangular frames receding to the horizon: large ones along the four axes of
     * the world's corridors, smaller and fainter ones on the diagonals. Frame i goes into phase
     * group i % CHASE_GROUPS.
     */
    private static void buildFrames(Mesh[] groups) {
        for (int dir = 0; dir < 8; dir++) {
            boolean axis = (dir & 1) == 0;
            float angle = dir * (Mth.TWO_PI / 8.0F);
            float fx = Mth.cos(angle);
            float fz = Mth.sin(angle);
            float sx = -fz;
            float sz = fx;
            int count = axis ? 24 : 14;
            float start = axis ? 260.0F : 900.0F;
            float step = axis ? 130.0F : 220.0F;
            float halfWidth = axis ? 95.0F : 70.0F;
            float halfHeight = axis ? 58.0F : 44.0F;
            float width = axis ? 5.0F : 4.0F;
            float[][] colors = axis ? AXIS_COLORS[dir / 2] : DIAGONAL_COLORS;
            for (int i = 0; i < count; i++) {
                float d = start + i * step;
                float cx = fx * d;
                float cz = fz * d;
                float fade = (axis ? 0.95F : 0.55F) * (float) Math.pow(1.0F - i / (float) count, 0.8);
                float[] c = colors[i & 1];
                Mesh m = groups[i % CHASE_GROUPS];
                float x0 = cx - sx * halfWidth;
                float z0 = cz - sz * halfWidth;
                float x1 = cx + sx * halfWidth;
                float z1 = cz + sz * halfWidth;
                line(m, x0, -halfHeight, z0, x1, -halfHeight, z1, width, c[0], c[1], c[2], fade, fade);
                line(m, x0, halfHeight, z0, x1, halfHeight, z1, width, c[0], c[1], c[2], fade, fade);
                line(m, x0, -halfHeight, z0, x0, halfHeight, z0, width, c[0], c[1], c[2], fade, fade);
                line(m, x1, -halfHeight, z1, x1, halfHeight, z1, width, c[0], c[1], c[2], fade, fade);
            }
        }
    }

    /** Recursive frames hanging in the void, most above the horizon, a few deep below. */
    private static void buildPortals(Mesh mesh, RandomSource random) {
        for (int i = 0; i < 9; i++) {
            float azimuth = random.nextFloat() * Mth.TWO_PI;
            float elevation = (i < 6 ? 1.0F : -1.0F) * (12.0F + random.nextFloat() * 40.0F) * Mth.DEG_TO_RAD;
            float halfSize = RADIUS * (float) Math.tan((7.0F + random.nextFloat() * 9.0F) * Mth.DEG_TO_RAD);
            float aspect = 0.7F + random.nextFloat() * 0.5F;
            float[] c = LIGHTS[random.nextInt(LIGHTS.length)];
            float alpha = 0.5F + random.nextFloat() * 0.3F;
            float dx = Mth.cos(elevation) * Mth.cos(azimuth);
            float dy = Mth.sin(elevation);
            float dz = Mth.cos(elevation) * Mth.sin(azimuth);
            // right = normalize(cross(up, dir)); up' = cross(dir, right)
            float rx = dz;
            float rz = -dx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            rx /= rl;
            rz /= rl;
            float ux = dy * rz;
            float uy = dz * rx - dx * rz;
            float uz = -dy * rx;
            float cx = dx * RADIUS;
            float cy = dy * RADIUS;
            float cz = dz * RADIUS;
            float hw = halfSize;
            float hh = halfSize * aspect;
            mesh.vertex(cx - rx * hw - ux * hh, cy - uy * hh, cz - rz * hw - uz * hh, 0.0F, 1.0F, c[0], c[1], c[2], alpha);
            mesh.vertex(cx + rx * hw - ux * hh, cy - uy * hh, cz + rz * hw - uz * hh, 1.0F, 1.0F, c[0], c[1], c[2], alpha);
            mesh.vertex(cx + rx * hw + ux * hh, cy + uy * hh, cz + rz * hw + uz * hh, 1.0F, 0.0F, c[0], c[1], c[2], alpha);
            mesh.vertex(cx - rx * hw + ux * hh, cy + uy * hh, cz - rz * hw + uz * hh, 0.0F, 0.0F, c[0], c[1], c[2], alpha);
        }
    }

    /** Points of light scattered over the sky, away from the horizon where the frames are. */
    private static void buildLights(Mesh[] groups, RandomSource random) {
        float minSin = Mth.sin(5.0F * Mth.DEG_TO_RAD);
        int placed = 0;
        int attempts = 0;
        while (placed < 520 && attempts++ < 5000) {
            float y = random.nextFloat() * 2.0F - 1.0F;
            if (Math.abs(y) < minSin) {
                continue;
            }
            float azimuth = random.nextFloat() * Mth.TWO_PI;
            float horizontal = (float) Math.sqrt(1.0F - y * y);
            float dx = horizontal * Mth.cos(azimuth);
            float dz = horizontal * Mth.sin(azimuth);
            float half = RADIUS * (float) Math.tan((0.25F + random.nextFloat() * 0.65F) * Mth.DEG_TO_RAD);
            float[] c = LIGHTS[random.nextInt(LIGHTS.length)];
            float alpha = 0.45F + random.nextFloat() * 0.55F;
            float rx = dz;
            float rz = -dx;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl < 1.0E-4F) {
                continue;
            }
            rx /= rl;
            rz /= rl;
            float ux = y * rz;
            float uy = dz * rx - dx * rz;
            float uz = -y * rx;
            float cx = dx * RADIUS;
            float cy = y * RADIUS;
            float cz = dz * RADIUS;
            Mesh m = groups[placed % TWINKLE_GROUPS];
            m.vertex(cx - rx * half - ux * half, cy - uy * half, cz - rz * half - uz * half, 0.0F, 1.0F, c[0], c[1], c[2], alpha);
            m.vertex(cx + rx * half - ux * half, cy - uy * half, cz + rz * half - uz * half, 1.0F, 1.0F, c[0], c[1], c[2], alpha);
            m.vertex(cx + rx * half + ux * half, cy + uy * half, cz + rz * half + uz * half, 1.0F, 0.0F, c[0], c[1], c[2], alpha);
            m.vertex(cx - rx * half + ux * half, cy + uy * half, cz - rz * half + uz * half, 0.0F, 0.0F, c[0], c[1], c[2], alpha);
            placed++;
        }
    }

    /** A straight glowing line split into segments, with alpha from {@code fade} at every joint. */
    private static void strip(Mesh mesh, float ax, float ay, float az, float bx, float by, float bz, int segments, float width,
                              float r, float g, float b, Fade fade) {
        float px = ax;
        float py = ay;
        float pz = az;
        float pa = fade.alpha(ax, ay, az);
        for (int i = 1; i <= segments; i++) {
            float t = i / (float) segments;
            float qx = ax + (bx - ax) * t;
            float qy = ay + (by - ay) * t;
            float qz = az + (bz - az) * t;
            float qa = fade.alpha(qx, qy, qz);
            if (pa > 0.004F || qa > 0.004F) {
                line(mesh, px, py, pz, qx, qy, qz, width, r, g, b, pa, qa);
            }
            px = qx;
            py = qy;
            pz = qz;
            pa = qa;
        }
    }

    /**
     * A glowing ribbon from a to b in sky space, facing the camera at the origin, then pushed
     * onto the sky sphere. Its width stays constant in sky space, so it thins with distance.
     */
    private static void line(Mesh mesh, float ax, float ay, float az, float bx, float by, float bz, float width,
                             float r, float g, float b, float alphaA, float alphaB) {
        float mx = (ax + bx) * 0.5F;
        float my = (ay + by) * 0.5F;
        float mz = (az + bz) * 0.5F;
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float length = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1.0E-6F) {
            return;
        }
        float k = width * 0.5F / length;
        sx *= k;
        sy *= k;
        sz *= k;
        mesh.projected(ax - sx, ay - sy, az - sz, 0.0F, 0.0F, r, g, b, alphaA);
        mesh.projected(ax + sx, ay + sy, az + sz, 0.0F, 1.0F, r, g, b, alphaA);
        mesh.projected(bx + sx, by + sy, bz + sz, 1.0F, 1.0F, r, g, b, alphaB);
        mesh.projected(bx - sx, by - sy, bz - sz, 1.0F, 0.0F, r, g, b, alphaB);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** One vertex buffer under construction (position, texture, colour quads). */
    private static final class Mesh implements AutoCloseable {
        private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 16);
        private final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        void vertex(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
            builder.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, Mth.clamp(a, 0.0F, 1.0F));
        }

        /** A vertex pushed along its ray from the camera onto the sky sphere: same place on screen. */
        void projected(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            float k = length < 1.0E-6F ? 0.0F : RADIUS / length;
            vertex(x * k, y * k, z * k, u, v, r, g, b, a);
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
