package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A glitch frame of a corrupted mob: the model is drawn once more over itself, but some of its
 * faces show the wrong part of their texture (the texture slipped) and some are torn loose and
 * hang beside the body. Faces that are neither are left out, so nothing is drawn twice for
 * nothing. Works on whole quads as models emit them. One instance, reused; render thread only.
 */
final class GlitchConsumer implements VertexConsumer {
    static final GlitchConsumer INSTANCE = new GlitchConsumer();

    private VertexConsumer target;
    private long seed;
    private int slipThreshold;
    private int tearThreshold;
    private float amplitude;
    private int vertex;
    private boolean skip;
    private float du;
    private float dv;
    private float dx;
    private float dy;
    private float dz;

    private GlitchConsumer() {
    }

    /**
     * @param slip      0..1, share of faces whose texture slips
     * @param tear      0..1, share of faces torn loose
     * @param amplitude how far torn faces move, camera units
     */
    GlitchConsumer begin(VertexConsumer out, long frameSeed, float slip, float tear, float amplitude) {
        this.target = out;
        this.seed = frameSeed;
        this.slipThreshold = (int) (slip * 1024.0F);
        this.tearThreshold = (int) (tear * 1024.0F);
        this.amplitude = amplitude;
        this.vertex = 0;
        return this;
    }

    void end() {
        target = null;
    }

    private void startQuad() {
        long h = Tentacles.mix(seed + (vertex >> 2) * 0x9E3779B97F4A7C15L);
        boolean slip = (h & 1023L) < slipThreshold;
        boolean tear = ((h >>> 10) & 1023L) < tearThreshold;
        skip = !slip && !tear;
        du = slip ? (((h >>> 20) & 31L) - 15.5F) / 96.0F : 0.0F;
        dv = slip ? (((h >>> 25) & 31L) - 15.5F) / 96.0F : 0.0F;
        if (tear) {
            dx = (((h >>> 30) & 255L) / 127.5F - 1.0F) * amplitude;
            dy = (((h >>> 38) & 255L) / 127.5F - 1.0F) * amplitude * 0.6F;
            dz = (((h >>> 46) & 255L) / 127.5F - 1.0F) * amplitude;
        } else {
            dx = 0.0F;
            dy = 0.0F;
            dz = 0.0F;
        }
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                          float normalX, float normalY, float normalZ) {
        if ((vertex & 3) == 0) {
            startQuad();
        }
        vertex++;
        if (!skip) {
            target.addVertex(x + dx, y + dy, z + dz, color, u + du, v + dv, overlay, light, normalX, normalY, normalZ);
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if ((vertex & 3) == 0) {
            startQuad();
        }
        vertex++;
        if (!skip) {
            target.addVertex(x + dx, y + dy, z + dz);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        if (!skip) {
            target.setColor(red, green, blue, alpha);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (!skip) {
            target.setUv(u + du, v + dv);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        if (!skip) {
            target.setUv1(u, v);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        if (!skip) {
            target.setUv2(u, v);
        }
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (!skip) {
            target.setNormal(x, y, z);
        }
        return this;
    }
}
