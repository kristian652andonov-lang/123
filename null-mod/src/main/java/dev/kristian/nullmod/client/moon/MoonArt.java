package dev.kristian.nullmod.client.moon;

import dev.kristian.nullmod.moon.MoonGeometry;

/**
 * The pixel work behind the shattering moon: takes the current resource pack's moon phase
 * sheet (4 x 2 phases, ABGR pixels exactly as {@code NativeImage} stores them) and draws
 * cracks, broken-out pieces, shatter gaps and distortion into a copy, deterministically from
 * the shared {@link MoonGeometry} and the moon progress.
 * <p>
 * Pure Java on int arrays: no GL, no Minecraft classes, so it can be called anywhere and is
 * cheap to test. Small (vanilla-sized) moons are upscaled with nearest-neighbour sampling so
 * the cracks can be finer than the moon's own pixels.
 */
final class MoonArt {
    static final int COLUMNS = 4;
    static final int ROWS = 2;
    private static final int OPAQUE_BLACK = 0xFF000000;
    private static final float NO_EDGE = 1.0E9F;

    private final int[] base;
    private final int baseWidth;
    private final int baseHeight;
    private final int baseCellW;
    private final int baseCellH;
    private final int scale;
    private final int width;
    private final int height;
    private final int cellW;
    private final int cellH;
    /** The lit disc inside a phase cell, in working pixels (cell-local). */
    private final float discX;
    private final float discY;
    private final float discW;
    private final float discH;
    private final MoonGeometry geometry;

    private final short[] shardMap;
    private final float[] edgeDist;
    private final short[] edgeNeighbour;

    // Scratch buffers reused between renders.
    private final float[] crackMask;
    private final float[] lightMask;
    private final int[] rowShift;
    private final int[] rowChroma;
    private final boolean[] broken;
    private final float[] offsetX;
    private final float[] offsetY;

