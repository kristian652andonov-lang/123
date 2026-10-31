package dev.kristian.nullmod.client.variant;

/**
 * The pixel work behind corrupted and infested mobs. Given a mob's own texture (ARGB, row
 * major) it builds an overlay of the same size that is transparent everywhere except where the
 * texture is damaged, so the renderer can draw it on the same model over the untouched mob.
 * <p>
 * Only integer arithmetic and one fixed pseudo-random sequence (SplitMix64), so
 * {@code tools/gen_variants.py} reproduces every overlay pixel for pixel offline. No Minecraft
 * classes: it runs on worker threads.
 */
final class VariantPixels {
    /** Rectangles of the texture copied from somewhere else, with their colour channels mixed up. */
    static final int STYLE_GLITCH = 0;
    /** Holes of pure black, as if part of the mob had not loaded. */
    static final int STYLE_VOID = 1;
    /** Patches of the purple-and-black missing texture. */
    static final int STYLE_MISSING = 2;
    /** Inverted, negative patches. */
    static final int STYLE_NEGATIVE = 3;
    /** Rows and columns smeared out from one pixel, like corrupted video memory. */
    static final int STYLE_SMEAR = 4;
    /** Large black areas full of white pinprick eyes. */
    static final int STYLE_EYES = 5;
    static final int STYLES = 6;

    /** How far an infestation has spread (veins, sores, sickness). */
    static final int INFEST_STAGES = 3;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MISSING_MAGENTA = 0xFFF800F8;
    private static final int VOID_EDGE = 0xFF0C0A0E;
    private static final int EYE_WHITE = 0xFFE8E6E2;
    private static final int EYE_HALO = 0xFF3C3C3C;
    private static final int VEIN_CORE = 0xFF0E050C;
    private static final int VEIN_FRINGE = 0xFF400A16;
    private static final int WOUND_CORE = 0xFF050002;
    private static final int WOUND_RING = 0xFF3A0610;
    private static final int SORE_CORE = 0xFF1A0306;
    private static final int SORE_RING = 0xFF6E0E16;
    private static final int PUS = 0xFF8C7A3C;

    /** Sixteen walking directions, 1/16 pixel units (rounded cos/sin of i * 22.5 degrees). */
    private static final int[] DIR_X = {16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6, 0, 6, 11, 15};
    private static final int[] DIR_Y = {0, 6, 11, 15, 16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6};
    private static final int MAX_BRANCHES = 256;

    private VariantPixels() {
    }

    /** A corrupted overlay and, for the eyes style, the emissive pixels (null otherwise). */
    record Corrupted(int[] overlay, int[] glow) {
    }

    /**
     * The corrupted look of a texture: a primary style at full strength, a secondary one (or -1)
     * at less than half, and scattered dead pixels.
     */
    static Corrupted corrupt(int[] src, int width, int height, int primary, int secondary, long seed) {
        return corrupt(src, width, height, primary, secondary, seed, false);
    }

    /**
     * As above; {@code heavy} (IT) breaks it as hard as it gets: the secondary style at full
     * strength too, and twice the dead pixels. {@code tools/gen_variants.py} mirrors only the
     * ordinary look.
     */
    static Corrupted corrupt(int[] src, int width, int height, int primary, int secondary, long seed, boolean heavy) {
        Canvas c = new Canvas(src, width, height);
        if (c.visibleCount == 0) {
            return new Corrupted(c.out, null);
        }
        Rand r = new Rand(seed ^ 0x4E554C4C56415249L);
        int[] glow = apply(c, r, primary, 100);
        if (secondary >= 0 && secondary != primary) {
            int[] more = apply(c, r, secondary, heavy ? 100 : 40);
            if (glow == null) {
                glow = more;
            } else if (more != null) {
                for (int i = 0; i < glow.length; i++) {
                    if (glow[i] == 0) {
                        glow[i] = more[i];
                    }
                }
            }
        }
        deadPixels(c, r);
        if (heavy) {
            deadPixels(c, r);
        }
        return new Corrupted(c.out, glow);
    }

