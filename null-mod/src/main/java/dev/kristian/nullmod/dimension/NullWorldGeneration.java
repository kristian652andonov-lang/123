package dev.kristian.nullmod.dimension;

import dev.kristian.nullmod.registry.NullBlocks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The geometry of the NULL dimension: a pure, deterministic function from {@code (seed, x, y, z)}
 * to a block. Nothing here touches a world, so the chunk generator, the decay system (which
 * regenerates removed regions exactly) and the tests all see the same structure.
 * <p>
 * Layout:
 * <ul>
 * <li>A lattice of infinite corridors along X and Z, one line every {@link #CELL} blocks, stacked
 * in {@link #TIERS} tiers {@link #TIER_SPACING} blocks apart. The main tier (floor at
 * {@link #MAIN_FLOOR}) is complete and never broken, so the whole dimension can be walked; the
 * other tiers exist on some lines only and break off in places. Corridors come in four styles
 * (enclosed tube, open bridge, glass tunnel, data tunnel) and every {@link #RING_SPACING}
 * blocks a glowing rectangular ring frames the corridor, so looking along one shows rings
 * receding into the dark.</li>
 * <li>Where two corridors of a tier cross there is a hub room; at every crossing of the main
 * lattice four pillars with glowing edges run through the whole height of the world.</li>
 * <li>The square voids between the lines hold, per cell, sequences of huge gates, recursive
 * rectangle portals, floating platforms with data towers, data monoliths and grid lattices, or
 * nothing at all.</li>
 * <li>A fixed, seed-independent spawn platform surrounds (0, {@link #SPAWN_Y}, 0).</li>
 * </ul>
 * Blocks are produced as small integer material ids first ({@link #sampleId}); {@link #sample}
 * maps them to block states. For speed the chunk generator evaluates one column at a time with
 * {@link #column} and {@link #idAt}, which is exactly {@link #sampleId} split in two.
 */
public final class NullWorldGeneration {
    // ------------------------------------------------------------------ vertical layout
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 256;
    public static final int MAX_Y = MIN_Y + HEIGHT - 1;

    // ------------------------------------------------------------------ the corridor lattice
    /** Distance between two parallel corridor lines. */
    public static final int CELL = 56;
    /** Corridor walls stand at |w| == WALL; the walkable interior is |w| < WALL. */
    static final int WALL = 5;
    /** Half size of the square where two lines cross. */
    static final int HUB = 6;
    /** Ceiling height above the corridor floor. */
    static final int CEILING = 7;
    static final int RING_SPACING = 6;
    public static final int TIER_BASE = 27;
    public static final int TIER_SPACING = 36;
    public static final int TIERS = 6;
    public static final int MAIN_TIER = 1;
    /** Floor of the main tier; every main corridor and hub is walkable at {@code MAIN_FLOOR + 1}. */
    public static final int MAIN_FLOOR = TIER_BASE + MAIN_TIER * TIER_SPACING;

    // ------------------------------------------------------------------ the spawn platform
    /** Players stand at this y on the spawn platform. */
    public static final int SPAWN_Y = MAIN_FLOOR + 1;
    public static final int PLATFORM_RADIUS = 12;
    static final int PLATFORM_BOTTOM = MAIN_FLOOR - 7;
    static final int PLATFORM_TOP = MAIN_FLOOR + 17;
    static final int HALO_Y = MAIN_FLOOR + 25;
    static final int HALO_RADIUS = 16;
    /** Nothing seed-dependent is ever placed inside this box around the spawn platform. */
    static final int CLEARANCE_RADIUS = 18;
    static final int CLEARANCE_MIN_Y = MAIN_FLOOR - 19;
    static final int CLEARANCE_MAX_Y = MAIN_FLOOR + 37;

    // ------------------------------------------------------------------ materials
    public static final int AIR = 0;
    public static final int VOID = 1;
    public static final int GRID = 2;
    public static final int FRAME = 3;
    public static final int DATA = 4;
    public static final int GLASS = 5;
    public static final int GLOW_RED = 6;
    public static final int GLOW_GREEN = 7;
    public static final int GLOW_BLUE = 8;
    public static final int GLOW_CYAN = 9;
    public static final int GLOW_MAGENTA = 10;
    public static final int GLOW_YELLOW = 11;
    public static final int GLOW_WHITE = 12;
    public static final int MATERIALS = 13;

    /** Colour cycles for rings, gates and portals. */
    private static final int[][] PALETTES = {
            {GLOW_RED, GLOW_MAGENTA, GLOW_BLUE, GLOW_MAGENTA},
            {GLOW_CYAN, GLOW_BLUE, GLOW_WHITE, GLOW_BLUE},
            {GLOW_GREEN, GLOW_CYAN, GLOW_BLUE, GLOW_CYAN},
            {GLOW_YELLOW, GLOW_RED, GLOW_MAGENTA, GLOW_RED},
            {GLOW_MAGENTA, GLOW_BLUE, GLOW_CYAN, GLOW_BLUE},
            {GLOW_WHITE, GLOW_CYAN, GLOW_MAGENTA, GLOW_CYAN},
            {GLOW_RED, GLOW_YELLOW, GLOW_GREEN, GLOW_CYAN, GLOW_BLUE, GLOW_MAGENTA},
            {GLOW_BLUE, GLOW_BLUE, GLOW_CYAN, GLOW_WHITE, GLOW_CYAN, GLOW_BLUE}
    };
    /** Glowing rims of the stepped underside of the spawn platform, from the top step down. */
    private static final int[] PLATFORM_RIMS = {FRAME, FRAME, GLOW_CYAN, GLOW_BLUE, GLOW_MAGENTA, GLOW_RED, GLOW_YELLOW, GLOW_WHITE};
    private static final int[] PILLAR_BANDS = {GLOW_CYAN, GLOW_MAGENTA, GLOW_BLUE, GLOW_WHITE, GLOW_RED, GLOW_BLUE};

    // ------------------------------------------------------------------ corridor styles
    static final int STYLE_TUBE = 0;
    static final int STYLE_BRIDGE = 1;
    static final int STYLE_GLASS = 2;
    static final int STYLE_DATA = 3;

    // ------------------------------------------------------------------ column kinds
    static final int KIND_VOID = 0;
    static final int KIND_X = 1;
    static final int KIND_Z = 2;
    static final int KIND_HUB = 3;

    // ------------------------------------------------------------------ void features
    static final int F_GATES = 1;
    static final int F_PORTAL = 2;
    static final int F_PLATFORM = 3;
    static final int F_TOWER = 4;
    static final int F_MONOLITH = 5;
    static final int F_LATTICE = 6;
    /** At most this many features per cell (a cluster of platforms plus a secondary feature). */
    static final int MAX_FEATURES = 6;

    private static final long SALT_LINE = 0x4C494E45L;
    private static final long SALT_GAP = 0x47415053L;
    private static final long SALT_CELL = 0x43454C4CL;
    private static final long SALT_HOLE = 0x484F4C45L;
    private static final long SALT_RING = 0x52494E47L;

    private static volatile BlockState[] states;

    private NullWorldGeneration() {
    }

    // ================================================================== public API

    /** The block at a position. Pure and deterministic; safe to call from any thread. */
    public static BlockState sample(long seed, int x, int y, int z) {
        return state(sampleId(seed, x, y, z));
    }

    /** The material id at a position (one of the constants above). */
    public static int sampleId(long seed, int x, int y, int z) {
        Column column = new Column();
        column(seed, x, z, column);
        return idAt(column, y);
    }

    /** The block state of a material id. Only valid once the blocks are registered. */
    public static BlockState state(int id) {
        BlockState[] table = states;
        if (table == null) {
            table = buildStates();
            states = table;
        }
        return id > 0 && id < MATERIALS ? table[id] : table[AIR];
    }

    /**
     * True for positions the decay system must never touch: the spawn platform and its
     * surroundings, and the floor and lowest row of every main-tier corridor and hub, so the
     * walkways (and their railings) always stay intact.
     */
    public static boolean isProtected(int x, int y, int z) {
        if (Math.abs(x) <= CLEARANCE_RADIUS + 2 && Math.abs(z) <= CLEARANCE_RADIUS + 2
                && y >= CLEARANCE_MIN_Y && y <= CLEARANCE_MAX_Y) {
            return true;
        }
        if (y != MAIN_FLOOR && y != MAIN_FLOOR + 1) {
            return false;
        }
        int u = x - Math.floorDiv(x + CELL / 2, CELL) * CELL;
        int v = z - Math.floorDiv(z + CELL / 2, CELL) * CELL;
        return Math.abs(u) <= HUB || Math.abs(v) <= HUB;
    }

    /** True inside the box that only ever holds the spawn platform. */
    public static boolean inSpawnClearance(int x, int y, int z) {
        return Math.abs(x) <= CLEARANCE_RADIUS && Math.abs(z) <= CLEARANCE_RADIUS
                && y >= CLEARANCE_MIN_Y && y <= CLEARANCE_MAX_Y;
    }

    /** Highest non-air y of a column plus one, or {@link #MIN_Y} for an empty column. */
    public static int surfaceY(long seed, int x, int z) {
        Column column = new Column();
        column(seed, x, z, column);
        for (int y = MAX_Y; y >= MIN_Y; y--) {
            if (idAt(column, y) != AIR) {
                return y + 1;
            }
        }
        return MIN_Y;
    }

    /** Short description of what a column is, for the debug screen. */
    public static String describe(long seed, int x, int z) {
        Column c = new Column();
        column(seed, x, z, c);
        String kind = switch (c.kind) {
            case KIND_X -> "corridor x";
            case KIND_Z -> "corridor z";
            case KIND_HUB -> "hub";
            default -> "void (" + c.featureCount + " structures)";
        };
        return "NULL cell " + c.lineX + "," + c.lineZ + " " + kind;
    }

    /** Ticks a block id is a light source (used by tests and the decay system's statistics). */
    public static boolean isGlow(int id) {
        return id >= GLOW_RED && id <= GLOW_WHITE || id == FRAME || id == DATA;
    }

    // ================================================================== column evaluation

    /**
     * Everything about one (x, z) column that does not depend on y. Reusable: {@link #column}
     * overwrites every field.
     */
    public static final class Column {
        long seed;
        int x;
        int z;
        /** Index of the nearest Z-line (x = lineX * CELL) and the offset from it. */
        int lineX;
        int u;
        /** Index of the nearest X-line (z = lineZ * CELL) and the offset from it. */
        int lineZ;
        int v;
        int kind;
        boolean spawn;
        boolean halo;
        boolean pillar;
        boolean pillarEdge;
        /** Tiers present on the X-line through this column (gaps already applied), as bits. */
        int xTiers;
        /** Tiers present on the Z-line through this column (gaps already applied), as bits. */
        int zTiers;
        final int[] xStyle = new int[TIERS];
        final int[] xPalette = new int[TIERS];
        final int[] zStyle = new int[TIERS];
        final int[] zPalette = new int[TIERS];
        /** Ring material of the X- and Z-corridor at this column (ring only where along % 6 == 0). */
        final int[] xRing = new int[TIERS];
        final int[] zRing = new int[TIERS];
        boolean xRingHere;
        boolean zRingHere;
        int featureCount;
        final Feature[] features = new Feature[MAX_FEATURES];
        final Feature[] cellFeatures = new Feature[MAX_FEATURES];
        final Dice dice = new Dice();

        public Column() {
            for (int i = 0; i < MAX_FEATURES; i++) {
                features[i] = new Feature();
                cellFeatures[i] = new Feature();
            }
        }
    }

    /** Fills {@code out} with the y-independent data of column (x, z). */
    public static void column(long seed, int x, int z, Column out) {
        out.seed = seed;
        out.x = x;
        out.z = z;
        out.lineX = Math.floorDiv(x + CELL / 2, CELL);
        out.u = x - out.lineX * CELL;
        out.lineZ = Math.floorDiv(z + CELL / 2, CELL);
        out.v = z - out.lineZ * CELL;
        int au = Math.abs(out.u);
        int av = Math.abs(out.v);
        out.spawn = Math.abs(x) <= PLATFORM_RADIUS && Math.abs(z) <= PLATFORM_RADIUS;
        int haloRing = Math.max(Math.abs(x), Math.abs(z));
        out.halo = haloRing >= HALO_RADIUS - 1 && haloRing <= HALO_RADIUS;
        out.xTiers = 0;
        out.zTiers = 0;
        out.featureCount = 0;
        out.xRingHere = false;
        out.zRingHere = false;
        boolean origin = out.lineX == 0 && out.lineZ == 0;
        out.pillar = !origin && au >= WALL && au <= HUB && av >= WALL && av <= HUB;
        out.pillarEdge = out.pillar && au == HUB && av == HUB;

        if (au <= HUB && av <= HUB) {
            out.kind = KIND_HUB;
            lineData(seed, 0, out.lineZ, x, out.xStyle, out.xPalette, out.xRing, out, true);
            lineData(seed, 1, out.lineX, z, out.zStyle, out.zPalette, out.zRing, out, false);
        } else if (av <= HUB) {
            out.kind = KIND_X;
            lineData(seed, 0, out.lineZ, x, out.xStyle, out.xPalette, out.xRing, out, true);
        } else if (au <= HUB) {
            out.kind = KIND_Z;
            lineData(seed, 1, out.lineX, z, out.zStyle, out.zPalette, out.zRing, out, false);
        } else {
            out.kind = KIND_VOID;
            int cellX = Math.floorDiv(x, CELL);
            int cellZ = Math.floorDiv(z, CELL);
            int count = cellFeatures(seed, cellX, cellZ, out.cellFeatures, out.dice);
            for (int i = 0; i < count; i++) {
                Feature f = out.cellFeatures[i];
                if (x >= f.x0 && x <= f.x1 && z >= f.z0 && z <= f.z1) {
                    out.features[out.featureCount++].copy(f);
                }
            }
        }
    }

    /** The material id at height y of a prepared column. */
    public static int idAt(Column c, int y) {
        if (y < MIN_Y || y > MAX_Y) {
            return AIR;
        }
        if (c.spawn && y >= PLATFORM_BOTTOM && y <= PLATFORM_TOP) {
            return spawnPlatform(c.x, y, c.z);
        }
        if (c.halo && y == HALO_Y) {
            return GLOW_WHITE;
        }
        if (c.pillar) {
            return pillar(c, y);
        }
        if (c.kind == KIND_VOID) {
            if (inSpawnClearance(c.x, y, c.z)) {
                return AIR;
            }
            for (int i = 0; i < c.featureCount; i++) {
                int id = c.features[i].blockAt(c.seed, c.x, y, c.z);
                if (id != AIR) {
                    return id;
                }
            }
            return AIR;
        }
        int rel = y - TIER_BASE;
        if (rel < 0) {
            return AIR;
        }
        int tier = rel / TIER_SPACING;
        int h = rel - tier * TIER_SPACING;
        if (tier >= TIERS || h > CEILING) {
            return AIR;
        }
        int bit = 1 << tier;
        int id;
        if (c.kind == KIND_HUB) {
            boolean hasX = (c.xTiers & bit) != 0;
            boolean hasZ = (c.zTiers & bit) != 0;
            if (hasX && hasZ) {
                id = hubBlock(c, tier, h);
            } else if (hasX) {
                id = corridorBlock(c.xStyle[tier], c.v, h, c.xRingHere, c.xRing[tier], c.x);
            } else if (hasZ) {
                id = corridorBlock(c.zStyle[tier], c.u, h, c.zRingHere, c.zRing[tier], c.z);
            } else {
                id = AIR;
            }
        } else if (c.kind == KIND_X) {
            id = (c.xTiers & bit) != 0 ? corridorBlock(c.xStyle[tier], c.v, h, c.xRingHere, c.xRing[tier], c.x) : AIR;
        } else {
            id = (c.zTiers & bit) != 0 ? corridorBlock(c.zStyle[tier], c.u, h, c.zRingHere, c.zRing[tier], c.z) : AIR;
        }
        if (id != AIR && h > 1 && tier != MAIN_TIER && hole(c.seed, c.x, y, c.z, 0.05F)) {
            // The upper tiers are failing: blocks are simply missing here and there.
            return AIR;
        }
        if (id != AIR && h > 1 && tier == MAIN_TIER && id != GLASS && hole(c.seed, c.x, y, c.z, 0.012F)) {
            return AIR;
        }
        return id;
    }

    // ================================================================== corridors

    /**
     * Tier data of one corridor line, with the gaps of the current segment applied.
     *
     * @param axis  0 for a line along X (at z = line * CELL), 1 for a line along Z
     * @param along the coordinate along the line (x for an X-line, z for a Z-line)
     */
    private static void lineData(long seed, int axis, int line, int along, int[] styles, int[] palettes, int[] rings,
                                 Column out, boolean xLine) {
        int mask = 0;
        int segment = Math.floorDiv(along, CELL);
        int local = along - segment * CELL;
        boolean ringHere = Math.floorMod(along, RING_SPACING) == 0;
        int ringIndex = Math.floorDiv(along, RING_SPACING);
        for (int t = 0; t < TIERS; t++) {
            long h = hash(seed, SALT_LINE + axis, line, t, 0);
            boolean main = t == MAIN_TIER;
            boolean present = main || unit(h, 0) < 0.55F;
            if (!present) {
                continue;
            }
            if (!main) {
                long g = hash(seed, SALT_GAP + axis, line, t, segment);
                if (unit(g, 0) < 0.2F) {
                    int start = 7 + (int) ((g >>> 20) % 30L);
                    int end = Math.min(50, start + 10 + (int) ((g >>> 36) % 28L));
                    if (local >= start && local < end) {
                        continue;
                    }
                }
            }
            mask |= 1 << t;
            float styleRoll = unit(h, 16);
            int style;
            if (main) {
                style = styleRoll < 0.38F ? STYLE_BRIDGE : styleRoll < 0.72F ? STYLE_GLASS : styleRoll < 0.88F ? STYLE_TUBE : STYLE_DATA;
            } else {
                style = styleRoll < 0.3F ? STYLE_TUBE : styleRoll < 0.55F ? STYLE_BRIDGE : styleRoll < 0.8F ? STYLE_GLASS : STYLE_DATA;
            }
            styles[t] = style;
            int palette = (int) ((h >>> 40) % PALETTES.length);
            palettes[t] = palette;
            if (ringHere) {
                long r = hash(seed, SALT_RING + axis, line, t, ringIndex);
                // Now and then a ring is dead: its light simply is not there.
                rings[t] = unit(r, 0) < 0.07F ? VOID : color(palette, ringIndex);
            } else {
                rings[t] = AIR;
            }
        }
        if (xLine) {
            out.xTiers = mask;
            out.xRingHere = ringHere;
        } else {
            out.zTiers = mask;
            out.zRingHere = ringHere;
        }
    }

    /**
     * One block of a corridor cross-section.
     *
     * @param w     offset across the corridor (walls at |w| == 5)
     * @param h     height above the floor (0 = floor, 7 = ceiling)
     * @param ring  true where a glowing ring frames the corridor
     * @param along coordinate along the corridor (for wall panels)
     */
    static int corridorBlock(int style, int w, int h, boolean ring, int ringId, int along) {
        int aw = Math.abs(w);
        if (aw > WALL || h < 0 || h > CEILING) {
            return AIR;
        }
        if (h == 0) {
            if (ring) {
                return ringId;
            }
            if (aw == WALL) {
                return FRAME;
            }
            return aw == 0 ? DATA : GRID;
        }
        switch (style) {
            case STYLE_BRIDGE -> {
                if (aw == WALL) {
                    if (ring) {
                        return ringId;
                    }
                    return h == 1 ? GLASS : AIR;
                }
                return h == CEILING && ring ? ringId : AIR;
            }
            case STYLE_GLASS -> {
                if (h == CEILING) {
                    return ring ? ringId : aw == WALL ? FRAME : GLASS;
                }
                if (aw == WALL) {
                    return ring ? ringId : GLASS;
                }
                return AIR;
            }
            case STYLE_DATA -> {
                if (h == CEILING) {
                    return ring ? ringId : aw == WALL ? FRAME : VOID;
                }
                if (aw == WALL) {
                    if (ring) {
                        return ringId;
                    }
                    int bay = Math.floorDiv(along, RING_SPACING);
                    int inBay = Math.floorMod(along, RING_SPACING);
                    boolean panel = (bay & 1) == 0 && inBay >= 2 && inBay <= 4 && h >= 2 && h <= 5;
                    return panel ? DATA : VOID;
                }
                return AIR;
            }
            default -> {
                if (h == CEILING) {
                    return ring ? ringId : aw == WALL ? FRAME : VOID;
                }
                if (aw == WALL) {
                    if (ring) {
                        return ringId;
                    }
                    return h == 3 || h == 4 ? GLASS : VOID;
                }
                return AIR;
            }
        }
    }

    /** A hub room where two corridors of the same tier cross. */
    private static int hubBlock(Column c, int tier, int h) {
        int d = Math.max(Math.abs(c.u), Math.abs(c.v));
        if (h == 0) {
            if (d <= 1) {
                return DATA;
            }
            if (d == WALL) {
                return FRAME;
            }
            if (d == 3) {
                return color(c.xPalette[tier], c.lineX + c.lineZ);
            }
            return GRID;
        }
        if (h == CEILING) {
            boolean open = c.xStyle[tier] == STYLE_BRIDGE && c.zStyle[tier] == STYLE_BRIDGE;
            if (open) {
                return AIR;
            }
            if (d <= 1) {
                return GLOW_WHITE;
            }
            return d == WALL ? FRAME : VOID;
        }
        return AIR;
    }

    /** The pillars at the corners of every crossing of the main lattice, full height. */
    private static int pillar(Column c, int y) {
        int band = Math.floorMod(y, 12);
        if (band == 0) {
            return PILLAR_BANDS[Math.floorMod(Math.floorDiv(y, 12) + c.lineX * 3 + c.lineZ, PILLAR_BANDS.length)];
        }
        return c.pillarEdge ? FRAME : VOID;
    }

    // ================================================================== the spawn platform

    /** The fixed, seed-independent platform around (0, SPAWN_Y, 0), including its open air. */
    static int spawnPlatform(int x, int y, int z) {
        int ax = Math.abs(x);
        int az = Math.abs(z);
        int d = Math.max(ax, az);
        if (d > PLATFORM_RADIUS) {
            return AIR;
        }
        if (y == MAIN_FLOOR) {
            if (d == PLATFORM_RADIUS) {
                return FRAME;
            }
            if (d == 8) {
                return GLOW_BLUE;
            }
            if (d == 4) {
                return GLOW_MAGENTA;
            }
            return d <= 1 ? DATA : GRID;
        }
        if (y < MAIN_FLOOR) {
            // An inverted ziggurat with glowing rims hangs under the platform.
            int step = MAIN_FLOOR - y;
            int radius = PLATFORM_RADIUS - 2 * step + 2;
            if (radius < 0 || d > radius) {
                return AIR;
            }
            return d == radius ? PLATFORM_RIMS[Math.min(step, PLATFORM_RIMS.length - 1)] : VOID;
        }
        if (d != PLATFORM_RADIUS) {
            return AIR;
        }
        if (ax == PLATFORM_RADIUS && az == PLATFORM_RADIUS) {
            if (y <= MAIN_FLOOR + 4) {
                return FRAME;
            }
            return y == MAIN_FLOOR + 5 ? GLOW_WHITE : AIR;
        }
        if (y == MAIN_FLOOR + 1) {
            boolean opening = ax == PLATFORM_RADIUS && az < WALL || az == PLATFORM_RADIUS && ax < WALL;
            return opening ? AIR : GLASS;
        }
        return AIR;
    }

    // ================================================================== the voids between the lines

    /** A structure floating in a void cell. All coordinates are world coordinates, inclusive. */
    static final class Feature {
        int type;
        int x0;
        int x1;
        int z0;
        int z1;
        int y0;
        int y1;
        /** 0: planes of constant x, 1: planes of constant z. */
        int axis;
        /** First plane (gates), the plane (portal, monolith), or the lattice pitch. */
        int a0;
        int count;
        int spacing;
        int thick;
        int palette;
        boolean backplate;

        void copy(Feature f) {
            type = f.type;
            x0 = f.x0;
            x1 = f.x1;
            z0 = f.z0;
            z1 = f.z1;
            y0 = f.y0;
            y1 = f.y1;
            axis = f.axis;
            a0 = f.a0;
            count = f.count;
            spacing = f.spacing;
            thick = f.thick;
            palette = f.palette;
            backplate = f.backplate;
        }

        boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            return x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ;
        }

        int blockAt(long seed, int x, int y, int z) {
            if (y < y0 || y > y1) {
                return AIR;
            }
            int id = switch (type) {
                case F_GATES -> gate(x, y, z);
                case F_PORTAL -> portal(x, y, z);
                case F_PLATFORM -> platform(x, y, z);
                case F_TOWER -> y == y1 ? GLOW_WHITE : DATA;
                case F_MONOLITH -> monolith(x, y, z);
                case F_LATTICE -> lattice(x, z);
                default -> AIR;
            };
            if (id != AIR && type != F_TOWER && hole(seed, x, y, z, 0.035F)) {
                return AIR;
            }
            return id;
        }

        private int gate(int x, int y, int z) {
            int a = axis == 0 ? x : z;
            int b = axis == 0 ? z : x;
            int offset = a - a0;
            if (offset < 0 || offset % spacing != 0 || offset / spacing >= count) {
                return AIR;
            }
            int b0 = axis == 0 ? z0 : x0;
            int b1 = axis == 0 ? z1 : x1;
            boolean edge = b - b0 < thick || b1 - b < thick || y - y0 < thick || y1 - y < thick;
            return edge ? color(palette, offset / spacing) : AIR;
        }

        private int portal(int x, int y, int z) {
            int a = axis == 0 ? x : z;
            if (a != a0) {
                return AIR;
            }
            int b = axis == 0 ? z : x;
            int b0 = axis == 0 ? z0 : x0;
            int b1 = axis == 0 ? z1 : x1;
            int d = Math.min(Math.min(b - b0, b1 - b), Math.min(y - y0, y1 - y));
            int innerHalf = Math.min(b1 - b0, y1 - y0) / 2;
            if (d >= innerHalf - 1) {
                // The heart of the recursion.
                return backplate ? DATA : GLOW_WHITE;
            }
            if (d % 3 == 0) {
                int ring = d / 3;
                return ring == 0 ? FRAME : color(palette, ring);
            }
            return backplate ? VOID : AIR;
        }

        private int platform(int x, int y, int z) {
            if (y == y1) {
                boolean cornerX = x == x0 || x == x1;
                boolean cornerZ = z == z0 || z == z1;
                if (cornerX && cornerZ) {
                    return color(palette, 0);
                }
                return cornerX || cornerZ ? FRAME : GRID;
            }
            return VOID;
        }

        private int monolith(int x, int y, int z) {
            int b = axis == 0 ? z : x;
            int b0 = axis == 0 ? z0 : x0;
            int b1 = axis == 0 ? z1 : x1;
            boolean edge = b == b0 || b == b1 || y == y0 || y == y1;
            return edge ? FRAME : DATA;
        }

        private int lattice(int x, int z) {
            boolean onX = Math.floorMod(x - x0, a0) == 0;
            boolean onZ = Math.floorMod(z - z0, a0) == 0;
            if (onX && onZ) {
                return color(palette, 0);
            }
            return onX || onZ ? FRAME : AIR;
        }
    }

    /**
     * The structures of one void cell (the square between lines {@code cellX} and
     * {@code cellX + 1}, {@code cellZ} and {@code cellZ + 1}).
     *
     * @return number of features written to {@code out}
     */
    static int cellFeatures(long seed, int cellX, int cellZ, Feature[] out, Dice dice) {
        dice.reset(hash(seed, SALT_CELL, cellX, cellZ, 0));
        int ox = cellX * CELL;
        int oz = cellZ * CELL;
        int count = 0;
        boolean upper = dice.nextBoolean();
        float roll = dice.nextFloat();
        if (roll < 0.24F) {
            count = gates(dice, ox, oz, upper, out, count);
        } else if (roll < 0.40F) {
            count = portal(dice, ox, oz, upper, out, count);
        } else if (roll < 0.62F) {
            count = platforms(dice, ox, oz, upper, 1 + dice.nextInt(3), out, count);
        } else if (roll < 0.71F) {
            count = monolith(dice, ox, oz, upper, out, count);
        } else if (roll < 0.78F) {
            count = lattice(dice, ox, oz, upper, out, count);
        }
        if (dice.nextFloat() < 0.45F) {
            boolean other = !upper;
            float second = dice.nextFloat();
            if (second < 0.55F) {
                count = platforms(dice, ox, oz, other, 1, out, count);
            } else if (second < 0.8F) {
                count = lattice(dice, ox, oz, other, out, count);
            } else {
                count = gates(dice, ox, oz, other, out, count);
            }
        }
        if (cellX >= -1 && cellX <= 0 && cellZ >= -1 && cellZ <= 0) {
            // Keep the sky above the spawn platform clear of anything seed-dependent.
            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (!out[i].intersects(-CLEARANCE_RADIUS, CLEARANCE_MIN_Y, -CLEARANCE_RADIUS,
                        CLEARANCE_RADIUS, CLEARANCE_MAX_Y, CLEARANCE_RADIUS)) {
                    if (kept != i) {
                        out[kept].copy(out[i]);
                    }
                    kept++;
                }
            }
            count = kept;
        }
        return count;
    }

    /** A random base y for a structure of the given height in the lower or upper half. */
    private static int bandY(Dice dice, boolean upper, int height) {
        int low = upper ? 128 : 6;
        int high = upper ? 246 : 122;
        int room = Math.max(1, high - low - height);
        return low + dice.nextInt(room);
    }

    private static int gates(Dice dice, int ox, int oz, boolean upper, Feature[] out, int count) {
        if (count >= MAX_FEATURES) {
            return count;
        }
        Feature f = out[count];
        f.type = F_GATES;
        f.axis = dice.nextInt(2);
        f.count = 3 + dice.nextInt(4);
        f.spacing = 5 + dice.nextInt(4);
        int width = 18 + dice.nextInt(17);
        int height = 18 + dice.nextInt(23);
        f.thick = width >= 26 ? 2 : 1;
        f.palette = dice.nextInt(PALETTES.length);
        int span = (f.count - 1) * f.spacing;
        int a0 = 28 - span / 2;
        int b0 = 28 - width / 2;
        f.y0 = bandY(dice, upper, height);
        f.y1 = f.y0 + height - 1;
        if (f.axis == 0) {
            f.a0 = ox + a0;
            f.x0 = ox + a0;
            f.x1 = ox + a0 + span;
            f.z0 = oz + b0;
            f.z1 = oz + b0 + width - 1;
        } else {
            f.a0 = oz + a0;
            f.z0 = oz + a0;
            f.z1 = oz + a0 + span;
            f.x0 = ox + b0;
            f.x1 = ox + b0 + width - 1;
        }
        return count + 1;
    }

    private static int portal(Dice dice, int ox, int oz, boolean upper, Feature[] out, int count) {
        if (count >= MAX_FEATURES) {
            return count;
        }
        Feature f = out[count];
        f.type = F_PORTAL;
        f.axis = dice.nextInt(2);
        int width = 21 + 2 * dice.nextInt(8);
        int height = 21 + 2 * dice.nextInt(11);
        f.backplate = dice.nextFloat() < 0.5F;
        f.palette = dice.nextInt(PALETTES.length);
        int b0 = 28 - width / 2;
        f.y0 = bandY(dice, upper, height);
        f.y1 = f.y0 + height - 1;
        if (f.axis == 0) {
            f.a0 = ox + 28;
            f.x0 = f.a0;
            f.x1 = f.a0;
            f.z0 = oz + b0;
            f.z1 = oz + b0 + width - 1;
        } else {
            f.a0 = oz + 28;
            f.z0 = f.a0;
            f.z1 = f.a0;
            f.x0 = ox + b0;
            f.x1 = ox + b0 + width - 1;
        }
        return count + 1;
    }

    private static int platforms(Dice dice, int ox, int oz, boolean upper, int platforms, Feature[] out, int count) {
        int baseY = bandY(dice, upper, 40) + 4;
        for (int i = 0; i < platforms && count < MAX_FEATURES; i++) {
            Feature f = out[count++];
            f.type = F_PLATFORM;
            int w = 8 + dice.nextInt(13);
            int d = 8 + dice.nextInt(13);
            int x0 = 8 + dice.nextInt(42 - w);
            int z0 = 8 + dice.nextInt(42 - d);
            int thick = 1 + dice.nextInt(2);
            int top = Math.min(MAX_Y - 20, baseY + i * (9 + dice.nextInt(8)));
            f.palette = dice.nextInt(PALETTES.length);
            f.x0 = ox + x0;
            f.x1 = ox + x0 + w - 1;
            f.z0 = oz + z0;
            f.z1 = oz + z0 + d - 1;
            f.y1 = top;
            f.y0 = top - thick + 1;
            if (i == 0 && count < MAX_FEATURES && dice.nextFloat() < 0.55F) {
                Feature t = out[count++];
                t.type = F_TOWER;
                int cx = f.x0 + w / 2 - 1;
                int cz = f.z0 + d / 2 - 1;
                t.x0 = cx;
                t.x1 = cx + 1;
                t.z0 = cz;
                t.z1 = cz + 1;
                t.y0 = top + 1;
                t.y1 = top + 6 + dice.nextInt(10);
            }
        }
        return count;
    }

    private static int monolith(Dice dice, int ox, int oz, boolean upper, Feature[] out, int count) {
        if (count >= MAX_FEATURES) {
            return count;
        }
        Feature f = out[count];
        f.type = F_MONOLITH;
        f.axis = dice.nextInt(2);
        int width = 8 + dice.nextInt(9);
        int height = 20 + dice.nextInt(25);
        int thick = 1 + dice.nextInt(2);
        int b0 = 28 - width / 2;
        f.y0 = bandY(dice, upper, height);
        f.y1 = f.y0 + height - 1;
        if (f.axis == 0) {
            f.x0 = ox + 28;
            f.x1 = ox + 28 + thick - 1;
            f.z0 = oz + b0;
            f.z1 = oz + b0 + width - 1;
        } else {
            f.z0 = oz + 28;
            f.z1 = oz + 28 + thick - 1;
            f.x0 = ox + b0;
            f.x1 = ox + b0 + width - 1;
        }
        return count + 1;
    }

    private static int lattice(Dice dice, int ox, int oz, boolean upper, Feature[] out, int count) {
        if (count >= MAX_FEATURES) {
            return count;
        }
        Feature f = out[count];
        f.type = F_LATTICE;
        f.a0 = 4 + dice.nextInt(3);
        int size = 24 + dice.nextInt(15);
        int x0 = 28 - size / 2;
        f.x0 = ox + x0;
        f.x1 = ox + x0 + size - 1;
        f.z0 = oz + x0;
        f.z1 = oz + x0 + size - 1;
        f.y0 = bandY(dice, upper, 1);
        f.y1 = f.y0;
        f.palette = dice.nextInt(PALETTES.length);
        return count + 1;
    }

    // ================================================================== helpers

    static int color(int palette, int index) {
        int[] colors = PALETTES[Math.floorMod(palette, PALETTES.length)];
        return colors[Math.floorMod(index, colors.length)];
    }

    /** A block that is simply missing: the structure is failing. */
    private static boolean hole(long seed, int x, int y, int z, float chance) {
        return unit(hash(seed, SALT_HOLE, x, y, z), 8) < chance;
    }

    static long hash(long seed, long salt, int a, int b, int c) {
        long h = mix(seed ^ (salt * 0x9E3779B97F4A7C15L));
        h = mix(h + a * 0xC2B2AE3D27D4EB4FL);
        h = mix(h + b * 0x165667B19E3779F9L);
        return mix(h + c * 0x27D4EB2F165667C5L);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 16 bits of {@code h} starting at {@code shift}, as a float in [0, 1). */
    static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFFFFL) / 65536.0F;
    }

    /** A tiny deterministic random sequence (SplitMix64). */
    static final class Dice {
        private long state;

        void reset(long seed) {
            state = seed;
        }

        long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            return mix(state);
        }

        int nextInt(int bound) {
            if (bound <= 1) {
                return 0;
            }
            return (int) ((nextLong() >>> 33) % bound);
        }

        boolean nextBoolean() {
            return (nextLong() & 1L) != 0L;
        }

        float nextFloat() {
            return (nextLong() >>> 40) / (float) (1 << 24);
        }
    }

    private static BlockState[] buildStates() {
        BlockState[] table = new BlockState[MATERIALS];
        table[AIR] = Blocks.AIR.defaultBlockState();
        table[VOID] = NullBlocks.VOID_PANEL.get().defaultBlockState();
        table[GRID] = NullBlocks.GRID_PANEL.get().defaultBlockState();
        table[FRAME] = NullBlocks.FRAME_BLOCK.get().defaultBlockState();
        table[DATA] = NullBlocks.DATA_BLOCK.get().defaultBlockState();
        table[GLASS] = NullBlocks.NULL_GLASS.get().defaultBlockState();
        table[GLOW_RED] = NullBlocks.GLOW_RED.get().defaultBlockState();
        table[GLOW_GREEN] = NullBlocks.GLOW_GREEN.get().defaultBlockState();
        table[GLOW_BLUE] = NullBlocks.GLOW_BLUE.get().defaultBlockState();
        table[GLOW_CYAN] = NullBlocks.GLOW_CYAN.get().defaultBlockState();
        table[GLOW_MAGENTA] = NullBlocks.GLOW_MAGENTA.get().defaultBlockState();
        table[GLOW_YELLOW] = NullBlocks.GLOW_YELLOW.get().defaultBlockState();
        table[GLOW_WHITE] = NullBlocks.GLOW_WHITE.get().defaultBlockState();
        return table;
    }
}