    MoonArt(int[] base, int baseWidth, int baseHeight, MoonGeometry geometry) {
        this.base = base;
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
        this.baseCellW = Math.max(1, baseWidth / COLUMNS);
        this.baseCellH = Math.max(1, baseHeight / ROWS);
        int s = (int) Math.ceil(96.0 / Math.max(baseCellW, baseCellH));
        while (s > 1 && baseWidth * s > 2048) {
            s--;
        }
        this.scale = Math.max(1, Math.min(4, s));
        this.width = baseCellW * COLUMNS * scale;
        this.height = baseCellH * ROWS * scale;
        this.cellW = baseCellW * scale;
        this.cellH = baseCellH * scale;
        this.geometry = geometry;

        float[] box = findDisc();
        this.discX = box[0] * scale;
        this.discY = box[1] * scale;
        this.discW = Math.max(1.0F, box[2] * scale);
        this.discH = Math.max(1.0F, box[3] * scale);

        int cellPixels = cellW * cellH;
        this.shardMap = new short[cellPixels];
        this.edgeDist = new float[cellPixels];
        this.edgeNeighbour = new short[cellPixels];
        this.crackMask = new float[cellPixels];
        this.lightMask = new float[cellPixels];
        this.rowShift = new int[cellH];
        this.rowChroma = new int[cellH];
        this.broken = new boolean[geometry.shardCount()];
        this.offsetX = new float[geometry.shardCount()];
        this.offsetY = new float[geometry.shardCount()];
        buildShardMap();
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int cellWidth() {
        return cellW;
    }

    int cellHeight() {
        return cellH;
    }

    MoonGeometry geometry() {
        return geometry;
    }

    /** The disc box as fractions of one phase cell: x, y, width, height. */
    float[] discFractions() {
        return new float[] {discX / cellW, discY / cellH, discW / cellW, discH / cellH};
    }

    // ------------------------------------------------------------------ main image

    /**
     * Draws the moon at a progress value into {@code out} (size {@link #width()} x
     * {@link #height()}).
     */
    void render(float progress, int[] out) {
        float distortion = MoonGeometry.distortion(progress);
        float shatter = MoonGeometry.shatter(progress);
        int shards = geometry.shardCount();
        float pixelsPerDisc = (discW + discH) * 0.5F;
        for (int i = 0; i < shards; i++) {
            MoonGeometry.Shard shard = geometry.shard(i);
            broken[i] = shard.brokenAt(progress);
            float dx = shard.centroidX() - geometry.impactX();
            float dy = shard.centroidY() - geometry.impactY();
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1.0E-4F && shatter > 0.0F) {
                float push = shatter * (0.018F + 0.03F * shard.depth()) * pixelsPerDisc;
                offsetX[i] = dx / len * push;
                offsetY[i] = dy / len * push;
            } else {
                offsetX[i] = 0.0F;
                offsetY[i] = 0.0F;
            }
        }
        float gap = shatter * Math.max(1.0F, scale * 0.6F) * 1.5F;
        float rim = Math.max(1.0F, scale * 0.5F);

        drawCracks(progress);
        computeRows(distortion);
        float dim = 1.0F - 0.12F * distortion;

        for (int cy = 0; cy < ROWS; cy++) {
            for (int cx = 0; cx < COLUMNS; cx++) {
                int ox = cx * cellW;
                int oy = cy * cellH;
                for (int ly = 0; ly < cellH; ly++) {
                    int shift = rowShift[ly];
                    int chroma = rowChroma[ly];
                    int row = (oy + ly) * width + ox;
                    for (int lx = 0; lx < cellW; lx++) {
                        int sx = lx + shift;
                        int pixel = composite(cx, cy, sx, ly, gap, rim);
                        if (chroma != 0) {
                            int red = composite(cx, cy, sx + chroma, ly, gap, rim);
                            int blue = composite(cx, cy, sx - chroma, ly, gap, rim);
                            pixel = (pixel & 0xFF00FF00) | (red & 0x000000FF) | (blue & 0x00FF0000);
                        }
                        out[row + lx] = dim < 1.0F ? scaleColor(pixel, dim) : pixel;
                    }
                }
            }
        }
    }

    /** Everything black: the moon is gone. Additive blending makes black invisible. */
    void renderGone(int[] out) {
        java.util.Arrays.fill(out, 0, width * height, OPAQUE_BLACK);
    }

    private int composite(int cx, int cy, int lx, int ly, float gap, float rim) {
        if (lx < 0) {
            lx = 0;
        } else if (lx >= cellW) {
            lx = cellW - 1;
        }
        int local = ly * cellW + lx;
        int id = shardMap[local];
        float sampleX = lx;
        float sampleY = ly;
        float factor = 1.0F;
        if (id >= 0) {
            if (broken[id]) {
                return OPAQUE_BLACK;
            }
            int neighbour = edgeNeighbour[local];
            float distance = edgeDist[local];
            if (neighbour >= 0) {
                if (gap > 0.0F && distance < gap) {
                    return OPAQUE_BLACK;
                }
                if (broken[neighbour] && distance < rim + gap) {
                    factor *= 0.42F + 0.4F * Math.min(1.0F, (distance - gap) / (rim * 2.0F));
                }
            }
            sampleX -= offsetX[id];
            sampleY -= offsetY[id];
        }
        int pixel = sampleBase(cx, cy, sampleX, sampleY);
        float crack = crackMask[local];
        if (crack > 0.0F) {
            factor *= 1.0F - crack * 0.86F;
        }
        float light = lightMask[local];
        if (light > 0.0F) {
            factor *= 1.0F + light * 0.32F;
        }
        return factor == 1.0F ? pixel : scaleColor(pixel, factor);
    }

