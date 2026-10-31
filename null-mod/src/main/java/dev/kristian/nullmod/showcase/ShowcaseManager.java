package dev.kristian.nullmod.showcase;

import com.mojang.brigadier.tree.CommandNode;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.entity.PlayerLikeSpawns;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import dev.kristian.nullmod.field.FieldChunkGenerator;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.field.FieldGeneration;
import dev.kristian.nullmod.field.FieldHouse;
import dev.kristian.nullmod.field.FieldManager;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.network.TransitionPayload;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.world.LocationFinder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /null showcase}: a guided tour for one operator through every stage, one step at a time
 * ({@link ShowcasePlan}). It remembers the world's stage, pause state, speed and progress through
 * the stage, the time of day and where the operator stood, pauses progression (no stage advances on
 * its own and no NULL survival timer runs), then for each stage sets the stage and runs that stage's
 * events for the operator with the showcase hint, so they happen in plain view: block glitches close
 * in front, corruption and structures 10-30 blocks ahead (through the build protection, as always),
 * NULLROOT and Visitors in sight, the sky at night with the operator told to look up (their camera
 * is never moved). Stage 5 takes the operator into the NULL dimension, Stage 6 into the field, whose
 * cycle runs while they are there (the field's day and night, never the NULL timer).
 * <p>
 * When the tour ends ({@code /null showcase stop}, the last step, the operator's logout, the server
 * stopping) everything is put back: the operator returns to where they stood (through the black
 * screen when that means changing dimension), then the stage, its progress, the speed and the pause
 * state are restored. Each step is shown on the operator's actionbar only. One tour runs at a time
 * on a server; on a server with other players the stages change for them as well.
 */
public final class ShowcaseManager implements CorruptionSystem {
    public static final ShowcaseManager INSTANCE = new ShowcaseManager();
    public static final String NAME = "showcase";

    public static final int DEFAULT_STEP_SECONDS = 8;
    public static final int MIN_STEP_SECONDS = 3;
    public static final int MAX_STEP_SECONDS = 60;

    /** A stage step shows its title at least this long. */
    private static final int STAGE_TITLE_TICKS = 60;
    /** How long a stage step waits for the move into (or out of) the NULL dimension or the field. */
    private static final int SETTLE_TIMEOUT_TICKS = 20 * 45;
    /** After arriving somewhere new the screen is still coming back from black. */
    private static final int ARRIVAL_DWELL_TICKS = 100;
    /** A step that could not happen stays on the actionbar this long. */
    private static final int FAILED_STEP_TICKS = 50;
    /** The actionbar fades after three seconds; it is sent again before that. */
    private static final int ACTIONBAR_REFRESH_TICKS = 30;
    /** Night was just set: the sky event waits until the client has the new time. */
    private static final int NIGHT_SETTLE_TICKS = 30;
    /** The time of day when the moon stands highest. */
    private static final long MIDNIGHT = 18000L;
    /** The mob step stays at least this long: the mobs need a moment to show what they are. */
    private static final int MOBS_MIN_TICKS = 200;
    /** The shatter takes five seconds; its step shows it all and the dark coming down. */
    private static final int SHATTER_MIN_TICKS = 160;
    /** The night step of the full tour ends this long before the countdown would run out (no hunt). */
    private static final int NIGHT_SAFETY_TICKS = 60;
    /** IT's hunt step ends when the hunt does, or after this long. */
    private static final int HUNT_TIMEOUT_TICKS = 20 * 200;
    /** The house step shows the hiding place before the hunt begins. */
    private static final int HOUSE_MIN_TICKS = 100;
    /** At the end, how long to wait for a running move (or a respawn) before giving up on the way home. */
    private static final int END_WAIT_TIMEOUT_TICKS = 20 * 40;
    /** Ticks the clock may take to catch up with the progress the world had when the tour began. */
    private static final int CATCH_UP_TICKS = 40;
    /** Progress closer than this to the saved one counts as caught up. */
    private static final float CATCH_UP_TOLERANCE = 0.002F;
    /** The catch-up never aims past this point of the stage, so it can never tip into the next one. */
    private static final float CATCH_UP_LIMIT = 0.995F;

    private final RandomSource random = RandomSource.create();
    private int stepSeconds = DEFAULT_STEP_SECONDS;
    @Nullable
    private Tour tour;

    /** What the world and the operator looked like when the tour began. */
    public record Saved(CorruptionStage stage, boolean paused, double speed, float progress, ResourceKey<Level> dimension, Vec3 pos,
                 float yaw, float pitch, @Nullable FieldCycle.Phase fieldPhase) {
    }

    private enum EndPhase { WAIT, MOVE, RESTORE, CATCH_UP }

    private static final class Ending {
        final String reasonKey;
        final boolean keepStage;
        EndPhase phase = EndPhase.WAIT;
        long since;
        long moveAt;
        boolean arriveAfterMove;
        int catchUpTicks;
        float lastProgress = -1.0F;
        double lastSpeed;

        Ending(String reasonKey, boolean keepStage, long since) {
            this.reasonKey = reasonKey;
            this.keepStage = keepStage;
            this.since = since;
        }
    }

    private static final class Tour {
        final UUID viewer;
        final Saved saved;
        final List<ShowcaseStep> steps;
        int index = -1;
        long stepStart;
        long stepEnd;
        boolean skip;
        Component line = Component.empty();
        long lastActionbar = Long.MIN_VALUE;
        // The stage step waiting for the player to arrive.
        boolean settling;
        boolean nudged;
        long settleDeadline;
        long settledAt = -1L;
        @Nullable
        ResourceKey<Level> settleFrom;
        // An event step waiting for the night to reach the client.
        @Nullable
        String pendingEvent;
        long pendingAt;
        // The night set for the sky steps.
        boolean nightSet;
        long timeBeforeNight;
        long nightTime;
        // What the tour changed or made.
        final List<UUID> props = new ArrayList<>();
        @Nullable
        ResourceKey<Level> propsLevel;
        boolean runningForField;
        boolean visitedElsewhere;
        @Nullable
        CorruptionStage expectedStage;
        int shown;
        int failed;
        @Nullable
        Ending ending;

        Tour(UUID viewer, Saved saved, List<ShowcaseStep> steps) {
            this.viewer = viewer;
            this.saved = saved;
            this.steps = steps;
        }
    }

    private ShowcaseManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================== public API

    /** True while a tour runs (or is ending) on this server. */
    public boolean active() {
        return tour != null;
    }

    /** The operator watching the tour, or null. */
    @Nullable
    public UUID viewer() {
        Tour t = tour;
        return t == null ? null : t.viewer;
    }

    /**
     * True for the player watching the running tour: no random event of its own interrupts the
     * steps (the tour runs each event itself), also while the field's cycle runs in Stage 6.
     */
    public boolean watching(ServerPlayer player) {
        Tour t = tour;
        return t != null && t.viewer.equals(player.getUUID());
    }

    /** Seconds each step lasts (3-60), for the running tour and the next ones. */
    public int stepSeconds() {
        return stepSeconds;
    }

    public void setStepSeconds(int seconds) {
        stepSeconds = Mth.clamp(seconds, MIN_STEP_SECONDS, MAX_STEP_SECONDS);
    }

    /** The steps of the running tour (empty when none runs), for commands and tests. */
    public List<ShowcaseStep> steps() {
        Tour t = tour;
        return t == null ? List.of() : List.copyOf(t.steps);
    }

    /** What the world looked like when the running tour began (null when none runs). */
    @Nullable
    public Saved saved() {
        Tour t = tour;
        return t == null ? null : t.saved;
    }

    /** True while the running tour is on its way back (its last step is over). */
    public boolean ending() {
        Tour t = tour;
        return t != null && t.ending != null;
    }

    /**
     * Starts a tour for {@code player}: all six stages, or only {@code onlyStage}. Returns false
     * (and starts nothing) when a tour already runs.
     */
    public boolean start(ServerPlayer player, @Nullable CorruptionStage onlyStage) {
        if (tour != null) {
            return false;
        }
        MinecraftServer server = player.server;
        CorruptionData data = CorruptionManager.data(server);
        boolean inField = FieldDimension.isField(player.level());
        Saved saved = new Saved(data.stage(), data.paused(), data.speed(), progress(server), player.level().dimension(),
                player.position(), player.getYRot(), player.getXRot(), inField ? FieldManager.INSTANCE.phase() : null);
        List<ShowcaseStep> steps = onlyStage == null
                ? ShowcasePlan.fullTour(NullEventRegistry.all())
                : ShowcasePlan.forStage(onlyStage, NullEventRegistry.all(), true);
        Tour t = new Tour(player.getUUID(), saved, steps);
        tour = t;
        // From here on nothing moves on its own: no stage advance, no NULL survival time, no random event.
        CorruptionManager.setPaused(server, true);
        NullMod.LOGGER.info("NULL showcase started for {} ({}), the world was at stage {}", player.getGameProfile().getName(),
                onlyStage == null ? "all stages" : "stage " + onlyStage.number(), saved.stage().number());
        return true;
    }

    /** Skips to the next step. False when no tour runs or it is already ending. */
    public boolean next() {
        Tour t = tour;
        if (t == null || t.ending != null) {
            return false;
        }
        t.skip = true;
        return true;
    }

    /** Ends the running tour and puts everything back (over the next seconds). False when none runs or it is already ending. */
    public boolean stop(MinecraftServer server) {
        Tour t = tour;
        if (t == null || t.ending != null) {
            return false;
        }
        beginEnding(server, t, "null.showcase.end.stopped", false);
        return true;
    }

    /**
     * Ends the running tour at once: the world's stage, speed and pause state and the time of day
     * are put back right now, without bringing the operator home (logout, server stop, tests).
     */
    public void finishNow(MinecraftServer server, String reasonKey) {
        Tour t = tour;
        if (t == null) {
            return;
        }
        removeProps(server, t);
        restoreDayTime(server, t);
        boolean keepStage = t.ending != null && t.ending.keepStage;
        if (!keepStage) {
            restoreStage(server, t);
        }
        CorruptionManager.setSpeed(server, t.saved.speed());
        CorruptionManager.setPaused(server, t.saved.paused());
        tour = null;
        ServerPlayer player = server.getPlayerList().getPlayer(t.viewer);
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(prefix().append(Component.translatable(reasonKey).withStyle(ChatFormatting.GRAY)));
        }
        NullMod.LOGGER.info("NULL showcase ended ({}); the world is back at stage {}", reasonKey,
                CorruptionManager.stage(server).number());
    }

    // ================================================================== CorruptionSystem

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        Tour t = tour;
        if (t == null) {
            return;
        }
        long now = server.getTickCount();
        ServerPlayer player = server.getPlayerList().getPlayer(t.viewer);
        if (t.ending != null) {
            tickEnding(server, t, t.ending, player, now);
            return;
        }
        if (player == null || player.hasDisconnected()) {
            finishNow(server, "null.showcase.end.logout");
            return;
        }
        if (player.level().dimension() != t.saved.dimension()) {
            t.visitedElsewhere = true;
        }
        if (!player.isAlive()) {
            // The tour waits on the death screen; the respawn puts the player back where the stage happens.
            if (t.stepEnd != Long.MAX_VALUE) {
                t.stepEnd++;
            }
            return;
        }
        if (t.index < 0 || t.skip || now >= t.stepEnd) {
            t.skip = false;
            if (t.index >= 0) {
                endStep(server, t, current(t));
            }
            t.index++;
            if (t.index >= t.steps.size()) {
                beginEnding(server, t, "null.showcase.end.done", false);
                return;
            }
            startStep(server, t, player, current(t), now);
        } else {
            continueStep(server, t, player, current(t), now);
        }
        if (tour == t && t.ending == null && now - t.lastActionbar >= ACTIONBAR_REFRESH_TICKS) {
            player.displayClientMessage(t.line, true);
            t.lastActionbar = now;
        }
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        Tour t = tour;
        if (t == null || t.ending != null || to == t.expectedStage) {
            return;
        }
        // Somebody else moved the stage (or the clock did, after someone resumed NULL): the tour
        // steps aside and leaves the stage where they put it.
        NullMod.LOGGER.info("NULL showcase: the stage was changed to {} from outside the tour; ending it", to.number());
        beginEnding(server, t, "null.showcase.end.stage_changed", true);
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        Tour t = tour;
        if (t != null && t.viewer.equals(player.getUUID())) {
            finishNow(player.server, "null.showcase.end.logout");
        }
    }

    @Override
    public void onReset(MinecraftServer server) {
        Tour t = tour;
        if (t == null) {
            return;
        }
        // A reset decides the stage, speed and pause state itself; only what the tour made goes.
        removeProps(server, t);
        restoreDayTime(server, t);
        tour = null;
        ServerPlayer player = server.getPlayerList().getPlayer(t.viewer);
        if (player != null) {
            player.sendSystemMessage(prefix().append(Component.translatable("null.showcase.end.reset").withStyle(ChatFormatting.GRAY)));
        }
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        // Normally already done by the earlier ServerStoppingEvent listener of ShowcaseModule.
        finishNow(server, "null.showcase.end.server");
        stepSeconds = DEFAULT_STEP_SECONDS;
    }

    // ================================================================== steps

    private static ShowcaseStep current(Tour t) {
        return t.steps.get(t.index);
    }

    private int stepTicks() {
        return stepSeconds * 20;
    }

    private void startStep(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        t.settling = false;
        t.pendingEvent = null;
        t.stepStart = now;
        switch (step.kind()) {
            case STAGE -> startStage(server, t, player, step, now);
            case EVENT -> startEvent(server, t, player, step, now);
            case MOBS -> startMobs(server, t, player, step, now);
            case FIELD -> startField(server, t, player, step, now);
            case HOUSE -> startHouse(server, t, player, step, now);
        }
        t.lastActionbar = Long.MIN_VALUE;
    }

    private void continueStep(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        if (t.settling) {
            continueStage(t, player, step, now);
        } else if (t.pendingEvent != null && now >= t.pendingAt) {
            String id = t.pendingEvent;
            t.pendingEvent = null;
            runEvent(server, t, player, step, id, now);
        } else if (step.kind() == ShowcaseStep.Kind.FIELD || step.kind() == ShowcaseStep.Kind.HOUSE) {
            continueField(t, step, now);
        }
    }

    private void endStep(MinecraftServer server, Tour t, ShowcaseStep step) {
        if (step.kind() == ShowcaseStep.Kind.MOBS) {
            removeProps(server, t, true);
        }
        int next = t.index + 1;
        boolean nextNeedsNight = next < t.steps.size() && t.steps.get(next).kind() == ShowcaseStep.Kind.EVENT
                && NullEventRegistry.get(t.steps.get(next).subject()).map(ShowcasePlan::needsNight).orElse(false);
        if (!nextNeedsNight) {
            restoreDayTime(server, t);
        }
    }

    // ------------------------------------------------------------------ stage

    private void startStage(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        CorruptionStage stage = step.stage();
        removeProps(server, t);
        player.sendSystemMessage(prefix().append(Component.translatable("null.showcase.header", stage.number(), stage.title(),
                countSteps(t, stage) - 1).withStyle(ChatFormatting.WHITE)));
        setStage(server, t, stage);
        if (stage == CorruptionStage.HARVEST) {
            // The field's own day and night only run while NULL runs. Nothing else can move on in
            // Stage 6: it is the last stage, and nobody is in the NULL dimension to time.
            CorruptionManager.setPaused(server, false);
            t.runningForField = true;
        } else if (t.runningForField) {
            CorruptionManager.setPaused(server, true);
            t.runningForField = false;
        }
        t.settling = true;
        t.nudged = false;
        t.settledAt = -1L;
        t.settleFrom = player.level().dimension();
        t.settleDeadline = now + SETTLE_TIMEOUT_TICKS;
        t.stepEnd = Long.MAX_VALUE;
        String key = switch (stage) {
            case NULL -> "null.showcase.step.stage.null";
            case HARVEST -> "null.showcase.step.stage.field";
            default -> "null.showcase.step.stage";
        };
        t.line = line(step, Component.translatable(key), null, null);
    }

    /** The stage step waits until the player is where the stage happens and no move is running. */
    private void continueStage(Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        CorruptionStage stage = step.stage();
        boolean moving = NullDimensionManager.INSTANCE.inTransition(player) || FieldManager.INSTANCE.inTransition(player);
        boolean there = isWhereStageHappens(stage, player.level());
        if (!moving && !there && !t.nudged) {
            // Nobody is taking the player there (the automatic NULL transition is off, or the move
            // was refused): the tour asks for it itself, once.
            t.nudged = true;
            if (stage == CorruptionStage.NULL) {
                NullDimensionManager.INSTANCE.beginEnter(player);
            } else if (stage == CorruptionStage.HARVEST) {
                FieldManager.INSTANCE.beginEnter(player);
            } else if (NullDimensions.isNull(player.level())) {
                NullDimensionManager.INSTANCE.beginReturn(player);
            } else if (FieldDimension.isField(player.level())) {
                FieldManager.INSTANCE.beginReturn(player);
            }
            return;
        }
        if (there && !moving) {
            if (t.settledAt < 0L) {
                t.settledAt = now;
            }
            boolean arrived = player.level().dimension() != t.settleFrom;
            long ready = t.settledAt + (arrived ? ARRIVAL_DWELL_TICKS : 0L);
            t.settling = false;
            t.stepEnd = Math.max(ready, t.stepStart + STAGE_TITLE_TICKS);
            return;
        }
        if (now >= t.settleDeadline) {
            t.settling = false;
            t.stepEnd = now;
            Component where = Component.translatable(stage == CorruptionStage.NULL ? "null.showcase.place.null"
                    : stage == CorruptionStage.HARVEST ? "null.showcase.place.field" : "null.showcase.place.world");
            player.sendSystemMessage(prefix().append(Component.translatable("null.showcase.warn.unreachable", where)
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        t.line = line(step, Component.translatable(stage == CorruptionStage.NULL ? "null.showcase.step.stage.null"
                : stage == CorruptionStage.HARVEST ? "null.showcase.step.stage.field" : "null.showcase.step.stage"),
                Component.translatable("null.showcase.step.waiting"), null);
    }

    private static boolean isWhereStageHappens(CorruptionStage stage, Level level) {
        return switch (stage) {
            case NULL -> NullDimensions.isNull(level);
            case HARVEST -> FieldDimension.isField(level);
            default -> !NullDimensions.isNull(level) && !FieldDimension.isField(level);
        };
    }

    private static int countSteps(Tour t, CorruptionStage stage) {
        int count = 0;
        for (ShowcaseStep step : t.steps) {
            if (step.stage() == stage) {
                count++;
            }
        }
        return count;
    }

    private static void setStage(MinecraftServer server, Tour t, CorruptionStage stage) {
        t.expectedStage = stage;
        try {
            CorruptionManager.setStage(server, stage);
        } finally {
            t.expectedStage = null;
        }
    }

    // ------------------------------------------------------------------ events

    private void startEvent(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        Optional<NullEventDefinition> found = NullEventRegistry.get(step.subject());
        if (found.isEmpty()) {
            t.stepEnd = now;
            return;
        }
        NullEventDefinition event = found.get();
        if (ShowcasePlan.needsNight(event) && !t.nightSet) {
            setNight(server, t);
            // Let the client have the night before the sky does anything.
            t.pendingEvent = event.id();
            t.pendingAt = now + NIGHT_SETTLE_TICKS;
            t.stepEnd = Long.MAX_VALUE;
            t.line = line(step, label(event), hint(event, player), null);
            return;
        }
        runEvent(server, t, player, step, event.id(), now);
    }

    private void runEvent(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, String id, long now) {
        NullEventDefinition event = NullEventRegistry.get(id).orElse(null);
        if (event == null) {
            t.stepEnd = now;
            return;
        }
        Component problem = null;
        if (!RandomEventManager.canHappenAt(event, player.level())) {
            problem = Component.translatable("null.showcase.problem.not_here");
        } else if (!RandomEventManager.INSTANCE.showcase(player, event, CorruptionManager.context(server))) {
            problem = Component.translatable(whyNot(event));
        }
        if (problem == null) {
            t.shown++;
            t.stepEnd = now + stepTicks();
        } else {
            t.failed++;
            t.stepEnd = now + Math.min(stepTicks(), FAILED_STEP_TICKS);
        }
        t.line = line(step, label(event), problem == null ? hint(event, player) : null, problem);
    }

    private static String whyNot(NullEventDefinition event) {
        if (NullConfig.eventDisabled(event.id())) {
            return "null.showcase.problem.disabled";
        }
        if ((event.has(EventTag.WORLD) && !NullConfig.mayReplaceNatural()) || (event.has(EventTag.STRUCTURE) && !NullConfig.mayBuildStructures())) {
            return "null.showcase.problem.world_off";
        }
        return "null.showcase.problem.failed";
    }

    /** The event's name for the actionbar: a translation if one exists, else its id in words. */
    static MutableComponent label(NullEventDefinition event) {
        return Component.translatableWithFallback("null.showcase.event." + event.id(), event.id().replace('_', ' '));
    }

    /** Where to look (or listen), from what the event touches. */
    @Nullable
    private static Component hint(NullEventDefinition event, ServerPlayer player) {
        String key;
        if (ShowcasePlan.needsNight(event)) {
            key = player.serverLevel().canSeeSky(BlockPos.containing(player.getEyePosition()))
                    ? "null.showcase.hint.look_up" : "null.showcase.hint.look_up_outside";
        } else if (event.has(EventTag.DIMENSION)) {
            key = "null.showcase.hint.around";
        } else if (event.has(EventTag.WORLD) || event.has(EventTag.STRUCTURE) || event.has(EventTag.VISITOR)
                || (event.has(EventTag.NULLROOT) && !event.has(EventTag.AUDIO))) {
            key = "null.showcase.hint.ahead";
        } else if (ShowcasePlan.blockGlitch(event)) {
            key = "null.showcase.hint.ground";
        } else if (event.has(EventTag.FAKE_PLAYER)) {
            key = "null.showcase.hint.list";
        } else if (event.has(EventTag.CHAT)) {
            key = "null.showcase.hint.chat";
        } else if (event.has(EventTag.AUDIO) && !event.has(EventTag.VISUAL)) {
            key = "null.showcase.hint.listen";
        } else {
            return null;
        }
        return Component.translatable(key);
    }

    // ------------------------------------------------------------------ mobs

    private void startMobs(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        // Corrupted and parasite mobs belong to their own system; its command does the converting.
        if (!hasCommand(server, "null", "mob")) {
            t.stepEnd = now;
            return;
        }
        ServerLevel level = player.serverLevel();
        int converted = 0;
        converted += spawnProp(server, t, player, level, EntityType.COW, step.subject(), 6.0, 11.0);
        converted += spawnProp(server, t, player, level, EntityType.PIG, step.subject(), 6.0, 11.0);
        converted += spawnProp(server, t, player, level, EntityType.ZOMBIE, step.subject(), 10.0, 15.0);
        if (converted == 0) {
            removeProps(server, t);
            t.stepEnd = now;
            return;
        }
        t.shown++;
        t.stepEnd = now + Math.max(stepTicks(), MOBS_MIN_TICKS);
        Component label = Component.translatable(ShowcasePlan.MOBS_PARASITE.equals(step.subject())
                ? "null.showcase.step.mobs.parasite" : "null.showcase.step.mobs.corrupted");
        t.line = line(step, label, Component.translatable("null.showcase.hint.ahead"), null);
    }

    /** Spawns one mob in view and converts it with {@code /null mob}; returns 1 if that worked. */
    private int spawnProp(MinecraftServer server, Tour t, ServerPlayer player, ServerLevel level, EntityType<? extends Mob> type,
                          String variant, double min, double max) {
        Optional<PlayerLikeSpawns.Spot> spot = PlayerLikeSpawns.inFront(player, random, min, max, true)
                .or(() -> PlayerLikeSpawns.around(player, random, 4.0, max, 24));
        if (spot.isEmpty()) {
            return 0;
        }
        Mob mob = type.create(level);
        if (mob == null) {
            return 0;
        }
        Vec3 pos = spot.get().pos();
        float yaw = (float) (Mth.atan2(player.getZ() - pos.z, player.getX() - pos.x) * (180.0 / Math.PI)) - 90.0F;
        mob.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        mob.setYHeadRot(yaw);
        mob.setPersistenceRequired();
        if (type == EntityType.ZOMBIE) {
            // It must not burn away in the sun before it has been seen.
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            mob.setDropChance(EquipmentSlot.HEAD, 0.0F);
        }
        if (!level.addFreshEntity(mob)) {
            return 0;
        }
        t.props.add(mob.getUUID());
        t.propsLevel = level.dimension();
        return runCommand(server, "null mob " + variant + " " + mob.getUUID()) ? 1 : 0;
    }

    private static void removeProps(MinecraftServer server, Tour t) {
        removeProps(server, t, false);
    }

    /**
     * Takes away the mobs the tour spawned: all of them, or only the hostile ones (the animals
     * stay for the rest of their stage, where the mob events can find them; a zombie does not
     * follow the player through the blackouts that come later).
     */
    private static void removeProps(MinecraftServer server, Tour t, boolean hostileOnly) {
        ServerLevel level = t.propsLevel == null ? null : server.getLevel(t.propsLevel);
        if (level == null) {
            t.props.clear();
            return;
        }
        Iterator<UUID> it = t.props.iterator();
        while (it.hasNext()) {
            Entity entity = level.getEntity(it.next());
            if (entity == null) {
                it.remove();
            } else if (!hostileOnly || entity instanceof Enemy) {
                entity.discard();
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ the field

    private void startField(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        if (!FieldDimension.isField(player.level())) {
            t.stepEnd = now;
            return;
        }
        String phase = step.subject();
        // If the countdown ran out on its own just now, IT is already on its way: no second one.
        boolean alreadyHunting = ShowcasePlan.FIELD_HUNT.equals(phase) && FieldManager.INSTANCE.phase() == FieldCycle.Phase.HUNT;
        if (!alreadyHunting && !runCommand(server, "null field " + phase)) {
            FieldCycle.Phase fallback = switch (phase) {
                case ShowcasePlan.FIELD_SHATTER -> FieldCycle.Phase.SHATTER;
                case ShowcasePlan.FIELD_NIGHT -> FieldCycle.Phase.NIGHT;
                case ShowcasePlan.FIELD_HUNT -> FieldCycle.Phase.HUNT;
                case ShowcasePlan.FIELD_DAWN -> FieldCycle.Phase.DAWN;
                default -> FieldCycle.Phase.DAY;
            };
            FieldManager.INSTANCE.skipTo(server, fallback);
        }
        t.shown++;
        t.stepEnd = switch (phase) {
            case ShowcasePlan.FIELD_SHATTER -> now + Math.max(stepTicks(), SHATTER_MIN_TICKS);
            case ShowcasePlan.FIELD_HUNT -> now + HUNT_TIMEOUT_TICKS;
            default -> now + stepTicks();
        };
        Component hint = ShowcasePlan.FIELD_HUNT.equals(phase) ? Component.translatable("null.showcase.hint.stay_inside")
                : ShowcasePlan.FIELD_DAY.equals(phase) || ShowcasePlan.FIELD_CRACKING.equals(phase)
                ? Component.translatable("null.showcase.hint.sun") : null;
        t.line = line(step, Component.translatable("null.showcase.step.field." + phase), hint, null);
    }

    /**
     * The hunt step lasts as long as IT hunts. Every other step of the field ends before the
     * countdown runs out, so IT only ever comes in the hunt step, with the player in a house.
     */
    private void continueField(Tour t, ShowcaseStep step, long now) {
        FieldCycle.Phase phase = FieldManager.INSTANCE.phase();
        if (step.kind() == ShowcaseStep.Kind.FIELD && ShowcasePlan.FIELD_HUNT.equals(step.subject())) {
            if (phase != FieldCycle.Phase.HUNT && now > t.stepStart + 40) {
                t.stepEnd = now;
            }
        } else if (phase == FieldCycle.Phase.NIGHT && FieldManager.INSTANCE.remaining() <= NIGHT_SAFETY_TICKS) {
            t.stepEnd = Math.min(t.stepEnd, now);
        }
    }

    private void startHouse(MinecraftServer server, Tour t, ServerPlayer player, ShowcaseStep step, long now) {
        if (!FieldDimension.isField(player.level())) {
            t.stepEnd = now;
            return;
        }
        ServerLevel level = player.serverLevel();
        FieldHouse house = FieldGeneration.nearestHouse(FieldChunkGenerator.seedOf(level), player.getBlockX(), player.getBlockZ());
        BlockPos inside = insideSpot(level, house);
        double dx = house.doorX() + 0.5 - (inside.getX() + 0.5);
        double dz = house.doorZ() + 0.5 - (inside.getZ() + 0.5);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        player.teleportTo(level, inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, yaw, 0.0F);
        player.resetFallDistance();
        boolean sheltered;
        try {
            sheltered = ItEntity.isSheltered(player);
        } catch (RuntimeException e) {
            sheltered = true;
        }
        t.stepEnd = now + Math.max(stepTicks(), HOUSE_MIN_TICKS);
        t.line = line(step, Component.translatable("null.showcase.step.house"), Component.translatable("null.showcase.hint.stay_inside"),
                sheltered ? null : Component.translatable("null.showcase.problem.open_house"));
    }

    /** A floor spot inside the house, as near its middle as possible (the middle when its chunk is not loaded yet). */
    private static BlockPos insideSpot(ServerLevel level, FieldHouse house) {
        BlockPos middle = new BlockPos(house.x(), house.floor() + 1, house.z());
        if (!LocationFinder.isLoaded(level, house.x(), house.z())) {
            return middle;
        }
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -house.halfWidthX() + 1; dx < house.halfWidthX(); dx++) {
            for (int dz = -house.halfWidthZ() + 1; dz < house.halfWidthZ(); dz++) {
                BlockPos feet = new BlockPos(house.x() + dx, house.floor() + 1, house.z() + dz);
                int distance = dx * dx + dz * dz;
                if (distance < bestDistance && house.inInterior(feet.getX(), feet.getZ())
                        && LocationFinder.isLoaded(level, feet.getX(), feet.getZ()) && PlayerLikeSpawns.standable(level, feet)) {
                    best = feet;
                    bestDistance = distance;
                }
            }
        }
        return best != null ? best : middle;
    }

    // ------------------------------------------------------------------ night for the sky

    /**
     * Midnight for the sky steps: the last one before now, so that putting the old time back later
     * is a step forward. The corruption clock (which only follows the day time while NULL counts,
     * and the tour has paused it) then has nothing left to wait for: a step back that it judged as
     * one is paid back by the step forward. Only on a world's very first day, which has no midnight
     * before it, the next one is used instead.
     */
    private static void setNight(MinecraftServer server, Tour t) {
        if (t.nightSet) {
            return;
        }
        ServerLevel overworld = server.overworld();
        long time = overworld.getDayTime();
        long day = Level.TICKS_PER_DAY;
        long forward = Math.floorMod(MIDNIGHT - time, day);
        long back = day - forward;
        long night = forward == 0L ? time : time - back >= 0L ? time - back : time + forward;
        t.timeBeforeNight = time;
        t.nightTime = night;
        t.nightSet = true;
        if (night != time) {
            overworld.setDayTime(night);
            server.forceTimeSynchronization();
        }
    }

    /** Back to the old time of day, plus however long the night was shown. */
    private static void restoreDayTime(MinecraftServer server, Tour t) {
        if (!t.nightSet) {
            return;
        }
        t.nightSet = false;
        ServerLevel overworld = server.overworld();
        long shown = Math.max(0L, overworld.getDayTime() - t.nightTime);
        overworld.setDayTime(t.timeBeforeNight + shown);
        server.forceTimeSynchronization();
    }

    // ================================================================== the way back

    private void beginEnding(MinecraftServer server, Tour t, String reasonKey, boolean keepStage) {
        removeProps(server, t);
        restoreDayTime(server, t);
        t.pendingEvent = null;
        t.ending = new Ending(reasonKey, keepStage, server.getTickCount());
        ServerPlayer player = server.getPlayerList().getPlayer(t.viewer);
        if (player != null) {
            player.displayClientMessage(Component.translatable("null.showcase.title").withStyle(ChatFormatting.GRAY)
                    .append(dot())
                    .append(Component.translatable("null.showcase.ending").withStyle(ChatFormatting.GRAY)), true);
        }
    }

    private void tickEnding(MinecraftServer server, Tour t, Ending e, @Nullable ServerPlayer player, long now) {
        boolean present = player != null && !player.hasDisconnected();
        switch (e.phase) {
            case WAIT -> {
                boolean timedOut = now - e.since > END_WAIT_TIMEOUT_TICKS;
                if (!present) {
                    e.phase = EndPhase.RESTORE;
                } else if (!timedOut && (!player.isAlive() || NullDimensionManager.INSTANCE.inTransition(player)
                        || FieldManager.INSTANCE.inTransition(player))) {
                    return;
                } else if (timedOut || !player.isAlive()) {
                    e.phase = EndPhase.RESTORE;
                } else {
                    beginWayHome(server, t, e, player, now);
                }
            }
            case MOVE -> {
                if (!present) {
                    e.phase = EndPhase.RESTORE;
                } else if (now >= e.moveAt) {
                    arriveHome(server, t, e, player);
                    e.phase = EndPhase.RESTORE;
                }
            }
            case RESTORE -> {
                if (!e.keepStage) {
                    restoreStage(server, t);
                    if (wantsCatchUp(server, t) && present) {
                        e.phase = EndPhase.CATCH_UP;
                        return;
                    }
                }
                finish(server, t, e, player);
            }
            case CATCH_UP -> {
                if (catchUp(server, t, e)) {
                    finish(server, t, e, player);
                }
            }
        }
    }

    /** Takes the player back to where they stood: through the black screen when that means another dimension. */
    private void beginWayHome(MinecraftServer server, Tour t, Ending e, ServerPlayer player, long now) {
        Saved saved = t.saved;
        ServerLevel home = server.getLevel(saved.dimension());
        if (home == null) {
            e.phase = EndPhase.RESTORE;
            return;
        }
        if (player.level().dimension() == saved.dimension()) {
            if (t.visitedElsewhere && player.position().distanceToSqr(saved.pos()) > 4.0) {
                // Back in the same dimension (a stage set on the way brought them), but not where they stood.
                player.teleportTo(home, saved.pos().x, saved.pos().y, saved.pos().z, saved.yaw(), saved.pitch());
            }
            e.phase = EndPhase.RESTORE;
            return;
        }
        BlockPos target = BlockPos.containing(saved.pos());
        home.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(target), 2, target);
        boolean darkPlace = NullDimensions.NULL_LEVEL.equals(saved.dimension()) || FieldDimension.FIELD_LEVEL.equals(saved.dimension());
        e.arriveAfterMove = darkPlace;
        if (darkPlace) {
            NullNetworking.sendToPlayer(player, new TransitionPayload(TransitionPayload.PHASE_COLLAPSE, NullDimensionManager.COLLAPSE_TICKS));
            e.moveAt = now + NullDimensionManager.COLLAPSE_TICKS;
        } else {
            NullNetworking.sendToPlayer(player, new TransitionPayload(TransitionPayload.PHASE_RETURN, NullDimensionManager.RETURN_TICKS));
            e.moveAt = now + NullDimensionManager.RETURN_TICKS;
        }
        e.phase = EndPhase.MOVE;
    }

    private static void arriveHome(MinecraftServer server, Tour t, Ending e, ServerPlayer player) {
        Saved saved = t.saved;
        ServerLevel home = server.getLevel(saved.dimension());
        if (home == null || !player.isAlive()) {
            NullNetworking.sendToPlayer(player, new TransitionPayload(TransitionPayload.PHASE_CLEAR, 0));
            return;
        }
        if (player.isSleeping()) {
            player.stopSleeping();
        }
        player.teleportTo(home, saved.pos().x, saved.pos().y, saved.pos().z, saved.yaw(), saved.pitch());
        if (player.level() != home) {
            // The move was refused: give the screen back rather than leaving it black.
            NullNetworking.sendToPlayer(player, new TransitionPayload(TransitionPayload.PHASE_CLEAR, 0));
            return;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        if (e.arriveAfterMove) {
            NullNetworking.sendToPlayer(player, new TransitionPayload(TransitionPayload.PHASE_ARRIVE, NullDimensionManager.ARRIVE_TICKS));
        }
    }

    /** Puts the world's stage back (always set again: the clock goes back to the world's hour in it). */
    private static void restoreStage(MinecraftServer server, Tour t) {
        setStage(server, t, t.saved.stage());
        if (t.saved.stage() == CorruptionStage.HARVEST && t.saved.fieldPhase() != null
                && FieldManager.INSTANCE.phase() != t.saved.fieldPhase()) {
            // A hunt cannot be resumed: its countdown comes again instead.
            FieldCycle.Phase phase = t.saved.fieldPhase() == FieldCycle.Phase.HUNT ? FieldCycle.Phase.NIGHT : t.saved.fieldPhase();
            FieldManager.INSTANCE.skipTo(server, phase);
        }
    }

    /**
     * Setting the stage put the clock at the start of it; the stages the clock decides (1-4) get
     * their progress back by letting the clock run fast for a tick or two (NULL and HARVEST are
     * left at their start: their progress is not on the night clock, and NULL's survival timer
     * must not see a fast clock).
     */
    private static boolean wantsCatchUp(MinecraftServer server, Tour t) {
        CorruptionStage stage = t.saved.stage();
        if (stage.atLeast(CorruptionStage.NULL) || !NullConfig.get(NullConfig.PROGRESSION_ENABLED)) {
            return false;
        }
        float target = Math.min(t.saved.progress(), CATCH_UP_LIMIT);
        return target - progress(server) > CATCH_UP_TOLERANCE;
    }

    /** Progress through the current stage, straight from the clock (the tick's context may be older). */
    private static float progress(MinecraftServer server) {
        CorruptionData data = CorruptionManager.data(server);
        return CorruptionStageManager.progressWithin(data.stage(), data.corruptionTicks());
    }

    /**
     * One tick of the catch-up: measures how far the last tick moved the clock and sets the speed
     * so the next one lands just short of the saved progress. True when done.
     */
    private static boolean catchUp(MinecraftServer server, Tour t, Ending e) {
        CorruptionStage stage = t.saved.stage();
        if (CorruptionManager.stage(server) != stage) {
            // It went too far after all (the pace changed meanwhile): back to the stage's start.
            setStage(server, t, stage);
            return true;
        }
        float target = Math.min(t.saved.progress(), CATCH_UP_LIMIT);
        float progress = progress(server);
        if (target - progress <= CATCH_UP_TOLERANCE || ++e.catchUpTicks > CATCH_UP_TICKS) {
            return true;
        }
        double speed;
        if (e.lastProgress < 0.0F) {
            // First tick: measure at the normal speed.
            speed = 1.0;
        } else {
            float gained = progress - e.lastProgress;
            if (gained <= 0.0F) {
                // The clock does not count (nobody online, the tick rate frozen...): leave it.
                return true;
            }
            speed = Math.max(1.0, (target - progress) / (gained / e.lastSpeed) * 0.97);
        }
        e.lastProgress = progress;
        e.lastSpeed = speed;
        CorruptionManager.setSpeed(server, speed);
        CorruptionManager.setPaused(server, false);
        return false;
    }

    private void finish(MinecraftServer server, Tour t, Ending e, @Nullable ServerPlayer player) {
        CorruptionManager.setSpeed(server, t.saved.speed());
        CorruptionManager.setPaused(server, t.saved.paused());
        tour = null;
        CorruptionStage stage = CorruptionManager.stage(server);
        if (player != null && !player.hasDisconnected()) {
            player.sendSystemMessage(prefix().append(Component.translatable(e.reasonKey).withStyle(ChatFormatting.GRAY)));
            player.sendSystemMessage(prefix().append(Component.translatable("null.showcase.end.summary", t.shown, t.failed,
                    stage.number(), stage.title()).withStyle(ChatFormatting.GRAY)));
            player.displayClientMessage(Component.empty(), true);
        }
        NullMod.LOGGER.info("NULL showcase ended ({}): {} shown, {} could not happen; the world is at stage {}",
                e.reasonKey, t.shown, t.failed, stage.number());
    }

    // ================================================================== helpers

    /** "NULL showcase · Stage 2 FRACTURE · camera shake · listen (4/17)" */
    private static Component line(ShowcaseStep step, Component label, @Nullable Component hint, @Nullable Component problem) {
        MutableComponent line = Component.translatable("null.showcase.title").withStyle(ChatFormatting.GRAY)
                .append(dot())
                .append(Component.translatable("null.showcase.stage", step.stage().number(), step.stage().title()).withStyle(ChatFormatting.WHITE))
                .append(dot())
                .append(label.copy().withStyle(ChatFormatting.GRAY));
        if (hint != null) {
            line.append(dot()).append(hint.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC));
        }
        if (problem != null) {
            line.append(dot()).append(problem.copy().withStyle(ChatFormatting.RED));
        }
        return line.append(Component.literal(String.format(Locale.ROOT, " (%d/%d)", step.number(), step.count()))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent dot() {
        return Component.literal(" \u00b7 ").withStyle(ChatFormatting.DARK_GRAY);
    }

    static MutableComponent prefix() {
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.translatable("null.showcase.title").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** True if the command tree has this literal path (for commands other systems may or may not provide). */
    static boolean hasCommand(MinecraftServer server, String... path) {
        CommandNode<CommandSourceStack> node = server.getCommands().getDispatcher().getRoot();
        for (String name : path) {
            node = node.getChild(name);
            if (node == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs a command as the server, silently. True if it ran and reported success with a result
     * above 0; false if it does not exist, failed or changed nothing.
     */
    static boolean runCommand(MinecraftServer server, String command) {
        boolean[] ok = {false};
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput()
                .withCallback((success, result) -> ok[0] |= success && result > 0);
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("NULL showcase: '/{}' failed", command, e);
            return false;
        }
        return ok[0];
    }
}
