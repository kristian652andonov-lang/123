package dev.kristian.nullmod.moon;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the moon's damage lands on the moon: stand-in moon phase sheets in the layouts
 * resource packs use (vanilla's small moon in the middle of each cell, high-resolution round moons
 * with a glow, odd sizes, moons drawn off-centre) and the rules {@link MoonArt} must keep for every
 * phase and progress value. Plain Java (the GameTests and the offline sky preview both run it);
 * the sheets are test data only and never shipped.
 */
public final class MoonArtCheck {
    /** Progress values the checks draw: before the first crack, every breaking phase, shattered. */
    public static final float[] PROGRESS = {0.0F, 0.05F, 0.2F, 0.45F, 0.6F, 0.75F, 0.9F, 0.99F};
    private static final long[] SEEDS = {0L, 42L, -7_123_456_789L, 0x4E554C4C2D4E554CL};

    /**
     * A stand-in sheet: {@code cellW x cellH} cells, a moon {@code diameter} pixels across centred in
     * each cell (moved by {@code shiftX/shiftY} per cell), square pixel art like vanilla or round and
     * smooth like high-resolution packs.
     *
     * @param halo     a faint glow around the moon (vanilla has one)
     * @param opaque   opaque black background (vanilla) instead of transparent black
     * @param darkSide the unlit part of a phase drawn dim (vanilla) instead of left out
     */
    public record StandIn(String name, int cellW, int cellH, int diameter, boolean round, boolean halo, boolean opaque,
                          boolean darkSide, int[] shiftX, int[] shiftY) {
        public int width() {
            return cellW * MoonArt.COLUMNS;
        }

        public int height() {
            return cellH * MoonArt.ROWS;
        }

        /** Left edge of the moon in a cell (cell-local source pixels). */
        public int moonX(int cell) {
            return (cellW - diameter) / 2 + shiftX[cell];
        }

        public int moonY(int cell) {
            return (cellH - diameter) / 2 + shiftY[cell];
        }
    }

    private MoonArtCheck() {
    }

    /** The layouts every check runs on. */
    public static List<StandIn> layouts() {
        int[] none = new int[MoonArt.CELLS];
        List<StandIn> list = new ArrayList<>();
        // Vanilla's layout: 32 x 32 cells with an 8 x 8 moon in the middle, on transparent black.
        list.add(new StandIn("vanilla layout", 32, 32, 8, false, false, false, false, none, none));
        // The same as vanilla really draws it: opaque black, a dim halo, a dim unlit side.
        list.add(new StandIn("vanilla with halo", 32, 32, 8, false, true, true, true, none, none));
        // A high-resolution pack: round smooth moon with a soft glow.
        list.add(new StandIn("hd round", 128, 128, 58, true, true, false, false, none, none));
        // Odd sizes (not a multiple of four, non-square cells) with phases drawn off-centre.
        list.add(new StandIn("odd off-centre", 50, 48, 20, true, false, true, true,
                new int[] {0, 3, -4, 6, 0, -2, 5, -6}, new int[] {0, -3, 2, 5, -6, 4, 0, 1}));
        // A tiny pack: 16 x 16 cells, a 4 x 4 moon.
        list.add(new StandIn("tiny", 16, 16, 4, false, false, true, false, none, none));
        return list;
    }

    // ------------------------------------------------------------------ stand-in sheets

