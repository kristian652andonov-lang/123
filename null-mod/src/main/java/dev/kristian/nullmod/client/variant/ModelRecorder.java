package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Catches the vertices a model draws instead of drawing them. A layer re-renders its parent
 * model into this (exactly as vanilla's eye layers re-render it into a real buffer), which gives
 * the body's surface this frame in whatever pose it is in: where tentacles can burst out of it,
 * which texture pixels lie there, and where its face is. Works for any model that draws through
 * a vertex consumer, vanilla or modded. One instance, reused; render thread only.
 */
final class ModelRecorder implements VertexConsumer {
    static final ModelRecorder INSTANCE = new ModelRecorder();
    /** 1024 quads: far more than any vanilla model; bigger models are cut off. */
    static final int MAX_VERTICES = 4096;

    final float[] x = new float[MAX_VERTICES];
    final float[] y = new float[MAX_VERTICES];
    final float[] z = new float[MAX_VERTICES];
    final float[] u = new float[MAX_VERTICES];
    final float[] v = new float[MAX_VERTICES];
    final float[] nx = new float[MAX_VERTICES];
    final float[] ny = new float[MAX_VERTICES];
    final float[] nz = new float[MAX_VERTICES];
    private int count;
    /** The chained form: the vertex the setters belong to, or -1. */
    private int open = -1;

    ModelRecorder() {
    }

    void reset() {
        count = 0;
        open = -1;
    }

    /** Whole quads recorded. */
    int quads() {
        return count / 4;
    }

    // ------------------------------------------------------------------ VertexConsumer

    @Override
    public void addVertex(float px, float py, float pz, int color, float tu, float tv, int overlay, int light,
                          float normalX, float normalY, float normalZ) {
        open = -1;
        if (count >= MAX_VERTICES) {
            return;
        }
        int i = count++;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        u[i] = tu;
        v[i] = tv;
        nx[i] = normalX;
        ny[i] = normalY;
        nz[i] = normalZ;
    }

    @Override
    public VertexConsumer addVertex(float px, float py, float pz) {
        if (count >= MAX_VERTICES) {
            open = -1;
            return this;
        }
        int i = count++;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        u[i] = 0.0F;
        v[i] = 0.0F;
        nx[i] = 0.0F;
        ny[i] = 1.0F;
        nz[i] = 0.0F;
        open = i;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float tu, float tv) {
        if (open >= 0) {
            u[open] = tu;
            v[open] = tv;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int tu, int tv) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int tu, int tv) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        if (open >= 0) {
            nx[open] = normalX;
            ny[open] = normalY;
            nz[open] = normalZ;
        }
        return this;
    }

    // ------------------------------------------------------------------ reading the body

    /** A point inside quad q at (s, t), corners v0, v1, v2, v3 in drawing order. */
    float pointX(int q, float s, float t) {
        return bilinear(x, q * 4, s, t);
    }

    float pointY(int q, float s, float t) {
        return bilinear(y, q * 4, s, t);
    }

    float pointZ(int q, float s, float t) {
        return bilinear(z, q * 4, s, t);
    }

    float pointU(int q, float s, float t) {
        return bilinear(u, q * 4, s, t);
    }

    float pointV(int q, float s, float t) {
        return bilinear(v, q * 4, s, t);
    }

    private static float bilinear(float[] a, int i, float s, float t) {
        float top = a[i] + (a[i + 1] - a[i]) * s;
        float bottom = a[i + 3] + (a[i + 2] - a[i + 3]) * s;
        return top + (bottom - top) * t;
    }

    /** Area of quad q (camera units squared). */
    float area(int q) {
        int i = q * 4;
        float ax = x[i + 1] - x[i];
        float ay = y[i + 1] - y[i];
        float az = z[i + 1] - z[i];
        float bx = x[i + 3] - x[i];
        float by = y[i + 3] - y[i];
        float bz = z[i + 3] - z[i];
        float cx = ay * bz - az * by;
        float cy = az * bx - ax * bz;
        float cz = ax * by - ay * bx;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Average of every recorded vertex, written to {@code out} (x, y, z). */
    void center(float[] out) {
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int n = quads() * 4;
        for (int i = 0; i < n; i++) {
            sx += x[i];
            sy += y[i];
            sz += z[i];
        }
        float inv = n == 0 ? 0.0F : 1.0F / n;
        out[0] = (float) (sx * inv);
        out[1] = (float) (sy * inv);
        out[2] = (float) (sz * inv);
    }

    /**
     * Chooses up to {@code wanted} points on the body for tentacles: big faces are likelier,
     * high ones more than low ones, undersides seldom, at most one per face while others are
     * left. Writes quad indices and positions inside them; returns how many it found.
     */
    int pickAnchors(long seed, int wanted, int[] quad, float[] s, float[] t) {
        int quads = quads();
        if (quads == 0 || wanted <= 0) {
            return 0;
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxArea = 0.0F;
        for (int q = 0; q < quads; q++) {
            int i = q * 4;
            for (int k = 0; k < 4; k++) {
                minY = Math.min(minY, y[i + k]);
                maxY = Math.max(maxY, y[i + k]);
            }
            maxArea = Math.max(maxArea, area(q));
        }
        if (maxArea <= 0.0F) {
            return 0;
        }
        float height = Math.max(1.0E-4F, maxY - minY);
        float[] weight = new float[quads];
        boolean[] taken = new boolean[quads];
        for (int q = 0; q < quads; q++) {
            float a = area(q);
            if (a < maxArea * 0.04F) {
                continue;
            }
            int i = q * 4;
            float cy = (y[i] + y[i + 1] + y[i + 2] + y[i + 3]) * 0.25F;
            float high = 0.35F + 0.65F * (cy - minY) / height;
            float len = (float) Math.sqrt(nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i]);
            float down = len > 1.0E-4F ? ny[i] / len : 0.0F;
            weight[q] = a * high * (down < -0.6F ? 0.15F : 1.0F);
        }
        VariantPixels.Rand random = new VariantPixels.Rand(seed ^ 0x414E43484F52532EL);
        int found = 0;
        for (int k = 0; k < wanted; k++) {
            float total = 0.0F;
            for (int q = 0; q < quads; q++) {
                if (!taken[q]) {
                    total += weight[q];
                }
            }
            if (total <= 0.0F) {
                // Every face has one: start sharing them.
                java.util.Arrays.fill(taken, false);
                for (int q = 0; q < quads; q++) {
                    total += weight[q];
                }
                if (total <= 0.0F) {
                    break;
                }
            }
            float pick = unit(random) * total;
            int chosen = -1;
            for (int q = 0; q < quads; q++) {
                if (taken[q] || weight[q] <= 0.0F) {
                    continue;
                }
                chosen = q;
                pick -= weight[q];
                if (pick <= 0.0F) {
                    break;
                }
            }
            if (chosen < 0) {
                break;
            }
            taken[chosen] = true;
            quad[found] = chosen;
            s[found] = 0.2F + 0.6F * unit(random);
            t[found] = 0.2F + 0.6F * unit(random);
            found++;
        }
        return found;
    }

    /**
     * The quad that is most likely the front of the face: turned the way the mob looks, close to
     * its eye height, big and in front. -1 if nothing faces that way.
     */
    int faceQuad(float lookX, float lookY, float lookZ, float eyeX, float eyeY, float eyeZ, float maxDy) {
        int quads = quads();
        int best = -1;
        float bestScore = -Float.MAX_VALUE;
        for (int q = 0; q < quads; q++) {
            int i = q * 4;
            float len = (float) Math.sqrt(nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i]);
            if (len < 1.0E-4F) {
                continue;
            }
            float facing = (nx[i] * lookX + ny[i] * lookY + nz[i] * lookZ) / len;
            if (facing < 0.6F) {
                continue;
            }
            float cx = (x[i] + x[i + 1] + x[i + 2] + x[i + 3]) * 0.25F;
            float cy = (y[i] + y[i + 1] + y[i + 2] + y[i + 3]) * 0.25F;
            float cz = (z[i] + z[i + 1] + z[i + 2] + z[i + 3]) * 0.25F;
            float dy = Math.abs(cy - eyeY);
            if (dy > maxDy) {
                continue;
            }
            float forward = (cx - eyeX) * lookX + (cy - eyeY) * lookY + (cz - eyeZ) * lookZ;
            float score = forward * 0.6F + (float) Math.sqrt(area(q)) - dy;
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        return best;
    }

    private static float unit(VariantPixels.Rand random) {
        return (random.nextLong() >>> 40) / 16777216.0F;
    }
}
