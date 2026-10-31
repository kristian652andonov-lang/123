package dev.kristian.nullmod.client.field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * How the field's sun breaks, generated from a seed (a new pattern every day). All coordinates lie
 * in the sun's own plane, in units of its radius (the disc is the unit circle).
 * <ul>
 * <li><b>Pieces</b>: the disc split into a dozen irregular convex shards (a Voronoi partition), the
 * pieces it falls apart into when it shatters.</li>
 * <li><b>Cracks</b>: the shards' borders, jagged, spreading through the day from a first point of
 * impact along the network (each segment has the time, 0..1, at which it appears), with fine
 * fractures branching off them.</li>
 * <li><b>Leaks</b>: points along the cracks where light gets through, and rays of light escaping at
 * the rim where the cracks reach it.</li>
 * </ul>
 */
final class FieldSunGeometry {
    /** A shard of the sun: its outline, centre, and how it drifts away when the sun breaks. */
    record Piece(float[] xs, float[] ys, float cx, float cy, float dirX, float dirY, float speed, float spin, float fall) {
    }

    /** A straight bit of crack from a to b, visible from {@code time} on. */
    record Segment(float ax, float ay, float bx, float by, float width, float time, boolean main) {
    }

    /** Light leaking through the crack at a point. */
    record Glint(float x, float y, float size, float time) {
    }

    /** A ray of light escaping at the rim. */
    record Ray(float x, float y, float dirX, float dirY, float length, float time) {
    }

    private static final int RIM_SEGMENTS = 72;
    private static final double SEGMENT_LENGTH = 0.03;

    final List<Piece> pieces = new ArrayList<>();
    final List<Segment> segments = new ArrayList<>();
    final List<Glint> glints = new ArrayList<>();
    final List<Ray> rays = new ArrayList<>();

    private FieldSunGeometry() {
    }