    /** Draws a stand-in sheet (ABGR pixels, row by row). */
    public static int[] sheet(StandIn spec) {
        int w = spec.width();
        int h = spec.height();
        int[] out = new int[w * h];
        int background = spec.opaque() ? 0xFF000000 : 0;
        java.util.Arrays.fill(out, background);
        float r = spec.diameter() * 0.5F;
        for (int cell = 0; cell < MoonArt.CELLS; cell++) {
            int ox = (cell % MoonArt.COLUMNS) * spec.cellW();
            int oy = (cell / MoonArt.COLUMNS) * spec.cellH();
            float cx = spec.moonX(cell) + r;
            float cy = spec.moonY(cell) + r;
            for (int y = 0; y < spec.cellH(); y++) {
                for (int x = 0; x < spec.cellW(); x++) {
                    float px = x + 0.5F;
                    float py = y + 0.5F;
                    float coverage = coverage(spec, px - cx, py - cy, r);
                    int index = (oy + y) * w + ox + x;
                    if (coverage <= 0.0F) {
                        if (spec.halo()) {
                            float edge = outside(spec, px - cx, py - cy, r);
                            float glow = edge <= 2.0F * Math.max(1.0F, r / 4.0F) ? 40.0F
                                    : edge <= 6.0F * Math.max(1.0F, r / 4.0F) ? 20.0F : 0.0F;
                            if (glow > 0.0F) {
                                out[index] = grey(Math.round(glow), spec.opaque() ? 255 : Math.round(glow * 4.0F));
                            }
                        }
                        continue;
                    }
                    float dx = (px - cx) / r;
                    float dy = (py - cy) / r;
                    boolean lit = litInPhase(cell, dx, dy);
                    int shade = lit ? surface(dx, dy) : (spec.darkSide() ? 22 + (int) (18 * hash(x * 31 + y)) : 0);
                    if (shade <= 0) {
                        continue;
                    }
                    int alpha = Math.round(255.0F * coverage);
                    if (spec.opaque()) {
                        out[index] = grey(Math.round(shade * coverage), 255);
                    } else {
                        out[index] = grey(shade, alpha);
                    }
                }
            }
        }
        return out;
    }

    /** 0..1 how much of the pixel the moon covers (round moons are anti-aliased, square ones are not). */
    private static float coverage(StandIn spec, float dx, float dy, float r) {
        if (!spec.round()) {
            return Math.abs(dx) < r && Math.abs(dy) < r ? 1.0F : 0.0F;
        }
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        return Math.max(0.0F, Math.min(1.0F, r - d + 0.5F));
    }

    /** Distance outside the moon's outline. */
    private static float outside(StandIn spec, float dx, float dy, float r) {
        if (!spec.round()) {
            float ex = Math.max(0.0F, Math.abs(dx) - r);
            float ey = Math.max(0.0F, Math.abs(dy) - r);
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
        return (float) Math.sqrt(dx * dx + dy * dy) - r;
    }

    /** Vanilla's order of phases: full, waning (lit part shrinking towards the left), new, waxing. */
    static boolean litInPhase(int cell, float dx, float dy) {
        float[] litFraction = {1.0F, 0.75F, 0.5F, 0.25F, 0.0F, 0.25F, 0.5F, 0.75F};
        float f = litFraction[cell];
        if (f >= 1.0F) {
            return true;
        }
        if (f <= 0.0F) {
            return false;
        }
        float edge = (2.0F * f - 1.0F) * (float) Math.sqrt(Math.max(0.0F, 1.0F - Math.min(1.0F, dy * dy)));
        return cell < 4 ? dx < edge : -dx < edge;
    }

    /** A pale grey surface with a few darker maria. */
    private static int surface(float dx, float dy) {
        float shade = 222.0F;
        float[][] maria = {{-0.35F, -0.2F, 0.32F}, {0.3F, 0.25F, 0.28F}, {0.05F, -0.5F, 0.2F}};
        for (float[] m : maria) {
            float ex = dx - m[0];
            float ey = dy - m[1];
            if (ex * ex + ey * ey < m[2] * m[2]) {
                shade = 168.0F;
            }
        }
        return (int) shade;
    }

    private static int grey(int value, int alpha) {
        int v = Math.max(0, Math.min(255, value));
        int b = Math.min(255, v + 10);
        return (Math.max(0, Math.min(255, alpha)) << 24) | (b << 16) | (v << 8) | v;
    }

    private static float hash(int n) {
        long h = n * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 40) * 0x1.0p-24F;
    }

    // ------------------------------------------------------------------ checks

    /** Runs every check on every layout with a few world seeds; returns the problems found (empty when all is well). */
    public static List<String> run() {
        return run(SEEDS);
    }

