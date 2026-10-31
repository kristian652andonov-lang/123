package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

/**
 * Parasite tentacles, built at draw time: a spine that leaves the body along a direction and
 * writhes (two travelling waves in two planes, never in sync between tentacles), wrapped in a
 * tube that is thick and swollen at the root, carries slow peristaltic bulges and thins to a
 * point, a bone hook or a gaping mouth of petals around a red throat.
 * <p>
 * Coordinates are whatever space the caller works in (camera-relative for entities); lengths
 * and radii are given in blocks and multiplied by {@code unit}. Cross sections come from cached
 * ring tables and every point goes into reused arrays: nothing is allocated per frame. Render
 * thread only. {@code tools/gen_variants.py} mirrors this math for its offline preview.
 */
final class Tentacles {
    static final int TIP_POINT = 0;
    static final int TIP_HOOK = 1;
    static final int TIP_MOUTH = 2;

    static final int MAX_SEGMENTS = 14;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    /** Where along a hooked tentacle the bone begins. */
    private static final float HOOK_START = 0.68F;

    // Texture atlas regions (textures/entity/variant/tentacle.png, 64 x 64).
    private static final float SKIN_U0 = 0.0F;
    private static final float SKIN_UW = 0.5F;
    private static final float SKIN_V0 = 0.0F;
    private static final float SKIN2_V0 = 0.5F;
    private static final float SKIN_VH = 0.5F;
    private static final float BONE_U0 = 0.5F;
    private static final float BONE_UW = 0.25F;
    private static final float BONE_V0 = 0.0F;
    private static final float BONE_VH = 0.5F;
    private static final float MAW_U0 = 0.75F;
    private static final float MAW_UW = 0.25F;
    private static final float MAW_V0 = 0.0F;
    private static final float MAW_VH = 0.5F;
    private static final float THROAT_U = 0.75F;
    private static final float THROAT_V = 0.75F;
    private static final float THROAT_R = 0.24F;

    /** Ring templates: cos/sin of the corner angles for 4- and 6-sided tubes. */
    private static final float[][] RING_COS = new float[7][];
    private static final float[][] RING_SIN = new float[7][];

    static {
        for (int sides : new int[]{4, 5, 6}) {
            RING_COS[sides] = new float[sides + 1];
            RING_SIN[sides] = new float[sides + 1];
            for (int j = 0; j <= sides; j++) {
                double angle = Math.PI * 2.0 * j / sides;
                RING_COS[sides][j] = (float) Math.cos(angle);
                RING_SIN[sides][j] = (float) Math.sin(angle);
            }
        }
    }

    private static final float[] PX = new float[MAX_SEGMENTS + 1];
    private static final float[] PY = new float[MAX_SEGMENTS + 1];
    private static final float[] PZ = new float[MAX_SEGMENTS + 1];
    private static final float[] SX = new float[MAX_SEGMENTS];
    private static final float[] SY = new float[MAX_SEGMENTS];
    private static final float[] SZ = new float[MAX_SEGMENTS];
    private static final float[] UX = new float[MAX_SEGMENTS + 1];
    private static final float[] UY = new float[MAX_SEGMENTS + 1];
    private static final float[] UZ = new float[MAX_SEGMENTS + 1];
    private static final float[] VX = new float[MAX_SEGMENTS + 1];
    private static final float[] VY = new float[MAX_SEGMENTS + 1];
    private static final float[] VZ = new float[MAX_SEGMENTS + 1];
    private static final float[] TX = new float[MAX_SEGMENTS + 1];
    private static final float[] TY = new float[MAX_SEGMENTS + 1];
    private static final float[] TZ = new float[MAX_SEGMENTS + 1];
    private static final float[] RR = new float[MAX_SEGMENTS + 1];
    private static final int PETAL_SEGMENTS = 3;
    private static final float[] QX = new float[PETAL_SEGMENTS + 1];
    private static final float[] QY = new float[PETAL_SEGMENTS + 1];
    private static final float[] QZ = new float[PETAL_SEGMENTS + 1];

    private Tentacles() {
    }

