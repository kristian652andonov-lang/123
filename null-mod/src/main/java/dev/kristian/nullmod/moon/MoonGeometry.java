package dev.kristian.nullmod.moon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the moon breaks apart, as a pure function of the world seed. The server uses it to know
 * when the next crack or piece is due (so events and milestones line up with what players
 * see) and the client uses the very same data to draw cracks, holes and drifting fragments.
 * <p>
 * Everything lives in <em>disc space</em>: the moon's lit disc mapped onto the unit square,
 * {@code (0,0)} at the top left like the texture, with the disc itself being the circle of
 * radius 0.5 around {@code (0.5, 0.5)}. The moon is cut into a Voronoi pattern around an
 * impact point (a natural shatter pattern). Shared shard borders become the cracks, so a piece
 * that breaks away is always outlined by cracks first.
 * <p>
 * The class only uses {@link StrictMath} and its own random generator, so the result is
 * bit-for-bit identical on every machine: server and clients always agree.
 */
public final class MoonGeometry {
    /** Progress at which the first hairline crack appears. */
    public static final float FIRST_CRACK = 0.02F;
    /** Small pieces start breaking away. */
    public static final float PIECES = 0.40F;
    /** Larger sections disappear. */
    public static final float SECTIONS = 0.55F;
    /** The moon becomes increasingly distorted. */
    public static final float DISTORTION = 0.70F;
    /** The moon is shattered into drifting fragments. */
    public static final float SHATTER = 0.85F;
    /** Threshold value for things that never happen before the final disappearance. */
    public static final float NEVER = 2.0F;

    private static final long SALT = 0x4E554C4C4D4F4F4EL;
    private static final double VISIBLE_RADIUS = 0.485;
    /** Shards with less of the round disc than this never break on their own (see {@link Shard#leader()}). */
    private static final float MIN_PIECE_AREA = 0.0015F;

    private static MoonGeometry cached;

    private final long seed;
    private final float impactX;
    private final float impactY;
    private final Shard[] shards;
    private final Crack[] cracks;
    private final int[] majorCracks;
    private final int[] holeOrder;

    /** One piece of the moon. */
    public static final class Shard {
        private final int index;
        private final float[] x;
        private final float[] y;
        private final float centroidX;
        private final float centroidY;
        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;
        private final float visibleArea;
        private float breakAt = NEVER;
        private int leader = -1;
        private final float driftX;
        private final float driftY;
        private final float driftDistance;
        private final float spin;
        private final float wobble;
        private final float phase;
        private final float depth;

        Shard(int index, float[] x, float[] y, float visibleArea, Rng rng, float impactX, float impactY) {
            this.index = index;
            this.x = x;
            this.y = y;
            float[] c = centroid(x, y);
            this.centroidX = c[0];
            this.centroidY = c[1];
            float x0 = Float.MAX_VALUE;
            float y0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE;
            float y1 = -Float.MAX_VALUE;
            for (int i = 0; i < x.length; i++) {
                x0 = Math.min(x0, x[i]);
                y0 = Math.min(y0, y[i]);
                x1 = Math.max(x1, x[i]);
                y1 = Math.max(y1, y[i]);
            }
            this.minX = x0;
            this.minY = y0;
            this.maxX = x1;
            this.maxY = y1;
            this.visibleArea = visibleArea;
            double dx = centroidX - impactX;
            double dy = centroidY - impactY;
            double len = StrictMath.sqrt(dx * dx + dy * dy);
            double angle = len < 1.0E-4 ? rng.next() * StrictMath.PI * 2.0 : StrictMath.atan2(dy, dx);
            angle += (rng.next() - 0.5) * 1.1;
            this.driftX = (float) StrictMath.cos(angle);
            this.driftY = (float) StrictMath.sin(angle);
            this.driftDistance = (float) (0.55 + rng.next() * 0.95);
            this.spin = (float) ((rng.next() - 0.5) * 2.0);
            this.wobble = (float) (0.015 + rng.next() * 0.045);
            this.phase = (float) (rng.next() * StrictMath.PI * 2.0);
            this.depth = (float) rng.next();
        }

        public int index() {
            return index;
        }

        /** Outline in disc space (jagged, shared exactly with the neighbouring shards). */
        public float[] x() {
            return x;
        }

        public float[] y() {
            return y;
        }

        public int size() {
            return x.length;
        }

        public float centroidX() {
            return centroidX;
        }

        public float centroidY() {
            return centroidY;
        }

        public float minX() {
            return minX;
        }

        public float minY() {
            return minY;
        }

        public float maxX() {
            return maxX;
        }

        public float maxY() {
            return maxY;
        }

        /** Approximate area of the part that lies on the visible disc. */
        public float visibleArea() {
            return visibleArea;
        }

        /** Progress at which this shard breaks away, or {@link #NEVER}. */
        public float breakAt() {
            return breakAt;
        }