    /**
     * The infested look of a texture: the host turns sick and dark, black veins spread from the
     * points where tentacles leave the body (pixel coordinates, x and y pairs), with wounds there
     * and sores around them; later stages spread further.
     */
    static int[] infest(int[] src, int width, int height, int[] anchors, int anchorCount, int stage, long seed) {
        Canvas c = new Canvas(src, width, height);
        if (c.visibleCount == 0) {
            return c.out;
        }
        Rand r = new Rand(seed ^ 0x494E464553544544L);
        int k = c.k;
        int st = clamp(stage, 0, INFEST_STAGES - 1);
        int reach = st == 0 ? 45 : st == 1 ? 75 : 100;

        // Where the veins start: the tentacles' roots first, then a few random spots.
        int[] sx = new int[16];
        int[] sy = new int[16];
        int seeds = 0;
        int anchorSeeds = 0;
        int count = Math.min(Math.max(anchorCount, 0), anchors == null ? 0 : anchors.length / 2);
        for (int a = 0; a < count && seeds < 10; a++) {
            int ax = anchors[a * 2];
            int ay = anchors[a * 2 + 1];
            for (int attempt = 0; attempt < 9; attempt++) {
                int x = attempt == 0 ? ax : ax + r.range(-3 * k, 3 * k);
                int y = attempt == 0 ? ay : ay + r.range(-3 * k, 3 * k);
                if (c.visible(x, y)) {
                    sx[seeds] = x;
                    sy[seeds] = y;
                    seeds++;
                    break;
                }
            }
        }
        anchorSeeds = seeds;
        int extra = c.counts + (seeds < 2 ? 2 - seeds : 0);
        for (int e = 0; e < extra && seeds < sx.length; e++) {
            int p = c.pick(r);
            sx[seeds] = p % c.w;
            sy[seeds] = p / c.w;
            seeds++;
        }

        // The whole host turns dull and sick once the infestation has taken hold.
        if (st >= 1) {
            int keep = 230 - 22 * st;
            for (int i = 0; i < c.out.length; i++) {
                int s = c.src[i];
                if ((s >>> 24) < 16) {
                    continue;
                }
                int red = (s >> 16) & 255;
                int green = (s >> 8) & 255;
                int blue = s & 255;
                int luma = (red * 77 + green * 150 + blue * 29) >> 8;
                red = ((red * 3 + luma) >> 2) * keep >> 8;
                green = ((green * 3 + luma) >> 2) * (keep - 14) >> 8;
                blue = ((blue * 3 + luma) >> 2) * (keep - 6) >> 8;
                c.out[i] = rgb(red, green, blue);
            }
        }

        // Dark, bruised patches around every seed.
        for (int i = 0; i < seeds; i++) {
            int radius = 3 * k + 4 * k * reach / 100 + r.range(0, 2 * k);
            bruise(c, sx[i], sy[i], radius);
        }
        int spots = st + 1;
        for (int i = 0; i < spots; i++) {
            int p = c.pick(r);
            bruise(c, p % c.w, p / c.w, 2 * k + r.range(0, 2 * k) * reach / 100);
        }

        // Veins: random walks with momentum that branch now and then.
        boolean[] core = new boolean[c.out.length];
        int[] bx = new int[MAX_BRANCHES];
        int[] by = new int[MAX_BRANCHES];
        int[] bd = new int[MAX_BRANCHES];
        int[] bs = new int[MAX_BRANCHES];
        int[] bl = new int[MAX_BRANCHES];
        int top = 0;
        for (int i = 0; i < seeds; i++) {
            int branches = 3 + r.nextInt(3) + (st == 2 ? 1 : 0);
            for (int b = 0; b < branches && top < MAX_BRANCHES; b++) {
                bx[top] = sx[i] * 16 + 8;
                by[top] = sy[i] * 16 + 8;
                bd[top] = r.nextInt(16);
                bs[top] = (8 * k + r.nextInt(16 * k)) * reach / 100;
                bl[top] = 0;
                top++;
            }
        }
        while (top > 0) {
            top--;
            int px = bx[top];
            int py = by[top];
            int d = bd[top];
            int steps = bs[top];
            int depth = bl[top];
            for (int s = 0; s < steps; s++) {
                if (r.nextInt(100) < 35) {
                    d = (d + r.range(-1, 1)) & 15;
                }
                px += DIR_X[d];
                py += DIR_Y[d];
                int x = px >> 4;
                int y = py >> 4;
                if (!c.visible(x, y)) {
                    break;
                }
                for (int oy = 0; oy < k; oy++) {
                    for (int ox = 0; ox < k; ox++) {
                        if (c.visible(x + ox, y + oy)) {
                            int idx = (y + oy) * c.w + x + ox;
                            c.out[idx] = VEIN_CORE;
                            core[idx] = true;
                        }
                    }
                }
                if (depth < 2 && top < MAX_BRANCHES && r.nextInt(100) < 7) {
                    bx[top] = px;
                    by[top] = py;
                    bd[top] = (d + (r.nextInt(2) == 0 ? 3 : -3)) & 15;
                    bs[top] = (steps - s) / 2;
                    bl[top] = depth + 1;
                    top++;
                }
            }
        }
        // A dark red fringe along every vein.
        for (int y = 0; y < c.h; y++) {
            for (int x = 0; x < c.w; x++) {
                int idx = y * c.w + x;
                if (core[idx] || !c.visible(x, y)) {
                    continue;
                }
                boolean near = (x > 0 && core[idx - 1]) || (x + 1 < c.w && core[idx + 1])
                        || (y > 0 && core[idx - c.w]) || (y + 1 < c.h && core[idx + c.w]);
                if (near) {
                    int base = c.out[idx] != 0 ? c.out[idx] : c.src[idx];
                    c.out[idx] = mix(base, VEIN_FRINGE, 128);
                }
            }
        }

        // Torn wounds where the tentacles come out.
        int woundRadius = k + (k * st) / 2;
        for (int i = 0; i < anchorSeeds; i++) {
            int r2 = woundRadius * woundRadius;
            int inner = (woundRadius * woundRadius) / 4;
            for (int dy = -woundRadius; dy <= woundRadius; dy++) {
                for (int dx = -woundRadius; dx <= woundRadius; dx++) {
                    int d2 = dx * dx + dy * dy;
                    if (d2 <= r2) {
                        c.put(sx[i] + dx, sy[i] + dy, d2 <= inner ? WOUND_CORE : WOUND_RING);
                    }
                }
            }
        }

        // Sores around the seeds.
        if (st >= 1) {
            int sores = (1 + st) * Math.max(1, seeds / 2) + r.nextInt(2);
            for (int i = 0; i < sores; i++) {
                int s = r.nextInt(seeds);
                int x = sx[s] + r.range(-6 * k, 6 * k);
                int y = sy[s] + r.range(-6 * k, 6 * k);
                if (!c.visible(x, y)) {
                    continue;
                }
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) {
                            c.block(x, y, SORE_CORE);
                        } else if (ox == 0 || oy == 0) {
                            c.block(x + ox * k, y + oy * k, SORE_RING);
                        } else {
                            // Ragged corners: pus, torn flesh, or nothing.
                            int roll = r.nextInt(10);
                            if (roll < 4) {
                                c.block(x + ox * k, y + oy * k, PUS);
                            } else if (roll < 7) {
                                c.block(x + ox * k, y + oy * k, WOUND_RING);
                            }
                        }
                    }
                }
            }
        }
        return c.out;
    }

    // ------------------------------------------------------------------ corrupted styles

    private static int[] apply(Canvas c, Rand r, int style, int amount) {
        switch (style) {
            case STYLE_GLITCH -> glitch(c, r, amount);
            case STYLE_VOID -> voids(c, r, amount);
            case STYLE_MISSING -> missing(c, r, amount);
            case STYLE_NEGATIVE -> negative(c, r, amount);
            case STYLE_SMEAR -> smear(c, r, amount);
            case STYLE_EYES -> {
                return eyes(c, r, amount);
            }
            default -> {
            }
        }
        return null;
    }

    private static void glitch(Canvas c, Rand r, int amount) {
        int k = c.k;
        int blocks = count(c, 5, 7, amount);
        for (int b = 0; b < blocks; b++) {
            int p = c.pick(r);
            int cx = p % c.w;
            int cy = p / c.w;
            int bw = r.range(2 * k, 9 * k);
            int bh = r.range(k, 4 * k);
            int sign = r.nextInt(2) == 0 ? -1 : 1;
            int dx = sign * r.range(3 * k, 18 * k);
            int dy = r.range(-4 * k, 4 * k);
            int mode = r.nextInt(6);
            int x0 = cx - bw / 2;
            int y0 = cy - bh / 2;
            for (int y = y0; y < y0 + bh; y++) {
                for (int x = x0; x < x0 + bw; x++) {
                    if (!c.visible(x, y)) {
                        continue;
                    }
                    int sxp = Math.floorMod(x + dx, c.w);
                    int syp = clamp(y + dy, 0, c.h - 1);
                    int s = c.src[syp * c.w + sxp];
                    if ((s >>> 24) < 16) {
                        s = c.src[y * c.w + x];
                    }
                    c.put(x, y, transform(c, mode, s, x, y));
                }
            }
        }
        int tears = count(c, 1, 2, amount);
        for (int t = 0; t < tears; t++) {
            int p = c.pick(r);
            int x0 = p % c.w;
            int y0 = p / c.w;
            int len = r.range(8 * k, 28 * k);
            int shift = (r.nextInt(2) == 0 ? -1 : 1) * r.range(2 * k, 7 * k);
            int rows = r.range(1, k);
            for (int y = y0; y < y0 + rows; y++) {
                for (int x = x0; x < x0 + len; x++) {
                    if (!c.visible(x, y)) {
                        continue;
                    }
                    int s = c.src[y * c.w + Math.floorMod(x - shift, c.w)];
                    if ((s >>> 24) < 16) {
                        s = c.src[y * c.w + x];
                    }
                    c.put(x, y, s);
                }
            }
        }
    }

    private static int transform(Canvas c, int mode, int s, int x, int y) {
        int red = (s >> 16) & 255;
        int green = (s >> 8) & 255;
        int blue = s & 255;
        return switch (mode) {
            case 0 -> rgb(blue, green, red);
            case 1 -> rgb(red, blue, green);
            case 2 -> rgb(Math.min(255, red * 3 / 2 + 40), green / 3, blue / 3);
            case 3 -> rgb(red / 4, Math.min(255, green + 30), Math.min(255, blue * 5 / 4 + 30));
            case 4 -> rgb(red & 0xC0, green & 0xC0, blue & 0xC0);
            default -> {
                int left = c.srcOr(x - 2 * c.k, y, s);
                int right = c.srcOr(x + 2 * c.k, y, s);
                yield rgb((left >> 16) & 255, green, right & 255);
            }
        };
    }

    private static void voids(Canvas c, Rand r, int amount) {
        int k = c.k;
        int n = count(c, 2, 3, amount);
        for (int i = 0; i < n; i++) {
            int p = c.pick(r);
            int cx = p % c.w;
            int cy = p / c.w;
            int rects = r.range(2, 3);
            for (int q = 0; q < rects; q++) {
                int rw = r.range(2 * k, 8 * k);
                int rh = r.range(2 * k, 6 * k);
                int ox = cx + r.range(-3 * k, 3 * k) - rw / 2;
                int oy = cy + r.range(-3 * k, 3 * k) - rh / 2;
                for (int y = oy; y < oy + rh; y++) {
                    for (int x = ox; x < ox + rw; x++) {
                        if (!c.visible(x, y)) {
                            continue;
                        }
                        boolean edge = x == ox || y == oy || x == ox + rw - 1 || y == oy + rh - 1;
                        if (edge && c.out[y * c.w + x] != BLACK && r.nextInt(3) == 0) {
                            c.put(x, y, VOID_EDGE);
                        } else {
                            c.put(x, y, BLACK);
                        }
                    }
                }
            }
        }
    }

    private static void missing(Canvas c, Rand r, int amount) {
        int k = c.k;
        int n = count(c, 2, 3, amount);
        int cell = k * (1 + r.nextInt(2));
        for (int i = 0; i < n; i++) {
            int p = c.pick(r);
            int cx = p % c.w;
            int cy = p / c.w;
            int bw = r.range(4 * k, 10 * k);
            int bh = r.range(3 * k, 8 * k);
            int ox = Math.floorDiv(cx - bw / 2, cell) * cell;
            int oy = Math.floorDiv(cy - bh / 2, cell) * cell;
            for (int y = oy; y < oy + bh; y++) {
                for (int x = ox; x < ox + bw; x++) {
                    if (c.visible(x, y)) {
                        c.put(x, y, ((x / cell + y / cell) & 1) == 0 ? MISSING_MAGENTA : BLACK);
                    }
                }
            }
        }
    }

    private static void negative(Canvas c, Rand r, int amount) {
        int k = c.k;
        int n = count(c, 2, 3, amount);
        for (int i = 0; i < n; i++) {
            int p = c.pick(r);
            int cx = p % c.w;
            int cy = p / c.w;
            int bw = r.range(3 * k, 10 * k);
            int bh = r.range(2 * k, 7 * k);
            boolean rotate = r.nextInt(2) == 0;
            int ox = cx - bw / 2;
            int oy = cy - bh / 2;
            for (int y = oy; y < oy + bh; y++) {
                for (int x = ox; x < ox + bw; x++) {
                    if (!c.visible(x, y)) {
                        continue;
                    }
                    int s = c.src[y * c.w + x];
                    int red = 255 - ((s >> 16) & 255);
                    int green = 255 - ((s >> 8) & 255);
                    int blue = 255 - (s & 255);
                    c.put(x, y, rotate ? rgb(green, blue, red) : rgb(red, green, blue));
                }
            }
        }
    }

    private static void smear(Canvas c, Rand r, int amount) {
        int k = c.k;
        int rows = count(c, 3, 5, amount);
        for (int i = 0; i < rows; i++) {
            int p = c.pick(r);
            int x0 = p % c.w;
            int y0 = p / c.w;
            int band = r.range(1, 2 * k);
            int len = r.range(6 * k, 24 * k);
            int dir = r.nextInt(2) == 0 ? -1 : 1;
            for (int y = y0; y < y0 + band; y++) {
                if (!c.visible(x0, y)) {
                    continue;
                }
                int color = c.src[y * c.w + x0];
                for (int s = 1; s <= len; s++) {
                    int x = x0 + dir * s;
                    if (c.visible(x, y)) {
                        c.put(x, y, color);
                    }
                }
            }
        }
        int cols = count(c, 1, 2, amount);
        for (int i = 0; i < cols; i++) {
            int p = c.pick(r);
            int x0 = p % c.w;
            int y0 = p / c.w;
            int band = r.range(1, k);
            int len = r.range(4 * k, 14 * k);
            for (int x = x0; x < x0 + band; x++) {
                if (!c.visible(x, y0)) {
                    continue;
                }
                int color = c.src[y0 * c.w + x];
                for (int s = 1; s <= len; s++) {
                    if (c.visible(x, y0 + s)) {
                        c.put(x, y0 + s, color);
                    }
                }
            }
        }
    }

    private static int[] eyes(Canvas c, Rand r, int amount) {
        int k = c.k;
        boolean[] dark = new boolean[c.out.length];
        int blobs = count(c, 3, 3, amount);
        for (int i = 0; i < blobs; i++) {
            int p = c.pick(r);
            int cx = p % c.w;
            int cy = p / c.w;
            int rects = r.range(2, 3);
            for (int q = 0; q < rects; q++) {
                int rw = r.range(4 * k, 12 * k);
                int rh = r.range(3 * k, 9 * k);
                int ox = cx + r.range(-4 * k, 4 * k) - rw / 2;
                int oy = cy + r.range(-3 * k, 3 * k) - rh / 2;
                for (int y = oy; y < oy + rh; y++) {
                    for (int x = ox; x < ox + rw; x++) {
                        if (c.visible(x, y)) {
                            c.put(x, y, BLACK);
                            dark[y * c.w + x] = true;
                        }
                    }
                }
            }
        }
        int[] glow = new int[c.out.length];
        int wanted = count(c, 3, 5, amount);
        int attempts = wanted * 8;
        int placed = 0;
        while (placed < wanted && attempts-- > 0) {
            int p = c.pick(r);
            int x = p % c.w;
            int y = p / c.w;
            int gap = r.range(2 * k, 3 * k) + k;
            if (!darkBlock(c, dark, x, y) || !darkBlock(c, dark, x + gap, y)) {
                continue;
            }
            boolean single = r.nextInt(10) == 0;
            eye(c, glow, x, y);
            if (!single) {
                eye(c, glow, x + gap, y);
            }
            placed++;
        }
        return glow;
    }

    private static boolean darkBlock(Canvas c, boolean[] dark, int x, int y) {
        for (int oy = 0; oy < c.k; oy++) {
            for (int ox = 0; ox < c.k; ox++) {
                int px = x + ox;
                int py = y + oy;
                if (px < 0 || py < 0 || px >= c.w || py >= c.h || !dark[py * c.w + px]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void eye(Canvas c, int[] glow, int x, int y) {
        int k = c.k;
        for (int oy = 0; oy < k; oy++) {
            for (int ox = 0; ox < k; ox++) {
                c.put(x + ox, y + oy, EYE_WHITE);
                glow[(y + oy) * c.w + x + ox] = WHITE;
            }
        }
        for (int i = 0; i < k; i++) {
            halo(c, glow, x - 1, y + i);
            halo(c, glow, x + k, y + i);
            halo(c, glow, x + i, y - 1);
            halo(c, glow, x + i, y + k);
        }
    }

    private static void halo(Canvas c, int[] glow, int x, int y) {
        if (x >= 0 && y >= 0 && x < c.w && y < c.h && glow[y * c.w + x] == 0) {
            glow[y * c.w + x] = EYE_HALO;
        }
    }

    /** Single dead texels: mostly black, some white or missing-texture magenta. */
    private static void deadPixels(Canvas c, Rand r) {
        int n = count(c, 4, 6, 100);
        for (int i = 0; i < n; i++) {
            int p = c.pick(r);
            int kind = r.nextInt(10);
            int color = kind < 5 ? BLACK : kind < 7 ? WHITE : MISSING_MAGENTA;
            c.block(p % c.w, p / c.w, color);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void bruise(Canvas c, int cx, int cy, int radius) {
        int r2 = Math.max(1, radius * radius);
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int d2 = dx * dx + dy * dy;
                int x = cx + dx;
                int y = cy + dy;
                if (d2 > r2 || !c.visible(x, y)) {
                    continue;
                }
                int idx = y * c.w + x;
                int s = c.out[idx] != 0 ? c.out[idx] : c.src[idx];
                int f = 100 + 60 * d2 / r2;
                int red = ((s >> 16) & 255) * f >> 8;
                int green = (((s >> 8) & 255) * f * 13) >> 12;
                int blue = ((s & 255) * f * 14) >> 12;
                c.out[idx] = rgb(red, green, blue);
            }
        }
    }

    /** {@code base + per * amount / 100} scaled by the texture's size, at least one. */
    private static int count(Canvas c, int base, int per, int amount) {
        return Math.max(1, (base * 100 + per * amount) * c.counts / 100);
    }

    private static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (clamp(red, 0, 255) << 16) | (clamp(green, 0, 255) << 8) | clamp(blue, 0, 255);
    }

    /** {@code a} blended toward {@code b} by {@code weight}/256. */
    private static int mix(int a, int b, int weight) {
        int inv = 256 - weight;
        int red = (((a >> 16) & 255) * inv + ((b >> 16) & 255) * weight) >> 8;
        int green = (((a >> 8) & 255) * inv + ((b >> 8) & 255) * weight) >> 8;
        int blue = ((a & 255) * inv + (b & 255) * weight) >> 8;
        return rgb(red, green, blue);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /** The texture being damaged and the overlay being built. */
    private static final class Canvas {
        final int[] src;
        final int[] out;
        final int w;
        final int h;
        /** Pixels per vanilla texel: 1 for 64-wide textures, 2 for 128-wide ones... */
        final int k;
        /** How many times more patches than on a 64x32 texture. */
        final int counts;
        final int[] visibleIndex;
        final int visibleCount;

        Canvas(int[] src, int w, int h) {
            this.src = src;
            this.w = w;
            this.h = h;
            this.out = new int[w * h];
            this.k = Math.max(1, w / 64);
            this.counts = Math.max(1, (w * h) / (2048 * k * k));
            int[] index = new int[w * h];
            int n = 0;
            for (int i = 0; i < w * h; i++) {
                if ((src[i] >>> 24) >= 16) {
                    index[n++] = i;
                }
            }
            this.visibleIndex = index;
            this.visibleCount = n;
        }

        boolean visible(int x, int y) {
            return x >= 0 && y >= 0 && x < w && y < h && (src[y * w + x] >>> 24) >= 16;
        }

        int pick(Rand r) {
            return visibleIndex[r.nextInt(visibleCount)];
        }

        int srcOr(int x, int y, int fallback) {
            return visible(x, y) ? src[y * w + x] : fallback;
        }

        void put(int x, int y, int argb) {
            if (visible(x, y)) {
                out[y * w + x] = argb | 0xFF000000;
            }
        }

        /** A k-by-k block (one vanilla texel) starting at x, y. */
        void block(int x, int y, int argb) {
            for (int oy = 0; oy < k; oy++) {
                for (int ox = 0; ox < k; ox++) {
                    put(x + ox, y + oy, argb);
                }
            }
        }
    }

    /** SplitMix64: tiny, fast and easy to reproduce exactly in Python. */
    static final class Rand {
        private long state;

        Rand(long seed) {
            this.state = seed;
        }

        long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** 0 (inclusive) to bound (exclusive). */
        int nextInt(int bound) {
            return bound <= 1 ? 0 : (int) ((nextLong() >>> 33) % bound);
        }

        /** lo to hi, both inclusive. */
        int range(int lo, int hi) {
            return hi <= lo ? lo : lo + nextInt(hi - lo + 1);
        }
    }
}