    /** What one tentacle is like; chosen once from the mob's seed and kept. */
    static final class Spec {
        /** Full grown length and root radius, in blocks. */
        float length;
        float radius;
        int tip;
        /** Uses the second (sucker-covered) skin. */
        boolean suckers;
        /** Writhing: angular amplitude (radians at the tip), sag, wave speeds and numbers, phases. */
        float amp;
        float droop;
        float w1;
        float w2;
        float w3;
        float w4;
        float k1;
        float k2;
        float p1;
        float p2;
        float p3;
        float p4;
        float p5;
        float twist;
        /** Speed of the bulges travelling along it, and of the mouth's biting. */
        float pulseSpeed;
        float biteSpeed;

        /** Fills this spec from a seed; {@code size} is the host's size in blocks. */
        void roll(long seed, float size, int index, int count) {
            long h = mix(seed + 0x2545F4914F6CDD1DL * (index + 1));
            float a = unit(h);
            float b = unit(h >>> 11);
            float c = unit(h >>> 22);
            float d = unit(h >>> 33);
            long g = mix(h);
            float e = unit(g);
            float f = unit(g >>> 11);
            float q = unit(g >>> 22);
            float r = unit(g >>> 33);
            length = size * (0.5F + 0.65F * a);
            radius = Mth.clamp(size * (0.032F + 0.022F * b), 0.018F, 0.16F);
            // Every tentacle has its own tip; at least one hook and one mouth on bigger hosts.
            int roll = (int) (c * 10.0F);
            tip = roll < 4 ? TIP_POINT : roll < 7 ? TIP_HOOK : TIP_MOUTH;
            if (count >= 4 && index == 0) {
                tip = TIP_MOUTH;
            } else if (count >= 4 && index == 1) {
                tip = TIP_HOOK;
            }
            if (tip == TIP_MOUTH) {
                radius *= 1.25F;
            }
            suckers = d < 0.35F;
            amp = 0.55F + 0.55F * e;
            droop = 0.1F + 0.35F * f;
            w1 = 0.06F + 0.07F * q;
            w2 = 0.11F + 0.09F * r;
            w3 = 0.05F + 0.08F * unit(h >>> 44);
            w4 = 0.13F + 0.08F * unit(g >>> 44);
            k1 = 2.2F + 2.6F * a * b + 0.6F * c;
            k2 = 4.5F + 3.0F * d;
            p1 = TWO_PI * unit(mix(g + 1));
            p2 = TWO_PI * unit(mix(g + 2));
            p3 = TWO_PI * unit(mix(g + 3));
            p4 = TWO_PI * unit(mix(g + 4));
            p5 = TWO_PI * unit(mix(g + 5));
            twist = TWO_PI * unit(mix(g + 6));
            pulseSpeed = 0.035F + 0.03F * unit(mix(g + 7));
            biteSpeed = 0.08F + 0.08F * unit(mix(g + 8));
        }
    }

    /**
     * Draws one tentacle from (bx, by, bz) leaving along the unit direction (dx, dy, dz).
     *
     * @param unit      camera units per block
     * @param extent    0..1, how much of its length is out (growth, retraction)
     * @param thickness 0..1, how thick it is relative to its full radius
     * @param time      ticks (with partial tick)
     * @param agitation 1 normally; more when the host is hurt (faster, wider thrashing)
     * @param segments  spine segments (level of detail), at most {@link #MAX_SEGMENTS}
     * @param sides     4, 5 or 6
     * @param tips      false draws every tip as a plain point (far away)
     */
    static void draw(VertexConsumer out, float bx, float by, float bz, float dx, float dy, float dz, Spec spec,
                     float unit, float extent, float thickness, float time, float agitation, int segments, int sides,
                     boolean tips, int light, int overlay) {
        float length = spec.length * unit * extent;
        float radius = spec.radius * unit * thickness;
        if (length < 0.01F * unit || radius <= 0.0F) {
            return;
        }
        int n = Mth.clamp(segments, 2, MAX_SEGMENTS);
        int s = sides <= 4 ? 4 : sides >= 6 ? 6 : 5;
        int tip = tips ? spec.tip : TIP_POINT;
        float dl = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0E-5F) {
            return;
        }
        dx /= dl;
        dy /= dl;
        dz /= dl;
        // Two directions across the base direction.
        float ux;
        float uy;
        float uz;
        if (Math.abs(dy) < 0.9F) {
            ux = -dz;
            uy = 0.0F;
            uz = dx;
        } else {
            ux = 0.0F;
            uy = dz;
            uz = -dy;
        }
        float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        float vx = dy * uz - dz * uy;
        float vy = dz * ux - dx * uz;
        float vz = dx * uy - dy * ux;

