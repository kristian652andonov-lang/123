package dev.kristian.nullmod.moon;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.NullEventRegistry;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tests for the Shattering Moon: the shared geometry and the server-side progression. */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class MoonGameTests {
    private MoonGameTests() {
    }

    /**
     * Stage 1 with a whole moon, whatever earlier batches left behind: going through Stage 2
     * makes the move into Stage 1 a real stage change, which always resets the moon.
     */
    private static void startWhole(MinecraftServer server) {
        CorruptionManager.setStage(server, CorruptionStage.UNDER_MIDDLE);
        CorruptionManager.setStage(server, CorruptionStage.START);
    }

    @GameTest(template = "empty", batch = "moon_geometry")
    public static void geometryIsDeterministicAndOrdered(GameTestHelper helper) {
        for (long seed : new long[] {0L, 42L, -7_123_456_789L, 0x4E554C4C2D4E554CL}) {
            MoonGeometry a = MoonGeometry.compute(seed);
            MoonGeometry b = MoonGeometry.compute(seed);
            helper.assertTrue(a.shardCount() == b.shardCount() && a.crackCount() == b.crackCount()
                    && a.holeCount() == b.holeCount(), "geometry differs between two runs for seed " + seed);
            helper.assertTrue(a.shardCount() >= 12, "too few shards for seed " + seed);
            helper.assertTrue(a.holeCount() >= 8, "too few pieces break away for seed " + seed);
            helper.assertTrue(a.crackCount() > 0 && a.crack(0).appearAt() == MoonGeometry.FIRST_CRACK,
                    "the first crack does not appear at the start for seed " + seed);
            for (int i = 0; i < a.crackCount(); i++) {
                helper.assertTrue(a.crack(i).appearAt() == b.crack(i).appearAt(), "crack timing differs for seed " + seed);
                if (i > 0) {
                    helper.assertTrue(a.crack(i).appearAt() >= a.crack(i - 1).appearAt(), "cracks are not in order for seed " + seed);
                }
            }
            float previous = -1.0F;
            for (int k = 0; k < a.holeCount(); k++) {
                MoonGeometry.Shard shard = a.shard(a.hole(k));
                helper.assertTrue(shard.breakAt() > previous, "pieces are not in order for seed " + seed);
                helper.assertTrue(shard.breakAt() >= MoonGeometry.PIECES && shard.breakAt() < 1.0F,
                        "a piece breaks outside the breaking phases for seed " + seed);
                previous = shard.breakAt();
                // A piece is always outlined by cracks before it falls out.
                for (int i = 0; i < a.crackCount(); i++) {
                    MoonGeometry.Crack crack = a.crack(i);
                    if (crack.shardA() == shard.index() || crack.shardB() == shard.index()) {
                        helper.assertTrue(crack.fullAt() <= shard.breakAt(),
                                "shard " + shard.index() + " breaks before its border cracked for seed " + seed);
                    }
                }
            }
            helper.assertTrue(a.nextHole(0.0F) == a.hole(0), "nextHole disagrees with the hole order");
            helper.assertTrue(a.newestHole(1.0F) == a.hole(a.holeCount() - 1), "newestHole disagrees with the hole order");
            List<MoonCorruptionManager.Milestone> milestones = MoonCorruptionManager.INSTANCE.milestones(seed);
            helper.assertTrue(milestones.size() >= 8, "too few moon milestones for seed " + seed);
            for (int i = 1; i < milestones.size(); i++) {
                helper.assertTrue(milestones.get(i).at() >= milestones.get(i - 1).at(), "milestones are not in order");
            }
        }
        helper.assertTrue(NullEventRegistry.get("moon_crack").isPresent() && NullEventRegistry.get("moon_piece").isPresent()
                && NullEventRegistry.get("moon_distortion").isPresent(), "moon events are not registered");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moon_1")
    public static void progressFollowsTheClockAndResets(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionData data = CorruptionManager.data(server);
        startWhole(server);
        CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
        helper.runAfterDelay(30, () -> {
            float expected = MoonCorruptionManager.targetProgress(data.corruptionTicks());
            if (MoonCorruptionManager.enabled()) {
                helper.assertTrue(expected > 0.0F && expected < 1.0F, "Stage 3 should ask for a partly broken moon");
                helper.assertTrue(data.moonProgress() >= expected - 0.01F, "the moon did not follow the clock");
            }
            helper.assertFalse(data.moonGone(), "the moon must not disappear in Stage 3");
            CorruptionManager.setStage(server, CorruptionStage.START);
            helper.assertTrue(data.moonProgress() == 0.0F && !data.moonGone(), "Stage 1 did not reset the moon");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "moon_2")
    public static void stageFiveRemovesTheMoon(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionData data = CorruptionManager.data(server);
        if (!MoonCorruptionManager.enabled()) {
            helper.succeed();
            return;
        }
        startWhole(server);
        helper.assertTrue(data.moonProgress() == 0.0F && !data.moonGone(), "Stage 1 did not reset the moon");
        CorruptionManager.setStage(server, CorruptionStage.NULL);
        helper.assertTrue(data.moonGone() && data.moonProgress() >= 1.0F, "the moon did not disappear when Stage 5 began");
        CorruptionManager.setStage(server, CorruptionStage.ABOVE_MIDDLE);
        helper.assertFalse(data.moonGone(), "moving back to Stage 4 did not bring the moon back");
        helper.assertTrue(data.moonProgress() < 1.0F, "moving back to Stage 4 did not move the moon back");
        CorruptionManager.setStage(server, CorruptionStage.START);
        helper.assertTrue(data.moonProgress() == 0.0F && !data.moonGone(), "Stage 1 did not reset the moon");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "moon_3")
    public static void progressNeverGoesBackOnItsOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionData data = CorruptionManager.data(server);
        startWhole(server);
        CorruptionManager.setStage(server, CorruptionStage.UNDER_MIDDLE);
        MoonCorruptionManager.INSTANCE.setProgress(data, 0.5F, false);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(data.moonProgress() >= 0.5F, "the moon healed itself");
            CorruptionManager.setStage(server, CorruptionStage.START);
            helper.succeed();
        });
    }
}