        public boolean brokenAt(float progress) {
            return progress >= breakAt;
        }

        /**
         * The piece this shard belongs to, or -1 if it is a piece of its own. A shard that hardly
         * touches the round disc (a corner of the square around it, which a moon drawn as a square
         * shows) is part of the neighbouring piece it shares the longest border with: it breaks away
         * at the same moment and moves with it. Such shards are never holes of their own.
         */
        public int leader() {
            return leader;
        }

        /** Unit direction the fragment drifts in once it broke away. */
        public float driftX() {
            return driftX;
        }

        public float driftY() {
            return driftY;
        }

        /** How far the fragment ends up from where it was, in disc diameters. */
        public float driftDistance() {
            return driftDistance;
        }

        /** Signed rotation speed, -1..1. */
        public float spin() {
            return spin;
        }

        /** Amplitude of the slow floating movement, in disc diameters. */
        public float wobble() {
            return wobble;
        }

        public float phase() {
            return phase;
        }

        /** 0..1, used for small size and brightness differences between fragments. */
        public float depth() {
            return depth;
        }

        public boolean contains(float px, float py) {
            if (px < minX || px > maxX || py < minY || py > maxY) {
                return false;
            }
            return insidePolygon(x, y, px, py);
        }
    }

    /** A crack: a polyline that grows from its first point once its time has come. */
    public static final class Crack {
        private int index;
        private final float[] x;
        private final float[] y;
        private final float[] cumulative;
        private final float length;
        private float appearAt;
        private float fullAt;
        private final boolean major;
        private final float width;
        private final int shardA;
        private final int shardB;

        Crack(float[] x, float[] y, float appearAt, float fullAt, boolean major, float width, int shardA, int shardB) {
            this.x = x;
            this.y = y;
            this.cumulative = new float[x.length];
            float total = 0.0F;
            for (int i = 1; i < x.length; i++) {
                float dx = x[i] - x[i - 1];
                float dy = y[i] - y[i - 1];
                total += (float) StrictMath.sqrt(dx * dx + dy * dy);
                cumulative[i] = total;
            }
            this.length = total;
            this.appearAt = appearAt;
            this.fullAt = Math.max(appearAt + 0.001F, fullAt);
            this.major = major;
            this.width = width;
            this.shardA = shardA;
            this.shardB = shardB;
        }

        public int index() {
            return index;
        }

        public float[] x() {
            return x;
        }

        public float[] y() {
            return y;
        }

        /** Distance along the polyline at each point. */
        public float[] cumulative() {
            return cumulative;
        }

        public float length() {
            return length;
        }

        public float appearAt() {
            return appearAt;
        }

        public float fullAt() {
            return fullAt;
        }

        /** Major cracks are shard borders and the first impact cracks; minor ones are side branches. */
        public boolean major() {
            return major;
        }

        /** Relative width, about 0.5..1.2. */
        public float width() {
            return width;
        }

        /** The shards on both sides, or -1 for cracks that are not a shard border. */
        public int shardA() {
            return shardA;
        }

        public int shardB() {
            return shardB;
        }

        /** 0..1: how much of the crack exists at a progress value. */
        public float growth(float progress) {
            if (progress < appearAt) {
                return 0.0F;
            }
            if (progress >= fullAt) {
                return 1.0F;
            }
            return (progress - appearAt) / (fullAt - appearAt);
        }
    }

