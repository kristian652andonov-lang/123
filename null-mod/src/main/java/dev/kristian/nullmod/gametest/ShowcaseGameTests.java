package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.EventPacing;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.event.ShowcaseHint;
import dev.kristian.nullmod.showcase.ShowcaseManager;
import dev.kristian.nullmod.showcase.ShowcasePlan;
import dev.kristian.nullmod.showcase.ShowcaseStep;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /null showcase} and the event pacing: the tour covers every event of every stage, gives
 * the world back as it found it, places showcase events in front of the player, and the random
 * events come at the new pace without ever crowding together at high speed.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class ShowcaseGameTests {
    /** Average seconds between random events per stage 1-6 at normal speed and frequency. */
    private static final double[] EXPECTED_SECONDS = {60.0, 35.0, 24.0, 16.0, 10.0, 30.0};

    private ShowcaseGameTests() {
    }

    @GameTest(template = "empty", batch = "showcase_plan")
    public static void tourShowsEveryEventOfEveryStage(GameTestHelper helper) {
        List<NullEventDefinition> events = List.copyOf(NullEventRegistry.all());
        for (CorruptionStage stage : CorruptionStage.values()) {
            List<ShowcaseStep> steps = ShowcasePlan.forStage(stage, events, true);
            helper.assertTrue(!steps.isEmpty() && steps.get(0).kind() == ShowcaseStep.Kind.STAGE && steps.get(0).stage() == stage,
                    "stage " + stage.number() + " does not begin by setting the stage");
            Set<String> shown = new HashSet<>();
            boolean skySeen = false;
            for (int i = 0; i < steps.size(); i++) {
                ShowcaseStep step = steps.get(i);
                helper.assertTrue(step.stage() == stage, "a step of stage " + stage.number() + " belongs to another stage");
                helper.assertTrue(step.number() == i + 1 && step.count() == steps.size(), "steps of stage " + stage.number() + " are misnumbered");
                if (step.kind() == ShowcaseStep.Kind.EVENT) {
                    shown.add(step.subject());
                    boolean sky = NullEventRegistry.get(step.subject()).map(ShowcasePlan::needsNight).orElse(false);
                    helper.assertFalse(skySeen && !sky, "in stage " + stage.number() + " a ground event comes after the sky ones");
                    skySeen |= sky;
                }
            }
            for (NullEventDefinition event : events) {
                if (event.minStage() == stage) {
                    helper.assertTrue(shown.contains(event.id()), "stage " + stage.number() + " does not show " + event.id());
                }
            }
            if (stage == CorruptionStage.UNDER_MIDDLE || stage == CorruptionStage.MIDDLE) {
                String variant = stage == CorruptionStage.UNDER_MIDDLE ? ShowcasePlan.MOBS_CORRUPTED : ShowcasePlan.MOBS_PARASITE;
                helper.assertTrue(steps.stream().anyMatch(s -> s.kind() == ShowcaseStep.Kind.MOBS && variant.equals(s.subject())),
                        "stage " + stage.number() + " shows no " + variant + " mobs");
            }
        }
        List<ShowcaseStep> field = ShowcasePlan.forStage(CorruptionStage.HARVEST, events, true);
        for (String phase : List.of(ShowcasePlan.FIELD_DAY, ShowcasePlan.FIELD_CRACKING, ShowcasePlan.FIELD_SHATTER,
                ShowcasePlan.FIELD_NIGHT, ShowcasePlan.FIELD_HUNT, ShowcasePlan.FIELD_DAWN)) {
            helper.assertTrue(field.stream().anyMatch(s -> s.kind() == ShowcaseStep.Kind.FIELD && phase.equals(s.subject())),
                    "the field tour has no " + phase);
        }
        int house = indexOf(field, ShowcaseStep.Kind.HOUSE, "");
        int hunt = indexOf(field, ShowcaseStep.Kind.FIELD, ShowcasePlan.FIELD_HUNT);
        helper.assertTrue(house >= 0 && hunt > house, "the player is not put into a house before IT hunts");

        // The whole tour: every stage in order, every event exactly once, and never IT's hunt.
        List<ShowcaseStep> tour = ShowcasePlan.fullTour(events);
        Map<String, Integer> times = new HashMap<>();
        int lastStage = 0;
        for (ShowcaseStep step : tour) {
            helper.assertTrue(step.stage().number() >= lastStage, "the tour goes back to an earlier stage");
            lastStage = step.stage().number();
            helper.assertFalse(step.kind() == ShowcaseStep.Kind.HOUSE || ShowcasePlan.FIELD_HUNT.equals(step.subject()),
                    "the full tour must not set IT on the player");
            if (step.kind() == ShowcaseStep.Kind.EVENT) {
                times.merge(step.subject(), 1, Integer::sum);
            }
        }
        for (NullEventDefinition event : events) {
            helper.assertTrue(times.getOrDefault(event.id(), 0) == 1, "the full tour shows " + event.id() + " "
                    + times.getOrDefault(event.id(), 0) + " times");
        }
        helper.assertTrue(lastStage == CorruptionStage.HARVEST.number(), "the full tour does not reach HARVEST");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "showcase_rates")
    public static void eventsComeAtTheNewPaceAndNeverCrowd(GameTestHelper helper) {
        for (CorruptionStage stage : CorruptionStage.values()) {
            long expected = Math.round(EXPECTED_SECONDS[stage.number() - 1] * 20.0);
            long average = RandomEventManager.averageDelayTicks(stage, 1.0, 1.0);
            helper.assertTrue(Math.abs(average - expected) <= 1, "stage " + stage.number() + ": average wait " + average
                    + " ticks, expected " + expected);
            long fast = RandomEventManager.averageDelayTicks(stage, 1.0, EventPacing.factorFor(100.0, EventPacing.MAX_SPEED_FACTOR));
            helper.assertTrue(Math.abs(fast - Math.max(RandomEventManager.MIN_GAP_TICKS, expected / 6)) <= 1,
                    "stage " + stage.number() + ": at speed 100 events come " + (double) expected / fast + "x as often, not 6x");
            long shortest = RandomEventManager.shortestDelayTicks(stage, 10.0, EventPacing.MAX_SPEED_FACTOR);
            helper.assertTrue(shortest >= RandomEventManager.MIN_GAP_TICKS, "stage " + stage.number()
                    + ": two events can come " + shortest + " ticks apart");
        }
        helper.assertTrue(EventPacing.factorFor(100.0, 6.0) == 6.0, "speed 100 must be capped at 6x");
        helper.assertTrue(EventPacing.factorFor(3.0, 6.0) == 3.0, "speed 3 must make events 3x as frequent");
        helper.assertTrue(EventPacing.factorFor(0.25, 6.0) == 1.0, "a slow clock must keep the normal pace");
        helper.assertTrue(EventPacing.factorFor(Double.NaN, 6.0) == 1.0, "a broken speed must keep the normal pace");
        helper.assertTrue(EventPacing.factorFor(100.0, 2.0) == 2.0, "the configured limit must hold");
        helper.assertTrue(EventPacing.factorFor(100.0, 50.0) == EventPacing.MAX_SPEED_FACTOR, "the limit can never exceed 6x");
        helper.assertTrue(EventPacing.scale(1000.0, 0.0, 1.0, 40L) >= Long.MAX_VALUE / 4L, "frequency 0 must mean never");
        helper.assertTrue(EventPacing.scale(10.0, 1.0, 6.0, 40L) == 40L, "the minimum gap must hold");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "showcase_place")
    public static void showcasePlacesThingsInFrontOfThePlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            BlockPos origin = helper.absolutePos(new BlockPos(8, 2, 8));
            float yaw = 37.0F;
            player.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, yaw, 0.0F);
            Vec3 forward = LocationFinder.forward(player);
            RandomSource random = RandomSource.create(7L);
            boolean[] allInFront = {true};
            ShowcaseHint.run(player, () -> {
                for (int i = 0; i < 200; i++) {
                    // Whatever view a generator asks for, the showcase picks from straight ahead.
                    LocationFinder.View asked = LocationFinder.View.values()[i % LocationFinder.View.values().length];
                    LocationFinder.Column c = LocationFinder.column(player, random, 10.0, 30.0, asked);
                    double dx = c.x() + 0.5 - player.getX();
                    double dz = c.z() + 0.5 - player.getZ();
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    double cos = (dx * forward.x + dz * forward.z) / Math.max(1.0E-6, distance);
                    // The column is a whole block, so it may sit up to a block off the exact ray.
                    double slack = Math.toDegrees(Math.atan2(1.0, distance));
                    if (distance < 9.0 || distance > 31.0 || cos < Math.cos(Math.toRadians(LocationFinder.FRONT_HALF_ANGLE + slack))) {
                        allInFront[0] = false;
                    }
                }
                return true;
            });
            helper.assertTrue(allInFront[0], "a showcase placement left the 25 degree cone in front of the player");
            helper.assertFalse(ShowcaseHint.active(player), "the showcase hint outlived its event");

            boolean anyBehind = false;
            for (int i = 0; i < 50; i++) {
                LocationFinder.Column c = LocationFinder.column(player, random, 10.0, 30.0, LocationFinder.View.BEHIND);
                double dx = c.x() + 0.5 - player.getX();
                double dz = c.z() + 0.5 - player.getZ();
                anyBehind |= dx * forward.x + dz * forward.z < 0.0;
            }
            helper.assertTrue(anyBehind, "without the hint, BEHIND must still mean behind");

            GenerationRequest req = new GenerationRequest(helper.getLevel(), player, random, CorruptionStage.UNDER_MIDDLE, 0.5F,
                    false, true);
            helper.assertTrue(req.forced(), "a showcase request must count as forced");
            helper.assertTrue(req.view(LocationFinder.View.HIDDEN) == LocationFinder.View.FRONT, "a showcase request must look ahead");
            double patchMin = req.minDistance(16.0, 6.0);
            helper.assertTrue(patchMin == 10.0 && req.maxDistance(64.0, patchMin) == 30.0, "a patch must land 10-30 blocks ahead");
            double villageMin = req.minDistance(60.0, 34.0);
            helper.assertTrue(villageMin == 34.0 && req.maxDistance(110.0, villageMin) == 44.0,
                    "a village must stand far enough away not to be built around the player");
            GenerationRequest normal = new GenerationRequest(helper.getLevel(), player, random, CorruptionStage.UNDER_MIDDLE, 0.5F, false);
            helper.assertTrue(normal.maxDistance(110.0, 50.0) == 110.0 && normal.view(LocationFinder.View.HIDDEN) == LocationFinder.View.HIDDEN,
                    "ordinary requests must keep their placement");
        } finally {
            removeMock(server, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "showcase_restore", timeoutTicks = 400)
    public static void stoppingTheTourGivesTheWorldBack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        if (ShowcaseManager.INSTANCE.active()) {
            helper.fail("another showcase is running");
        }
        CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
        CorruptionManager.setSpeed(server, 2.5);
        CorruptionManager.setPaused(server, false);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(ShowcaseManager.INSTANCE.start(player, CorruptionStage.UNDER_MIDDLE), "the tour did not start");
        helper.assertFalse(ShowcaseManager.INSTANCE.start(player, null), "a second tour started while one runs");
        CorruptionData data = CorruptionManager.data(server);
        helper.assertTrue(data.paused(), "the tour did not pause NULL");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.UNDER_MIDDLE, "the tour did not set its stage");
            helper.assertTrue(data.paused(), "NULL runs on during the tour");
            helper.assertTrue(ShowcaseManager.INSTANCE.stop(server), "the tour could not be stopped");
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(ShowcaseManager.INSTANCE.active(), "the tour is still running");
            helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.MIDDLE, "the stage was not put back");
            helper.assertTrue(data.speed() == 2.5, "the speed was not put back");
            helper.assertFalse(data.paused(), "the pause state was not put back");
            CorruptionManager.setSpeed(server, 1.0);
            removeMock(server, player);
        });
    }

    @GameTest(template = "empty", batch = "showcase_outside", timeoutTicks = 400)
    public static void aStageSetFromOutsideEndsTheTourAndStays(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        if (ShowcaseManager.INSTANCE.active()) {
            helper.fail("another showcase is running");
        }
        CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
        CorruptionManager.setSpeed(server, 1.5);
        CorruptionManager.setPaused(server, false);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(ShowcaseManager.INSTANCE.start(player, CorruptionStage.UNDER_MIDDLE), "the tour did not start");
        helper.runAfterDelay(5, () -> CorruptionManager.setStage(server, CorruptionStage.START));
        helper.succeedWhen(() -> {
            helper.assertFalse(ShowcaseManager.INSTANCE.active(), "the tour did not step aside");
            helper.assertTrue(CorruptionManager.stage(server) == CorruptionStage.START, "the stage set from outside was undone");
            helper.assertTrue(CorruptionManager.data(server).speed() == 1.5, "the speed was not put back");
            helper.assertFalse(CorruptionManager.data(server).paused(), "the pause state was not put back");
            CorruptionManager.setSpeed(server, 1.0);
            removeMock(server, player);
        });
    }

    private static int indexOf(List<ShowcaseStep> steps, ShowcaseStep.Kind kind, String subject) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == kind && steps.get(i).subject().equals(subject)) {
                return i;
            }
        }
        return -1;
    }

    /** A mock player left on the server would get random events (and tours) during later tests. */
    private static void removeMock(MinecraftServer server, ServerPlayer player) {
        try {
            server.getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after a showcase test", e);
        }
    }
}
