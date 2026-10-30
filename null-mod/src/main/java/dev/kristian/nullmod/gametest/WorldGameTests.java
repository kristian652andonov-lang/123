package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.registry.NullAttachments;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.world.ChunkCorruptionManager;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import dev.kristian.nullmod.world.structure.Blueprint;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * World corruption must never touch player builds, never overwrite anything when it only
 * places into air, and respect the world modification settings. Every test runs its jobs
 * synchronously through {@link SafeBlockPlacer#runNow}, with fixed seeds, and finishes in its
 * first tick. Relative y=0 of the empty template is the stone floor the test framework lays
 * under every test; the structure block and start button sit next to the origin, so everything
 * here stays at least seven blocks away from it.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class WorldGameTests {
    private WorldGameTests() {
    }

    /** A chest and planks beside natural stone: forced corruption right next to them changes nothing nearby. */
    @GameTest(template = "empty", batch = "world_1", skyAccess = true)
    public static void corruptionSparesBuilds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withWorldSettings(helper, new BlockPos(9, 0, 9), true, true, () -> {
            // A 7x7 square of natural stone fenced by sponge (neither natural nor "built"), so the
            // patch cannot wander further than five blocks from the chest.
            for (int x = 5; x <= 13; x++) {
                for (int z = 5; z <= 13; z++) {
                    boolean rim = x == 5 || x == 13 || z == 5 || z == 13;
                    helper.setBlock(new BlockPos(x, 0, z), rim ? Blocks.SPONGE : Blocks.STONE);
                }
            }
            helper.setBlock(new BlockPos(8, 1, 8), Blocks.CHEST);
            helper.setBlock(new BlockPos(9, 1, 8), Blocks.OAK_PLANKS);

            PlacementJob job = ChunkCorruptionManager.INSTANCE.buildPatch(level, helper.absolutePos(new BlockPos(9, 0, 10)), 12, 4,
                    RandomSource.create(1L));
            helper.assertTrue(job != null && !job.isEmpty(), "the patch found no natural stone to plan on");
            PlacementJob.Result result = SafeBlockPlacer.INSTANCE.runNow(level, job);
            helper.assertTrue(result.placed() == 0, "corruption changed " + result.placed() + " blocks next to a chest");
            for (int x = 6; x <= 12; x++) {
                for (int z = 6; z <= 12; z++) {
                    if (!(x == 8 && z == 8) && !(x == 9 && z == 8)) {
                        helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
                    }
                }
            }
            helper.assertBlockPresent(Blocks.CHEST, new BlockPos(8, 1, 8));
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(9, 1, 8));
        });
        helper.succeed();
    }

    /** The same corruption far enough from anything built does change natural ground (the control). */
    @GameTest(template = "empty", batch = "world_2", skyAccess = true)
    public static void corruptionReachesNaturalGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withWorldSettings(helper, new BlockPos(12, 0, 12), true, true, () -> {
            helper.setBlock(new BlockPos(12, 0, 12), Blocks.STONE);
            PlacementJob job = ChunkCorruptionManager.INSTANCE.buildPatch(level, helper.absolutePos(new BlockPos(12, 0, 12)), 1, 1,
                    RandomSource.create(2L));
            helper.assertTrue(job != null && job.size() == 1, "a one-block patch should plan exactly one change");
            PlacementJob.Result result = SafeBlockPlacer.INSTANCE.runNow(level, job);
            helper.assertTrue(result.placed() == 1, "natural stone away from builds was not corrupted");
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(12, 0, 12));
        });
        helper.succeed();
    }

    /** PLACE_INTO_AIR fills the air and leaves every block that was already there. */
    @GameTest(template = "empty", batch = "world_3", skyAccess = true)
    public static void placeIntoAirNeverReplaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withWorldSettings(helper, new BlockPos(10, 2, 11), true, true, () -> {
            BlockState[] row = {
                    Blocks.STONE.defaultBlockState(),
                    Blocks.DIRT.defaultBlockState(),
                    Blocks.AIR.defaultBlockState(),
                    Blocks.SPONGE.defaultBlockState(),
                    Blocks.AIR.defaultBlockState(),
                    Blocks.GRASS_BLOCK.defaultBlockState(),
                    NullBlocks.RETRO_COBBLESTONE.get().defaultBlockState()};
            for (int i = 0; i < row.length; i++) {
                helper.setBlock(new BlockPos(8 + i, 2, 11), row[i]);
            }
            BlockState planks = NullBlocks.RETRO_PLANKS.get().defaultBlockState();
            PlacementJob job = new PlacementJob(level, "test_place_into_air");
            for (int i = 0; i < row.length; i++) {
                job.place(helper.absolutePos(new BlockPos(8 + i, 2, 11)), planks);
            }
            PlacementJob.Result result = SafeBlockPlacer.INSTANCE.runNow(level, job);
            for (int i = 0; i < row.length; i++) {
                BlockPos pos = new BlockPos(8 + i, 2, 11);
                if (row[i].isAir()) {
                    helper.assertBlockPresent(planks.getBlock(), pos);
                } else {
                    helper.assertBlockPresent(row[i].getBlock(), pos);
                }
            }
            helper.assertTrue(result.placed() == 2, "expected 2 placements into air, got " + result.placed());
        });
        helper.succeed();
    }

    /** With natural replacement switched off, corruption changes no natural block (structures still work). */
    @GameTest(template = "empty", batch = "world_4", skyAccess = true)
    public static void noNaturalReplacementWhenDisabled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withWorldSettings(helper, new BlockPos(11, 0, 11), false, true, () -> {
            for (int x = 8; x <= 14; x++) {
                for (int z = 8; z <= 14; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            PlacementJob patch = ChunkCorruptionManager.INSTANCE.buildPatch(level, helper.absolutePos(new BlockPos(11, 0, 11)), 8, 3,
                    RandomSource.create(3L));
            helper.assertTrue(patch != null && !patch.isEmpty(), "the patch found no natural stone to plan on");
            PlacementJob.Result patchResult = SafeBlockPlacer.INSTANCE.runNow(level, patch);
            helper.assertTrue(patchResult.placed() == 0, "corruption replaced " + patchResult.placed() + " natural blocks while disabled");

            PlacementJob direct = new PlacementJob(level, "test_replace_disabled");
            direct.replace(helper.absolutePos(new BlockPos(13, 0, 13)), NullBlocks.BLACK_BLOCK.get().defaultBlockState());
            helper.assertTrue(SafeBlockPlacer.INSTANCE.runNow(level, direct).placed() == 0, "a natural replacement ran while disabled");
            for (int x = 8; x <= 14; x++) {
                for (int z = 8; z <= 14; z++) {
                    helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
                }
            }

            PlacementJob air = new PlacementJob(level, "test_air_still_allowed");
            air.place(helper.absolutePos(new BlockPos(11, 3, 11)), NullBlocks.RETRO_PLANKS.get().defaultBlockState());
            helper.assertTrue(SafeBlockPlacer.INSTANCE.runNow(level, air).placed() == 1, "placing into air should still be allowed");
        });
        helper.succeed();
    }

    /** A door is placed whole or not at all: never a lone half. */
    @GameTest(template = "empty", batch = "world_5", skyAccess = true)
    public static void doorsArePlacedWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withWorldSettings(helper, new BlockPos(10, 1, 12), true, true, () -> {
            helper.setBlock(new BlockPos(9, 0, 12), Blocks.STONE);
            helper.setBlock(new BlockPos(12, 0, 12), Blocks.STONE);
            helper.setBlock(new BlockPos(12, 2, 12), Blocks.STONE);
            Blueprint door = new Blueprint();
            door.door(0, 0, 0, Blocks.OAK_DOOR, Direction.SOUTH, DoorHingeSide.LEFT, false, true);
            PlacementJob job = new PlacementJob(level, "test_doors");
            door.appendTo(job, helper.absolutePos(new BlockPos(9, 1, 12)), Rotation.NONE, Mirror.NONE);
            door.appendTo(job, helper.absolutePos(new BlockPos(12, 1, 12)), Rotation.NONE, Mirror.NONE);
            PlacementJob.Result result = SafeBlockPlacer.INSTANCE.runNow(level, job);
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(9, 1, 12));
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(9, 2, 12));
            helper.assertBlockNotPresent(Blocks.OAK_DOOR, new BlockPos(12, 1, 12));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(12, 2, 12));
            helper.assertTrue(result.placed() == 2, "expected exactly one whole door, placed " + result.placed() + " blocks");
        });
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs the body with world modification on, the given natural/structure switches and the
     * default protection radius, then restores the world's settings.
     */
    private static void withWorldSettings(GameTestHelper helper, BlockPos center, boolean replaceNatural, boolean structures,
                                          Runnable body) {
        ServerLevel level = helper.getLevel();
        Map<Long, Integer> marks = prepareArea(level, helper.absolutePos(center), 32);
        try {
            withConfig(helper, replaceNatural, structures, body);
        } finally {
            restoreMarks(level, marks);
        }
    }

    private static void withConfig(GameTestHelper helper, boolean replaceNatural, boolean structures, Runnable body) {
        if (!NullConfig.loaded()) {
            // Without a loaded config NULL runs on its defaults: everything allowed, radius 8.
            if (!replaceNatural || !structures) {
                helper.fail("NULL's server config is not loaded, so world settings cannot be switched for this test");
            }
            body.run();
            return;
        }
        boolean modification = NullConfig.WORLD_MODIFICATION.get();
        boolean natural = NullConfig.REPLACE_NATURAL_BLOCKS.get();
        boolean generate = NullConfig.GENERATE_STRUCTURES.get();
        int radius = NullConfig.BUILD_PROTECTION_RADIUS.get();
        try {
            NullConfig.WORLD_MODIFICATION.set(true);
            NullConfig.REPLACE_NATURAL_BLOCKS.set(replaceNatural);
            NullConfig.GENERATE_STRUCTURES.set(structures);
            NullConfig.BUILD_PROTECTION_RADIUS.set(8);
            body.run();
        } finally {
            NullConfig.WORLD_MODIFICATION.set(modification);
            NullConfig.REPLACE_NATURAL_BLOCKS.set(natural);
            NullConfig.GENERATE_STRUCTURES.set(generate);
            NullConfig.BUILD_PROTECTION_RADIUS.set(radius);
        }
    }

    /**
     * Loads (synchronously) every chunk near a position, so "unloaded counts as protected" never
     * decides a test, and hides the player-placed marks other tests leave in nearby chunks (a
     * neighbouring test that "placed" a block must not protect this one). Marks are restored by
     * {@link #restoreMarks}.
     */
    private static Map<Long, Integer> prepareArea(ServerLevel level, BlockPos center, int radius) {
        Map<Long, Integer> saved = new HashMap<>();
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                int marks = chunk.getData(NullAttachments.PLAYER_BLOCKS);
                if (marks > 0) {
                    saved.put(ChunkPos.asLong(cx, cz), marks);
                    chunk.setData(NullAttachments.PLAYER_BLOCKS, 0);
                }
            }
        }
        return saved;
    }

    private static void restoreMarks(ServerLevel level, Map<Long, Integer> saved) {
        for (Map.Entry<Long, Integer> entry : saved.entrySet()) {
            level.getChunk(ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()))
                    .setData(NullAttachments.PLAYER_BLOCKS, entry.getValue());
        }
    }
}
