package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.entity.variant.MobVariant;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * What the client keeps about one variant mob to draw it: its looks chosen from its seed (the
 * corruption styles, the tentacles and where they leave the body), its smoothed growth, its
 * generated textures, and where the renderer put it this frame. Created once per mob and reused
 * every frame; the glitch timings are pure functions of the mob's seed and age, so nothing needs
 * ticking. Render thread only.
 */
final class MobVisual {
    static final int MAX_TENTACLES = 8;
    /** Different tentacle layouts per kind of mob, so infested textures can be shared. */
    static final int LAYOUTS = 8;

    final int id;
    MobVariant variant = MobVariant.NONE;
    long seed;
    float targetGrowth;
    float growth;
    private float lastTime = Float.NaN;

    // Corrupted: the styles of its damaged texture.
    int primary;
    int secondary;

    // Parasite: the tentacles and where they come out.
    int layout;
    int tentacles;
    final Tentacles.Spec[] specs = new Tentacles.Spec[MAX_TENTACLES];
    boolean anchorsChosen;
    /** When the anchors were chosen (mob age in ticks), so a model that changes shape is re-read now and then. */
    float anchorsTime;
    int anchorQuads;
    int anchorCount;
    final int[] anchorQuad = new int[MAX_TENTACLES];
    final float[] anchorS = new float[MAX_TENTACLES];
    final float[] anchorT = new float[MAX_TENTACLES];
    /** Texture coordinates (u, v pairs, 0..1) of the anchors: where the veins start. */
    final float[] anchorUv = new float[MAX_TENTACLES * 2];
    long anchorsHash;

    // Where the renderer put the mob this frame (camera space), written before it is drawn.
    long frame = -1;
    float originX;
    float originY;
    float originZ;
    /** Camera units per block (1 in the world; larger in inventory screens). */
    float unit = 1.0F;

    // Its generated overlay (corrupted or infested) and what it was made for.
    @Nullable
    VariantTextures.Entry overlay;
    /** The overlay that replaces {@link #overlay} as soon as it is ready (a new stage, a new texture). */
    @Nullable
    VariantTextures.Entry overlayPending;
    @Nullable
    ResourceLocation overlaySource;
    int overlayStage = -1;
    long overlayAnchors;

    int nextSound;

    MobVisual(int id) {
        this.id = id;
    }

    /** (Re)initialises the looks for a variant and seed. */
    void reset(MobVariant newVariant, long newSeed, float newGrowth) {
        variant = newVariant;
        seed = newSeed;
        targetGrowth = newGrowth;
        growth = newGrowth;
        lastTime = Float.NaN;
        long h = Tentacles.mix(newSeed ^ 0x434F525255505445L);
        primary = (int) Long.remainderUnsigned(h, VariantPixels.STYLES);
        secondary = (primary + 1 + (int) ((h >>> 20) % (VariantPixels.STYLES - 1))) % VariantPixels.STYLES;
        layout = (int) ((h >>> 40) % LAYOUTS);
        tentacles = 0;
        anchorsChosen = false;
        anchorQuads = 0;
        anchorCount = 0;
        anchorsHash = 0L;
        overlay = null;
        overlayPending = null;
        overlaySource = null;
        overlayStage = -1;
        overlayAnchors = 0L;
    }

    /** Sets up the tentacles for a host of this size (blocks); once. */
    void ensureTentacles(float hostSize) {
        if (tentacles > 0) {
            return;
        }
        long h = Tentacles.mix(seed ^ 0x54454E5441434C45L);
        int count = Mth.clamp(Math.round(2.0F + 2.2F * hostSize) + (int) (h & 1L), 3, MAX_TENTACLES);
        // Small hosts get tentacles long for their size: a chicken should look overrun.
        float size = Math.max(0.8F, hostSize);
        for (int k = 0; k < count; k++) {
            if (specs[k] == null) {
                specs[k] = new Tentacles.Spec();
            }
            specs[k].roll(seed, size, k, count);
        }
        tentacles = count;
    }

    /** Moves the shown growth toward the server's value without jumps. */
    void updateGrowth(float time) {
        if (Float.isNaN(lastTime) || time < lastTime || time - lastTime > 200.0F) {
            growth = targetGrowth;
        } else {
            float step = (time - lastTime) * 0.004F;
            growth = growth < targetGrowth ? Math.min(targetGrowth, growth + step) : Math.max(targetGrowth, growth - step);
        }
        lastTime = time;
    }

