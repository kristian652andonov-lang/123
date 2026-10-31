package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionClock;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.HarvestTimer;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldManager;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tests for the night-based pacing and Stage 6 (HARVEST): the clock follows the overworld's day
 * time (sleeping and {@code /time} count, it never goes back), stages begin after the configured
 * nights, {@code /null pace} round-trips through the server config, surviving the NULL dimension
 * starts HARVEST, and {@code /null stage 6} moves players into the field. The GameTest server
 * loads no datapack dimensions, so the NULL dimension and the field themselves are not here: the
 * decisions are tested on their own, and the moves are checked as scheduled. Every test that
 * changes the world's stage, clock or pace has its own batch.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class HarvestGameTests {
    private static final double DAY = CorruptionStageManager.TICKS_PER_NIGHT;
    /** FML re-reads a written config file on another thread about a second later; wait well past that. */
    private static final int CONFIG_SETTLE_TICKS = 60;

    private HarvestGameTests() {
    }

    @GameTest(template = "empty", batch = "harvest_logic")
    public static void stagesBeginAfterNights(GameTestHelper helper) {
        helper.assertTrue(NullConfig.STAGE_2_NIGHTS.getDefault() == 3.0 && NullConfig.STAGE_3_NIGHTS.getDefault() == 6.0
                        && NullConfig.STAGE_4_NIGHTS.getDefault() == 10.0 && NullConfig.STAGE_5_NIGHTS.getDefault() == 15.0
                        && NullConfig.HARVEST_SURVIVAL_MINUTES.getDefault() == 5.0,
                "the default pace must be FRACTURE after 3 nights, INFESTATION 6, VISITATION 10, NULL 15, HARVEST after 5 minutes");
        CorruptionStage previous = CorruptionStage.START;
        for (CorruptionStage stage : List.of(CorruptionStage.UNDER_MIDDLE, CorruptionStage.MIDDLE, CorruptionStage.ABOVE_MIDDLE,
                CorruptionStage.NULL)) {
            double threshold = CorruptionStageManager.threshold(stage);
            helper.assertTrue(threshold == CorruptionStageManager.nights(stage) * DAY, stage + " does not begin after whole in-game days");
            helper.assertTrue(CorruptionStageManager.stageFor(threshold - 1.0) == previous, "one tick before " + stage + " is not " + previous);
            helper.assertTrue(CorruptionStageManager.stageFor(threshold) == stage, stage + " does not begin at its night");
            previous = stage;
        }
        helper.assertTrue(CorruptionStageManager.stageFor(1.0E12) == CorruptionStage.NULL, "the clock must never reach HARVEST on its own");
        helper.assertTrue(CorruptionStage.HARVEST.isFinal() && !CorruptionStage.NULL.isFinal(), "HARVEST must be the final stage");
        helper.assertTrue(CorruptionStage.byNumber(6) == CorruptionStage.HARVEST && CorruptionStage.HARVEST.next() == CorruptionStage.HARVEST,
                "stage 6 is HARVEST and nothing follows it");
        helper.assertTrue(CorruptionStageManager.nightsPassed(3.0 * DAY - 1.0) == 2L && CorruptionStageManager.nightsPassed(3.0 * DAY) == 3L,
                "a night is counted when the day turns");
        helper.assertTrue(CorruptionStageManager.harvestSurvivalTicks() == NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES) * 1200.0,
                "the survival time is in minutes");
        // /null pace: later stages move later and keep their distance to the stage before them.
        double[] pushed = CorruptionStageManager.pushLater(new double[] {0.0, 0.0, 4.0, 6.0, 10.0, 15.0}, 2, 8.0);
        helper.assertTrue(pushed[2] == 8.0 && pushed[3] == 10.0 && pushed[4] == 14.0 && pushed[5] == 15.0,
                "pushLater did not keep the distances: " + pushed[3] + "/" + pushed[4] + "/" + pushed[5]);
        // A stage set by command begins at the last dawn, at the world's hour, and stays that stage.
        for (long dayTime : new long[] {0L, 6000L, 13000L, 23999L, 7L * 24000L + 18000L}) {
            for (CorruptionStage stage : List.of(CorruptionStage.START, CorruptionStage.UNDER_MIDDLE, CorruptionStage.MIDDLE,
                    CorruptionStage.ABOVE_MIDDLE)) {
                double start = CorruptionStageManager.stageStart(stage, dayTime);
                helper.assertTrue(CorruptionStageManager.stageFor(start) == stage, "stageStart left " + stage + " at day time " + dayTime);
                if (CorruptionStageManager.threshold(stage.next()) - CorruptionStageManager.threshold(stage) >= 2.0 * DAY) {
                    helper.assertTrue(Math.floorMod((long) start, 24000L) == Math.floorMod(dayTime, 24000L),
                            "stageStart of " + stage + " does not keep the hour of day time " + dayTime);
                }
            }
            helper.assertTrue(CorruptionStageManager.stageStart(CorruptionStage.NULL, dayTime) == CorruptionStageManager.threshold(CorruptionStage.NULL)
                            && CorruptionStageManager.stageStart(CorruptionStage.HARVEST, dayTime) == CorruptionStageManager.threshold(CorruptionStage.NULL),
                    "NULL and HARVEST must start exactly at NULL's threshold");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "harvest_logic")
    public static void clockFollowsTheDayAndNeverGoesBack(GameTestHelper helper) {
        CorruptionClock clock = new CorruptionClock();
        helper.assertTrue(clock.step(1000L) == 0L, "the first look at the day time only sets the reference");
        helper.assertTrue(clock.step(1001L) == 1L, "one tick of day time is one clock tick");
        // Sleeping: the day time jumps to the next dawn.
        helper.assertTrue(clock.step(24000L) == 22999L, "a night slept through must be counted");
        // /time add.
        helper.assertTrue(clock.step(48000L + 6000L) == 30000L, "/time add must be counted");
        // /time set to an earlier hour of the same day (absolute time far back): nothing, and the
        // clock waits until the day has caught up.
        helper.assertTrue(clock.step(1000L) == 0L && clock.waiting() == 5000L, "setting the hour back must count nothing and wait");
        helper.assertTrue(clock.step(5000L) == 0L && clock.waiting() == 1000L, "the clock must wait for the day to catch up");
        helper.assertTrue(clock.step(6500L) == 500L && clock.waiting() == 0L, "after waiting the clock must count again");
        // /time set day in the middle of the night (absolute time back, the hour forward through dawn): the rest of the night counts.
        clock.step(18000L);
        helper.assertTrue(clock.step(1000L) == 7000L, "setting the morning at midnight must count the rest of the night");
        // Many steps, never negative.
        long[] times = {1000L, 999L, 50000L, 10L, 23999L, 0L, 72000L, 71999L, 1L, 30000L};
        for (long time : times) {
            helper.assertTrue(clock.step(time) >= 0L, "the clock went back");
        }
        clock.reset();
        helper.assertTrue(clock.step(5L) == 0L && clock.waiting() == 0L, "a reset clock starts over");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "harvest_logic")
    public static void survivingNullIsTimedWithoutBreaks(GameTestHelper helper) {
        HarvestTimer timer = new HarvestTimer();
        UUID player = UUID.randomUUID();
        double required = 100.0;
        for (int i = 0; i < 99; i++) {
            helper.assertFalse(timer.tick(player, HarvestTimer.State.SURVIVING, 1.0, required), "HARVEST began before the time was up");
        }
        helper.assertTrue(timer.tick(player, HarvestTimer.State.SURVIVING, 1.0, required), "HARVEST did not begin when the time was up");

        // Dying (or leaving NULL) starts it again.
        timer.clear();
        for (int i = 0; i < 60; i++) {
            timer.tick(player, HarvestTimer.State.SURVIVING, 1.0, required);
        }
        timer.tick(player, HarvestTimer.State.GONE, 1.0, required);
        helper.assertTrue(timer.time(player) == 0.0, "death did not reset the time");
        for (int i = 0; i < 99; i++) {
            helper.assertFalse(timer.tick(player, HarvestTimer.State.SURVIVING, 1.0, required), "the time before the death still counted");
        }
        helper.assertTrue(timer.tick(player, HarvestTimer.State.SURVIVING, 1.0, required), "a full time after the death did not count");

        // A transition or a pause holds the time without losing it; the speed scales it.
        timer.clear();
        for (int i = 0; i < 25; i++) {
            timer.tick(player, HarvestTimer.State.SURVIVING, 2.0, required);
        }
        for (int i = 0; i < 1000; i++) {
            helper.assertFalse(timer.tick(player, HarvestTimer.State.HELD, 2.0, required), "time ran while it was held");
        }
        helper.assertTrue(timer.time(player) == 50.0, "holding lost or added time");
        for (int i = 0; i < 24; i++) {
            timer.tick(player, HarvestTimer.State.SURVIVING, 2.0, required);
        }
        helper.assertTrue(timer.tick(player, HarvestTimer.State.SURVIVING, 2.0, required), "at speed 2 the time must run twice as fast");

        // Other players keep their own time; retain drops whoever is gone.
        UUID other = UUID.randomUUID();
        timer.clear();
        timer.tick(player, HarvestTimer.State.SURVIVING, 10.0, required);
        timer.tick(other, HarvestTimer.State.SURVIVING, 30.0, required);
        timer.retain(id -> id.equals(other));
        helper.assertTrue(timer.time(player) == 0.0 && timer.time(other) == 30.0, "times are not kept per player");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "harvest_logic")
    public static void oldMinuteClocksKeepTheirStage(GameTestHelper helper) {
        double minute = 1200.0;
        // Halfway through FRACTURE on the old clock (30..90 minutes), at noon.
        double fracture = CorruptionStageManager.migrateLegacyClock(CorruptionStage.UNDER_MIDDLE, 60.0 * minute, 6000L);
        double start = CorruptionStageManager.threshold(CorruptionStage.UNDER_MIDDLE);
        double end = CorruptionStageManager.threshold(CorruptionStage.MIDDLE);
        helper.assertTrue(CorruptionStageManager.stageFor(fracture) == CorruptionStage.UNDER_MIDDLE, "a migrated world left FRACTURE");
        helper.assertTrue(fracture <= start + 0.5 * (end - start) + 1.0e-6 && fracture >= start + 0.5 * (end - start) - DAY,
                "a migrated world is not as far into FRACTURE as it was");
        if (end - start >= 2.0 * DAY) {
            helper.assertTrue(Math.floorMod((long) fracture, 24000L) == 6000L, "a migrated clock does not keep the world's hour");
        }
        // At the very end of a stage it keeps the stage, with some of it left.
        for (CorruptionStage stage : List.of(CorruptionStage.START, CorruptionStage.UNDER_MIDDLE, CorruptionStage.MIDDLE,
                CorruptionStage.ABOVE_MIDDLE)) {
            double[] oldStarts = {0.0, 30.0, 90.0, 180.0, 300.0};
            double justBefore = oldStarts[stage.number()] * minute - 1.0;
            double migrated = CorruptionStageManager.migrateLegacyClock(stage, justBefore, 23999L);
            helper.assertTrue(CorruptionStageManager.stageFor(migrated) == stage, "a world at the end of " + stage + " lost its stage");
            helper.assertTrue(CorruptionStageManager.threshold(stage.next()) - migrated > 1000.0, "a migrated " + stage + " has nothing left");
        }
        double nul = CorruptionStageManager.migrateLegacyClock(CorruptionStage.NULL, 330.0 * minute, 0L);
        helper.assertTrue(nul >= CorruptionStageManager.threshold(CorruptionStage.NULL)
                        && CorruptionStageManager.progressWithin(CorruptionStage.NULL, nul) > 0.4F
                        && CorruptionStageManager.progressWithin(CorruptionStage.NULL, nul) < 0.6F,
                "a world half way to NULL's full strength does not keep that");

        // A save without the new format stays marked as a minute clock until it is moved.
        var registries = helper.getLevel().registryAccess();
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("stage", 3);
        legacy.putInt("highest_stage", 3);
        legacy.putDouble("corruption_ticks", 100.0 * minute);
        CompoundTag saved = CorruptionData.load(legacy, registries).save(new CompoundTag(), registries);
        helper.assertTrue(saved.getInt("format") == 1 && saved.getDouble("corruption_ticks") == 100.0 * minute,
                "an old save must keep its minute clock until it is moved to nights");
        CompoundTag current = new CompoundTag();
        current.putInt("format", 2);
        current.putInt("stage", 3);
        current.putDouble("corruption_ticks", 7.5 * DAY);
        CompoundTag again = CorruptionData.load(current, registries).save(new CompoundTag(), registries);
        helper.assertTrue(again.getInt("format") == 2 && again.getDouble("corruption_ticks") == 7.5 * DAY,
                "a night clock does not round-trip");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "harvest_clock")
    public static void nightsAreCountedFromTheDayTime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        CorruptionData data = CorruptionManager.data(server);
        GameRules.BooleanValue daylight = overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        boolean daylightBefore = daylight.get();
        boolean pausedBefore = data.paused();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        daylight.set(true, server);
        CorruptionManager.setPaused(server, false);
        CorruptionManager.setStage(server, CorruptionStage.START);
        if (!CorruptionManager.counting(server) || CorruptionManager.effectiveSpeed(server) <= 0.0) {
            cleanUp(server, player, daylight, daylightBefore, pausedBefore);
            helper.fail("NULL's clock does not count here (progression disabled or speed 0 in the config)");
            return;
        }
        double speed = CorruptionManager.effectiveSpeed(server);
        // Let the clock see the day time once, then sleep through a night.
        helper.runAfterDelay(2, () -> {
            double before = data.corruptionTicks();
            overworld.setDayTime(overworld.getDayTime() + 24000L);
            helper.runAfterDelay(2, () -> {
                double slept = data.corruptionTicks();
                if (slept < before + 24000.0 * speed) {
                    cleanUp(server, player, daylight, daylightBefore, pausedBefore);
                    helper.fail("a night slept through was not counted: " + before + " -> " + slept);
                    return;
                }
                // /time set back by a few hours: never back, and nothing counted.
                overworld.setDayTime(overworld.getDayTime() - 100000L);
                helper.runAfterDelay(2, () -> {
                    double after = data.corruptionTicks();
                    cleanUp(server, player, daylight, daylightBefore, pausedBefore);
                    helper.assertTrue(after >= slept, "the clock went back with the time: " + slept + " -> " + after);
                    helper.assertTrue(after - slept <= 10.0 * speed, "setting the time back counted time: " + slept + " -> " + after);
                    helper.succeed();
                });
            });
        });
    }

    private static void cleanUp(MinecraftServer server, ServerPlayer player, GameRules.BooleanValue daylight, boolean daylightBefore,
            boolean pausedBefore) {
        daylight.set(daylightBefore, server);
        CorruptionManager.setPaused(server, pausedBefore);
        CorruptionManager.setStage(server, CorruptionStage.START);
        removeMock(server, player);
    }

    /**
     * Each step waits {@link #CONFIG_SETTLE_TICKS} before it is checked: FML re-reads a saved config
     * file on its own thread about a second after every write, so values are compared once that has
     * happened (the last read always sees the last save).
     */
    @GameTest(template = "empty", batch = "harvest_pace", timeoutTicks = 900)
    public static void paceCommandRoundTrips(GameTestHelper helper) {
        if (!NullConfig.loaded()) {
            helper.fail("NULL's server config is not loaded, so /null pace cannot be tested");
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        List<ModConfigSpec.DoubleValue> values = List.of(NullConfig.STAGE_2_NIGHTS, NullConfig.STAGE_3_NIGHTS,
                NullConfig.STAGE_4_NIGHTS, NullConfig.STAGE_5_NIGHTS, NullConfig.HARVEST_SURVIVAL_MINUTES);
        double[] before = new double[values.size()];
        for (int i = 0; i < before.length; i++) {
            before[i] = values.get(i).get();
        }
        boolean pausedBefore = CorruptionManager.data(server).paused();
        Runnable restore = () -> {
            for (int i = 0; i < before.length; i++) {
                values.get(i).set(before[i]);
            }
            NullConfig.SPEC.save();
            CorruptionManager.setPaused(server, pausedBefore);
            CorruptionManager.paceChanged(server);
        };
        // Paused, so moving thresholds can never advance this world's stage during the test.
        helper.startSequence()
                .thenExecute(guarded(restore, () -> {
                    CorruptionManager.setPaused(server, true);
                    run(server, source, "null pace reset");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(defaultPace(), "/null pace reset did not restore 3/6/10/15 nights and 5 minutes");
                    run(server, source, "null pace 2 4");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.STAGE_2_NIGHTS) == 4.0, "/null pace 2 4 did not apply");
                    helper.assertTrue(CorruptionStageManager.threshold(CorruptionStage.UNDER_MIDDLE) == 4.0 * DAY, "the threshold did not follow the pace");
                    run(server, source, "null pace 3 3");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.STAGE_3_NIGHTS) == 6.0, "a stage moved before the one ahead of it");
                    run(server, source, "null pace 2 8");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.STAGE_2_NIGHTS) == 8.0 && NullConfig.get(NullConfig.STAGE_3_NIGHTS) == 10.0
                                    && NullConfig.get(NullConfig.STAGE_4_NIGHTS) == 14.0 && NullConfig.get(NullConfig.STAGE_5_NIGHTS) == 15.0,
                            "later stages were not pushed up keeping their distance: " + NullConfig.get(NullConfig.STAGE_2_NIGHTS) + "/"
                                    + NullConfig.get(NullConfig.STAGE_3_NIGHTS) + "/" + NullConfig.get(NullConfig.STAGE_4_NIGHTS) + "/"
                                    + NullConfig.get(NullConfig.STAGE_5_NIGHTS));
                    run(server, source, "null pace 5 2.5");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.STAGE_5_NIGHTS) == 15.0, "NULL moved before VISITATION");
                    run(server, source, "null pace 6 2.5");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES) == 2.5
                            && CorruptionStageManager.harvestSurvivalTicks() == 3000.0, "/null pace 6 2.5 did not apply");
                    run(server, source, "null pace 6 0.1");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> {
                    helper.assertTrue(NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES) == 2.5, "a survival time below the minimum was accepted");
                    run(server, source, "null pace");
                    run(server, source, "null pace reset");
                }))
                .thenIdle(CONFIG_SETTLE_TICKS)
                .thenExecute(guarded(restore, () -> helper.assertTrue(defaultPace(), "/null pace reset did not restore the defaults")))
                .thenExecute(restore)
                .thenSucceed();
    }

    /** Runs a step; if it fails, {@code cleanUp} runs before the failure is reported. */
    private static Runnable guarded(Runnable cleanUp, Runnable step) {
        return () -> {
            try {
                step.run();
            } catch (RuntimeException e) {
                cleanUp.run();
                throw e;
            }
        };
    }

    /** The pace the user asked for: 3, 6, 10 and 15 nights, and 5 minutes in NULL. */
    private static boolean defaultPace() {
        return NullConfig.get(NullConfig.STAGE_2_NIGHTS) == 3.0 && NullConfig.get(NullConfig.STAGE_3_NIGHTS) == 6.0
                && NullConfig.get(NullConfig.STAGE_4_NIGHTS) == 10.0 && NullConfig.get(NullConfig.STAGE_5_NIGHTS) == 15.0
                && NullConfig.get(NullConfig.HARVEST_SURVIVAL_MINUTES) == 5.0;
    }

    @GameTest(template = "empty", batch = "harvest_survival")
    public static void survivingNullStartsHarvest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionManager.setStage(server, CorruptionStage.START);
        CorruptionManager.requestHarvest(server);
        CorruptionManager.setStage(server, CorruptionStage.NULL);
        helper.runAfterDelay(2, () -> {
            if (CorruptionManager.stage(server) != CorruptionStage.NULL) {
                CorruptionManager.setStage(server, CorruptionStage.START);
                helper.fail("a request made before Stage 5 started HARVEST later");
                return;
            }
            CorruptionManager.requestHarvest(server);
            helper.runAfterDelay(2, () -> {
                CorruptionStage reached = CorruptionManager.stage(server);
                CorruptionManager.setStage(server, CorruptionStage.START);
                helper.assertTrue(reached == CorruptionStage.HARVEST, "surviving NULL did not start HARVEST (stage " + reached.number() + ")");
                helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.START, "/null stage 1 did not leave HARVEST");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", batch = "harvest_stage6")
    public static void stageSixTakesPlayersIntoTheField(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            run(server, source, "null stage 1");
            FieldCycle.Phase phaseBefore = FieldManager.INSTANCE.phase();
            int remainingBefore = FieldManager.INSTANCE.remaining();
            run(server, source, phaseBefore == FieldCycle.Phase.HUNT ? "null field night" : "null field hunt");
            helper.assertTrue(FieldManager.INSTANCE.phase() == phaseBefore && FieldManager.INSTANCE.remaining() == remainingBefore,
                    "/null field worked outside HARVEST");
            run(server, source, "null stage 6");
            helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.HARVEST, "/null stage 6 did not start HARVEST");
            helper.assertTrue(FieldManager.INSTANCE.inTransition(player), "/null stage 6 did not schedule the move into the field");
            helper.assertFalse(NullDimensionManager.INSTANCE.inTransition(player), "the NULL dimension also tried to move the player");
            run(server, source, "null stage 6");
            helper.assertTrue(FieldManager.INSTANCE.inTransition(player) && FieldManager.INSTANCE.phase() == FieldCycle.Phase.DAY,
                    "setting HARVEST again changed something");
            run(server, source, "null field night");
            helper.assertTrue(FieldManager.INSTANCE.phase() == FieldCycle.Phase.NIGHT && FieldManager.INSTANCE.remaining() == FieldCycle.length(FieldCycle.Phase.NIGHT),
                    "/null field night did not start the countdown");
            run(server, source, "null field cracking");
            int day = FieldCycle.length(FieldCycle.Phase.DAY);
            helper.assertTrue(FieldManager.INSTANCE.phase() == FieldCycle.Phase.DAY && FieldManager.INSTANCE.remaining() < day / 2,
                    "/null field cracking did not jump late into the day");
            run(server, source, "null field day");
            helper.assertTrue(FieldManager.INSTANCE.phase() == FieldCycle.Phase.DAY && FieldManager.INSTANCE.remaining() == day,
                    "/null field day did not start a new morning");
            run(server, source, "null stage 5");
            helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.NULL, "/null stage 5 did not leave HARVEST");
            helper.assertFalse(FieldManager.INSTANCE.inTransition(player), "leaving HARVEST did not cancel the move into the field");
            run(server, source, "null stage 1");
            helper.assertFalse(FieldManager.INSTANCE.inTransition(player) || NullDimensionManager.INSTANCE.inTransition(player),
                    "a move was left over at Stage 1");
        } finally {
            CorruptionManager.setStage(server, CorruptionStage.START);
            removeMock(server, player);
        }
        helper.succeed();
    }

    private static void run(MinecraftServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }

    /** A mock player left on the server would have random events rolled for it during later tests. */
    private static void removeMock(MinecraftServer server, ServerPlayer player) {
        try {
            server.getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after a HARVEST test", e);
        }
    }
}