    private MoonGeometry(long seed) {
        this.seed = seed;
        Rng rng = new Rng(seed ^ SALT);

        double impactAngle = rng.next() * StrictMath.PI * 2.0;
        // Close to the centre, so the cracks visibly spread from the middle of the moon.
        double impactRadius = 0.03 + rng.next() * 0.09;
        double ix = 0.5 + impactRadius * StrictMath.cos(impactAngle);
        double iy = 0.5 + impactRadius * StrictMath.sin(impactAngle);
        this.impactX = (float) ix;
        this.impactY = (float) iy;

        // ---- Voronoi sites: rings around the impact point, so the borders form the radial and
        // concentric cracks of a real impact.
        List<double[]> sites = new ArrayList<>();
        int[] counts = {3, 6, 9, 12, 15};
        double[] radii = {0.10, 0.24, 0.40, 0.60, 0.82};
        for (int ring = 0; ring < counts.length; ring++) {
            double rotation = rng.next() * StrictMath.PI * 2.0;
            double step = StrictMath.PI * 2.0 / counts[ring];
            for (int k = 0; k < counts[ring]; k++) {
                double angle = rotation + step * k + (rng.next() - 0.5) * step * 0.55;
                double r = radii[ring] * (0.82 + 0.36 * rng.next());
                addSite(sites, ix + r * StrictMath.cos(angle), iy + r * StrictMath.sin(angle));
            }
        }
        for (int k = 0; k < 4; k++) {
            double r = 0.45 * StrictMath.sqrt(rng.next());
            double angle = rng.next() * StrictMath.PI * 2.0;
            addSite(sites, 0.5 + r * StrictMath.cos(angle), 0.5 + r * StrictMath.sin(angle));
        }

        // ---- Voronoi cells by half-plane clipping; every edge remembers the neighbour that made it.
        int siteCount = sites.size();
        Poly[] cells = new Poly[siteCount];
        for (int i = 0; i < siteCount; i++) {
            double[] si = sites.get(i);
            Poly poly = Poly.square(-0.02, -0.02, 1.02, 1.02);
            for (int j = 0; j < siteCount && poly != null; j++) {
                if (j == i) {
                    continue;
                }
                double[] sj = sites.get(j);
                double nx = sj[0] - si[0];
                double ny = sj[1] - si[1];
                double c = nx * (si[0] + sj[0]) * 0.5 + ny * (si[1] + sj[1]) * 0.5;
                poly = poly.clip(nx, ny, c, j);
            }
            cells[i] = poly;
        }

        // ---- Jagged shared borders. Each pair of neighbours gets exactly one polyline.
        Map<Long, double[][]> borders = new HashMap<>();
        List<int[]> borderPairs = new ArrayList<>();
        for (int i = 0; i < siteCount; i++) {
            Poly poly = cells[i];
            if (poly == null) {
                continue;
            }
            for (int e = 0; e < poly.size; e++) {
                int j = poly.label[e];
                if (j < 0) {
                    continue;
                }
                long key = pairKey(i, j);
                if (borders.containsKey(key)) {
                    continue;
                }
                double ax = poly.x[e];
                double ay = poly.y[e];
                double bx = poly.x[(e + 1) % poly.size];
                double by = poly.y[(e + 1) % poly.size];
                borders.put(key, jagged(seed, Math.min(i, j), Math.max(i, j), ax, ay, bx, by));
                borderPairs.add(new int[] {Math.min(i, j), Math.max(i, j)});
            }
        }

        // ---- Final shard outlines.
        List<Shard> shardList = new ArrayList<>();
        int[] shardOfSite = new int[siteCount];
        Arrays.fill(shardOfSite, -1);
        for (int i = 0; i < siteCount; i++) {
            Poly poly = cells[i];
            if (poly == null || poly.size < 3) {
                continue;
            }
            List<double[]> outline = new ArrayList<>();
            for (int e = 0; e < poly.size; e++) {
                double ax = poly.x[e];
                double ay = poly.y[e];
                outline.add(new double[] {ax, ay});
                int j = poly.label[e];
                if (j < 0) {
                    continue;
                }
                double[][] border = borders.get(pairKey(i, j));
                if (border == null) {
                    continue;
                }
                // The border was built from one cell's point of view; walk it in this cell's direction.
                double bx = poly.x[(e + 1) % poly.size];
                double by = poly.y[(e + 1) % poly.size];
                double[] bxs = border[0];
                double[] bys = border[1];
                int n = bxs.length;
                boolean forward = dist2(bxs[0], bys[0], ax, ay) + dist2(bxs[n - 1], bys[n - 1], bx, by)
                        <= dist2(bxs[0], bys[0], bx, by) + dist2(bxs[n - 1], bys[n - 1], ax, ay);
                for (int k = 1; k < n - 1; k++) {
                    int idx = forward ? k : n - 1 - k;
                    outline.add(new double[] {bxs[idx], bys[idx]});
                }
            }
            float[] px = new float[outline.size()];
            float[] py = new float[outline.size()];
            for (int k = 0; k < px.length; k++) {
                px[k] = (float) outline.get(k)[0];
                py[k] = (float) outline.get(k)[1];
            }
            if (Math.abs(area(px, py)) < 1.0E-6F) {
                continue;
            }
            float visible = visibleArea(px, py);
            shardOfSite[i] = shardList.size();
            shardList.add(new Shard(shardList.size(), px, py, visible, rng, impactX, impactY));
        }
        this.shards = shardList.toArray(new Shard[0]);

        // ---- Cracks along the borders. They spread as one connected network from the impact:
        // every border is keyed by its distance from the impact along the border graph.
        List<double[][]> borderLines = new ArrayList<>();
        List<int[]> borderShards = new ArrayList<>();
        for (int[] pair : borderPairs) {
            int a = shardOfSite[pair[0]];
            int b = shardOfSite[pair[1]];
            if (a >= 0 && b >= 0) {
                borderLines.add(borders.get(pairKey(pair[0], pair[1])));
                borderShards.add(new int[] {a, b});
            }
        }
        double[] network = networkDistances(borderLines, ix, iy);
        List<Crack> crackList = new ArrayList<>();
        List<float[]> borderInfo = new ArrayList<>();
        for (int k = 0; k < borderLines.size(); k++) {
            double[] bxs = borderLines.get(k)[0];
            double[] bys = borderLines.get(k)[1];
            if (visibleFraction(bxs, bys) < 0.12) {
                continue;
            }
            int n = bxs.length;
            double startDistance = network[2 * k];
            double endDistance = network[2 * k + 1];
            boolean reverse = endDistance < startDistance;
            float[] cx = new float[n];
            float[] cy = new float[n];
            for (int p = 0; p < n; p++) {
                int idx = reverse ? n - 1 - p : p;
                cx[p] = (float) bxs[idx];
                cy[p] = (float) bys[idx];
            }
            // Radial borders (pointing away from the impact) crack first, so early cracks run
            // outwards as long lines instead of circling the impact.
            double ddx = cx[n - 1] - cx[0];
            double ddy = cy[n - 1] - cy[0];
            double rdx = (cx[0] + cx[n - 1]) * 0.5 - ix;
            double rdy = (cy[0] + cy[n - 1]) * 0.5 - iy;
            double norm = StrictMath.sqrt((ddx * ddx + ddy * ddy) * (rdx * rdx + rdy * rdy));
            double radial = norm < 1.0E-9 ? 0.0 : Math.abs(ddx * rdx + ddy * rdy) / norm;
            float key = (float) (Math.min(startDistance, endDistance) + rng.next() * 0.05 - radial * 0.09);
            borderInfo.add(new float[] {key, crackList.size()});
            int[] shardsOfBorder = borderShards.get(k);
            crackList.add(new Crack(cx, cy, NEVER, NEVER, true, (float) (0.85 + rng.next() * 0.3),
                    shardsOfBorder[0], shardsOfBorder[1]));
        }
        borderInfo.sort(Comparator.comparingDouble(info -> info[0]));
        int borderCount = borderInfo.size();
        // A few long cracks early on, spreading through Stage 2/3, the rest only as pieces
        // break away and when the moon finally shatters.
        int early = Math.max(3, Math.round(borderCount * 0.08F));
        int spread = Math.max(early + 3, Math.round(borderCount * 0.28F));
        int later = Math.max(spread + 1, Math.round(borderCount * 0.6F));
        float[] growth = new float[crackList.size()];
        for (int rank = 0; rank < borderCount; rank++) {
            int index = (int) borderInfo.get(rank)[1];
            Crack crack = crackList.get(index);
            float appear;
            if (rank < early) {
                appear = lerp(FIRST_CRACK + 0.03F, 0.19F, (float) rank / early);
            } else if (rank < spread) {
                appear = lerp(0.20F, PIECES - 0.01F, (float) (rank - early) / (spread - early));
            } else if (rank < later) {
                appear = lerp(PIECES, SHATTER - 0.02F, (float) (rank - spread) / (later - spread));
            } else {
                appear = lerp(SHATTER, 0.97F, (float) (rank - later) / Math.max(1, borderCount - later));
            }
            crack.appearAt = appear;
            growth[index] = (float) (0.010 + rng.next() * 0.016);
            crack.fullAt = appear + growth[index];
        }

        // ---- Which pieces break away, and when.
        assignBreaks(rng);
        assignFollowers(borderLines, borderShards);

        // A piece is always outlined by cracks before it falls out.
        for (int k = 0; k < crackList.size(); k++) {
            Crack crack = crackList.get(k);
            float limit = Math.min(shards[crack.shardA].breakAt, shards[crack.shardB].breakAt);
            if (limit >= NEVER) {
                continue;
            }
            float latestAppear = limit - 0.012F - (float) (rng.next() * 0.02);
            float latestFull = limit - 0.002F;
            if (crack.appearAt > latestAppear) {
                crack.appearAt = Math.max(FIRST_CRACK + 0.01F, latestAppear);
                crack.fullAt = crack.appearAt + growth[k];
            }
            if (crack.fullAt > latestFull) {
                crack.fullAt = latestFull;
            }
            crack.fullAt = Math.max(crack.appearAt + 0.001F, crack.fullAt);
        }

        // ---- The first impact: a small star of hairline cracks.
        int star = 3 + (int) (rng.next() * 2.0);
        double starRotation = rng.next() * StrictMath.PI * 2.0;
        for (int k = 0; k < star; k++) {
            double angle = starRotation + StrictMath.PI * 2.0 * k / star + (rng.next() - 0.5) * 0.9;
            double length = 0.04 + rng.next() * 0.05;
            float appear = k == 0 ? FIRST_CRACK : FIRST_CRACK + (float) (0.01 + rng.next() * 0.07);
            float[][] walk = walk(rng, ix, iy, angle, length, 3 + (int) (rng.next() * 2.0), 0.35);
            float width = (float) (0.75 + rng.next() * 0.3);
            if (walk[0].length >= 2) {
                crackList.add(new Crack(walk[0], walk[1], appear, appear + 0.012F, true, width, -1, -1));
            }
        }

        // ---- Side branches make the cracks look natural.
        int mainCount = crackList.size();
        for (int k = 0; k < mainCount; k++) {
            Crack parent = crackList.get(k);
            if (parent.appearAt >= NEVER || parent.length < 0.02F) {
                continue;
            }
            double roll = rng.next();
            int branches = roll < 0.55 ? 0 : roll < 0.9 ? 1 : 2;
            for (int b = 0; b < branches; b++) {
                double along = (0.2 + rng.next() * 0.6) * parent.length;
                int seg = 1;
                while (seg < parent.x.length - 1 && parent.cumulative[seg] < along) {
                    seg++;
                }
                float sx = parent.x[seg - 1];
                float sy = parent.y[seg - 1];
                double tangent = StrictMath.atan2(parent.y[seg] - sy, parent.x[seg] - sx);
                double angle = tangent + (rng.next() < 0.5 ? -1.0 : 1.0) * (0.6 + rng.next() * 0.7);
                float[][] walk = walk(rng, sx, sy, angle, 0.025 + rng.next() * 0.035, 2 + (int) (rng.next() * 2.0), 0.4);
                if (walk[0].length < 2) {
                    continue;
                }
                float appear = Math.min(0.99F, parent.fullAt + 0.02F + (float) (rng.next() * 0.06));
                crackList.add(new Crack(walk[0], walk[1], appear, appear + 0.01F, false, (float) (0.55 + rng.next() * 0.25), -1, -1));
            }
        }

        crackList.removeIf(crack -> crack.appearAt >= NEVER);
        crackList.sort(Comparator.comparingDouble(crack -> crack.appearAt));
        this.cracks = crackList.toArray(new Crack[0]);
        int majors = 0;
        for (int k = 0; k < cracks.length; k++) {
            cracks[k].index = k;
            if (cracks[k].major) {
                majors++;
            }
        }
        this.majorCracks = new int[majors];
        for (int k = 0, m = 0; k < cracks.length; k++) {
            if (cracks[k].major) {
                majorCracks[m++] = k;
            }
        }

        List<Shard> breaking = new ArrayList<>();
        for (Shard shard : shards) {
            if (shard.breakAt < NEVER && shard.leader < 0) {
                breaking.add(shard);
            }
        }
        breaking.sort(Comparator.comparingDouble(shard -> shard.breakAt));
        this.holeOrder = new int[breaking.size()];
        for (int k = 0; k < holeOrder.length; k++) {
            holeOrder[k] = breaking.get(k).index;
        }
    }

