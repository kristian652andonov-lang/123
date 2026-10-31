package dev.kristian.nullmod.moon;

import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * The pixel work behind the shattering moon. Takes a moon phase sheet (4 x 2 phase cells, pixels
 * packed ABGR exactly as {@code NativeImage} stores them), finds the moon's body in every cell and
 * draws cracks, broken-out pieces, shatter gaps and distortion onto that body only,
 * deterministically from the shared {@link MoonGeometry} and the moon progress.
 * <p>
 * The <em>body</em> is what the sky really shows as the moon: the pixels at least
 * {@link #threshold()} bright (light = brightest colour channel times alpha), filled to their
 * convex outline. The faint glow many moon textures draw around the moon (vanilla's 32 x 32 cells
 * hold an 8 x 8 moon inside a dim halo) and the background never count, and every pixel outside
 * the body stays exactly as the sheet has it, so nothing can ever appear beside the moon. Inside
 * the body, crack lines darken only the lit side of the current phase (they fade out across the
 * terminator), while holes go through the whole body. Disc space (see {@link MoonGeometry}) is
 * mapped onto the body's bounding box, which is found in each phase cell, whatever the texture's
 * resolution.
 * <p>
 * Plain Java on int arrays, no Minecraft classes: the client draws its moon texture with it and
 * the GameTests check it. Small moons are upscaled (nearest neighbour) so the body is about
 * {@link #TARGET_BODY} working pixels across and cracks can be finer than the moon's own pixels.
 * Not thread-safe (the client uses it on the render thread only).
 */
public final class MoonArt {
    public static final int COLUMNS = 4;
    public static final int ROWS = 2;
    public static final int CELLS = COLUMNS * ROWS;
    /** Working pixels the moon's body is drawn across (small moons are upscaled to about this). */
    public static final int TARGET_BODY = 64;
    /** Largest sheet width {@link #fit} lets through; the moon covers few pixels on screen. */
    public static final int MAX_SHEET_WIDTH = 1024;
    private static final int MAX_SCALE = 16;
    private static final int MAX_WORKING_WIDTH = 2048;
    /** A sheet whose brightest pixel is darker than this (0..255) has no visible moon. */
    private static final float MIN_PEAK = 8.0F;
    /** Pixels this bright, relative to the brightest one, are lit moon... */
    private static final float LIT_FRACTION = 0.30F;
    /** ...unless the moon is surrounded by a glow that bright: then up to this. */
    private static final float MAX_LIT_FRACTION = 0.60F;
    /** Lit areas smaller than this part of the largest one in a cell are stars or specks. */
    private static final float SPECK_FRACTION = 0.15F;
    /** Body pixels darker than this part of the threshold are the unlit side: crack lines do not show there. */
    private static final float UNLIT_FRACTION = 0.5F;
    /** Matching a phase against the full moon: how many lit pixels to compare, and what counts as a match. */
    private static final int MIN_MATCH_SAMPLES = 12;
    private static final int MAX_MATCH_SAMPLES = 600;
    private static final float GOOD_MATCH = 0.9F;
    private static final float FAIR_MATCH = 0.75F;
    private static final float MATCH_MARGIN = 0.15F;
    private static final float NO_EDGE = 1.0E9F;

    /** A moon phase sheet whose cells are exactly a quarter of its width and half of its height. */
    public record Sheet(int[] pixels, int width, int height) {
    }

    private final int[] base;
    private final int baseWidth;
    private final int baseCellW;
    private final int baseCellH;
    private final MoonGeometry geometry;

    private final boolean found;
    private final float threshold;
    private final float unlit;
    /** The body's bounding box in source pixels and its outline inside that box. */
    private final int bodyW;
    private final int bodyH;
    private final boolean[] bodyTexels;
    /** Where that box sits in each phase cell, in source pixels from the cell's corner. */
    private final int[] cellBoxX;
    private final int[] cellBoxY;

    private final int scale;
    private final int width;
    private final int height;
    private final int cellW;
    private final int cellH;
    /** The body box in working pixels; disc space covers exactly this box. */
    private final int boxW;
    private final int boxH;
    private final boolean[] bodyMask;

    private final short[] shardMap;
    private final float[] edgeDist;
    private final short[] edgeNeighbour;
    private final boolean[] shardVisible;
    private final float[] shardCenterX;
    private final float[] shardCenterY;

    /** The working sheet: the source upscaled, with the damage drawn onto the bodies. */
    private final int[] canvas;
    private boolean canvasGone;

    // Scratch buffers reused between renders.
    private final float[] crackMask;
    private final float[] lightMask;
    private final float[] glowMask;
    private final int[] rowShift;
    private final int[] rowChroma;
    private final boolean[] broken;
    private final float[] offsetX;
    private final float[] offsetY;
    /** The piece each shard belongs to (itself, or the leader it moves with). */
    private final int[] piece;

    /**
     * @param base       the sheet's pixels (ABGR), row by row, {@code baseWidth} per row
     * @param baseWidth  sheet width; the cells are {@code baseWidth / 4} wide (use {@link #fit} first
     *                   so they are exactly where the sky's UVs put them)
     * @param baseHeight sheet height; the cells are {@code baseHeight / 2} high
     */
    public MoonArt(int[] base, int baseWidth, int baseHeight, MoonGeometry geometry) {
        if (baseWidth < COLUMNS || baseHeight < ROWS || base.length < baseWidth * baseHeight) {
            throw new IllegalArgumentException("not a moon phase sheet: " + baseWidth + "x" + baseHeight);
        }
        this.base = base;
        this.baseWidth = baseWidth;
        this.baseCellW = baseWidth / COLUMNS;
        this.baseCellH = baseHeight / ROWS;
        this.geometry = geometry;

        Layout layout = detect(base, baseWidth, baseCellW, baseCellH);
        this.found = layout.found;
        this.threshold = layout.threshold;
        this.unlit = layout.threshold * UNLIT_FRACTION;
        this.bodyW = layout.bodyW;
        this.bodyH = layout.bodyH;
        this.bodyTexels = layout.body;
        this.cellBoxX = layout.cellX;
        this.cellBoxY = layout.cellY;

        int s = (int) Math.ceil((double) TARGET_BODY / Math.max(bodyW, bodyH));
        s = Math.max(1, Math.min(MAX_SCALE, s));
        while (s > 1 && baseCellW * COLUMNS * s > MAX_WORKING_WIDTH) {
            s--;
        }
        this.scale = s;
        this.cellW = baseCellW * s;
        this.cellH = baseCellH * s;
        this.width = cellW * COLUMNS;
        this.height = cellH * ROWS;
        this.boxW = bodyW * s;
        this.boxH = bodyH * s;
        this.bodyMask = new boolean[boxW * boxH];
        for (int y = 0; y < boxH; y++) {
            for (int x = 0; x < boxW; x++) {
                bodyMask[y * boxW + x] = bodyTexels[(y / s) * bodyW + x / s];
            }
        }

        int boxPixels = boxW * boxH;
        this.shardMap = new short[boxPixels];
        this.edgeDist = new float[boxPixels];
        this.edgeNeighbour = new short[boxPixels];
        this.crackMask = new float[boxPixels];
        this.lightMask = new float[boxPixels];
        this.glowMask = new float[boxPixels];
        this.rowShift = new int[boxH];
        this.rowChroma = new int[boxH];
        int shards = geometry.shardCount();
        this.broken = new boolean[shards];
        this.offsetX = new float[shards];
        this.offsetY = new float[shards];
        this.shardVisible = new boolean[shards];
        this.shardCenterX = new float[shards];
        this.shardCenterY = new float[shards];
        this.piece = new int[shards];
        for (int i = 0; i < shards; i++) {
            int leader = geometry.shard(i).leader();
            piece[i] = leader >= 0 ? leader : i;
        }
        buildShardMap();
        measureShards();

        this.canvas = new int[width * height];
        fillCanvasFromBase();
    }

    /**
     * Prepares a resource pack's moon sheet: resampled (nearest neighbour) so its cells are exactly a
     * quarter of its width and half of its height, at most {@code maxWidth} wide. Every output texel
     * takes the source texel that the sky's own UVs (phase cell = a quarter by a half of the texture)
     * point at, so the moon stays exactly where it is with sheets of any size, even ones not divisible
     * by four.
     *
     * @return the prepared sheet (the input itself if it already fits), or null if it cannot hold phases
     */
    @Nullable
    public static Sheet fit(int[] pixels, int width, int height, int maxWidth) {
        if (width < COLUMNS || height < ROWS || pixels.length < width * height) {
            return null;
        }
        int cell = width / COLUMNS;
        int cellHeight = height / ROWS;
        int limit = Math.max(1, maxWidth / COLUMNS);
        if (cell > limit) {
            cellHeight = Math.max(1, (int) ((long) cellHeight * limit / cell));
            cell = limit;
        }
        int w = cell * COLUMNS;
        int h = cellHeight * ROWS;
        if (w == width && h == height) {
            return new Sheet(pixels, width, height);
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = (int) Math.min(height - 1L, (2L * y + 1L) * height / (2L * h));
            for (int x = 0; x < w; x++) {
                int sx = (int) Math.min(width - 1L, (2L * x + 1L) * width / (2L * w));
                out[y * w + x] = pixels[sy * width + sx];
            }
        }
        return new Sheet(out, w, h);
    }

    // ------------------------------------------------------------------ layout queries

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cellWidth() {
        return cellW;
    }

    public int cellHeight() {
        return cellH;
    }

    /** Width of the source sheet (the texture fragments are cut from). */
    public int sheetWidth() {
        return baseCellW * COLUMNS;
    }

    public int sheetHeight() {
        return baseCellH * ROWS;
    }

    /** Working pixels per source pixel. */
    public int scale() {
        return scale;
    }

    public MoonGeometry geometry() {
        return geometry;
    }

    /** False if the sheet shows no moon at all (then nothing is ever drawn onto it). */
    public boolean found() {
        return found;
    }

    /** The light (0..255, brightest channel times alpha) from which a pixel counts as lit moon. */
    public float threshold() {
        return threshold;
    }

    /** The phase cell (0..7) the sky shows for a moon phase, as vanilla picks it. */
    public static int cellOf(int phase) {
        return Math.floorMod(phase, CELLS);
    }

    /** Left edge of the body box of a phase, in working pixels of the whole sheet. */
    public int boxX(int phase) {
        int cell = cellOf(phase);
        return (cell % COLUMNS) * cellW + cellBoxX[cell] * scale;
    }

    /** Top edge of the body box of a phase, in working pixels of the whole sheet. */
    public int boxY(int phase) {
        int cell = cellOf(phase);
        return (cell / COLUMNS) * cellH + cellBoxY[cell] * scale;
    }

    public int boxWidth() {
        return boxW;
    }

    public int boxHeight() {
        return boxH;
    }

    /** Left edge of the body box of a phase as a fraction of the phase cell. */
    public float discFractionX(int phase) {
        return cellBoxX[cellOf(phase)] / (float) baseCellW;
    }

    /** Top edge of the body box of a phase as a fraction of the phase cell. */
    public float discFractionY(int phase) {
        return cellBoxY[cellOf(phase)] / (float) baseCellH;
    }

    /** Width of the body box as a fraction of a phase cell (one disc diameter). */
    public float discFractionW() {
        return bodyW / (float) baseCellW;
    }

    public float discFractionH() {
        return bodyH / (float) baseCellH;
    }

    /**
     * Where a disc-space x lands across the phase cell the sky shows (0 = the cell's left edge,
     * 1 = its right edge). The sky stretches the cell over the moon quad, so this is also the
     * position on the quad; anything placed relative to the moon goes through here.
     */
    public float cellFractionX(int phase, float discX) {
        return discFractionX(phase) + discX * discFractionW();
    }

    /** The same for a disc-space y (0 = the cell's top edge). */
    public float cellFractionY(int phase, float discY) {
        return discFractionY(phase) + discY * discFractionH();
    }

    /** Texture u of a disc-space x in the phase's cell of the whole sheet (any texture of this layout). */
    public float sheetU(int phase, float discX) {
        return (cellOf(phase) % COLUMNS + cellFractionX(phase, discX)) / COLUMNS;
    }

    /** Texture v of a disc-space y in the phase's cell of the whole sheet. */
    public float sheetV(int phase, float discY) {
        return (cellOf(phase) / COLUMNS + cellFractionY(phase, discY)) / ROWS;
    }

    /** True if the working pixel (whole-sheet coordinates) belongs to the moon's body of its cell. */
    public boolean inBody(int x, int y) {
        if (!found || x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        int cell = (y / cellH) * COLUMNS + x / cellW;
        int bx = x - ((cell % COLUMNS) * cellW + cellBoxX[cell] * scale);
        int by = y - ((cell / COLUMNS) * cellH + cellBoxY[cell] * scale);
        return bx >= 0 && by >= 0 && bx < boxW && by < boxH && bodyMask[by * boxW + bx];
    }

    /** True if the source pixel (whole-sheet coordinates) belongs to the moon's body of its cell. */
    public boolean inBodyTexel(int x, int y) {
        if (!found || x < 0 || y < 0 || x >= baseCellW * COLUMNS || y >= baseCellH * ROWS) {
            return false;
        }
        int cell = (y / baseCellH) * COLUMNS + x / baseCellW;
        int bx = x - ((cell % COLUMNS) * baseCellW + cellBoxX[cell]);
        int by = y - ((cell / COLUMNS) * baseCellH + cellBoxY[cell]);
        return bx >= 0 && by >= 0 && bx < bodyW && by < bodyH && bodyTexels[by * bodyW + bx];
    }

    /** The untouched source pixel shown at a working pixel (whole-sheet coordinates). */
    public int sourcePixel(int x, int y) {
        int bx = Math.max(0, Math.min(baseCellW * COLUMNS - 1, x / scale));
        int by = Math.max(0, Math.min(baseCellH * ROWS - 1, y / scale));
        return base[by * baseWidth + bx];
    }

    /** True if part of a shard lies on the moon's body (a piece that breaks away shows something). */
    public boolean shardVisible(int shard) {
        return shardVisible[shard];
    }

    /** Centre of the part of a shard that lies on the body, in disc space. */
    public float shardCenterX(int shard) {
        return shardCenterX[shard];
    }

    public float shardCenterY(int shard) {
        return shardCenterY[shard];
    }

    // ------------------------------------------------------------------ drawing

    /** The working sheet ({@link #width()} x {@link #height()}, ABGR) as last drawn. Read only. */
    public int[] canvas() {
        return canvas;
    }

    /**
     * Draws the moon at a progress value into the canvas. Only pixels of the moon's bodies change;
     * returns true if the rest of the canvas changed too (it is put back after {@link #renderGone()}).
     */
    public boolean render(float progress) {
        boolean full = false;
        if (canvasGone) {
            fillCanvasFromBase();
            canvasGone = false;
            full = true;
        }
        if (!found) {
            return full;
        }
        float distortion = MoonGeometry.distortion(progress);
        float shatter = MoonGeometry.shatter(progress);
        float size = (boxW + boxH) * 0.5F;
        for (int i = 0; i < broken.length; i++) {
            MoonGeometry.Shard shard = geometry.shard(i);
            broken[i] = shard.brokenAt(progress);
            // A shard moves with the piece it belongs to.
            MoonGeometry.Shard mover = geometry.shard(piece[i]);
            float dx = mover.centroidX() - geometry.impactX();
            float dy = mover.centroidY() - geometry.impactY();
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1.0E-4F && shatter > 0.0F) {
                // Shattering: what is left drifts apart, away from the impact.
                float push = shatter * (0.018F + 0.03F * mover.depth());
                offsetX[i] = dx / len * push * boxW;
                offsetY[i] = dy / len * push * boxH;
            } else {
                offsetX[i] = 0.0F;
                offsetY[i] = 0.0F;
            }
        }
        float gap = shatter * Math.max(0.75F, size * 0.022F);
        float rim = Math.max(1.0F, size * 0.03F);

        drawCracks(progress, size);
        computeRows(distortion, size);
        float dim = 1.0F - 0.12F * distortion;

        for (int cell = 0; cell < CELLS; cell++) {
            int ox = boxX(cell);
            int oy = boxY(cell);
            for (int y = 0; y < boxH; y++) {
                int shift = rowShift[y];
                int chroma = rowChroma[y];
                int row = (oy + y) * width + ox;
                int maskRow = y * boxW;
                for (int x = 0; x < boxW; x++) {
                    if (!bodyMask[maskRow + x]) {
                        continue;
                    }
                    int sx = x + shift;
                    int pixel = composite(cell, sx, y, gap, rim);
                    if (chroma != 0) {
                        int red = composite(cell, sx + chroma, y, gap, rim);
                        int blue = composite(cell, sx - chroma, y, gap, rim);
                        pixel = (pixel & 0xFF00FF00) | (red & 0x000000FF) | (blue & 0x00FF0000);
                    }
                    canvas[row + x] = dim < 1.0F ? scaleColor(pixel, dim) : pixel;
                }
            }
        }
        return full;
    }

    /** No moon at all: the whole canvas becomes transparent black, invisible in any sky. */
    public void renderGone() {
        Arrays.fill(canvas, 0);
        canvasGone = true;
    }

    /**
     * The source sheet ({@link #sheetWidth()} x {@link #sheetHeight()}) with everything that is not
     * the moon's body made transparent black: the texture broken-off pieces are cut from, so a piece
     * is exactly the part missing from the moon, in the phase the sky shows.
     */
    public int[] bodyOnly() {
        int sheetW = baseCellW * COLUMNS;
        int[] out = new int[sheetW * baseCellH * ROWS];
        if (!found) {
            return out;
        }
        for (int cell = 0; cell < CELLS; cell++) {
            int ox = (cell % COLUMNS) * baseCellW + cellBoxX[cell];
            int oy = (cell / COLUMNS) * baseCellH + cellBoxY[cell];
            for (int y = 0; y < bodyH; y++) {
                for (int x = 0; x < bodyW; x++) {
                    if (bodyTexels[y * bodyW + x]) {
                        out[(oy + y) * sheetW + ox + x] = base[(oy + y) * baseWidth + ox + x];
                    }
                }
            }
        }
        return out;
    }

    /**
     * A glow image (one phase cell, {@link #cellWidth()} x {@link #cellHeight()}) showing the
     * given cracks lit up, only on the lit part of the moon's body in that phase. Alpha carries the
     * glow strength for additive blending; everything else is transparent black.
     *
     * @param cracks crack indices to light up, or {@code null} for every crack visible at {@code progress}
     */
    public int[] renderGlow(@Nullable int[] cracks, float progress, int phase, int[] out) {
        Arrays.fill(out, 0, cellW * cellH, 0);
        if (!found) {
            return out;
        }
        float size = (boxW + boxH) * 0.5F;
        Arrays.fill(glowMask, 0.0F);
        float radius = Math.max(1.6F, size * 0.055F);
        if (cracks == null) {
            for (int i = 0; i < geometry.crackCount(); i++) {
                MoonGeometry.Crack crack = geometry.crack(i);
                float growth = crack.growth(progress);
                if (growth > 0.0F) {
                    stroke(crack, growth, radius * crack.width(), glowMask, 0.0F, 0.0F, true);
                }
            }
        } else {
            for (int index : cracks) {
                if (index >= 0 && index < geometry.crackCount()) {
                    // Light up as much of the crack as exists (a fresh one at least starts to glow).
                    MoonGeometry.Crack crack = geometry.crack(index);
                    stroke(crack, Math.max(0.15F, crack.growth(progress)), radius * crack.width(), glowMask, 0.0F, 0.0F, true);
                }
            }
        }
        int cell = cellOf(phase);
        int ox = cellBoxX[cell] * scale;
        int oy = cellBoxY[cell] * scale;
        for (int y = 0; y < boxH; y++) {
            for (int x = 0; x < boxW; x++) {
                int local = y * boxW + x;
                float glow = glowMask[local];
                if (glow <= 0.0F || !bodyMask[local]) {
                    continue;
                }
                int alpha = Math.round(Math.min(1.0F, glow * litness(basePixel(cell, x, y))) * 255.0F);
                if (alpha > 0) {
                    out[(oy + y) * cellW + ox + x] = (alpha << 24) | (0xE6 << 16) | (0xF4 << 8) | 0xFF;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ internals: drawing

    private int composite(int cell, int x, int y, float gap, float rim) {
        if (x < 0 || x >= boxW || !bodyMask[y * boxW + x]) {
            // Not the moon (a distortion row slid past its edge): whatever the sheet has there.
            return basePixel(cell, x, y);
        }
        int local = y * boxW + x;
        int id = shardMap[local];
        float sampleX = x;
        float sampleY = y;
        float shade = 0.0F;
        if (id >= 0) {
            if (broken[id]) {
                return 0;
            }
            int neighbour = edgeNeighbour[local];
            float distance = edgeDist[local];
            if (neighbour >= 0 && piece[neighbour] != piece[id]) {
                if (broken[neighbour]) {
                    // Freshly broken edge: a dark rim where the piece tore away.
                    if (distance < rim) {
                        shade = 0.6F - 0.35F * (distance / rim);
                    }
                } else if (gap > 0.0F && distance < gap) {
                    // Shattered: the remaining pieces drift apart.
                    return 0;
                }
            }
            sampleX -= offsetX[id];
            sampleY -= offsetY[id];
        }
        int pixel = basePixel(cell, (int) Math.floor(sampleX), (int) Math.floor(sampleY));
        float crack = crackMask[local];
        float shine = lightMask[local];
        if (shade <= 0.0F && crack <= 0.0F && shine <= 0.0F) {
            return pixel;
        }
        // Lines only show where the moon is lit: on the dark side of a phase there is nothing to
        // darken, and dark strokes there would read as lines beside the moon.
        float lit = litness(pixel);
        if (lit <= 0.0F) {
            return pixel;
        }
        float factor = (1.0F - shade * lit) * (1.0F - crack * 0.86F * lit) * (1.0F + shine * 0.32F * lit);
        return scaleColor(pixel, factor);
    }

    /** 0 on the unlit side (and off the moon), 1 where the moon is fully lit. */
    private float litness(int pixel) {
        return smooth((light(pixel) - unlit) / Math.max(1.0F, threshold - unlit));
    }

    /** The source pixel at a working position relative to a cell's body box (clamped to the cell). */
    private int basePixel(int cell, int x, int y) {
        int tx = Math.floorDiv(cellBoxX[cell] * scale + x, scale);
        int ty = Math.floorDiv(cellBoxY[cell] * scale + y, scale);
        tx = Math.max(0, Math.min(baseCellW - 1, tx));
        ty = Math.max(0, Math.min(baseCellH - 1, ty));
        return base[((cell / COLUMNS) * baseCellH + ty) * baseWidth + (cell % COLUMNS) * baseCellW + tx];
    }

    private void fillCanvasFromBase() {
        for (int y = 0; y < height; y++) {
            int sourceRow = (y / scale) * baseWidth;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                canvas[row + x] = base[sourceRow + x / scale];
            }
        }
    }

    private void drawCracks(float progress, float size) {
        Arrays.fill(crackMask, 0.0F);
        Arrays.fill(lightMask, 0.0F);
        float baseWidthPx = Math.max(0.9F, size * 0.024F);
        float shine = size * 0.0125F;
        for (int i = 0; i < geometry.crackCount(); i++) {
            MoonGeometry.Crack crack = geometry.crack(i);
            float growth = crack.growth(progress);
            if (growth <= 0.0F) {
                continue;
            }
            float widen = 1.0F + 0.45F * smooth((progress - crack.fullAt()) / 0.35F);
            float w = baseWidthPx * crack.width() * widen * (crack.major() ? 1.0F : 0.75F);
            stroke(crack, growth, w, crackMask, 0.0F, 0.0F, false);
            stroke(crack, growth, Math.max(0.6F, w * 0.55F), lightMask, shine, shine, false);
        }
        for (int i = 0; i < lightMask.length; i++) {
            lightMask[i] = Math.max(0.0F, lightMask[i] - crackMask[i]);
        }
    }

    /**
     * Rasterises a crack (up to {@code growth} of its length) into a coverage mask of the body box
     * using the distance to each segment. With {@code soft} the coverage falls off smoothly (glow).
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
            float ax = xs[k - 1] * boxW + shiftX;
            float ay = ys[k - 1] * boxH + shiftY;
            float bx = xs[k] * boxW + shiftX;
            float by = ys[k] * boxH + shiftY;
            float segment = cumulative[k] - cumulative[k - 1];
            if (cumulative[k] > limit && segment > 0.0F) {
                float t = (limit - cumulative[k - 1]) / segment;
                bx = ax + (bx - ax) * t;
                by = ay + (by - ay) * t;
            }
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach));
            int x1 = Math.min(boxW - 1, (int) Math.ceil(Math.max(ax, bx) + reach));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, by) - reach));
            int y1 = Math.min(boxH - 1, (int) Math.ceil(Math.max(ay, by) + reach));
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
                    int index = py * boxW + px;
                    if (coverage > mask[index]) {
                        mask[index] = coverage;
                    }
                }
            }
        }
    }

    /** Glitch rows of the distortion: bands of the body slide sideways, some with a colour split. */
    private void computeRows(float distortion, float size) {
        Arrays.fill(rowShift, 0);
        Arrays.fill(rowChroma, 0);
        if (distortion <= 0.0F) {
            return;
        }
        long seed = geometry.seed();
        int band = Math.max(1, Math.round(boxH * 0.125F));
        int split = Math.max(1, Math.round(size / TARGET_BODY));
        for (int start = 0; start < boxH; start += band) {
            int bandIndex = start / band;
            float active = hash01(seed, bandIndex, 1);
            int rows = band + (int) (hash01(seed, bandIndex, 2) * band * 1.5F);
            if (active < distortion * 0.3F) {
                float magnitude = (0.6F + 1.6F * distortion) * size * 0.042F;
                int shift = Math.round((hash01(seed, bandIndex, 3) * 2.0F - 1.0F) * magnitude);
                int chroma = hash01(seed, bandIndex, 4) < distortion * 0.35F ? split : 0;
                for (int y = start; y < Math.min(boxH, start + rows); y++) {
                    rowShift[y] = shift;
                    rowChroma[y] = chroma;
                }
                start += rows - band;
            }
        }
    }

    // ------------------------------------------------------------------ internals: shards

    private void buildShardMap() {
        for (int y = 0; y < boxH; y++) {
            for (int x = 0; x < boxW; x++) {
                int local = y * boxW + x;
                float px = x + 0.5F;
                float py = y + 0.5F;
                int id = shardAt(px / boxW, py / boxH);
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
                float best = NO_EDGE;
                float bestX = px;
                float bestY = py;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    float ax = xs[j] * boxW;
                    float ay = ys[j] * boxH;
                    float bx = xs[i] * boxW;
                    float by = ys[i] * boxH;
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
                int other = shardAt((bestX + ox * 1.25F) / boxW, (bestY + oy * 1.25F) / boxH);
                edgeNeighbour[local] = (short) (other == id ? -1 : other);
            }
        }
    }

    /** Which shards have pixels on the body, and the centre of those pixels. */
    private void measureShards() {
        int shards = shardVisible.length;
        int[] count = new int[shards];
        double[] sumX = new double[shards];
        double[] sumY = new double[shards];
        for (int y = 0; y < boxH; y++) {
            for (int x = 0; x < boxW; x++) {
                int local = y * boxW + x;
                int id = shardMap[local];
                if (id >= 0 && bodyMask[local]) {
                    count[id]++;
                    sumX[id] += x + 0.5;
                    sumY[id] += y + 0.5;
                }
            }
        }
        for (int i = 0; i < shards; i++) {
            shardVisible[i] = found && count[i] > 0;
            if (count[i] > 0) {
                shardCenterX[i] = (float) (sumX[i] / count[i] / boxW);
                shardCenterY[i] = (float) (sumY[i] / count[i] / boxH);
            } else {
                shardCenterX[i] = geometry.shard(i).centroidX();
                shardCenterY[i] = geometry.shard(i).centroidY();
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

    // ------------------------------------------------------------------ internals: finding the moon

    /** Where the moon's body is in a sheet. */
    private static final class Layout {
        boolean found;
        float threshold;
        int bodyW;
        int bodyH;
        boolean[] body;
        final int[] cellX = new int[CELLS];
        final int[] cellY = new int[CELLS];
    }

    /**
     * Finds the body: the lit outline of the fullest moon on the sheet (normally the full moon,
     * phase 0), then where that outline sits in every phase cell.
     */
    private static Layout detect(int[] base, int stride, int cellW, int cellH) {
        Layout layout = new Layout();
        float[] light = new float[cellW * COLUMNS * cellH * ROWS];
        float peak = 0.0F;
        for (int y = 0; y < cellH * ROWS; y++) {
            for (int x = 0; x < cellW * COLUMNS; x++) {
                float value = light(base[y * stride + x]);
                light[y * cellW * COLUMNS + x] = value;
                peak = Math.max(peak, value);
            }
        }
        int reference = -1;
        long bestArea = -1L;
        float low = peak * LIT_FRACTION;
        if (peak >= MIN_PEAK) {
            for (int cell = 0; cell < CELLS; cell++) {
                boolean[] region = litRegion(light, cellW, cellH, cell, low);
                if (region != null) {
                    int[] box = bounds(region, cellW, cellH);
                    long area = (long) (box[2] - box[0] + 1) * (box[3] - box[1] + 1);
                    if (area > bestArea) {
                        bestArea = area;
                        reference = cell;
                    }
                }
            }
        }
        if (reference < 0) {
            // No moon to find: a box in the middle of every cell, with nothing in it.
            layout.found = false;
            layout.threshold = Math.max(MIN_PEAK, low);
            layout.bodyW = Math.max(1, cellW / 2);
            layout.bodyH = Math.max(1, cellH / 2);
            layout.body = new boolean[layout.bodyW * layout.bodyH];
            Arrays.fill(layout.cellX, (cellW - layout.bodyW) / 2);
            Arrays.fill(layout.cellY, (cellH - layout.bodyH) / 2);
            return layout;
        }
        float t = chooseThreshold(light, cellW, cellH, reference, peak, low);
        boolean[] region = litRegion(light, cellW, cellH, reference, t);
        if (region == null) {
            t = low;
            region = litRegion(light, cellW, cellH, reference, t);
        }
        boolean[] outline = convexFill(region, cellW, cellH);
        int[] box = bounds(outline, cellW, cellH);
        layout.found = true;
        layout.threshold = t;
        layout.bodyW = box[2] - box[0] + 1;
        layout.bodyH = box[3] - box[1] + 1;
        layout.body = new boolean[layout.bodyW * layout.bodyH];
        for (int y = 0; y < layout.bodyH; y++) {
            for (int x = 0; x < layout.bodyW; x++) {
                layout.body[y * layout.bodyW + x] = outline[(box[1] + y) * cellW + box[0] + x];
            }
        }
        // The same outline in every phase, moved only if a pack draws a phase's moon elsewhere.
        float tolerance = Math.max(8.0F, peak * 0.06F);
        for (int cell = 0; cell < CELLS; cell++) {
            int[] at = cell == reference ? new int[] {box[0], box[1]}
                    : locate(light, cellW, cellH, cell, reference, box[0], box[1], layout.bodyW, layout.bodyH, t, tolerance);
            layout.cellX[cell] = at[0];
            layout.cellY[cell] = at[1];
        }
        return layout;
    }

    /**
     * Where the body box sits in another phase's cell. Normally exactly where it is in the reference
     * cell. If the lit part of the phase is the full moon's surface somewhere else (a pack that draws
     * a phase off-centre), the box goes there; if the lit part merely sticks out of the box, the box
     * moves as little as needed to cover it. A phase with nothing lit keeps the reference position
     * unless its dim outline is clearly a moon of the body's size elsewhere.
     */
    private static int[] locate(float[] light, int cellW, int cellH, int cell, int reference, int refX, int refY,
                                int bodyW, int bodyH, float t, float tolerance) {
        boolean[] lit = litRegion(light, cellW, cellH, cell, t);
        if (lit == null) {
            return locateUnlit(light, cellW, cellH, cell, refX, refY, bodyW, bodyH);
        }
        int[] litBox = bounds(lit, cellW, cellH);
        int fallbackX = place(refX, bodyW, litBox[0], litBox[2], cellW);
        int fallbackY = place(refY, bodyH, litBox[1], litBox[3], cellH);
        int minX = Math.max(0, litBox[2] - bodyW + 1);
        int maxX = Math.min(cellW - bodyW, litBox[0]);
        int minY = Math.max(0, litBox[3] - bodyH + 1);
        int maxY = Math.min(cellH - bodyH, litBox[1]);
        int count = 0;
        for (boolean value : lit) {
            if (value) {
                count++;
            }
        }
        if (minX > maxX || minY > maxY || count < MIN_MATCH_SAMPLES) {
            return new int[] {fallbackX, fallbackY};
        }
        // Up to MAX_MATCH_SAMPLES lit pixels, spread evenly over the lit part.
        int every = Math.max(1, count / MAX_MATCH_SAMPLES);
        int[] sampleX = new int[count / every + 1];
        int[] sampleY = new int[sampleX.length];
        float[] sampleLight = new float[sampleX.length];
        int stride = cellW * COLUMNS;
        int ox = (cell % COLUMNS) * cellW;
        int oy = (cell / COLUMNS) * cellH;
        int n = 0;
        for (int p = 0, seen = 0; p < lit.length && n < sampleX.length; p++) {
            if (lit[p] && seen++ % every == 0) {
                sampleX[n] = p % cellW;
                sampleY[n] = p / cellW;
                sampleLight[n] = light[(oy + sampleY[n]) * stride + ox + sampleX[n]];
                n++;
            }
        }
        int rx = (reference % COLUMNS) * cellW;
        int ry = (reference / COLUMNS) * cellH;
        boolean referenceFits = refX >= minX && refX <= maxX && refY >= minY && refY <= maxY;
        float referenceScore = referenceFits
                ? match(light, stride, rx + refX, ry + refY, sampleX, sampleY, sampleLight, n, refX, refY, tolerance) : 0.0F;
        if (referenceScore >= GOOD_MATCH) {
            return new int[] {refX, refY};
        }
        float bestScore = -1.0F;
        int bestDistance = Integer.MAX_VALUE;
        int bestX = fallbackX;
        int bestY = fallbackY;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float score = match(light, stride, rx + refX, ry + refY, sampleX, sampleY, sampleLight, n, x, y, tolerance);
                int distance = Math.abs(x - refX) + Math.abs(y - refY);
                if (score > bestScore + 1.0E-6F || (score > bestScore - 1.0E-6F && distance < bestDistance)) {
                    bestScore = score;
                    bestDistance = distance;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        if (bestScore >= FAIR_MATCH && bestScore >= referenceScore + MATCH_MARGIN) {
            return new int[] {bestX, bestY};
        }
        return new int[] {fallbackX, fallbackY};
    }

    /**
     * Share of the samples (cell-local positions with their light) that show the reference moon's
     * surface when the body box of their cell is at {@code (x, y)}; {@code refLeft/refTop} is the
     * reference box's corner in the whole sheet.
     */
    private static float match(float[] light, int stride, int refLeft, int refTop, int[] sampleX, int[] sampleY,
                               float[] sampleLight, int n, int x, int y, float tolerance) {
        int hits = 0;
        for (int i = 0; i < n; i++) {
            float reference = light[(refTop + sampleY[i] - y) * stride + refLeft + sampleX[i] - x];
            if (Math.abs(reference - sampleLight[i]) <= tolerance) {
                hits++;
            }
        }
        return hits / (float) n;
    }

    /** A phase with nothing lit: its dim outline, if that is one body-sized area, else the reference position. */
    private static int[] locateUnlit(float[] light, int cellW, int cellH, int cell, int refX, int refY, int bodyW, int bodyH) {
        int stride = cellW * COLUMNS;
        int ox = (cell % COLUMNS) * cellW;
        int oy = (cell / COLUMNS) * cellH;
        float max = 0.0F;
        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < cellW; x++) {
                max = Math.max(max, light[(oy + y) * stride + ox + x]);
            }
        }
        boolean[] dim = max < MIN_PEAK * 0.5F ? null : litRegion(light, cellW, cellH, cell, max * 0.5F);
        if (dim == null) {
            return new int[] {refX, refY};
        }
        int[] box = bounds(dim, cellW, cellH);
        int w = box[2] - box[0] + 1;
        int h = box[3] - box[1] + 1;
        if (Math.abs(w - bodyW) > 1 || Math.abs(h - bodyH) > 1) {
            return new int[] {refX, refY};
        }
        int x = Math.max(0, Math.min(cellW - bodyW, box[0] + (w - bodyW) / 2));
        int y = Math.max(0, Math.min(cellH - bodyH, box[1] + (h - bodyH) / 2));
        return new int[] {x, y};
    }

    /**
     * The light from which a pixel counts as moon. Normally {@code low}; if the moon is wrapped in
     * a glow brighter than that, the level that separates moon from glow (Otsu's method), as long
     * as the pixels between the two levels lie around the moon rather than on it (darker maria on
     * the moon must not be cut away).
     */
    private static float chooseThreshold(float[] light, int cellW, int cellH, int cell, float peak, float low) {
        float otsu = otsu(light, cellW, cellH, cell, peak * 0.04F);
        float high = Math.min(peak * MAX_LIT_FRACTION, Math.max(low, otsu));
        if (high <= low) {
            return low;
        }
        boolean[] strong = litRegion(light, cellW, cellH, cell, high);
        if (strong == null) {
            return low;
        }
        int[] box = bounds(strong, cellW, cellH);
        int stride = cellW * COLUMNS;
        int ox = (cell % COLUMNS) * cellW;
        int oy = (cell / COLUMNS) * cellH;
        int inside = 0;
        int outside = 0;
        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < cellW; x++) {
                float value = light[(oy + y) * stride + ox + x];
                if (value >= low && value < high) {
                    if (x >= box[0] - 1 && x <= box[2] + 1 && y >= box[1] - 1 && y <= box[3] + 1) {
                        inside++;
                    } else {
                        outside++;
                    }
                }
            }
        }
        return outside > inside ? high : low;
    }

    /** Otsu's threshold over a cell's pixels brighter than {@code floor}; 0 if there are none. */
    private static float otsu(float[] light, int cellW, int cellH, int cell, float floor) {
        int[] histogram = new int[256];
        int stride = cellW * COLUMNS;
        int ox = (cell % COLUMNS) * cellW;
        int oy = (cell / COLUMNS) * cellH;
        long total = 0L;
        double sum = 0.0;
        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < cellW; x++) {
                float value = light[(oy + y) * stride + ox + x];
                if (value > floor) {
                    int bin = Math.max(0, Math.min(255, Math.round(value)));
                    histogram[bin]++;
                    total++;
                    sum += bin;
                }
            }
        }
        if (total == 0L) {
            return 0.0F;
        }
        long below = 0L;
        double sumBelow = 0.0;
        double best = -1.0;
        int bestBin = 0;
        for (int bin = 0; bin < 256; bin++) {
            below += histogram[bin];
            if (below == 0L) {
                continue;
            }
            long above = total - below;
            if (above == 0L) {
                break;
            }
            sumBelow += (double) bin * histogram[bin];
            double meanBelow = sumBelow / below;
            double meanAbove = (sum - sumBelow) / above;
            double between = (double) below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove);
            if (between > best) {
                best = between;
                bestBin = bin;
            }
        }
        return bestBin + 0.5F;
    }

    /**
     * The lit areas of a cell at a threshold (8-connected), without specks much smaller than the
     * largest one; cell-local, or null if nothing in the cell is that bright.
     */
    @Nullable
    private static boolean[] litRegion(float[] light, int cellW, int cellH, int cell, float t) {
        int stride = cellW * COLUMNS;
        int ox = (cell % COLUMNS) * cellW;
        int oy = (cell / COLUMNS) * cellH;
        int pixels = cellW * cellH;
        int[] label = new int[pixels];
        Arrays.fill(label, -1);
        int[] queue = new int[pixels];
        int[] sizes = new int[8];
        int count = 0;
        for (int start = 0; start < pixels; start++) {
            if (label[start] >= 0 || light[(oy + start / cellW) * stride + ox + start % cellW] < t) {
                continue;
            }
            if (count == sizes.length) {
                sizes = Arrays.copyOf(sizes, count * 2);
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            label[start] = count;
            while (head < tail) {
                int p = queue[head++];
                int px = p % cellW;
                int py = p / cellW;
                for (int ny = Math.max(0, py - 1); ny <= Math.min(cellH - 1, py + 1); ny++) {
                    for (int nx = Math.max(0, px - 1); nx <= Math.min(cellW - 1, px + 1); nx++) {
                        int q = ny * cellW + nx;
                        if (label[q] < 0 && light[(oy + ny) * stride + ox + nx] >= t) {
                            label[q] = count;
                            queue[tail++] = q;
                        }
                    }
                }
            }
            sizes[count] = tail;
            count++;
        }
        if (count == 0) {
            return null;
        }
        int largest = 0;
        for (int i = 0; i < count; i++) {
            largest = Math.max(largest, sizes[i]);
        }
        int keep = Math.max(1, (int) Math.ceil(largest * SPECK_FRACTION));
        boolean[] region = new boolean[pixels];
        for (int p = 0; p < pixels; p++) {
            region[p] = label[p] >= 0 && sizes[label[p]] >= keep;
        }
        return region;
    }

    /** Everything inside the convex outline of a region (pixel squares, tested at pixel centres). */
    private static boolean[] convexFill(boolean[] region, int w, int h) {
        // Corners of the pixels on the region's border are enough for its hull.
        long[] points = new long[64];
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!region[y * w + x]) {
                    continue;
                }
                boolean border = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || !region[y * w + x - 1] || !region[y * w + x + 1] || !region[(y - 1) * w + x] || !region[(y + 1) * w + x];
                if (!border) {
                    continue;
                }
                if (n + 4 > points.length) {
                    points = Arrays.copyOf(points, points.length * 2);
                }
                points[n++] = pack(x, y);
                points[n++] = pack(x + 1, y);
                points[n++] = pack(x, y + 1);
                points[n++] = pack(x + 1, y + 1);
            }
        }
        Arrays.sort(points, 0, n);
        // Andrew's monotone chain (points sorted by x, then y).
        long[] hull = new long[2 * n + 2];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && points[i] == points[i - 1]) {
                continue;
            }
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], points[i]) <= 0L) {
                k--;
            }
            hull[k++] = points[i];
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            if (points[i] == points[i + 1]) {
                continue;
            }
            while (k >= lower && cross(hull[k - 2], hull[k - 1], points[i]) <= 0L) {
                k--;
            }
            hull[k++] = points[i];
        }
        int corners = k - 1;
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Pixel centres in half units, so everything stays exact in integers.
                long cx = 2L * x + 1L;
                long cy = 2L * y + 1L;
                boolean inside = corners >= 3;
                for (int i = 0; i < corners && inside; i++) {
                    long a = hull[i];
                    long b = hull[i + 1];
                    long ax = 2L * unpackX(a);
                    long ay = 2L * unpackY(a);
                    long bx = 2L * unpackX(b);
                    long by = 2L * unpackY(b);
                    inside = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0L;
                }
                out[y * w + x] = inside || region[y * w + x];
            }
        }
        return out;
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int unpackX(long point) {
        return (int) (point >> 32);
    }

    private static int unpackY(long point) {
        return (int) point;
    }

    private static long cross(long o, long a, long b) {
        long ox = unpackX(o);
        long oy = unpackY(o);
        return (unpackX(a) - ox) * (long) (unpackY(b) - oy) - (unpackY(a) - oy) * (long) (unpackX(b) - ox);
    }

    /** Bounding box {x0, y0, x1, y1} (inclusive) of a non-empty cell-local region. */
    private static int[] bounds(boolean[] region, int w, int h) {
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (region[y * w + x]) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return new int[] {x0, y0, x1, y1};
    }

    /** Moves a box {@code [start, start + size)} as little as possible to cover {@code [lo, hi]}, inside {@code [0, limit)}. */
    private static int place(int start, int size, int lo, int hi, int limit) {
        int s = start;
        if (hi - lo + 1 > size) {
            s = (lo + hi + 1) / 2 - size / 2;
        } else if (lo < s) {
            s = lo;
        } else if (hi > s + size - 1) {
            s = hi - size + 1;
        }
        return Math.max(0, Math.min(limit - size, s));
    }

    // ------------------------------------------------------------------ pixels

    /** What a pixel adds to the sky: its brightest channel times its alpha, 0..255. */
    public static float light(int pixel) {
        int r = pixel & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = (pixel >> 16) & 0xFF;
        int a = (pixel >>> 24) & 0xFF;
        return Math.max(r, Math.max(g, b)) * a / 255.0F;
    }

    /** Multiplies the colour channels (not alpha) of an ABGR pixel. */
    public static int scaleColor(int pixel, float factor) {
        int r = Math.min(255, Math.round((pixel & 0xFF) * factor));
        int g = Math.min(255, Math.round(((pixel >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round(((pixel >> 16) & 0xFF) * factor));
        return (pixel & 0xFF000000) | (b << 16) | (g << 8) | r;
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
}