    /** Runs every check on every layout with the given world seeds. */
    public static List<String> run(long... seeds) {
        List<String> problems = new ArrayList<>();
        for (StandIn spec : layouts()) {
            int[] pixels = sheet(spec);
            for (long seed : seeds) {
                MoonArt art = new MoonArt(pixels, spec.width(), spec.height(), MoonGeometry.forSeed(seed));
                check(spec, pixels, art, problems);
                if (problems.size() > 20) {
                    return problems;
                }
            }
        }
        checkFit(problems);
        return problems;
    }

    /** The rules for one stand-in sheet and one moon. */
    public static void check(StandIn spec, int[] pixels, MoonArt art, List<String> problems) {
        String where = spec.name() + " (seed " + art.geometry().seed() + ")";
        if (!art.found()) {
            problems.add(where + ": the moon was not found");
            return;
        }
        // The disc is the moon: same size, same centre in every phase cell.
        float tolerance = spec.round() ? 1.0F : 0.01F;
        float sizeX = art.discFractionW() * spec.cellW();
        float sizeY = art.discFractionH() * spec.cellH();
        if (Math.abs(sizeX - spec.diameter()) > 2.0F * tolerance || Math.abs(sizeY - spec.diameter()) > 2.0F * tolerance) {
            problems.add(where + ": the disc is " + sizeX + " x " + sizeY + " pixels, the moon " + spec.diameter());
        }
        for (int phase = 0; phase < MoonArt.CELLS; phase++) {
            float centreX = art.cellFractionX(phase, 0.5F) * spec.cellW();
            float centreY = art.cellFractionY(phase, 0.5F) * spec.cellH();
            float expectX = spec.moonX(phase) + spec.diameter() * 0.5F;
            float expectY = spec.moonY(phase) + spec.diameter() * 0.5F;
            if (Math.abs(centreX - expectX) > tolerance || Math.abs(centreY - expectY) > tolerance) {
                problems.add(where + ": phase " + phase + " disc centre at " + centreX + ", " + centreY
                        + " but the moon's centre is " + expectX + ", " + expectY);
            }
        }
        int width = art.width();
        int height = art.height();
        int[] canvas = art.canvas();
        float unlit = art.threshold() * 0.5F;
        // The untouched sheet at working size, and which of its pixels are the moon.
        int[] expected = new int[width * height];
        boolean[] body = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                expected[y * width + x] = art.sourcePixel(x, y);
                body[y * width + x] = art.inBody(x, y);
            }
        }
        for (int round = 0; round < 2; round++) {
            for (float progress : MoonArtCheck.PROGRESS) {
                art.render(progress);
                int outside = 0;
                int unlitDarkened = 0;
                int litDarkened = 0;
                for (int i = 0; i < canvas.length; i++) {
                    int now = canvas[i];
                    int was = expected[i];
                    if (!body[i]) {
                        if (now != was) {
                            outside++;
                        }
                        continue;
                    }
                    if (now == was || (now >>> 24) == 0 || MoonArt.light(now) >= MoonArt.light(was) - 0.5F) {
                        continue;
                    }
                    if (MoonArt.light(was) <= unlit) {
                        unlitDarkened++;
                    } else if (MoonArt.light(now) < MoonArt.light(was) * 0.75F
                            && i % width < art.cellWidth() && i / width < art.cellHeight()) {
                        litDarkened++;
                    }
                }
                if (outside > 0) {
                    problems.add(where + ": " + outside + " pixels outside the moon changed at progress " + progress);
                }
                // Before the distortion nothing moves, so any darker pixel is a crack line.
                if (progress < MoonGeometry.DISTORTION && unlitDarkened > 0) {
                    problems.add(where + ": " + unlitDarkened + " crack pixels on the unlit side at progress " + progress);
                }
                if (progress >= 0.2F && progress < MoonGeometry.SHATTER && litDarkened == 0) {
                    problems.add(where + ": no crack is visible on the full moon at progress " + progress);
                }
            }
            if (round == 0) {
                art.renderGone();
                for (int value : canvas) {
                    if ((value >>> 24) != 0) {
                        problems.add(where + ": something is left of the moon after it is gone");
                        break;
                    }
                }
            }
        }
        if (art.render(0.5F)) {
            problems.add(where + ": redrawing after the last render changed more than the moon");
        }
        int[] glow = new int[art.cellWidth() * art.cellHeight()];
        for (int phase = 0; phase < MoonArt.CELLS; phase++) {
            art.renderGlow(null, 0.95F, phase, glow);
            int cellX = (phase % MoonArt.COLUMNS) * art.cellWidth();
            int cellY = (phase / MoonArt.COLUMNS) * art.cellHeight();
            for (int y = 0; y < art.cellHeight(); y++) {
                for (int x = 0; x < art.cellWidth(); x++) {
                    if ((glow[y * art.cellWidth() + x] >>> 24) == 0) {
                        continue;
                    }
                    if (!art.inBody(cellX + x, cellY + y) || MoonArt.light(art.sourcePixel(cellX + x, cellY + y)) <= unlit) {
                        problems.add(where + ": phase " + phase + " glow at " + x + ", " + y + " is not on the lit moon");
                        y = art.cellHeight();
                        break;
                    }
                }
            }
        }
        int[] pieces = art.bodyOnly();
        for (int y = 0; y < art.sheetHeight(); y++) {
            for (int x = 0; x < art.sheetWidth(); x++) {
                int value = pieces[y * art.sheetWidth() + x];
                if (art.inBodyTexel(x, y) ? value != pixels[y * spec.width() + x] : value != 0) {
                    problems.add(where + ": the fragment texture differs from the moon at " + x + ", " + y);
                    return;
                }
            }
        }
    }

    /** Sheets of any size keep the moon where the sky's UVs show it. */
    private static void checkFit(List<String> problems) {
        StandIn spec = new StandIn("fit", 33, 31, 9, false, false, true, false, new int[MoonArt.CELLS], new int[MoonArt.CELLS]);
        int[] pixels = sheet(spec);
        int[][] sizes = {{spec.width() + 2, spec.height() + 1}, {spec.width(), spec.height()}};
        for (int[] size : sizes) {
            // Pad the sheet on the right and at the bottom (nothing there), as an oddly sized pack would be.
            int w = size[0];
            int h = size[1];
            int[] padded = new int[w * h];
            for (int y = 0; y < spec.height(); y++) {
                System.arraycopy(pixels, y * spec.width(), padded, y * w, spec.width());
            }
            MoonArt.Sheet fitted = MoonArt.fit(padded, w, h, MoonArt.MAX_SHEET_WIDTH);
            if (fitted == null || fitted.width() % MoonArt.COLUMNS != 0 || fitted.height() % MoonArt.ROWS != 0) {
                problems.add("fit: " + w + "x" + h + " did not become a sheet of whole cells");
                continue;
            }
            for (int y = 0; y < fitted.height(); y++) {
                for (int x = 0; x < fitted.width(); x++) {
                    // Both must show the same texel for the same UV.
                    float u = (x + 0.5F) / fitted.width();
                    float v = (y + 0.5F) / fitted.height();
                    int sx = Math.min(w - 1, (int) (u * w));
                    int sy = Math.min(h - 1, (int) (v * h));
                    if (fitted.pixels()[y * fitted.width() + x] != padded[sy * w + sx]) {
                        problems.add("fit: " + w + "x" + h + " moved texel " + x + ", " + y);
                        return;
                    }
                }
            }
        }
        MoonArt.Sheet big = MoonArt.fit(new int[4096 * 2048], 4096, 2048, MoonArt.MAX_SHEET_WIDTH);
        if (big == null || big.width() != MoonArt.MAX_SHEET_WIDTH || big.height() != MoonArt.MAX_SHEET_WIDTH / 2) {
            problems.add("fit: a 4096x2048 sheet was not scaled down to " + MoonArt.MAX_SHEET_WIDTH + " wide");
        }
        if (MoonArt.fit(new int[6], 3, 2, MoonArt.MAX_SHEET_WIDTH) != null) {
            problems.add("fit: accepted a sheet too small for the phases");
        }
    }
}