    static FieldSunGeometry build(long seed) {
        FieldSunGeometry geometry = new FieldSunGeometry();
        Random random = new Random(seed);
        List<double[]> sites = sites(random);
        List<double[][]> cells = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            double[][] cell = cell(sites, i);
            cells.add(cell);
            geometry.addPiece(cell, random);
        }
        geometry.addCracks(cells, random);
        return geometry;
    }

    // ------------------------------------------------------------------ pieces

    /** A dozen points spread over the disc, never too close together. */
    private static List<double[]> sites(Random random) {
        List<double[]> sites = new ArrayList<>();
        int wanted = 11 + random.nextInt(4);
        for (int attempt = 0; attempt < 2000 && sites.size() < wanted; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = Math.sqrt(random.nextDouble()) * 0.93;
            double x = Math.cos(angle) * radius;
            double y = Math.sin(angle) * radius;
            boolean free = true;
            for (double[] site : sites) {
                if (Math.hypot(site[0] - x, site[1] - y) < 0.3) {
                    free = false;
                    break;
                }
            }
            if (free) {
                sites.add(new double[]{x, y});
            }
        }
        return sites;
    }

    /** The part of the disc closer to site i than to any other site. */
    private static double[][] cell(List<double[]> sites, int i) {
        List<double[]> poly = new ArrayList<>(RIM_SEGMENTS);
        for (int k = 0; k < RIM_SEGMENTS; k++) {
            double angle = k * Math.PI * 2.0 / RIM_SEGMENTS;
            poly.add(new double[]{Math.cos(angle), Math.sin(angle)});
        }
        double[] si = sites.get(i);
        for (int j = 0; j < sites.size() && poly.size() >= 3; j++) {
            if (j == i) {
                continue;
            }
            double[] sj = sites.get(j);
            double nx = sj[0] - si[0];
            double ny = sj[1] - si[1];
            double c = nx * (si[0] + sj[0]) * 0.5 + ny * (si[1] + sj[1]) * 0.5;
            poly = clip(poly, nx, ny, c);
        }
        return poly.toArray(new double[0][]);
    }

    /** Keeps the part of a convex polygon where {@code nx * x + ny * y <= c}. */
    private static List<double[]> clip(List<double[]> poly, double nx, double ny, double c) {
        List<double[]> out = new ArrayList<>(poly.size() + 2);
        for (int k = 0; k < poly.size(); k++) {
            double[] p = poly.get(k);
            double[] q = poly.get((k + 1) % poly.size());
            double dp = nx * p[0] + ny * p[1] - c;
            double dq = nx * q[0] + ny * q[1] - c;
            if (dp <= 0.0) {
                out.add(p);
            }
            if ((dp < 0.0 && dq > 0.0) || (dp > 0.0 && dq < 0.0)) {
                double t = dp / (dp - dq);
                out.add(new double[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
            }
        }
        return out;
    }

    private void addPiece(double[][] cell, Random random) {
        if (cell.length < 3) {
            return;
        }
        float[] xs = new float[cell.length];
        float[] ys = new float[cell.length];
        double area = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int k = 0; k < cell.length; k++) {
            double[] p = cell[k];
            double[] q = cell[(k + 1) % cell.length];
            double cross = p[0] * q[1] - q[0] * p[1];
            area += cross;
            cx += (p[0] + q[0]) * cross;
            cy += (p[1] + q[1]) * cross;
            xs[k] = (float) p[0];
            ys[k] = (float) p[1];
        }
        area *= 0.5;
        if (Math.abs(area) < 1.0E-6) {
            return;
        }
        cx /= 6.0 * area;
        cy /= 6.0 * area;
        double length = Math.hypot(cx, cy);
        double dirX;
        double dirY;
        if (length < 0.08) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            dirX = Math.cos(angle);
            dirY = Math.sin(angle);
        } else {
            dirX = cx / length;
            dirY = cy / length;
        }
        float speed = 0.7F + random.nextFloat() * 0.6F;
        float spin = (random.nextFloat() - 0.5F) * 1.8F;
        float fall = 0.2F + random.nextFloat() * 0.45F;
        pieces.add(new Piece(xs, ys, (float) cx, (float) cy, (float) dirX, (float) dirY, speed, spin, fall));
    }

    // ------------------------------------------------------------------ cracks

    /** The shards' borders become cracks that spread from one point of impact through the day. */
    private void addCracks(List<double[][]> cells, Random random) {
        Map<Long, Integer> nodeIds = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<int[]> edges = new ArrayList<>();
        for (double[][] cell : cells) {
            for (int k = 0; k < cell.length; k++) {
                double[] p = cell[k];
                double[] q = cell[(k + 1) % cell.length];
                double mx = (p[0] + q[0]) * 0.5;
                double my = (p[1] + q[1]) * 0.5;
                if (Math.hypot(mx, my) > 0.985 || Math.hypot(q[0] - p[0], q[1] - p[1]) < 1.0E-4) {
                    // Part of the rim, not a border between two shards.
                    continue;
                }
                int a = node(nodeIds, nodes, p);
                int b = node(nodeIds, nodes, q);
                long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                if (a != b && seen.add(key)) {
                    edges.add(new int[]{a, b});
                }
            }
        }
        if (edges.isEmpty()) {
            return;
        }
        List<List<int[]>> adjacency = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            adjacency.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.size(); e++) {
            adjacency.get(edges.get(e)[0]).add(new int[]{edges.get(e)[1], e});
            adjacency.get(edges.get(e)[1]).add(new int[]{edges.get(e)[0], e});
        }
        // The first blow lands somewhere inside, not at the rim.
        List<Integer> inner = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (Math.hypot(nodes.get(i)[0], nodes.get(i)[1]) < 0.6) {
                inner.add(i);
            }
        }
        int start = inner.isEmpty() ? 0 : inner.get(random.nextInt(inner.size()));
        double[] distance = shortestPaths(nodes, edges, adjacency, start);
        double max = 1.0E-6;
        for (double d : distance) {
            if (d < Double.MAX_VALUE) {
                max = Math.max(max, d);
            }
        }
        double[] time = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            time[i] = distance[i] < Double.MAX_VALUE ? distance[i] / max : 1.0;
        }
        // A small star of fractures where it struck.
        double[] impact = nodes.get(start);
        int arms = 5 + random.nextInt(3);
        for (int i = 0; i < arms; i++) {
            double angle = (i + random.nextDouble() * 0.6) * Math.PI * 2.0 / arms;
            double length = 0.05 + random.nextDouble() * 0.08;
            jagged(impact[0], impact[1], impact[0] + Math.cos(angle) * length, impact[1] + Math.sin(angle) * length,
                    0.0, 0.04, 0.011F, false, random);
        }
        // The network itself, each border cracking from its earlier end to its later one.
        for (int[] edge : edges) {
            int a = time[edge[0]] <= time[edge[1]] ? edge[0] : edge[1];
            int b = a == edge[0] ? edge[1] : edge[0];
            double[] p = nodes.get(a);
            double[] q = nodes.get(b);
            jagged(p[0], p[1], q[0], q[1], 0.03 + 0.85 * time[a], 0.03 + 0.85 * time[b], 0.017F, true, random);
        }
        for (int i = 0; i < nodes.size(); i++) {
            double[] p = nodes.get(i);
            double r = Math.hypot(p[0], p[1]);
            float t = (float) Math.min(1.0, 0.05 + 0.85 * time[i]);
            if (r > 0.97) {
                // A crack reached the rim: light escapes outwards there.
                rays.add(new Ray((float) p[0], (float) p[1], (float) (p[0] / r), (float) (p[1] / r),
                        0.3F + random.nextFloat() * 0.45F, Math.min(1.0F, t + 0.06F)));
            } else {
                glints.add(new Glint((float) p[0], (float) p[1], 0.035F + random.nextFloat() * 0.03F, t));
            }
        }
    }

    private static int node(Map<Long, Integer> ids, List<double[]> nodes, double[] p) {
        long key = Math.round(p[0] * 100000.0) * 1000003L + Math.round(p[1] * 100000.0);
        Integer id = ids.get(key);
        if (id == null) {
            id = nodes.size();
            ids.put(key, id);
            nodes.add(p);
        }
        return id;
    }

    /** Distances along the crack network from the start node (Dijkstra; the graph is tiny). */
    private static double[] shortestPaths(List<double[]> nodes, List<int[]> edges, List<List<int[]>> adjacency, int start) {
        double[] distance = new double[nodes.size()];
        Arrays.fill(distance, Double.MAX_VALUE);
        distance[start] = 0.0;
        boolean[] done = new boolean[nodes.size()];
        for (int round = 0; round < nodes.size(); round++) {
            int best = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (!done[i] && distance[i] < Double.MAX_VALUE && (best < 0 || distance[i] < distance[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            done[best] = true;
            for (int[] link : adjacency.get(best)) {
                int[] edge = edges.get(link[1]);
                double[] p = nodes.get(edge[0]);
                double[] q = nodes.get(edge[1]);
                double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
                if (distance[best] + length < distance[link[0]]) {
                    distance[link[0]] = distance[best] + length;
                }
            }
        }
        return distance;
    }

    /**
     * A jagged crack from (ax, ay) to (bx, by), appearing from {@code t0} at a to {@code t1} at b,
     * with fine fractures branching off main cracks.
     */
    private void jagged(double ax, double ay, double bx, double by, double t0, double t1, float width, boolean main, Random random) {
        double dx = bx - ax;
        double dy = by - ay;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-5) {
            return;
        }
        int steps = Math.max(2, (int) Math.ceil(length / SEGMENT_LENGTH));
        double px = -dy / length;
        double py = dx / length;
        double lastX = ax;
        double lastY = ay;
        for (int k = 1; k <= steps; k++) {
            double f = k / (double) steps;
            double jitter = k == steps ? 0.0 : (random.nextDouble() - 0.5) * 0.022;
            double x = ax + dx * f + px * jitter;
            double y = ay + dy * f + py * jitter;
            float time = (float) (t0 + (t1 - t0) * (k - 1) / steps);
            segments.add(new Segment((float) lastX, (float) lastY, (float) x, (float) y, width, time, main));
            if (main && random.nextFloat() < 0.13F) {
                branch(lastX, lastY, dx / length, dy / length, px, py, time, random);
            }
            lastX = x;
            lastY = y;
        }
    }

    /** A fine fracture splitting off a crack at a sharp angle and wandering into the shard beside it. */
    private void branch(double x, double y, double ux, double uy, double px, double py, float time, Random random) {
        double side = random.nextBoolean() ? 1.0 : -1.0;
        double forward = random.nextBoolean() ? 1.0 : -1.0;
        double split = Math.toRadians(25.0 + random.nextDouble() * 40.0);
        double bx = ux * forward * Math.cos(split) + px * side * Math.sin(split);
        double by = uy * forward * Math.cos(split) + py * side * Math.sin(split);
        double angle = Math.atan2(by, bx);
        int steps = 2 + random.nextInt(4);
        for (int step = 0; step < steps; step++) {
            double length = 0.018 + random.nextDouble() * 0.014;
            double nx = x + Math.cos(angle) * length;
            double ny = y + Math.sin(angle) * length;
            if (Math.hypot(nx, ny) > 0.97) {
                return;
            }
            segments.add(new Segment((float) x, (float) y, (float) nx, (float) ny, 0.0085F,
                    Math.min(1.0F, time + 0.02F + 0.012F * step), false));
            x = nx;
            y = ny;
            angle += (random.nextDouble() - 0.5) * 1.0;
        }
    }
}
