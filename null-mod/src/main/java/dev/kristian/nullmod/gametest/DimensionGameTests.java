package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.dimension.NullChunkGenerator;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.dimension.NullWorldGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The NULL dimension's geometry function. The checks that need the dimension itself (generated
 * chunks, the arrival platform, decay and regeneration) run in {@code DimensionSelfCheck} on the
 * real servers of the CI smoke tests: the GameTest server never loads datapack dimensions, and the
 * transition is driven end to end by the scripted client sessions.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class DimensionGameTests {
    private DimensionGameTests() {
    }

    @GameTest(template = "empty", batch = "dimension")
    public static void sampleIsDeterministic(GameTestHelper helper) {
        long[] seeds = {0L, 1L, -7L, 0x123456789ABCDEFL, NullChunkGenerator.seedFor(helper.getLevel().getSeed())};
        NullWorldGeneration.Column column = new NullWorldGeneration.Column();
        RandomSource random = RandomSource.create(42L);
        for (long seed : seeds) {
            for (int i = 0; i < 4000; i++) {
                int x = random.nextInt(40000) - 20000;
                int y = NullWorldGeneration.MIN_Y + random.nextInt(NullWorldGeneration.HEIGHT);
                int z = random.nextInt(40000) - 20000;
                int first = NullWorldGeneration.sampleId(seed, x, y, z);
                int second = NullWorldGeneration.sampleId(seed, x, y, z);
                NullWorldGeneration.column(seed, x, z, column);
                if (first != second || NullWorldGeneration.idAt(column, y) != first) {
                    helper.fail("sample is not deterministic at " + x + " " + y + " " + z + " for seed " + seed);
                }
                if (NullWorldGeneration.sample(seed, x, y, z) != NullWorldGeneration.state(first)) {
                    helper.fail("sample() and sampleId() disagree at " + x + " " + y + " " + z);
                }
            }
        }

        // The spawn platform is the same for every seed and every arrival spot is safe.
        for (int x = -NullWorldGeneration.PLATFORM_RADIUS; x <= NullWorldGeneration.PLATFORM_RADIUS; x++) {
            for (int z = -NullWorldGeneration.PLATFORM_RADIUS; z <= NullWorldGeneration.PLATFORM_RADIUS; z++) {
                for (int y = NullWorldGeneration.MAIN_FLOOR - 7; y <= NullWorldGeneration.MAIN_FLOOR + 17; y++) {
                    int expected = NullWorldGeneration.sampleId(seeds[0], x, y, z);
                    for (long seed : seeds) {
                        if (NullWorldGeneration.sampleId(seed, x, y, z) != expected) {
                            helper.fail("the spawn platform depends on the seed at " + x + " " + y + " " + z);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 13; i++) {
            BlockPos spot = NullDimensionManager.platformSpot(i);
            for (long seed : seeds) {
                helper.assertTrue(NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY() - 1, spot.getZ()) != NullWorldGeneration.AIR,
                        "no floor under arrival spot " + spot);
                helper.assertTrue(NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY(), spot.getZ()) == NullWorldGeneration.AIR
                                && NullWorldGeneration.sampleId(seed, spot.getX(), spot.getY() + 1, spot.getZ()) == NullWorldGeneration.AIR,
                        "arrival spot " + spot + " is blocked");
            }
        }

        // The main tier can be walked forever along both axes, whatever the seed.
        for (long seed : seeds) {
            for (int k = -40; k <= 40; k++) {
                int far = k * 977 + 13;
                helper.assertTrue(NullWorldGeneration.sampleId(seed, far, NullWorldGeneration.MAIN_FLOOR, 0) != NullWorldGeneration.AIR,
                        "main corridor along X is broken at x=" + far);
                helper.assertTrue(NullWorldGeneration.sampleId(seed, 0, NullWorldGeneration.MAIN_FLOOR, far) != NullWorldGeneration.AIR,
                        "main corridor along Z is broken at z=" + far);
            }
        }

        // Seeds matter outside the spawn platform, and the structure is mostly dark void.
        int differences = 0;
        int solid = 0;
        int total = 0;
        for (int i = 0; i < 20000; i++) {
            int x = random.nextInt(4000) - 2000;
            int y = NullWorldGeneration.MIN_Y + random.nextInt(NullWorldGeneration.HEIGHT);
            int z = random.nextInt(4000) - 2000;
            int a = NullWorldGeneration.sampleId(1L, x, y, z);
            if (a != NullWorldGeneration.sampleId(2L, x, y, z)) {
                differences++;
            }
            if (a != NullWorldGeneration.AIR) {
                solid++;
            }
            total++;
        }
        helper.assertTrue(differences > 120, "different seeds give the same structure (" + differences + " differences)");
        helper.assertTrue(solid > total / 400 && solid < total / 4, "implausible amount of structure: " + solid + " of " + total);
        helper.succeed();
    }
}