        // The spine: every segment points along the base direction turned by the waves.
        float step = length / n;
        float amp = spec.amp * Math.min(2.2F, agitation);
        float speed = agitation;
        PX[0] = bx;
        PY[0] = by;
        PZ[0] = bz;
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5F) / n;
            float env = t * (1.25F - 0.25F * t);
            float au = amp * env * (0.65F * Mth.sin(spec.w1 * speed * time - spec.k1 * t + spec.p1)
                    + 0.35F * Mth.sin(spec.w2 * speed * time - spec.k2 * t + spec.p2));
            float av = amp * env * (0.65F * Mth.sin(spec.w3 * speed * time - spec.k1 * 0.8F * t + spec.p3)
                    + 0.35F * Mth.sin(spec.w4 * speed * time - spec.k2 * 1.3F * t + spec.p4));
            if (tip == TIP_HOOK && t > HOOK_START) {
                float h = (t - HOOK_START) / (1.0F - HOOK_START);
                au += 2.7F * h * Mth.sqrt(h);
            }
            float cu = Mth.cos(au);
            float su = Mth.sin(au);
            float cv = Mth.cos(av);
            float sv = Mth.sin(av);
            float ex = dx * cu * cv + ux * su * cv + vx * sv;
            float ey = dy * cu * cv + uy * su * cv + vy * sv - spec.droop * t;
            float ez = dz * cu * cv + uz * su * cv + vz * sv;
            float el = Mth.sqrt(ex * ex + ey * ey + ez * ez);
            if (el < 1.0E-5F) {
                ex = dx;
                ey = dy;
                ez = dz;
                el = 1.0F;
            }
            SX[i] = ex / el;
            SY[i] = ey / el;
            SZ[i] = ez / el;
            PX[i + 1] = PX[i] + SX[i] * step;
            PY[i + 1] = PY[i] + SY[i] * step;
            PZ[i + 1] = PZ[i] + SZ[i] * step;
        }

        // Frames that do not spin along the tube (each one the last one projected).
        float px = ux;
        float py = uy;
        float pz = uz;
        for (int i = 0; i <= n; i++) {
            float tx;
            float ty;
            float tz;
            if (i == 0) {
                tx = SX[0];
                ty = SY[0];
                tz = SZ[0];
            } else if (i == n) {
                tx = SX[n - 1];
                ty = SY[n - 1];
                tz = SZ[n - 1];
            } else {
                tx = SX[i - 1] + SX[i];
                ty = SY[i - 1] + SY[i];
                tz = SZ[i - 1] + SZ[i];
                float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl < 1.0E-5F) {
                    tx = SX[i];
                    ty = SY[i];
                    tz = SZ[i];
                } else {
                    tx /= tl;
                    ty /= tl;
                    tz /= tl;
                }
            }
            float dot = px * tx + py * ty + pz * tz;
            float qx = px - dot * tx;
            float qy = py - dot * ty;
            float qz = pz - dot * tz;
            float ql = Mth.sqrt(qx * qx + qy * qy + qz * qz);
            if (ql > 1.0E-4F) {
                px = qx / ql;
                py = qy / ql;
                pz = qz / ql;
            }
            TX[i] = tx;
            TY[i] = ty;
            TZ[i] = tz;
            UX[i] = px;
            UY[i] = py;
            UZ[i] = pz;
            VX[i] = ty * pz - tz * py;
            VY[i] = tz * px - tx * pz;
            VZ[i] = tx * py - ty * px;

            float t = i / (float) n;
            float r = radius * (tip == TIP_MOUTH ? 1.0F - 0.45F * t : 1.0F - 0.82F * t);
            if (t < 0.12F) {
                float root = 1.0F - t / 0.12F;
                r *= 1.0F + 0.6F * root * root;
            }
            float wave = Mth.sin(TWO_PI * t * 2.2F - spec.pulseSpeed * speed * time + spec.p5);
            if (wave > 0.0F) {
                r *= 1.0F + 0.22F * wave * wave * wave;
            }
            if (tip == TIP_MOUTH && t > 0.8F) {
                float m = (t - 0.8F) / 0.2F;
                r *= 1.0F + 1.3F * m * m;
            }
            if (i == n && tip != TIP_MOUTH) {
                r = radius * 0.03F;
            }
            RR[i] = r;
        }

        // The tube.
        float[] cos = RING_COS[s];
        float[] sin = RING_SIN[s];
        float twistCos = Mth.cos(spec.twist);
        float twistSin = Mth.sin(spec.twist);
        for (int i = 0; i < n; i++) {
            float t0 = i / (float) n;
            boolean bone = tip == TIP_HOOK && t0 >= HOOK_START - 0.001F;
            float u0 = bone ? BONE_U0 : SKIN_U0;
            float uw = bone ? BONE_UW : SKIN_UW;
            float v0 = bone ? BONE_V0 : spec.suckers ? SKIN2_V0 : SKIN_V0;
            float vh = bone ? BONE_VH : SKIN_VH;
            float va = v0 + ((i & 1) == 0 ? 0.0F : vh * 0.5F);
            float vb = va + vh * 0.5F;
            for (int j = 0; j < s; j++) {
                float c0 = cos[j] * twistCos - sin[j] * twistSin;
                float s0 = sin[j] * twistCos + cos[j] * twistSin;
                float c1 = cos[j + 1] * twistCos - sin[j + 1] * twistSin;
                float s1 = sin[j + 1] * twistCos + cos[j + 1] * twistSin;
                float ua = u0 + uw * j / s;
                float ub = u0 + uw * (j + 1) / s;
                ringVertex(out, i, c0, s0, ua, va, light, overlay);
                ringVertex(out, i, c1, s1, ub, va, light, overlay);
                ringVertex(out, i + 1, c1, s1, ub, vb, light, overlay);
                ringVertex(out, i + 1, c0, s0, ua, vb, light, overlay);
            }
        }
        if (tip == TIP_MOUTH) {
            mouth(out, n, s, spec, speed, time, light, overlay);
        }
    }

    private static void ringVertex(VertexConsumer out, int i, float c, float s, float u, float v, int light, int overlay) {
        float nx = UX[i] * c + VX[i] * s;
        float ny = UY[i] * c + VY[i] * s;
        float nz = UZ[i] * c + VZ[i] * s;
        float r = RR[i];
        out.addVertex(PX[i] + nx * r, PY[i] + ny * r, PZ[i] + nz * r, -1, u, v, overlay, light, nx, ny, nz);
    }

    /** A red throat sunk into the open end and a ring of petals around it that open and bite. */
    private static void mouth(VertexConsumer out, int n, int s, Spec spec, float speed, float time, int light, int overlay) {
        float cx = PX[n];
        float cy = PY[n];
        float cz = PZ[n];
        float tx = TX[n];
        float ty = TY[n];
        float tz = TZ[n];
        float r = RR[n];
        float sunkX = cx - tx * r * 0.45F;
        float sunkY = cy - ty * r * 0.45F;
        float sunkZ = cz - tz * r * 0.45F;
        float[] cos = RING_COS[s];
        float[] sin = RING_SIN[s];
        for (int j = 0; j < s; j++) {
            float ax = UX[n] * cos[j] + VX[n] * sin[j];
            float ay = UY[n] * cos[j] + VY[n] * sin[j];
            float az = UZ[n] * cos[j] + VZ[n] * sin[j];
            float bx = UX[n] * cos[j + 1] + VX[n] * sin[j + 1];
            float by = UY[n] * cos[j + 1] + VY[n] * sin[j + 1];
            float bz = UZ[n] * cos[j + 1] + VZ[n] * sin[j + 1];
            out.addVertex(sunkX, sunkY, sunkZ, -1, THROAT_U, THROAT_V, overlay, light, tx, ty, tz);
            out.addVertex(sunkX, sunkY, sunkZ, -1, THROAT_U, THROAT_V, overlay, light, tx, ty, tz);
            out.addVertex(cx + bx * r, cy + by * r, cz + bz * r, -1, THROAT_U + cos[j + 1] * THROAT_R,
                    THROAT_V + sin[j + 1] * THROAT_R, overlay, light, tx, ty, tz);
            out.addVertex(cx + ax * r, cy + ay * r, cz + az * r, -1, THROAT_U + cos[j] * THROAT_R,
                    THROAT_V + sin[j] * THROAT_R, overlay, light, tx, ty, tz);
        }
        int petals = 5;
        float[] pc = RING_COS[4];
        float[] ps = RING_SIN[4];
        for (int p = 0; p < petals; p++) {
            float angle = TWO_PI * p / petals + spec.twist;
            float ca = Mth.cos(angle);
            float sa = Mth.sin(angle);
            float rx = UX[n] * ca + VX[n] * sa;
            float ry = UY[n] * ca + VY[n] * sa;
            float rz = UZ[n] * ca + VZ[n] * sa;
            float open = 0.35F + 0.8F * (0.5F + 0.5F * Mth.sin(spec.biteSpeed * speed * time + spec.p4 + p * 0.45F));
            float co = Mth.cos(open);
            float so = Mth.sin(open);
            float ex = tx * co + rx * so;
            float ey = ty * co + ry * so;
            float ez = tz * co + rz * so;
            // The petal's frame: along the rim (w) and across it (m), both square to its first direction.
            float wx = ty * rz - tz * ry;
            float wy = tz * rx - tx * rz;
            float wz = tx * ry - ty * rx;
            float mx = ey * wz - ez * wy;
            float my = ez * wx - ex * wz;
            float mz = ex * wy - ey * wx;
            QX[0] = cx + rx * r * 0.85F;
            QY[0] = cy + ry * r * 0.85F;
            QZ[0] = cz + rz * r * 0.85F;
            float seg = r * 0.95F;
            for (int k = 1; k <= PETAL_SEGMENTS; k++) {
                // Each petal curls back in toward the throat like a hooked tooth.
                ex += -rx * 0.42F + tx * 0.08F;
                ey += -ry * 0.42F + ty * 0.08F;
                ez += -rz * 0.42F + tz * 0.08F;
                float el = Mth.sqrt(ex * ex + ey * ey + ez * ez);
                ex /= el;
                ey /= el;
                ez /= el;
                QX[k] = QX[k - 1] + ex * seg;
                QY[k] = QY[k - 1] + ey * seg;
                QZ[k] = QZ[k - 1] + ez * seg;
            }
            // The petal's own little square tube, flat like a tooth.
            for (int k = 0; k < PETAL_SEGMENTS; k++) {
                float w0 = r * 0.42F * (1.0F - k / (float) PETAL_SEGMENTS) + r * 0.03F;
                float w1 = r * 0.42F * (1.0F - (k + 1) / (float) PETAL_SEGMENTS) + r * 0.03F;
                float va = MAW_V0 + MAW_VH * k / PETAL_SEGMENTS;
                float vb = MAW_V0 + MAW_VH * (k + 1) / PETAL_SEGMENTS;
                for (int j = 0; j < 4; j++) {
                    float c0 = pc[j];
                    float s0 = ps[j];
                    float c1 = pc[j + 1];
                    float s1 = ps[j + 1];
                    float ua = MAW_U0 + MAW_UW * j / 4.0F;
                    float ub = MAW_U0 + MAW_UW * (j + 1) / 4.0F;
                    petalVertex(out, k, mx, my, mz, wx, wy, wz, c0, s0, w0, ua, va, light, overlay);
                    petalVertex(out, k, mx, my, mz, wx, wy, wz, c1, s1, w0, ub, va, light, overlay);
                    petalVertex(out, k + 1, mx, my, mz, wx, wy, wz, c1, s1, w1, ub, vb, light, overlay);
                    petalVertex(out, k + 1, mx, my, mz, wx, wy, wz, c0, s0, w1, ua, vb, light, overlay);
                }
            }
        }
    }

    private static void petalVertex(VertexConsumer out, int k, float mx, float my, float mz, float wx, float wy, float wz,
                                    float c, float s, float width, float u, float v, int light, int overlay) {
        // Flat: half as thick across the mouth as along its rim.
        float nx = mx * c + wx * s;
        float ny = my * c + wy * s;
        float nz = mz * c + wz * s;
        float ox = mx * c * 0.5F + wx * s;
        float oy = my * c * 0.5F + wy * s;
        float oz = mz * c * 0.5F + wz * s;
        out.addVertex(QX[k] + ox * width, QY[k] + oy * width, QZ[k] + oz * width, -1, u, v, overlay, light, nx, ny, nz);
    }

    /** A 0..1 float from the low 11 bits of {@code bits}. */
    static float unit(long bits) {
        return (bits & 2047L) / 2048.0F;
    }

    static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
