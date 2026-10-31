package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.entity.stalker.StalkerEntity;
import dev.kristian.nullmod.entity.stalker.StalkerEvents;
import dev.kristian.nullmod.entity.stalker.StalkerKind;
import dev.kristian.nullmod.entity.stalker.StalkerManager;
import dev.kristian.nullmod.entity.stalker.StalkerModule;
import dev.kristian.nullmod.entity.stalker.StalkerTestAccess;
import dev.kristian.nullmod.entity.stalker.TemporaryChanges;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.registry.NullAttachments;
import dev.kristian.nullmod.world.BuildProtection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The figure of Stages 1-4 may only ever change three things, and each must be harmless: a window
 * pane it shatters comes back exactly as it was (whichever way it is put back); the miner digs
 * natural stone only, never near a build and never an ore; the door opener only toggles the
 * "open" state of wooden doors, trapdoors and gates. It belongs to Stages 1-4 only and is never
 * saved with the world. Every test finishes in its first tick; everything stays at least seven
 * blocks from the template's structure block.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class StalkerGameTests {
    private StalkerGameTests() {
    }

    /** A pane between two panes, in a stone wall: shattered and put back by every path, always the exact same state. */
    @GameTest(template = "empty", batch = "stalkers_1", skyAccess = true)
    public static void windowBreakerRestoresThePaneExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(11, 2, 10), () -> {
            for (int x = 9; x <= 13; x++) {
                helper.setBlock(new BlockPos(x, 1, 10), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 3, 10), Blocks.STONE);
            }
            helper.setBlock(new BlockPos(9, 2, 10), Blocks.STONE);
            helper.setBlock(new BlockPos(13, 2, 10), Blocks.STONE);
            for (int x = 10; x <= 12; x++) {
                helper.setBlock(new BlockPos(x, 2, 10), Blocks.GRAY_STAINED_GLASS_PANE);
            }
            BlockPos pane = helper.absolutePos(new BlockPos(11, 2, 10));
            BlockState before = level.getBlockState(pane);
            BlockState west = level.getBlockState(pane.west());
            BlockState east = level.getBlockState(pane.east());
            helper.assertTrue(before.getValue(BlockStateProperties.EAST) && before.getValue(BlockStateProperties.WEST),
                    "the test pane should be connected on both sides");
            helper.assertTrue(TemporaryChanges.canShatter(level, pane), "an ordinary window pane in a wall should be breakable");

            // 1. Put back by the regular schedule (here forced at once).
            shatter(helper, level, pane);
            helper.assertTrue(level.getBlockState(pane.west()) == west && level.getBlockState(pane.east()) == east,
                    "breaking the pane changed its neighbours");
            helper.assertTrue(TemporaryChanges.INSTANCE.undoNow(level, pane), "the broken pane was not remembered");
            helper.assertTrue(level.getBlockState(pane) == before, "the pane did not come back exactly: " + level.getBlockState(pane));

            // 2. Put back the way the chunk-unload hook does it (straight into the chunk).
            shatter(helper, level, pane);
            TemporaryChanges.INSTANCE.undoChunkNow(level, pane);
            helper.assertTrue(level.getBlockState(pane) == before, "the chunk-unload path did not restore the pane exactly");
            helper.assertFalse(TemporaryChanges.INSTANCE.isPending(level, pane), "the chunk-unload path left the change pending");

            // 3. Put back the way a server stop or /null reset does it.
            shatter(helper, level, pane);
            TemporaryChanges.INSTANCE.undoAll(level.getServer());
            helper.assertTrue(level.getBlockState(pane) == before, "undoing everything did not restore the pane exactly");

            // 4. Something the player put into the hole is never replaced.
            shatter(helper, level, pane);
            helper.setBlock(new BlockPos(11, 2, 10), Blocks.OAK_PLANKS);
            TemporaryChanges.INSTANCE.undoNow(level, pane);
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(11, 2, 10));
            helper.assertFalse(TemporaryChanges.INSTANCE.isPending(level, pane), "a filled hole was still waiting to be restored");
        });
        helper.succeed();
    }

    private static void shatter(GameTestHelper helper, ServerLevel level, BlockPos pane) {
        helper.assertTrue(TemporaryChanges.INSTANCE.shatter(level, pane, null, 0, 20 * 60), "the pane did not shatter");
        helper.assertTrue(level.getBlockState(pane).isAir(), "the shattered pane is still there");
        helper.assertTrue(TemporaryChanges.INSTANCE.isPending(level, pane), "the shattered pane is not waiting to be restored");
    }

    /** Never a window anything depends on: water behind it, a torch on it, a waterlogged pane, tinted glass in reach of a torch. */
    @GameTest(template = "empty", batch = "stalkers_2", skyAccess = true)
    public static void windowBreakerLeavesRiskyWindowsAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(10, 2, 10), () -> {
            helper.setBlock(new BlockPos(8, 2, 10), Blocks.GLASS_PANE);
            helper.setBlock(new BlockPos(8, 2, 11), Blocks.WATER);
            helper.assertFalse(TemporaryChanges.canShatter(level, helper.absolutePos(new BlockPos(8, 2, 10))),
                    "a pane holding back water may not break");

            helper.setBlock(new BlockPos(11, 2, 10), Blocks.GLASS);
            helper.setBlock(new BlockPos(11, 3, 10), Blocks.TORCH);
            helper.assertFalse(TemporaryChanges.canShatter(level, helper.absolutePos(new BlockPos(11, 2, 10))),
                    "glass carrying a torch may not break");

            helper.setBlock(new BlockPos(13, 2, 13), Blocks.GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
            helper.assertFalse(TemporaryChanges.canShatter(level, helper.absolutePos(new BlockPos(13, 2, 13))),
                    "a waterlogged pane may not break");

            helper.setBlock(new BlockPos(13, 2, 8), Blocks.STONE);
            helper.assertFalse(TemporaryChanges.canShatter(level, helper.absolutePos(new BlockPos(13, 2, 8))), "only windows break");
        });
        helper.succeed();
    }

    /** Natural stone is dug (the control); an ore right beside it stays where it is. */
    @GameTest(template = "empty", batch = "stalkers_3", skyAccess = true)
    public static void minerDigsStoneButNeverOres(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(12, 2, 12), () -> {
            for (int x = 10; x <= 14; x++) {
                for (int z = 10; z <= 14; z++) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
            helper.setBlock(new BlockPos(12, 2, 13), Blocks.IRON_ORE);
            helper.setBlock(new BlockPos(12, 1, 12), Blocks.DEEPSLATE);
            helper.assertTrue(StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(12, 2, 12))), "digging natural stone failed");
            helper.assertTrue(StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(12, 1, 12))), "digging deepslate failed");
            helper.assertTrue(StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(12, 2, 13))), "an ore should be stepped round, not fail");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, 2, 12));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, 1, 12));
            helper.assertBlockPresent(Blocks.IRON_ORE, new BlockPos(12, 2, 13));
        });
        helper.succeed();
    }

    /** Stone next to anything built is never dug, and nothing built is ever dug. */
    @GameTest(template = "empty", batch = "stalkers_4", skyAccess = true)
    public static void minerNeverDigsNearBuilds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(12, 2, 12), () -> {
            for (int x = 10; x <= 14; x++) {
                for (int z = 10; z <= 14; z++) {
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                }
            }
            helper.setBlock(new BlockPos(12, 2, 12), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(13, 1, 13), Blocks.CHEST);
            helper.assertFalse(StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(11, 1, 11))),
                    "stone beside a chest and planks was dug");
            StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(12, 2, 12)));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(11, 1, 11));
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(12, 2, 12));
            helper.assertBlockPresent(Blocks.CHEST, new BlockPos(13, 1, 13));
        });
        helper.succeed();
    }

    /** Stone in a chunk where a player has placed blocks is never dug. */
    @GameTest(template = "empty", batch = "stalkers_5", skyAccess = true)
    public static void minerNeverDigsWherePlayersBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(12, 2, 12), () -> {
            helper.setBlock(new BlockPos(12, 2, 12), Blocks.STONE);
            BlockPos placed = helper.absolutePos(new BlockPos(12, 3, 12));
            BuildProtection.markPlayerBlock(level, placed);
            try {
                helper.assertFalse(StalkerTestAccess.dig(level, helper.absolutePos(new BlockPos(12, 2, 12))),
                        "stone was dug in a chunk where a player placed blocks");
                helper.assertBlockPresent(Blocks.STONE, new BlockPos(12, 2, 12));
            } finally {
                // The mark must not protect the tests that run next to this one.
                level.getChunkAt(placed).setData(NullAttachments.PLAYER_BLOCKS, 0);
            }
        });
        helper.succeed();
    }

    /** The figure belongs to Stages 1-4: its events and its own schedule never reach NULL or HARVEST. */
    @GameTest(template = "empty", batch = "stalkers_6")
    public static void stalkersOnlyInStagesOneToFour(GameTestHelper helper) {
        for (String id : List.of(StalkerEvents.TREE, StalkerEvents.WINDOW, StalkerEvents.DOORS, StalkerEvents.WINDOW_BREAK,
                StalkerEvents.MINER)) {
            Optional<NullEventDefinition> event = NullEventRegistry.get(id);
            helper.assertTrue(event.isPresent(), id + " is not registered");
            NullEventDefinition definition = event.get();
            helper.assertTrue(definition.maxStage() == CorruptionStage.ABOVE_MIDDLE, id + " reaches beyond Stage 4");
            helper.assertTrue(definition.weightAt(CorruptionStage.NULL) == 0 && definition.weightAt(CorruptionStage.HARVEST) == 0,
                    id + " can run in Stage 5 or 6");
            helper.assertTrue(definition.weightAt(CorruptionStage.ABOVE_MIDDLE) > 0, id + " never runs in Stage 4");
        }
        helper.assertTrue(NullEventRegistry.get(StalkerEvents.WINDOW_BREAK).get().weightAt(CorruptionStage.START) == 0,
                "windows must not break in Stage 1");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        CorruptionContext real = CorruptionManager.context(player.server);
        for (CorruptionStage stage : new CorruptionStage[]{CorruptionStage.NULL, CorruptionStage.HARVEST}) {
            CorruptionContext ctx = new CorruptionContext(real.server(), real.data(), stage, 0.5F, 0.9F, 0.9F,
                    real.corruptionTicks(), false, real.seed());
            for (StalkerKind kind : StalkerKind.values()) {
                helper.assertFalse(kind.allowedAt(stage), kind + " is allowed in " + stage);
                helper.assertFalse(StalkerManager.INSTANCE.begin(player, kind, false, ctx, RandomSource.create(7L)),
                        kind + " started in " + stage);
            }
            helper.assertFalse(StalkerManager.stageAllows(stage), "the schedule runs in " + stage);
        }
        for (StalkerKind kind : StalkerKind.values()) {
            helper.assertTrue(kind.allowedAt(CorruptionStage.ABOVE_MIDDLE), kind + " is not allowed in Stage 4");
        }
        helper.succeed();
    }

    /** Wooden doors, trapdoors and gates only get opened and closed; nothing else about them, and nothing else, changes. */
    @GameTest(template = "empty", batch = "stalkers_7", skyAccess = true)
    public static void doorOpenerOnlyTogglesDoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        withSettings(helper, new BlockPos(11, 1, 11), () -> {
            for (int x = 7; x <= 15; x++) {
                helper.setBlock(new BlockPos(x, 0, 11), Blocks.STONE);
            }
            BlockState door = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
                    .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            helper.setBlock(new BlockPos(8, 1, 11), door);
            helper.setBlock(new BlockPos(8, 2, 11), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            BlockState ironDoor = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            helper.setBlock(new BlockPos(10, 1, 11), ironDoor);
            helper.setBlock(new BlockPos(10, 2, 11), ironDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            helper.setBlock(new BlockPos(12, 1, 11), Blocks.SPRUCE_TRAPDOOR);
            helper.setBlock(new BlockPos(14, 1, 11), Blocks.OAK_FENCE_GATE);
            helper.setBlock(new BlockPos(15, 1, 13), Blocks.CHEST);

            BlockPos doorPos = helper.absolutePos(new BlockPos(8, 1, 11));
            BlockPos trapdoor = helper.absolutePos(new BlockPos(12, 1, 11));
            BlockPos gate = helper.absolutePos(new BlockPos(14, 1, 11));
            List<BlockPos> found = StalkerTestAccess.doors(level, helper.absolutePos(new BlockPos(11, 1, 11)), 5, 2);
            helper.assertTrue(found.contains(doorPos) && found.contains(trapdoor) && found.contains(gate),
                    "the wooden door, trapdoor and gate were not all found: " + found);
            helper.assertTrue(found.size() == 3, "something else was taken for a door: " + found);

            Map<BlockPos, BlockState> before = new HashMap<>();
            for (int x = 7; x <= 15; x++) {
                for (int y = 0; y <= 3; y++) {
                    for (int z = 10; z <= 13; z++) {
                        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                        before.put(pos, level.getBlockState(pos));
                    }
                }
            }
            for (BlockPos pos : found) {
                helper.assertTrue(TemporaryChanges.INSTANCE.openDoor(level, pos, null, null, 0, 0), "could not open " + pos);
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState was = entry.getValue();
                BlockState now = level.getBlockState(pos);
                boolean toggled = pos.equals(doorPos) || pos.equals(doorPos.above()) || pos.equals(trapdoor) || pos.equals(gate);
                if (toggled) {
                    helper.assertTrue(now == was.setValue(BlockStateProperties.OPEN, true), "opening changed more than OPEN at " + pos + ": " + now);
                } else {
                    helper.assertTrue(now == was, "opening the doors changed " + pos + ": " + was + " -> " + now);
                }
            }
            for (BlockPos pos : found) {
                helper.assertTrue(TemporaryChanges.INSTANCE.undoNow(level, pos), "the opened door was not remembered: " + pos);
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                helper.assertTrue(level.getBlockState(entry.getKey()) == entry.getValue(),
                        "closing again did not leave " + entry.getKey() + " exactly as it was");
            }

            // A door the player changed after it was opened is theirs: it is not closed again.
            helper.assertTrue(TemporaryChanges.INSTANCE.openDoor(level, gate, null, null, 0, 0), "could not open the gate again");
            BlockState theirs = level.getBlockState(gate).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            level.setBlock(gate, theirs, 3);
            TemporaryChanges.INSTANCE.undoNow(level, gate);
            helper.assertTrue(level.getBlockState(gate) == theirs, "a gate the player had changed was closed anyway");
        });
        helper.succeed();
    }

    /** It is never saved with the world, and hitting it makes it simply not be there. */
    @GameTest(template = "empty", batch = "stalkers_8")
    public static void figureIsNeverSavedAndVanishesWhenHit(GameTestHelper helper) {
        helper.assertFalse(StalkerModule.STALKER.get().canSerialize(), "the figure would be saved with the world");
        StalkerEntity figure = helper.spawn(StalkerModule.STALKER.get(), new BlockPos(10, 1, 10));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        figure.hurt(helper.getLevel().damageSources().playerAttack(player), 4.0F);
        helper.assertTrue(figure.hasVanished(), "the figure is still there after being hit");
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs the body with world modification, natural replacement and every figure switch on (the
     * protection radius at its default), in an area whose chunks are loaded and carry no
     * player-placed marks from neighbouring tests; restores everything afterwards.
     */
    private static void withSettings(GameTestHelper helper, BlockPos center, Runnable body) {
        ServerLevel level = helper.getLevel();
        Map<Long, Integer> marks = clearMarks(level, helper.absolutePos(center), 32);
        try {
            if (!NullConfig.loaded()) {
                // Defaults: everything allowed, radius 8.
                body.run();
                return;
            }
            boolean modification = NullConfig.WORLD_MODIFICATION.get();
            boolean natural = NullConfig.REPLACE_NATURAL_BLOCKS.get();
            int radius = NullConfig.BUILD_PROTECTION_RADIUS.get();
            boolean enabled = NullConfig.STALKERS_ENABLED.get();
            boolean doors = NullConfig.STALKER_DOOR_OPENING.get();
            boolean windows = NullConfig.STALKER_WINDOW_BREAKING.get();
            boolean digging = NullConfig.STALKER_MINER_DIGGING.get();
            try {
                NullConfig.WORLD_MODIFICATION.set(true);
                NullConfig.REPLACE_NATURAL_BLOCKS.set(true);
                NullConfig.BUILD_PROTECTION_RADIUS.set(8);
                NullConfig.STALKERS_ENABLED.set(true);
                NullConfig.STALKER_DOOR_OPENING.set(true);
                NullConfig.STALKER_WINDOW_BREAKING.set(true);
                NullConfig.STALKER_MINER_DIGGING.set(true);
                body.run();
            } finally {
                NullConfig.WORLD_MODIFICATION.set(modification);
                NullConfig.REPLACE_NATURAL_BLOCKS.set(natural);
                NullConfig.BUILD_PROTECTION_RADIUS.set(radius);
                NullConfig.STALKERS_ENABLED.set(enabled);
                NullConfig.STALKER_DOOR_OPENING.set(doors);
                NullConfig.STALKER_WINDOW_BREAKING.set(windows);
                NullConfig.STALKER_MINER_DIGGING.set(digging);
            }
        } finally {
            restoreMarks(level, marks);
        }
    }

    private static Map<Long, Integer> clearMarks(ServerLevel level, BlockPos center, int radius) {
        Map<Long, Integer> saved = new HashMap<>();
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                int count = chunk.getData(NullAttachments.PLAYER_BLOCKS);
                if (count > 0) {
                    saved.put(ChunkPos.asLong(cx, cz), count);
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
