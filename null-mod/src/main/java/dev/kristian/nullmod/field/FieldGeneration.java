package dev.kristian.nullmod.field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The field as pure functions of its seed: an endless, gently rolling plain of ripe wheat on
 * farmland, a few old wooden buildings and some scenery. The chunk generator, the arrival and
 * respawn logic and the tests all ask the same functions, so they always agree.
 * <ul>
 * <li><b>Ground.</b> Two octaves of smooth value noise give heights within a few blocks of
 * {@link #BASE_Y}: bedrock, stone, dirt, then dry farmland with fully grown wheat on it.</li>
 * <li><b>Houses</b> ({@link FieldHouse}) stand on a staggered (roughly hexagonal) lattice, turned by
 * a seed-dependent angle and jittered, so they are never in a regular grid but always 40-90 blocks
 * from their nearest neighbour, and no point of the field is more than about 60 blocks from one.
 * The ground around a house is levelled to its floor and cleared into a small yard, then blends
 * back into the field.</li>
 * <li><b>Scenery</b> ({@link FieldScenery}) sits in jittered cells, on level ground, far from the
 * houses.</li>
 * </ul>
 * Everything is deterministic and local: one column depends only on houses and scenery within a few
 * dozen blocks, so chunks can be generated in any order on any thread.
 */
public final class FieldGeneration {
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 256;
    public static final int MAX_Y = MIN_Y + HEIGHT - 1;
    /** Average height of the farmland; the wheat stands one block above it. */
    public static final int BASE_Y = 64;

    // Houses: a staggered lattice (rows CELL_Z apart, every other row shifted by half a cell).
    static final double CELL_X = 78.0;
    static final double CELL_Z = 68.0;
    static final double JITTER = 10.0;
    /** The ground blends from a yard back to the natural height over this many blocks. */
    static final int BLEND = 8;
    /** Houses whose centre is further than this from a column never affect it. */
    static final int HOUSE_REACH = 24;

    // Scenery
    static final int SCENERY_CELL = 24;
    /** Scenery whose anchor is further than this from a column never affects it (fence lines are long). */
    static final int SCENERY_REACH = 30;
    /** Scenery keeps at least this far from every yard (so the arrival spots near a house are clear too). */
    static final int SCENERY_CLEARANCE = BLEND + 12;

    static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90};

    /** Where the generator writes. Positions outside the sink's area are ignored. */
    public interface Sink {
        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);
    }

    private FieldGeneration() {
    }

    // ================================================================== hashing and noise

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long hash(long seed, long a, long b) {
        return mix(seed + a * 0x9E3779B97F4A7C15L + mix(b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L));
    }

    /** Deterministic value in [0, 1). */
    static float hash01(long seed, long a, long b) {
        return (hash(seed, a, b) >>> 40) / (float) (1L << 24);
    }

    private static double hashSigned(long seed, long a, long b) {
        return ((hash(seed, a, b) >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    /** Smooth value noise in [-1, 1] with one feature per {@code scale} blocks. */
    static double valueNoise(long seed, double x, double z, double scale) {
        double fx = x / scale;
        double fz = z / scale;
        long x0 = (long) Math.floor(fx);
        long z0 = (long) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;
        double sx = tx * tx * (3.0 - 2.0 * tx);
        double sz = tz * tz * (3.0 - 2.0 * tz);
        double a = hashSigned(seed, x0, z0);
        double b = hashSigned(seed, x0 + 1, z0);
        double c = hashSigned(seed, x0, z0 + 1);
        double d = hashSigned(seed, x0 + 1, z0 + 1);
        double ab = a + (b - a) * sx;
        double cd = c + (d - c) * sx;
        return ab + (cd - ab) * sz;
    }

    // ================================================================== ground

    /** The natural ground height (the farmland level) before any yard is levelled. */
    public static int rawGround(long seed, int x, int z) {
        double n = 2.6 * valueNoise(seed ^ 0x6669656C6431L, x, z, 97.0)
                + 1.2 * valueNoise(seed ^ 0x6669656C6432L, x, z, 41.0)
                + 0.4 * valueNoise(seed ^ 0x6669656C6433L, x, z, 17.0);
        return BASE_Y + (int) Math.round(n);
    }

    /** The ground height (farmland, yard or floor level) at a column, yards included. */
    public static int groundY(long seed, int x, int z) {
        return groundY(seed, x, z, housesNear(seed, x, z));
    }

    static int groundY(long seed, int x, int z, List<FieldHouse> houses) {
        FieldHouse near = null;
        double distance = Double.MAX_VALUE;
        for (FieldHouse house : houses) {
            double d = house.padDistance(x, z);
            if (d < distance) {
                distance = d;
                near = house;
            }
        }
        int raw = rawGround(seed, x, z);
        if (near == null || distance >= BLEND) {
            return raw;
        }
        if (distance <= 0.0) {
            return near.floor();
        }
        double t = distance / BLEND;
        t = t * t * (3.0 - 2.0 * t);
        return (int) Math.round(near.floor() + (raw - near.floor()) * t);
    }

    // ================================================================== houses

    /** Seed-dependent turn of the lattice (10-35 degrees) and offset, so (0, 0) is nothing special. */
    private static double latticeAngle(long seed) {
        return Math.toRadians(10.0 + 25.0 * hash01(seed, 0x4C41L, 1));
    }

    private static double latticeOffsetX(long seed) {
        return hash01(seed, 0x4C41L, 2) * CELL_X;
    }

    private static double latticeOffsetZ(long seed) {
        return hash01(seed, 0x4C41L, 3) * CELL_Z;
    }

    /** The house of lattice cell (i, j), before the home house is turned to face the arrivals. */
    private static FieldHouse rawHouse(long seed, long i, long j) {
        long key = hash(seed ^ 0x686F757365L, i, j);
        double lx = i * CELL_X + ((j & 1L) != 0 ? CELL_X * 0.5 : 0.0) + (hash01(key, 1, 0) * 2.0 - 1.0) * JITTER;
        double lz = j * CELL_Z + (hash01(key, 2, 0) * 2.0 - 1.0) * JITTER;
        double angle = latticeAngle(seed);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        int x = (int) Math.floor(latticeOffsetX(seed) + lx * cos - lz * sin);
        int z = (int) Math.floor(latticeOffsetZ(seed) + lx * sin + lz * cos);
        float roll = hash01(key, 3, 0);
        FieldHouse.Type type = roll < 0.5F ? FieldHouse.Type.FARMHOUSE : roll < 0.75F ? FieldHouse.Type.BARN : FieldHouse.Type.SHED;
        int rotation = (int) (hash01(key, 4, 0) * 4.0F) & 3;
        int palette = hash01(key, 5, 0) < 0.5F ? 0 : 1;
        return new FieldHouse(key, x, z, rawGround(seed, x, z), type, rotation, palette);
    }

    private static FieldHouse house(long seed, long i, long j) {
        FieldHouse house = rawHouse(seed, i, j);
        return house.key() == homeKey(seed) ? house.withRotation(0) : house;
    }

    /** Adds every house whose centre lies in the box. */
    static void housesIn(long seed, int minX, int minZ, int maxX, int maxZ, List<FieldHouse> out, boolean raw) {
        double angle = latticeAngle(seed);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double ox = latticeOffsetX(seed);
        double oz = latticeOffsetZ(seed);
        // The box in lattice space (rotated back), widened by the jitter.
        double lminX = Double.MAX_VALUE;
        double lmaxX = -Double.MAX_VALUE;
        double lminZ = Double.MAX_VALUE;
        double lmaxZ = -Double.MAX_VALUE;
        int[][] corners = {{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}};
        for (int[] corner : corners) {
            double wx = corner[0] - ox;
            double wz = corner[1] - oz;
            double lx = wx * cos + wz * sin;
            double lz = -wx * sin + wz * cos;
            lminX = Math.min(lminX, lx);
            lmaxX = Math.max(lmaxX, lx);
            lminZ = Math.min(lminZ, lz);
            lmaxZ = Math.max(lmaxZ, lz);
        }
        double margin = JITTER + 2.0;
        long j0 = (long) Math.floor((lminZ - margin) / CELL_Z);
        long j1 = (long) Math.ceil((lmaxZ + margin) / CELL_Z);
        for (long j = j0; j <= j1; j++) {
            double shift = (j & 1L) != 0 ? CELL_X * 0.5 : 0.0;
            long i0 = (long) Math.floor((lminX - margin - shift) / CELL_X);
            long i1 = (long) Math.ceil((lmaxX + margin - shift) / CELL_X);
            for (long i = i0; i <= i1; i++) {
                FieldHouse house = raw ? rawHouse(seed, i, j) : house(seed, i, j);
                if (house.x() >= minX && house.x() <= maxX && house.z() >= minZ && house.z() <= maxZ) {
                    out.add(house);
                }
            }
        }
    }

    /** Houses that can affect the column (x, z). */
    static List<FieldHouse> housesNear(long seed, int x, int z) {
        List<FieldHouse> houses = new ArrayList<>(2);
        housesIn(seed, x - HOUSE_REACH, z - HOUSE_REACH, x + HOUSE_REACH, z + HOUSE_REACH, houses, false);
        return houses;
    }

    /** Every house whose centre lies in the box (for tests and searches). */
    public static List<FieldHouse> houses(long seed, int minX, int minZ, int maxX, int maxZ) {
        List<FieldHouse> houses = new ArrayList<>();
        housesIn(seed, minX, minZ, maxX, maxZ, houses, false);
        return houses;
    }

    /** The house nearest to a column (searching further out until one is found). */
    public static FieldHouse nearestHouse(long seed, int x, int z) {
        for (int radius = 64; ; radius *= 2) {
            List<FieldHouse> houses = new ArrayList<>();
            housesIn(seed, x - radius, z - radius, x + radius, z + radius, houses, false);
            FieldHouse best = null;
            long bestDistance = Long.MAX_VALUE;
            for (FieldHouse house : houses) {
                long dx = house.x() - x;
                long dz = house.z() - z;
                long distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = house;
                }
            }
            // The nearest one is certain once it is closer than the edge of the searched box.
            if (best != null && bestDistance <= (long) radius * radius) {
                return best;
            }
        }
    }

    /** Home house key per seed (a server has one field; tests use a few seeds). */
    private static final Map<Long, Long> HOME_KEYS = new ConcurrentHashMap<>();

    /** The key of the home house (nearest to the origin), where everyone arrives. Cached per seed. */
    private static long homeKey(long seed) {
        Long cached = HOME_KEYS.get(seed);
        if (cached != null) {
            return cached;
        }
        long key = nearestRaw(seed);
        if (HOME_KEYS.size() > 32) {
            HOME_KEYS.clear();
        }
        HOME_KEYS.put(seed, key);
        return key;
    }

    /** Key of the lattice house nearest to the origin, searching outwards until it is certain. */
    private static long nearestRaw(long seed) {
        for (int radius = 64; ; radius *= 2) {
            List<FieldHouse> houses = new ArrayList<>();
            housesIn(seed, -radius, -radius, radius, radius, houses, true);
            FieldHouse best = null;
            long bestDistance = Long.MAX_VALUE;
            for (FieldHouse house : houses) {
                long distance = (long) house.x() * house.x() + (long) house.z() * house.z();
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = house;
                }
            }
            if (best != null && bestDistance <= (long) radius * radius) {
                return best.key();
            }
        }
    }

    /** The house where everyone arrives: the one nearest the origin, its door facing south. */
    public static FieldHouse homeHouse(long seed) {
        return nearestHouse(seed, 0, 0);
    }

    // ================================================================== scenery

    private static double sceneryOffsetX(long seed) {
        return hash01(seed, 0x5343L, 1) * SCENERY_CELL;
    }

    private static double sceneryOffsetZ(long seed) {
        return hash01(seed, 0x5343L, 2) * SCENERY_CELL;
    }

    /** The scenery of cell (i, j), or null if the cell is empty or its item does not fit there. */
    @Nullable
    private static FieldScenery scenery(long seed, long i, long j) {
        long key = hash(seed ^ 0x7363656E65L, i, j);
        float roll = hash01(key, 1, 0);
        FieldScenery.Kind kind;
        if (roll < 0.40F) {
            return null;
        } else if (roll < 0.58F) {
            kind = FieldScenery.Kind.HAY;
        } else if (roll < 0.71F) {
            kind = FieldScenery.Kind.SCARECROW;
        } else if (roll < 0.85F) {
            kind = FieldScenery.Kind.FENCE;
        } else if (roll < 0.95F) {
            kind = FieldScenery.Kind.TREE;
        } else {
            kind = FieldScenery.Kind.CART;
        }
        int x = (int) Math.floor(sceneryOffsetX(seed) + i * SCENERY_CELL + 4 + hash01(key, 2, 0) * (SCENERY_CELL - 8));
        int z = (int) Math.floor(sceneryOffsetZ(seed) + j * SCENERY_CELL + 4 + hash01(key, 3, 0) * (SCENERY_CELL - 8));
        int rotation = (int) (hash01(key, 4, 0) * 4.0F) & 3;
        int size = switch (kind) {
            case HAY -> 1 + (int) (hash01(key, 5, 0) * 4.0F);
            case FENCE -> 8 + (int) (hash01(key, 5, 0) * 17.0F);
            case TREE -> 4 + (int) (hash01(key, 5, 0) * 4.0F);
            default -> (int) (hash01(key, 5, 0) * 4.0F);
        };
        if (kind == FieldScenery.Kind.SCARECROW && hash01(key, 6, 0) < 0.7F) {
            // Most scarecrows watch the way people come from, with the sun at their backs.
            rotation = 0;
        }
        if (kind == FieldScenery.Kind.FENCE) {
            rotation &= 1;
        }
        FieldScenery item = new FieldScenery(key, kind, x, z, rawGround(seed, x, z), rotation, size);
        // Keep well away from every yard, so the item stands on natural ground.
        List<FieldHouse> houses = new ArrayList<>(2);
        housesIn(seed, item.minX() - HOUSE_REACH - SCENERY_CLEARANCE, item.minZ() - HOUSE_REACH - SCENERY_CLEARANCE,
                item.maxX() + HOUSE_REACH + SCENERY_CLEARANCE, item.maxZ() + HOUSE_REACH + SCENERY_CLEARANCE, houses, false);
        for (FieldHouse house : houses) {
            if (boxDistance(item, house) < SCENERY_CLEARANCE) {
                return null;
            }
        }
        if (kind != FieldScenery.Kind.FENCE) {
            for (int wx = item.minX(); wx <= item.maxX(); wx++) {
                for (int wz = item.minZ(); wz <= item.maxZ(); wz++) {
                    if (item.needsLevelGround(wx, wz) && rawGround(seed, wx, wz) != item.ground()) {
                        return null;
                    }
                }
            }
        }
        return item;
    }

    private static double boxDistance(FieldScenery item, FieldHouse house) {
        int dx = Math.max(0, Math.max(house.padMinX() - item.maxX(), item.minX() - house.padMaxX()));
        int dz = Math.max(0, Math.max(house.padMinZ() - item.maxZ(), item.minZ() - house.padMaxZ()));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Scenery that touches the box, in writing order. */
    static List<FieldScenery> sceneryTouching(long seed, int minX, int minZ, int maxX, int maxZ) {
        List<FieldScenery> out = new ArrayList<>();
        double ox = sceneryOffsetX(seed);
        double oz = sceneryOffsetZ(seed);
        long i0 = (long) Math.floor((minX - SCENERY_REACH - ox) / SCENERY_CELL);
        long i1 = (long) Math.floor((maxX + SCENERY_REACH - ox) / SCENERY_CELL);
        long j0 = (long) Math.floor((minZ - SCENERY_REACH - oz) / SCENERY_CELL);
        long j1 = (long) Math.floor((maxZ + SCENERY_REACH - oz) / SCENERY_CELL);
        for (long j = j0; j <= j1; j++) {
            for (long i = i0; i <= i1; i++) {
                FieldScenery item = scenery(seed, i, j);
                if (item != null && item.maxX() >= minX && item.minX() <= maxX && item.maxZ() >= minZ && item.minZ() <= maxZ) {
                    out.add(item);
                }
            }
        }
        out.sort(Comparator.comparingLong(FieldScenery::key));
        return out;
    }

    // ================================================================== writing

    /** Generates the area {@code [minX, maxX] x [minZ, maxZ]} (all heights) into the sink. */
    public static void fill(long seed, int minX, int minZ, int maxX, int maxZ, Sink sink) {
        List<FieldHouse> houses = new ArrayList<>();
        housesIn(seed, minX - HOUSE_REACH, minZ - HOUSE_REACH, maxX + HOUSE_REACH, maxZ + HOUSE_REACH, houses, false);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                terrain(seed, x, z, houses, sink);
            }
        }
        for (FieldHouse house : houses) {
            int x0 = Math.max(minX, house.minX());
            int x1 = Math.min(maxX, house.maxX());
            int z0 = Math.max(minZ, house.minZ());
            int z1 = Math.min(maxZ, house.maxZ());
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = house.floor(); y <= house.topY(); y++) {
                        BlockState state = house.blockAt(x, y, z);
                        if (state != null) {
                            sink.set(x, y, z, state);
                        }
                    }
                }
            }
        }
        for (FieldScenery item : sceneryTouching(seed, minX, minZ, maxX, maxZ)) {
            int x0 = Math.max(minX, item.minX());
            int x1 = Math.min(maxX, item.maxX());
            int z0 = Math.max(minZ, item.minZ());
            int z1 = Math.min(maxZ, item.maxZ());
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    item.writeColumn(seed, x, z, rawGround(seed, x, z), sink);
                }
            }
        }
    }

    /** Bedrock, stone, dirt and the surface of one column: the field, a yard or a floor. */
    private static void terrain(long seed, int x, int z, List<FieldHouse> houses, Sink sink) {
        FieldHouse near = null;
        double distance = Double.MAX_VALUE;
        for (FieldHouse house : houses) {
            double d = house.padDistance(x, z);
            if (d < distance) {
                distance = d;
                near = house;
            }
        }
        int ground = groundY(seed, x, z, houses);
        sink.set(x, MIN_Y, z, FieldBlocks.BEDROCK);
        for (int y = MIN_Y + 1; y < ground - 3; y++) {
            sink.set(x, y, z, FieldBlocks.STONE);
        }
        for (int y = Math.max(MIN_Y + 1, ground - 3); y < ground; y++) {
            sink.set(x, y, z, FieldBlocks.DIRT);
        }
        if (near != null && distance <= 0.0) {
            if (near.inFootprint(x, z)) {
                sink.set(x, ground, z, FieldBlocks.DIRT);
                return;
            }
            yard(seed, x, z, ground, near, sink);
            return;
        }
        // Now and then a bare spot where nothing came up. It is not farmland, which would turn to
        // dirt on its own without a crop on it: the field never changes by itself.
        if (hash01(seed ^ 0x77686561L, x, z) < 0.015F) {
            sink.set(x, ground, z, FieldBlocks.COARSE_DIRT);
            if (hash01(seed ^ 0x77686562L, x, z) < 0.3F) {
                sink.set(x, ground + 1, z, FieldBlocks.DEAD_BUSH);
            }
            return;
        }
        sink.set(x, ground, z, FieldBlocks.FARMLAND);
        sink.set(x, ground + 1, z, FieldBlocks.WHEAT);
    }

    /** The trodden ground around a house: coarse dirt, dry grass, a path from the door. */
    private static void yard(long seed, int x, int z, int ground, FieldHouse house, Sink sink) {
        if (house.onPath(x, z)) {
            sink.set(x, ground, z, FieldBlocks.DIRT_PATH);
            return;
        }
        float roll = hash01(seed ^ 0x79617264L, x, z);
        if (roll < 0.32F) {
            sink.set(x, ground, z, FieldBlocks.GRASS_BLOCK);
            if (hash01(seed ^ 0x79617265L, x, z) < 0.35F) {
                sink.set(x, ground + 1, z, FieldBlocks.SHORT_GRASS);
            }
        } else {
            sink.set(x, ground, z, FieldBlocks.COARSE_DIRT);
            if (roll > 0.97F) {
                sink.set(x, ground + 1, z, FieldBlocks.DEAD_BUSH);
            }
        }
    }

    // ================================================================== queries

    /** One column as generated, indexed by {@code y - MIN_Y}. */
    public static BlockState[] column(long seed, int x, int z) {
        BlockState[] states = new BlockState[HEIGHT];
        Arrays.fill(states, FieldBlocks.AIR);
        fill(seed, x, z, x, z, new Sink() {
            @Override
            public BlockState get(int px, int py, int pz) {
                return px == x && pz == z && py >= MIN_Y && py <= MAX_Y ? states[py - MIN_Y] : FieldBlocks.AIR;
            }

            @Override
            public void set(int px, int py, int pz, BlockState state) {
                if (px == x && pz == z && py >= MIN_Y && py <= MAX_Y) {
                    states[py - MIN_Y] = state;
                }
            }
        });
        return states;
    }

    /** The generated block at one position (slow: generates the whole column). */
    public static BlockState sample(long seed, int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            return FieldBlocks.AIR;
        }
        return column(seed, x, z)[y - MIN_Y];
    }

    /**
     * Where players arrive (feet positions as {x, y, z}): in the wheat south-east of the home house,
     * whose door faces them, so that standing there facing the sun (north) the house is ahead on
     * the left, close enough to reach in seconds and clear of the sun. In order of preference; the
     * caller checks each against the real level.
     */
    public static List<int[]> arrivalSpots(long seed) {
        FieldHouse house = homeHouse(seed);
        int[][] offsets = {{5, 14}, {8, 15}, {5, 17}, {9, 12}, {11, 16}, {3, 16}, {8, 18}, {12, 13}, {2, 19}, {12, 18}};
        List<int[]> spots = new ArrayList<>(offsets.length);
        for (int[] offset : offsets) {
            int x = house.x() + house.halfWidthX() + offset[0];
            int z = house.z() + house.halfWidthZ() + offset[1];
            spots.add(new int[]{x, groundY(seed, x, z) + 1, z});
        }
        return spots;
    }

    /**
     * Spots in front of a house's door (feet positions as {x, y, z}), for respawning: on the path
     * and beside it, a few blocks out. In order of preference.
     */
    public static List<int[]> doorSpots(long seed, FieldHouse house) {
        int ox = house.front().getStepX();
        int oz = house.front().getStepZ();
        int[][] offsets = {{3, 0}, {3, 1}, {3, -1}, {4, 2}, {4, -2}, {5, 0}, {2, 2}, {2, -2}};
        List<int[]> spots = new ArrayList<>(offsets.length);
        for (int[] offset : offsets) {
            int along = offset[0];
            int side = offset[1];
            int x = house.doorX() + ox * along + oz * side;
            int z = house.doorZ() + oz * along - ox * side;
            spots.add(new int[]{x, groundY(seed, x, z) + 1, z});
        }
        return spots;
    }
}