    /** The geometry for a world seed (cached, the last one is kept). */
    public static synchronized MoonGeometry forSeed(long seed) {
        MoonGeometry geometry = cached;
        if (geometry == null || geometry.seed != seed) {
            geometry = new MoonGeometry(seed);
            cached = geometry;
        }
        return geometry;
    }

    /** A fresh, uncached instance (for tests that compare two independent computations). */
    static MoonGeometry compute(long seed) {
        return new MoonGeometry(seed);
    }

    public long seed() {
        return seed;
    }

    public float impactX() {
        return impactX;
    }

    public float impactY() {
        return impactY;
    }

    public int shardCount() {
        return shards.length;
    }

    public Shard shard(int index) {
        return shards[index];
    }

    public int crackCount() {
        return cracks.length;
    }

    /** Cracks sorted by the progress at which they appear. */
    public Crack crack(int index) {
        return cracks[index];
    }

    /** Shard indices in the order they break away. */
    public int holeCount() {
        return holeOrder.length;
    }

    public int hole(int order) {
        return holeOrder[order];
    }

    /** The major crack that most recently appeared, or -1. */
    public int newestCrack(float progress) {
        int found = -1;
        for (int index : majorCracks) {
            if (cracks[index].appearAt <= progress) {
                found = index;
            } else {
                break;
            }
        }
        return found;
    }