    private int sampleBase(int cx, int cy, float x, float y) {
        int bx = (int) Math.floor(x / scale);
        int by = (int) Math.floor(y / scale);
        if (bx < 0) {
            bx = 0;
        } else if (bx >= baseCellW) {
            bx = baseCellW - 1;
        }
        if (by < 0) {
            by = 0;
        } else if (by >= baseCellH) {
            by = baseCellH - 1;
        }
        return base[(cy * baseCellH + by) * baseWidth + cx * baseCellW + bx];
    }

    // ------------------------------------------------------------------ crack flash overlay

    /**
     * A glow image (one phase cell, {@link #cellWidth()} x {@link #cellHeight()}) showing the
     * given cracks lit up, only where the moon is lit in that phase. Alpha carries the glow
     * strength for additive blending.
     *
     * @param cracks crack indices to light up, or {@code null} for every crack visible at {@code progress}
     */
    int[] renderGlow(int[] cracks, float progress, int phase, int[] out) {
        int cellPixels = cellW * cellH;
        java.util.Arrays.fill(crackMask, 0.0F);
        float radius = Math.max(1.6F, scale * 0.9F);
        if (cracks == null) {
            for (int i = 0; i < geometry.crackCount(); i++) {
                float growth = geometry.crack(i).growth(progress);
                if (growth > 0.0F) {
                    stroke(geometry.crack(i), growth, radius * geometry.crack(i).width(), crackMask, 0.0F, 0.0F, true);
                }
            }
        } else {
            for (int index : cracks) {
                if (index >= 0 && index < geometry.crackCount()) {
                    stroke(geometry.crack(index), 1.0F, radius * geometry.crack(index).width(), crackMask, 0.0F, 0.0F, true);
                }
            }
        }
        int cx = Math.floorMod(phase, COLUMNS);
        int cy = Math.floorMod(phase / COLUMNS, ROWS);
        for (int local = 0; local < cellPixels; local++) {
            float glow = crackMask[local];
            if (glow <= 0.0F) {
                out[local] = 0;
                continue;
            }
            int lx = local % cellW;
            int ly = local / cellW;
            int pixel = sampleBase(cx, cy, lx, ly);
            float lum = Math.max(pixel & 0xFF, Math.max((pixel >> 8) & 0xFF, (pixel >> 16) & 0xFF)) / 255.0F;
            float lit = smooth((lum - 0.05F) / 0.25F);
            int alpha = Math.round(Math.min(1.0F, glow * lit) * 255.0F);
            out[local] = alpha <= 0 ? 0 : (alpha << 24) | (0xE6 << 16) | (0xF4 << 8) | 0xFF;
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private void drawCracks(float progress) {
        java.util.Arrays.fill(crackMask, 0.0F);
        java.util.Arrays.fill(lightMask, 0.0F);
        float baseWidth = Math.max(1.0F, 0.45F * scale);
        for (int i = 0; i < geometry.crackCount(); i++) {
            MoonGeometry.Crack crack = geometry.crack(i);
            float growth = crack.growth(progress);
            if (growth <= 0.0F) {
                continue;
            }
            float widen = 1.0F + 0.5F * smooth((progress - crack.fullAt()) / 0.3F);
            float w = baseWidth * crack.width() * widen * (crack.major() ? 1.0F : 0.8F);
            stroke(crack, growth, w, crackMask, 0.0F, 0.0F, false);
            stroke(crack, growth, Math.max(0.7F, w * 0.6F), lightMask, 0.85F, 0.85F, false);
        }
        for (int i = 0; i < lightMask.length; i++) {
            lightMask[i] = Math.max(0.0F, lightMask[i] - crackMask[i]);
        }
    }

    /**
     * Rasterises a crack (up to {@code growth} of its length) into a coverage mask using the
     * distance to each segment. With {@code soft} the coverage falls off smoothly (glow).
     */
    private void stroke(MoonGeometry.Crack crack, float growth, float w, float[] mask, float shiftX, float shiftY, boolean soft) {
        float[] xs = crack.x();
        float[] ys = crack.y();
        float[] cumulative = crack.cumulative();
        float limit = crack.length() * growth;
        float half = w * 0.5F;
        float reach = soft ? w * 1.6F : half + 1.0F;
        for (int k = 1; k < xs.length; k++) {
            if (cumulative[k - 1] >= limit) {
                break;
            }
            float ax = discX + xs[k - 1] * discW + shiftX;
            float ay = discY + ys[k - 1] * discH + shiftY;
            float bx = discX + xs[k] * discW + shiftX;
            float by = discY + ys[k] * discH + shiftY;
            float segment = cumulative[k] - cumulative[k - 1];
            if (cumulative[k] > limit && segment > 0.0F) {
                float t = (limit - cumulative[k - 1]) / segment;
                bx = ax + (bx - ax) * t;
                by = ay + (by - ay) * t;
            }
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
            int x1 = Math.min(cellW - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
            int y1 = Math.min(cellH - 1, (int) Math.ceil(Math.max(ay, by) + reach));
            float dx = bx - ax;
            float dy = by - ay;
            float len2 = dx * dx + dy * dy;
            for (int py = y0; py <= y1; py++) {
                for (int px = x0; px <= x1; px++) {
                    float cx = px + 0.5F - ax;
                    float cy = py + 0.5F - ay;
                    float t = len2 <= 1.0E-6F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (cx * dx + cy * dy) / len2));
                    float ex = cx - dx * t;
                    float ey = cy - dy * t;
                    float d = (float) Math.sqrt(ex * ex + ey * ey);
                    float coverage;
                    if (soft) {
                        float core = Math.max(0.0F, Math.min(1.0F, half + 0.5F - d));
                        float halo = (float) Math.exp(-(d * d) / (w * w * 0.9F)) * 0.55F;
                        coverage = Math.max(core, halo);
                    } else {
                        coverage = Math.max(0.0F, Math.min(1.0F, half + 0.5F - d));
                    }
                    int index = py * cellW + px;
                    if (coverage > mask[index]) {
                        mask[index] = coverage;
                    }
                }
            }
        }
    }

    private void computeRows(float distortion) {
        long seed = geometry.seed();
        int band = Math.max(1, 2 * scale);
        for (int ly = 0; ly < cellH; ly++) {
            rowShift[ly] = 0;
            rowChroma[ly] = 0;
        }
        if (distortion <= 0.0F) {
            return;
        }
        for (int start = 0; start < cellH; start += band) {
            int bandIndex = start / band;
            float active = hash01(seed, bandIndex, 1);
            int height = band + (int) (hash01(seed, bandIndex, 2) * band * 2.0F);
            if (active < distortion * 0.5F) {
                float magnitude = (0.6F + 2.4F * distortion) * Math.max(1, scale - 1);
                int shift = Math.round((hash01(seed, bandIndex, 3) * 2.0F - 1.0F) * magnitude);
                int chroma = hash01(seed, bandIndex, 4) < distortion * 0.7F ? Math.max(1, Math.round(distortion * scale * 0.8F)) : 0;
                for (int ly = start; ly < Math.min(cellH, start + height); ly++) {
                    rowShift[ly] = shift;
                    rowChroma[ly] = chroma;
                }
                start += height - band;
            }
        }
    }

    private void buildShardMap() {
        for (int ly = 0; ly < cellH; ly++) {
            for (int lx = 0; lx < cellW; lx++) {
                int local = ly * cellW + lx;
                float u = (lx + 0.5F - discX) / discW;
                float v = (ly + 0.5F - discY) / discH;
                int id = shardAt(u, v);
                shardMap[local] = (short) id;
                if (id < 0) {
                    edgeDist[local] = NO_EDGE;
                    edgeNeighbour[local] = -1;
                    continue;
                }
                // Nearest border of this shard, in working pixels, and who is on the other side.
                MoonGeometry.Shard shard = geometry.shard(id);
                float[] xs = shard.x();
                float[] ys = shard.y();
                float px = lx + 0.5F;
                float py = ly + 0.5F;
                float best = NO_EDGE;
                float bestX = px;
                float bestY = py;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    float ax = discX + xs[j] * discW;
                    float ay = discY + ys[j] * discH;
                    float bx = discX + xs[i] * discW;
                    float by = discY + ys[i] * discH;
                    float dx = bx - ax;
                    float dy = by - ay;
                    float len2 = dx * dx + dy * dy;
                    float t = len2 <= 1.0E-6F ? 0.0F : Math.max(0.0F, Math.min(1.0F, ((px - ax) * dx + (py - ay) * dy) / len2));
                    float qx = ax + dx * t;
                    float qy = ay + dy * t;
                    float d = (float) Math.sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy));
                    if (d < best) {
                        best = d;
                        bestX = qx;
                        bestY = qy;
                    }
                }
                edgeDist[local] = best;
                float ox = bestX - px;
                float oy = bestY - py;
                float len = (float) Math.sqrt(ox * ox + oy * oy);
                if (len < 1.0E-4F) {
                    ox = 0.0F;
                    oy = 0.0F;
                } else {
                    ox /= len;
                    oy /= len;
                }
                float probeX = bestX + ox * 1.25F;
                float probeY = bestY + oy * 1.25F;
                int other = shardAt((probeX - discX) / discW, (probeY - discY) / discH);
                edgeNeighbour[local] = (short) (other == id ? -1 : other);
            }
        }
    }

    private int shardAt(float u, float v) {
        if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
            return -1;
        }
        int count = geometry.shardCount();
        for (int i = 0; i < count; i++) {
            if (geometry.shard(i).contains(u, v)) {
                return i;
            }
        }
        // Numeric slivers between two outlines: take the nearest shard centre.
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            MoonGeometry.Shard shard = geometry.shard(i);
            float dx = shard.centroidX() - u;
            float dy = shard.centroidY() - v;
            float d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** Bounding box of the lit full-moon disc in source cell pixels: x, y, w, h. */
    private float[] findDisc() {
        float[] box = litBox(0, 0);
        if (box == null) {
            for (int cy = 0; cy < ROWS && box == null; cy++) {
                for (int cx = 0; cx < COLUMNS && box == null; cx++) {
                    box = litBox(cx, cy);
                }
            }
        }
        if (box == null) {
            return new float[] {baseCellW * 0.2F, baseCellH * 0.2F, baseCellW * 0.6F, baseCellH * 0.6F};
        }
        return box;
    }

    private float[] litBox(int cx, int cy) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < baseCellH; y++) {
            for (int x = 0; x < baseCellW; x++) {
                int pixel = base[(cy * baseCellH + y) * baseWidth + cx * baseCellW + x];
                int alpha = (pixel >>> 24) & 0xFF;
                int bright = Math.max(pixel & 0xFF, Math.max((pixel >> 8) & 0xFF, (pixel >> 16) & 0xFF));
                if (alpha > 16 && bright > 24) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0 || x1 - x0 < 2 || y1 - y0 < 2) {
            return null;
        }
        return new float[] {x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    private static float hash01(long seed, int index, int salt) {
        long h = seed ^ (index * 0x9E3779B97F4A7C15L) ^ (salt * 0xD1B54A32D192ED03L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) * 0x1.0p-24F;
    }

    private static float smooth(float t) {
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t >= 1.0F) {
            return 1.0F;
        }
        return t * t * (3.0F - 2.0F * t);
    }

    /** Multiplies the colour channels (not alpha) of an ABGR pixel. */
    static int scaleColor(int pixel, float factor) {
        int r = Math.min(255, Math.round((pixel & 0xFF) * factor));
        int g = Math.min(255, Math.round(((pixel >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round(((pixel >> 16) & 0xFF) * factor));
        return (pixel & 0xFF000000) | (b << 16) | (g << 8) | r;
    }
}
