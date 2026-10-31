package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.RegionCorruption;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.world.BuildProtection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests for NULL's core: the stage clock, persistence, commands, events and build protection. */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class CoreGameTests {
    private CoreGameTests() {
    }

    @GameTest(template = "empty", batch = "core_registry")
    public static void eventRegistryIsComplete(GameTestHelper helper) {
        int count = NullEventRegistry.size();
        helper.assertTrue(count >= 50, "expected at least 50 random events, found " + count);
        Set<String> ids = new HashSet<>();
        // Stage 6 (HARVEST, the field) has its own cycle and may have no random events.
        int[] perStage = new int[CorruptionStage.NULL.number()];
        for (NullEventDefinition event : NullEventRegistry.all()) {
            helper.assertTrue(ids.add(event.id()), "duplicate event id " + event.id());
            helper.assertTrue(event.weight() > 0, event.id() + " has no weight");
            helper.assertTrue(event.minStage().number() <= event.maxStage().number(), event.id() + " has an empty stage range");
            for (CorruptionStage stage : CorruptionStage.values()) {
                if (stage.atMost(CorruptionStage.NULL) && event.weightAt(stage) > 0) {
                    perStage[stage.number() - 1]++;
                }
            }
        }
        for (int i = 0; i < perStage.length; i++) {
            helper.assertTrue(perStage[i] >= 5, "stage " + (i + 1) + " only has " + perStage[i] + " events");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_thresholds")
    public static void thresholdsAreOrdered(GameTestHelper helper) {
        double previous = -1.0;
        for (CorruptionStage stage : CorruptionStage.values()) {
            if (stage == CorruptionStage.HARVEST) {
                // Not reached by the clock: even a huge clock value stays at NULL.
                helper.assertTrue(CorruptionStageManager.stageFor(Double.MAX_VALUE) == CorruptionStage.NULL,
                        "the clock must never reach HARVEST on its own");
                continue;
            }
            double threshold = CorruptionStageManager.threshold(stage);
            helper.assertTrue(threshold > previous, "threshold of " + stage + " is not after the previous one");
            helper.assertTrue(CorruptionStageManager.stageFor(threshold) == stage, "stageFor(threshold) != " + stage);
            previous = threshold;
        }
        helper.assertTrue(CorruptionStageManager.overallProgress(0.0) == 0.0F, "progress at zero");
        helper.assertTrue(CorruptionStageManager.overallProgress(CorruptionStageManager.threshold(CorruptionStage.NULL)) == 1.0F,
                "progress at Stage 5");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_stage")
    public static void setStageMovesTheClock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionData data = CorruptionManager.data(server);
        CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
        helper.assertTrue(data.stage() == CorruptionStage.MIDDLE, "stage was not raised");
        helper.assertTrue(CorruptionStageManager.stageFor(data.corruptionTicks()) == CorruptionStage.MIDDLE,
                "clock was not moved to the start of the stage");
        helper.assertTrue(data.highestStage().atLeast(CorruptionStage.MIDDLE), "highest stage not recorded");
        CorruptionManager.setStage(server, CorruptionStage.START);
        helper.assertTrue(data.stage() == CorruptionStage.START, "stage was not lowered");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_persist")
    public static void corruptionDataRoundTrips(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("stage", 4);
        tag.putInt("highest_stage", 4);
        tag.putDouble("corruption_ticks", 123456.5);
        tag.putLong("active_ticks", 99L);
        tag.putBoolean("paused", true);
        tag.putDouble("speed", 2.5);
        tag.putFloat("moon_progress", 0.75F);
        tag.putLong("seed", 42L);
        CompoundTag systems = new CompoundTag();
        CompoundTag mine = new CompoundTag();
        mine.putString("hello", "world");
        systems.put("test", mine);
        tag.put("systems", systems);

        var registries = helper.getLevel().registryAccess();
        CorruptionData loaded = CorruptionData.load(tag, registries);
        CorruptionData again = CorruptionData.load(loaded.save(new CompoundTag(), registries), registries);
        helper.assertTrue(again.stage() == CorruptionStage.ABOVE_MIDDLE, "stage lost");
        helper.assertTrue(again.corruptionTicks() == 123456.5, "clock lost");
        helper.assertTrue(again.activeTicks() == 99L, "active time lost");
        helper.assertTrue(again.paused(), "pause lost");
        helper.assertTrue(again.speed() == 2.5, "speed lost");
        helper.assertTrue(again.moonProgress() == 0.75F, "moon lost");
        helper.assertTrue(again.seed() == 42L, "seed lost");
        helper.assertTrue("world".equals(again.systemTag("test").getString("hello")), "system data lost");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_commands")
    public static void commandsWork(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "null status");
        server.getCommands().performPrefixedCommand(source, "null pause");
        helper.assertTrue(CorruptionManager.data(server).paused(), "/null pause did not pause");
        server.getCommands().performPrefixedCommand(source, "null resume");
        helper.assertFalse(CorruptionManager.data(server).paused(), "/null resume did not resume");
        server.getCommands().performPrefixedCommand(source, "null speed 3");
        helper.assertTrue(CorruptionManager.data(server).speed() == 3.0, "/null speed did not apply");
        server.getCommands().performPrefixedCommand(source, "null speed 1");
        server.getCommands().performPrefixedCommand(source, "null stage 2");
        helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.UNDER_MIDDLE, "/null stage 2 did not apply");
        server.getCommands().performPrefixedCommand(source, "null reset");
        helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.UNDER_MIDDLE, "a single /null reset must only ask for confirmation");
        server.getCommands().performPrefixedCommand(source, "null reset");
        helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.START, "confirmed /null reset did not reset");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_region")
    public static void regionFactorIsStable(GameTestHelper helper) {
        for (int i = 0; i < 2000; i++) {
            int x = (i * 7919) % 100000 - 50000;
            int z = (i * 104729) % 100000 - 50000;
            float a = RegionCorruption.regionFactor(1234L, x, z);
            float b = RegionCorruption.regionFactor(1234L, x, z);
            helper.assertTrue(a == b, "region factor is not deterministic");
            helper.assertTrue(a >= 0.34F && a <= 1.66F, "region factor out of range: " + a);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_protection")
    public static void builtBlocksProtectTheirSurroundings(GameTestHelper helper) {
        BlockPos local = new BlockPos(4, 1, 4);
        helper.setBlock(local, Blocks.OAK_PLANKS);
        BlockPos near = helper.absolutePos(new BlockPos(6, 1, 4));
        helper.assertTrue(BuildProtection.isProtected(helper.getLevel(), near), "planks did not protect a block two blocks away");
        BuildProtection.markPlayerBlock(helper.getLevel(), helper.absolutePos(local));
        helper.assertTrue(BuildProtection.playerBlocks(helper.getLevel(), helper.absolutePos(local)) > 0, "chunk was not marked");
        helper.assertTrue(BuildProtection.isNatural(Blocks.STONE.defaultBlockState()), "stone should be natural");
        helper.assertFalse(BuildProtection.isNatural(Blocks.OAK_PLANKS.defaultBlockState()), "planks must never be natural");
        helper.assertTrue(BuildProtection.canPlaceInto(Blocks.AIR.defaultBlockState()), "air should accept structures");
        helper.assertFalse(BuildProtection.canPlaceInto(Blocks.CHEST.defaultBlockState()), "a chest must never be replaced");
        helper.assertTrue(BuildProtection.isNatural(Blocks.OAK_LEAVES.defaultBlockState()), "leaves of a tree should be natural");
        helper.assertFalse(BuildProtection.isNatural(Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)),
                "leaves a player placed must never be natural");
        helper.assertFalse(BuildProtection.isNatural(Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.WATERLOGGED, true)),
                "waterlogged leaves must never be replaced");
        helper.assertFalse(BuildProtection.canPlaceInto(Blocks.LIGHT.defaultBlockState()), "a light block must never be replaced");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "core_disabled")
    public static void disabledEventsNeverRun(GameTestHelper helper) {
        if (!NullConfig.loaded()) {
            helper.fail("NULL's server config is not loaded, so the disabled list cannot be switched for this test");
        }
        MinecraftServer server = helper.getLevel().getServer();
        NullEventDefinition event = NullEventRegistry.get("camera_shake").orElseThrow();
        CorruptionContext ctx = CorruptionManager.context(server);
        List<? extends String> disabled = NullConfig.DISABLED_EVENTS.get();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            NullConfig.DISABLED_EVENTS.set(List.of(event.id()));
            helper.assertFalse(RandomEventManager.INSTANCE.force(player, event, ctx), "an event on the disabled list ran from /null event");
            NullConfig.DISABLED_EVENTS.set(List.of());
            helper.assertTrue(RandomEventManager.INSTANCE.force(player, event, ctx), "the same event did not run once it was enabled again");
        } finally {
            NullConfig.DISABLED_EVENTS.set(disabled);
            // A mock player left on the server would have random events rolled for it during later tests.
            try {
                server.getPlayerList().remove(player);
            } catch (RuntimeException e) {
                NullMod.LOGGER.warn("Could not remove the mock player after a core test", e);
            }
        }
        helper.succeed();
    }
}