    /** The next major crack that has not appeared yet, or -1. */
    public int nextCrack(float progress) {
        for (int index : majorCracks) {
            if (cracks[index].appearAt > progress) {
                return index;
            }
        }
        return -1;
    }

    /** The shard that most recently broke away, or -1. */
    public int newestHole(float progress) {
        int found = -1;
        for (int index : holeOrder) {
            if (shards[index].breakAt <= progress) {
                found = index;
            } else {
                break;
            }
        }
        return found;
    }

    /** The next shard to break away, or -1. */
    public int nextHole(float progress) {
        for (int index : holeOrder) {
            if (shards[index].breakAt > progress) {
                return index;
            }
        }
        return -1;
    }

    /** 0..1 strength of the distortion (grows from {@link #DISTORTION}). */
    public static float distortion(float progress) {
        float t = clamp01((progress - DISTORTION) / (1.0F - DISTORTION));
        return t * t * (3.0F - 2.0F * t);
    }

    /** 0..1 how far the shattering has gone (grows from {@link #SHATTER}). */
    public static float shatter(float progress) {
        return clamp01((progress - SHATTER) / (1.0F - SHATTER));
    }

    // ------------------------------------------------------------------ construction helpers

    private void assignBreaks(Rng rng) {
        List<Shard> candidates = new ArrayList<>();
        for (Shard shard : shards) {
            if (shard.visibleArea >= MIN_PIECE_AREA) {
                candidates.add(shard);
            }
        }
        Comparator<Shard> byImpact = Comparator.comparingDouble(
                shard -> dist2(shard.centroidX, shard.centroidY, impactX, impactY));

        // Small rim pieces first.
        List<Shard> small = new ArrayList<>();
        for (Shard shard : candidates) {
            float full = Math.abs(area(shard.x, shard.y));
            if (shard.visibleArea < 0.02F && shard.visibleArea < full * 0.97F) {
                small.add(shard);
            }
        }
        small.sort(byImpact);
        List<Shard> phaseA = take(small, 3, candidates);

        // Then larger sections near the impact.
        List<Shard> large = new ArrayList<>();
        for (Shard shard : candidates) {
            if (shard.visibleArea >= 0.02F) {
                large.add(shard);
            }
        }
        large.sort(byImpact);
        List<Shard> phaseB = take(large, 3, candidates);

        // A few more while the moon distorts, spreading outwards from the impact.
        List<Shard> remaining = new ArrayList<>(candidates);
        remaining.sort(byImpact);
        List<Shard> phaseC = take(remaining, 3, candidates);

        // The rest shatters, except a few core pieces (large, far from the impact) that hold on
        // until the very end.
        List<Shard> rest = new ArrayList<>(candidates);
        rest.sort(Comparator.comparingDouble(shard -> -shard.visibleArea
                * (0.4 + StrictMath.sqrt(dist2(shard.centroidX, shard.centroidY, impactX, impactY)))));
        int cores = Math.min(4, rest.size());
        List<Shard> phaseD = new ArrayList<>(rest.subList(cores, rest.size()));
        phaseD.sort(byImpact);

        spread(phaseA, PIECES + 0.01F, SECTIONS - 0.01F, rng);
        spread(phaseB, SECTIONS + 0.01F, DISTORTION - 0.01F, rng);
        spread(phaseC, DISTORTION + 0.01F, SHATTER - 0.01F, rng);
        spread(phaseD, SHATTER + 0.01F, 0.98F, rng);
    }