    /** Which infestation texture a parasite shows at its growth. */
    int infestStage() {
        return growth < 0.35F ? 0 : growth < 0.7F ? 1 : 2;
    }

    // ------------------------------------------------------------------ glitch timings

    /** A 64-bit hash of this mob and a time slot. */
    private long slotHash(long salt, long slot) {
        return Tentacles.mix(seed ^ salt ^ (slot * 0x9E3779B97F4A7C15L));
    }

    /** A float 0..1 from the low 20 bits. */
    private static float unit20(long bits) {
        return (bits & 0xFFFFFL) / 1048576.0F;
    }

    /** True for the tick or two a corrupted mob is not drawn at all. */
    boolean blinkedOut(float time, float chance) {
        if (chance <= 0.0F) {
            return false;
        }
        long slot = (long) Math.floor(time / 3.0F);
        long h = slotHash(0x424C494E4BL, slot);
        if (unit20(h) >= chance) {
            return false;
        }
        float local = time - slot * 3.0F;
        float length = 1.0F + ((h >>> 20) & 1L);
        return local >= 0.5F && local < 0.5F + length;
    }

    /**
     * The displacement of a stutter (a dropped frame), written to {@code out} in blocks, or
     * false when the mob is where it should be.
     */
    boolean stutter(float time, float chance, float width, float[] out) {
        if (chance <= 0.0F) {
            return false;
        }
        long slot = (long) Math.floor(time / 2.0F);
        long h = slotHash(0x53545554544552L, slot);
        if (unit20(h) >= chance) {
            return false;
        }
        boolean big = ((h >>> 20) & 15L) == 0L;
        float distance = width * (big ? 0.3F : 0.06F + 0.12F * unit20(h >>> 24));
        float angle = unit20(h >>> 44) * Mth.TWO_PI;
        out[0] = Mth.cos(angle) * distance;
        out[1] = (unit20(h >>> 30) - 0.35F) * width * 0.12F;
        out[2] = Mth.sin(angle) * distance;
        return true;
    }

    /** The seed of a glitch frame (texture slipping, faces torn loose) at this time, or 0. */
    long glitchFrame(float time, float chance) {
        if (chance <= 0.0F) {
            return 0L;
        }
        long slot = (long) Math.floor(time / 2.0F);
        long h = slotHash(0x474C49544348L, slot);
        return unit20(h) < chance ? h | 1L : 0L;
    }

    /**
     * How black the mob is right now, 0..1: now and then it turns into a pure black shape with
     * glowing eyes for a second or two. {@code fade} is how many ticks it takes to come and go.
     */
    float silhouette(float time, float chance, float fade) {
        if (chance <= 0.0F) {
            return 0.0F;
        }
        float offset = unit20(Tentacles.mix(seed ^ 0x53494CL)) * 60.0F;
        long slot = (long) Math.floor((time + offset) / 60.0F);
        long h = slotHash(0x534948L, slot);
        if (unit20(h) >= chance) {
            return 0.0F;
        }
        float length = 16.0F + ((h >>> 20) % 26L);
        float start = (h >>> 30) % (long) (60.0F - length);
        float local = time + offset - slot * 60.0F - start;
        if (local < 0.0F || local > length) {
            return 0.0F;
        }
        float edge = Math.max(0.5F, fade);
        return Mth.clamp(Math.min(local, length - local) / edge, 0.0F, 1.0F);
    }

    /** A parasite's body swelling as something moves under its skin, 0..1. */
    float swell(float time) {
        float phase = unit20(Tentacles.mix(seed ^ 0x5357454CL)) * Mth.TWO_PI;
        float s = Mth.sin(time * 0.07F + phase);
        return s > 0.0F ? s * s * s * s : 0.0F;
    }

    /** A parasite's sudden twitch: degrees to turn this instant, or 0. */
    float twitch(float time, float chance) {
        long slot = (long) Math.floor(time / 2.0F);
        long h = slotHash(0x5457495443L, slot);
        if (unit20(h) >= chance) {
            return 0.0F;
        }
        return (unit20(h >>> 20) - 0.5F) * 14.0F;
    }
}
