package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.field.FieldChunkGenerator;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldGeneration;
import dev.kristian.nullmod.field.FieldHouse;
import dev.kristian.nullmod.field.FieldShelter;
import dev.kristian.nullmod.field.FieldTuning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The field's generator and cycle, tested as functions: the GameTest server never loads datapack
 * dimensions (the real dimension is checked by {@code FieldSelfCheck} on the CI smoke-test servers).
 * Houses are checked against {@link FieldShelter}, the field's copy of the shelter test IT uses.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class FieldGameTests {
    private static final long[] SEEDS = {0L, -7L, 0x123456789ABCDEFL, FieldChunkGenerator.seedFor(42L)};

    private FieldGameTests() {
    }

    /** A region generated into memory. */
    private static final class Region implements FieldGeneration.Sink {
        private final Map<Long, BlockState> blocks = new HashMap<>();
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;

        Region(long seed, int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            FieldGeneration.fill(seed, minX, minZ, maxX, maxZ, this);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
        }

        @Override
        public BlockState get(int x, int y, int z) {
            BlockState state = blocks.get(key(x, y, z));
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }

        @Override
        public void set(int x, int y, int z, BlockState state) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return;
            }
            if (state.isAir()) {
                blocks.remove(key(x, y, z));
            } else {
                blocks.put(key(x, y, z), state);
            }
        }

        boolean standable(int x, int y, int z) {
            return FieldShelter.solid(get(x, y - 1, z)) && !FieldShelter.solid(get(x, y, z)) && !FieldShelter.solid(get(x, y + 1, z));
        }
    }

    @GameTest(template = "empty", batch = "field")
    public static void generatorIsDeterministic(GameTestHelper helper) {
        RandomSource random = RandomSource.create(17L);
        for (long seed : SEEDS) {
            for (int c = 0; c < 4; c++) {
                int cx = random.nextInt(200) - 100;
                int cz = random.nextInt(200) - 100;
                Region first = new Region(seed, cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15);
                Region second = new Region(seed, cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15);
                helper.assertTrue(first.blocks.equals(second.blocks), "chunk " + cx + " " + cz + " differs between two runs (seed " + seed + ")");
                // A single column must come out exactly as the whole chunk generates it.
                for (int i = 0; i < 12; i++) {
                    int x = cx * 16 + random.nextInt(16);
                    int z = cz * 16 + random.nextInt(16);
                    BlockState[] column = FieldGeneration.column(seed, x, z);
                    for (int y = FieldGeneration.MIN_Y; y <= FieldGeneration.MAX_Y; y++) {
                        if (column[y - FieldGeneration.MIN_Y] != first.get(x, y, z)) {
                            helper.fail("column " + x + " " + z + " differs from its chunk at y " + y + " (seed " + seed + ")");
                        }
                    }
                }
            }
        }
        // Different seeds, different fields.
        int differences = 0;
        for (int i = 0; i < 400; i++) {
            int x = random.nextInt(4000) - 2000;
            int z = random.nextInt(4000) - 2000;
            if (FieldGeneration.groundY(1L, x, z) != FieldGeneration.groundY(2L, x, z)) {
                differences++;
            }
        }
        helper.assertTrue(differences > 40, "different seeds give the same ground (" + differences + " differences)");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "field")
    public static void wheatStaysAlive(GameTestHelper helper) {
        RandomSource random = RandomSource.create(23L);
        int wheat = 0;
        int columns = 0;
        for (long seed : SEEDS) {
            for (int i = 0; i < 300; i++) {
                int x = random.nextInt(3000) - 1500;
                int z = random.nextInt(3000) - 1500;
                BlockState[] column = FieldGeneration.column(seed, x, z);
                columns++;
                for (int y = FieldGeneration.MIN_Y + 1; y <= FieldGeneration.MAX_Y; y++) {
                    BlockState state = column[y - FieldGeneration.MIN_Y];
                    if (!state.is(Blocks.WHEAT)) {
                        continue;
                    }
                    wheat++;
                    BlockState soil = column[y - 1 - FieldGeneration.MIN_Y];
                    helper.assertTrue(soil.is(Blocks.FARMLAND), "wheat without farmland at " + x + " " + y + " " + z);
                    helper.assertTrue(state.getValue(BlockStateProperties.AGE_7) == 7, "wheat not fully grown at " + x + " " + z);
                    // Crops need light 8 or a view of the sky: nothing may stand over the wheat.
                    for (int above = y + 1; above <= FieldGeneration.MAX_Y; above++) {
                        if (!column[above - FieldGeneration.MIN_Y].isAir()) {
                            helper.fail("wheat at " + x + " " + y + " " + z + " is covered by " + column[above - FieldGeneration.MIN_Y]);
                        }
                    }
                }
                // Farmland only ever lies under a crop, which keeps it farmland.
                for (int y = FieldGeneration.MIN_Y; y < FieldGeneration.MAX_Y; y++) {
                    if (column[y - FieldGeneration.MIN_Y].is(Blocks.FARMLAND)) {
                        helper.assertTrue(column[y + 1 - FieldGeneration.MIN_Y].is(Blocks.WHEAT), "bare farmland at " + x + " " + y + " " + z);
                    }
                }
            }
        }
        helper.assertTrue(wheat > columns * 7 / 10, "the field is not a wheat field (" + wheat + " wheat in " + columns + " columns)");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "field")
    public static void housesAreShelters(GameTestHelper helper) {
        int houses = 0;
        int floorSpots = 0;
        for (long seed : SEEDS) {
            for (FieldHouse house : FieldGeneration.houses(seed, -260, -260, 260, 260)) {
                Region region = new Region(seed, house.minX() - 10, house.minZ() - 10, house.maxX() + 10, house.maxZ() + 10);
                int y = house.floor() + 1;
                for (int x = house.minX(); x <= house.maxX(); x++) {
                    for (int z = house.minZ(); z <= house.maxZ(); z++) {
                        if (!house.inInterior(x, z) || FieldShelter.solid(region.get(x, y, z)) || FieldShelter.solid(region.get(x, y + 1, z))) {
                            continue;
                        }
                        helper.assertTrue(FieldShelter.solid(region.get(x, y - 1, z)), "no floor in " + house + " at " + x + " " + z);
                        helper.assertTrue(FieldShelter.isIndoors(region::get, x, y, z), house + " is no shelter at " + x + " " + y + " " + z);
                        floorSpots++;
                    }
                }
                // A real door, two blocks high, closed, on something solid.
                BlockState lower = region.get(house.doorX(), house.doorY(), house.doorZ());
                BlockState upper = region.get(house.doorX(), house.doorY() + 1, house.doorZ());
                helper.assertTrue(lower.getBlock() instanceof DoorBlock && lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                        && upper.getBlock() instanceof DoorBlock && upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                        && !lower.getValue(BlockStateProperties.OPEN), house + " has no closed two-block door");
                helper.assertTrue(FieldShelter.solid(region.get(house.doorX(), house.doorY() - 1, house.doorZ())), house + "'s door stands on nothing");
                // Out in front of the door is outside, and safe to stand on.
                for (int[] spot : FieldGeneration.doorSpots(seed, house)) {
                    helper.assertTrue(region.standable(spot[0], spot[1], spot[2]), "spot in front of " + house + " is not safe");
                    helper.assertTrue(!FieldShelter.isIndoors(region::get, spot[0], spot[1], spot[2]), "spot in front of " + house + " counts as indoors");
                }
                houses++;
            }
        }
        helper.assertTrue(houses >= 20 && floorSpots >= houses * 8, "too few houses checked (" + houses + ", " + floorSpots + " spots)");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "field")
    public static void housesAreSpaced(GameTestHelper helper) {
        for (long seed : SEEDS) {
            List<FieldHouse> houses = FieldGeneration.houses(seed, -600, -600, 600, 600);
            double nearestMin = Double.MAX_VALUE;
            double nearestMax = 0.0;
            for (FieldHouse house : houses) {
                if (Math.abs(house.x()) > 400 || Math.abs(house.z()) > 400) {
                    continue;
                }
                double nearest = Double.MAX_VALUE;
                for (FieldHouse other : houses) {
                    if (other != house) {
                        nearest = Math.min(nearest, Math.hypot(other.x() - house.x(), other.z() - house.z()));
                    }
                }
                nearestMin = Math.min(nearestMin, nearest);
                nearestMax = Math.max(nearestMax, nearest);
            }
            helper.assertTrue(nearestMin >= 40.0 && nearestMax <= 90.0,
                    "houses are not 40-90 blocks apart (" + nearestMin + " to " + nearestMax + ", seed " + seed + ")");
            // Not a regular grid: the gaps vary.
            helper.assertTrue(nearestMax - nearestMin >= 8.0, "houses stand in a regular grid (seed " + seed + ")");
            // From anywhere, a house is well inside the countdown's reach.
            double farthest = 0.0;
            for (int x = -300; x <= 300; x += 5) {
                for (int z = -300; z <= 300; z += 5) {
                    double nearest = Double.MAX_VALUE;
                    for (FieldHouse house : houses) {
                        nearest = Math.min(nearest, Math.hypot(house.x() - x, house.z() - z));
                    }
                    farthest = Math.max(farthest, nearest);
                }
            }
            helper.assertTrue(farthest <= 62.0, "a spot is " + farthest + " blocks from the nearest house (seed " + seed + ")");
            // Everyone arrives on safe ground beside the home house, outside it.
            FieldHouse home = FieldGeneration.homeHouse(seed);
            helper.assertTrue(home.rotation() == 0, "the home house does not face the arrivals");
            List<int[]> spots = FieldGeneration.arrivalSpots(seed);
            Region region = new Region(seed, home.minX() - 6, home.minZ() - 6, home.maxX() + 30, home.maxZ() + 30);
            for (int[] spot : spots) {
                helper.assertTrue(region.standable(spot[0], spot[1], spot[2]), "arrival spot " + spot[0] + " " + spot[1] + " " + spot[2] + " is not safe");
                helper.assertTrue(!home.inFootprint(spot[0], spot[2]), "arrival spot inside the house");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "field")
    public static void cyclePhasesInOrder(GameTestHelper helper) {
        FieldCycle cycle = new FieldCycle();
        List<String> seen = new ArrayList<>();
        int start = 0;
        int ticks = 0;
        // IT stops hunting 30 seconds into the hunt.
        while (seen.size() < 10 && ticks < 100000) {
            FieldCycle.Phase before = cycle.phase();
            boolean huntOver = before == FieldCycle.Phase.HUNT && cycle.elapsed() >= 600;
            FieldCycle.Phase began = cycle.tick(huntOver);
            ticks++;
            if (began != null) {
                seen.add(before.name() + ":" + (ticks - start));
                start = ticks;
                helper.assertTrue(began == FieldCycle.following(before), before + " was followed by " + began);
            }
        }
        List<String> expected = List.of(
                "DAY:" + FieldTuning.DAY_TICKS, "SHATTER:" + FieldTuning.SHATTER_TICKS, "NIGHT:" + FieldTuning.NIGHT_TICKS, "HUNT:601",
                "DAWN:" + FieldTuning.DAWN_TICKS,
                "DAY:" + FieldTuning.DAY_TICKS, "SHATTER:" + FieldTuning.SHATTER_TICKS, "NIGHT:" + FieldTuning.NIGHT_TICKS, "HUNT:601",
                "DAWN:" + FieldTuning.DAWN_TICKS);
        helper.assertTrue(seen.equals(expected), "phases ran " + seen + ", expected " + expected);
        helper.assertTrue(cycle.cycle() == 2 && cycle.phase() == FieldCycle.Phase.DAY, "two full days should have passed");

        // A hunt that never ends on its own still ends.
        cycle.set(FieldCycle.Phase.HUNT, 0);
        int hunt = 0;
        while (cycle.phase() == FieldCycle.Phase.HUNT && hunt < 100000) {
            cycle.tick(false);
            hunt++;
        }
        helper.assertTrue(hunt == FieldTuning.HUNT_TICKS + FieldTuning.HUNT_WATCHDOG_TICKS, "the hunt watchdog ended it after " + hunt);

        // Saved and loaded, the cycle carries on where it was.
        cycle.set(FieldCycle.Phase.NIGHT, 345);
        CompoundTag tag = new CompoundTag();
        cycle.save(tag);
        FieldCycle loaded = new FieldCycle();
        loaded.load(tag);
        helper.assertTrue(loaded.phase() == FieldCycle.Phase.NIGHT && loaded.elapsed() == 345 && loaded.cycle() == cycle.cycle()
                && loaded.remaining() == FieldTuning.NIGHT_TICKS - 345, "the cycle does not survive saving");
        helper.succeed();
    }
}