    /**
     * Gives every shard that hardly touches the round disc a leader: the neighbour it shares the
     * longest border with, among the shards already settled (pieces, the core pieces that never
     * break, and followers settled in an earlier round). It then breaks exactly when its leader does.
     */
    private void assignFollowers(List<double[][]> lines, List<int[]> pairs) {
        int n = shards.length;
        double[][] shared = new double[n][n];
        for (int k = 0; k < lines.size(); k++) {
            double[] xs = lines.get(k)[0];
            double[] ys = lines.get(k)[1];
            double length = 0.0;
            for (int p = 1; p < xs.length; p++) {
                length += StrictMath.sqrt(dist2(xs[p - 1], ys[p - 1], xs[p], ys[p]));
            }
            int a = pairs.get(k)[0];
            int b = pairs.get(k)[1];
            shared[a][b] += length;
            shared[b][a] += length;
        }
        boolean[] settled = new boolean[n];
        for (int i = 0; i < n; i++) {
            settled[i] = shards[i].visibleArea >= MIN_PIECE_AREA;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (settled[i]) {
                    continue;
                }
                int best = -1;
                for (int j = 0; j < n; j++) {
                    if (settled[j] && shared[i][j] > 0.0 && (best < 0 || shared[i][j] > shared[i][best])) {
                        best = j;
                    }
                }
                if (best < 0) {
                    continue;
                }
                Shard lead = shards[best].leader >= 0 ? shards[shards[best].leader] : shards[best];
                shards[i].leader = lead.index;
                shards[i].breakAt = lead.breakAt;
                settled[i] = true;
                changed = true;
            }
        }
    }

    private static List<Shard> take(List<Shard> from, int count, List<Shard> pool) {
        List<Shard> out = new ArrayList<>();
        for (Shard shard : from) {
            if (out.size() >= count) {
                break;
            }
            if (pool.remove(shard)) {
                out.add(shard);
            }
        }
        return out;
    }

    private static void spread(List<Shard> list, float from, float to, Rng rng) {
        int n = list.size();
        for (int k = 0; k < n; k++) {
            float t = n == 1 ? 0.5F : (float) k / (n - 1);
            float jitter = (float) ((rng.next() - 0.5) * (to - from) / Math.max(2, n) * 0.6);
            list.get(k).breakAt = Math.max(from, Math.min(to, from + (to - from) * t + jitter));
        }
        // Keep the order strictly increasing after the jitter.
        for (int k = 1; k < n; k++) {
            if (list.get(k).breakAt <= list.get(k - 1).breakAt) {
                list.get(k).breakAt = Math.min(to, list.get(k - 1).breakAt + 0.002F);
            }
        }
    }

    /**
     * Distance of both ends of every border from the impact, measured along the border graph
     * (so cracks grow as one connected network). Returns {start0, end0, start1, end1, ...}.
     */
    private static double[] networkDistances(List<double[][]> lines, double ix, double iy) {
        List<double[]> nodes = new ArrayList<>();
        int[] ends = new int[lines.size() * 2];
        for (int k = 0; k < lines.size(); k++) {
            double[] xs = lines.get(k)[0];
            double[] ys = lines.get(k)[1];
            ends[2 * k] = node(nodes, xs[0], ys[0]);
            ends[2 * k + 1] = node(nodes, xs[xs.length - 1], ys[ys.length - 1]);
        }
        int count = nodes.size();
        double[] distance = new double[count];
        for (int n = 0; n < count; n++) {
            distance[n] = StrictMath.sqrt(dist2(nodes.get(n)[0], nodes.get(n)[1], ix, iy)) * 1.6;
        }
        double[] lengths = new double[lines.size()];
        for (int k = 0; k < lines.size(); k++) {
            double[] xs = lines.get(k)[0];
            double[] ys = lines.get(k)[1];
            double total = 0.0;
            for (int p = 1; p < xs.length; p++) {
                total += StrictMath.sqrt(dist2(xs[p - 1], ys[p - 1], xs[p], ys[p]));
            }
            lengths[k] = total;
        }
        boolean[] done = new boolean[count];
        for (int round = 0; round < count; round++) {
            int best = -1;
            for (int n = 0; n < count; n++) {
                if (!done[n] && (best < 0 || distance[n] < distance[best])) {
                    best = n;
                }
            }
            if (best < 0) {
                break;
            }
            done[best] = true;
            for (int k = 0; k < lines.size(); k++) {
                int a = ends[2 * k];
                int b = ends[2 * k + 1];
                if (a == best && !done[b]) {
                    distance[b] = Math.min(distance[b], distance[a] + lengths[k]);
                } else if (b == best && !done[a]) {
                    distance[a] = Math.min(distance[a], distance[b] + lengths[k]);
                }
            }
        }
        double[] out = new double[lines.size() * 2];
        for (int k = 0; k < ends.length; k++) {
            out[k] = distance[ends[k]];
        }
        return out;
    }

    private static int node(List<double[]> nodes, double x, double y) {
        for (int n = 0; n < nodes.size(); n++) {
            if (dist2(nodes.get(n)[0], nodes.get(n)[1], x, y) < 1.0E-12) {
                return n;
            }
        }
        nodes.add(new double[] {x, y});
        return nodes.size() - 1;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.max(0.0F, Math.min(1.0F, t));
    }

    private static void addSite(List<double[]> sites, double x, double y) {
        if (x < -0.3 || x > 1.3 || y < -0.3 || y > 1.3) {
            return;
        }
        for (double[] site : sites) {
            if (dist2(site[0], site[1], x, y) < 0.025 * 0.025) {
                return;
            }
        }
        sites.add(new double[] {x, y});
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /** A shared border with small perpendicular kinks, deterministic per pair of shards. */
    private static double[][] jagged(long seed, int a, int b, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len = StrictMath.sqrt(dx * dx + dy * dy);
        int parts = len < 1.0E-4 ? 1 : Math.max(1, (int) StrictMath.round(len / 0.03));
        double[] xs = new double[parts + 1];
        double[] ys = new double[parts + 1];
        xs[0] = ax;
        ys[0] = ay;
        xs[parts] = bx;
        ys[parts] = by;
        if (parts > 1) {
            Rng rng = new Rng(seed ^ SALT ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL));
            double nx = -dy / len;
            double ny = dx / len;
            double amplitude = Math.min(0.016, len * 0.11);
            for (int k = 1; k < parts; k++) {
                double t = (double) k / parts + (rng.next() - 0.5) * 0.35 / parts;
                double offset = (rng.next() * 2.0 - 1.0) * amplitude;
                xs[k] = ax + dx * t + nx * offset;
                ys[k] = ay + dy * t + ny * offset;
            }
        }
        return new double[][] {xs, ys};
    }

    /** A wandering crack path from a point, stopping at the edge of the disc. */
    private static float[][] walk(Rng rng, double x, double y, double angle, double length, int segments, double turn) {
        List<float[]> points = new ArrayList<>();
        points.add(new float[] {(float) x, (float) y});
        double step = length / segments;
        double a = angle;
        for (int k = 0; k < segments; k++) {
            a += (rng.next() - 0.5) * 2.0 * turn;
            double s = step * (0.7 + rng.next() * 0.6);
            x += StrictMath.cos(a) * s;
            y += StrictMath.sin(a) * s;
            if (dist2(x, y, 0.5, 0.5) > 0.5 * 0.5) {
                break;
            }
            points.add(new float[] {(float) x, (float) y});
        }
        float[] xs = new float[points.size()];
        float[] ys = new float[points.size()];
        for (int k = 0; k < xs.length; k++) {
            xs[k] = points.get(k)[0];
            ys[k] = points.get(k)[1];
        }
        return new float[][] {xs, ys};
    }

    private static double visibleFraction(double[] xs, double[] ys) {
        int inside = 0;
        int samples = 0;
        for (int k = 0; k < xs.length - 1; k++) {
            for (int s = 0; s < 4; s++) {
                double t = (s + 0.5) / 4.0;
                double px = xs[k] + (xs[k + 1] - xs[k]) * t;
                double py = ys[k] + (ys[k + 1] - ys[k]) * t;
                samples++;
                if (dist2(px, py, 0.5, 0.5) < VISIBLE_RADIUS * VISIBLE_RADIUS) {
                    inside++;
                }
            }
        }
        return samples == 0 ? 0.0 : (double) inside / samples;
    }

    private static float visibleArea(float[] xs, float[] ys) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            x0 = Math.min(x0, xs[i]);
            y0 = Math.min(y0, ys[i]);
            x1 = Math.max(x1, xs[i]);
            y1 = Math.max(y1, ys[i]);
        }
        double cell = 0.01;
        int hits = 0;
        for (double py = y0 + cell * 0.5; py < y1; py += cell) {
            for (double px = x0 + cell * 0.5; px < x1; px += cell) {
                if (dist2(px, py, 0.5, 0.5) < VISIBLE_RADIUS * VISIBLE_RADIUS && insidePolygon(xs, ys, (float) px, (float) py)) {
                    hits++;
                }
            }
        }
        return (float) (hits * cell * cell);
    }

    static boolean insidePolygon(float[] xs, float[] ys, float px, float py) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > py) != (ys[j] > py)) {
                float cross = (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i];
                if (px < cross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static float area(float[] xs, float[] ys) {
        double sum = 0.0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            sum += (double) xs[j] * ys[i] - (double) xs[i] * ys[j];
        }
        return (float) (sum * 0.5);
    }

    private static float[] centroid(float[] xs, float[] ys) {
        double a = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double cross = (double) xs[j] * ys[i] - (double) xs[i] * ys[j];
            a += cross;
            cx += (xs[j] + xs[i]) * cross;
            cy += (ys[j] + ys[i]) * cross;
        }
        if (Math.abs(a) < 1.0E-12) {
            double sx = 0.0;
            double sy = 0.0;
            for (int i = 0; i < xs.length; i++) {
                sx += xs[i];
                sy += ys[i];
            }
            return new float[] {(float) (sx / xs.length), (float) (sy / xs.length)};
        }
        return new float[] {(float) (cx / (3.0 * a)), (float) (cy / (3.0 * a))};
    }

    private static double dist2(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(1.0F, value);
    }

    /** Convex polygon with a label per edge (the site whose bisector created it, -1 for the frame). */
    private static final class Poly {
        final double[] x;
        final double[] y;
        final int[] label;
        final int size;

        Poly(double[] x, double[] y, int[] label, int size) {
            this.x = x;
            this.y = y;
            this.label = label;
            this.size = size;
        }

        static Poly square(double x0, double y0, double x1, double y1) {
            return new Poly(new double[] {x0, x1, x1, x0}, new double[] {y0, y0, y1, y1}, new int[] {-1, -1, -1, -1}, 4);
        }

        /** Keeps the part where {@code nx*x + ny*y <= c}; the new edge on the line gets {@code lineLabel}. */
        Poly clip(double nx, double ny, double c, int lineLabel) {
            double[] ox = new double[size + 2];
            double[] oy = new double[size + 2];
            int[] ol = new int[size + 2];
            int n = 0;
            for (int k = 0; k < size; k++) {
                int next = (k + 1) % size;
                double px = x[k];
                double py = y[k];
                double qx = x[next];
                double qy = y[next];
                double dp = nx * px + ny * py - c;
                double dq = nx * qx + ny * qy - c;
                boolean pIn = dp <= 0.0;
                boolean qIn = dq <= 0.0;
                if (pIn) {
                    ox[n] = px;
                    oy[n] = py;
                    ol[n] = label[k];
                    n++;
                    if (!qIn) {
                        double t = dp / (dp - dq);
                        ox[n] = px + (qx - px) * t;
                        oy[n] = py + (qy - py) * t;
                        ol[n] = lineLabel;
                        n++;
                    }
                } else if (qIn) {
                    double t = dp / (dp - dq);
                    ox[n] = px + (qx - px) * t;
                    oy[n] = py + (qy - py) * t;
                    ol[n] = label[k];
                    n++;
                }
            }
            if (n < 3) {
                return null;
            }
            return new Poly(ox, oy, ol, n);
        }
    }

    /** SplitMix64: tiny, fast and identical everywhere. */
    static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed;
        }

        double next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z ^= z >>> 31;
            return (z >>> 11) * 0x1.0p-53;
        }
    }
}
