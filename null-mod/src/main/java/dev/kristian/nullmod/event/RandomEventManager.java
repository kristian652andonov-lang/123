package dev.kristian.nullmod.event;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.RegionCorruption;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Picks and runs random events. Each player gets their own schedule: after a randomised wait
 * (shorter in later stages, scaled by the frequency setting and by the speed settings through
 * {@link EventPacing}) one eligible event is chosen by weight, skipping events on cooldown, events
 * the config forbids and events that ran very recently for that player.
 */
public final class RandomEventManager implements CorruptionSystem {
    public static final RandomEventManager INSTANCE = new RandomEventManager();

    /**
     * Average seconds between events for one player in Stages 1-6, before the frequency and speed
     * multipliers. In HARVEST (the field) only a few subtle events can run, and only by day.
     */
    private static final float[] BASE_INTERVAL_SECONDS = {60.0F, 35.0F, 24.0F, 16.0F, 10.0F, 30.0F};
    /** Each wait is the average times a random factor in [JITTER_MIN, JITTER_MIN + JITTER_SPAN] (mean 1). */
    private static final float JITTER_MIN = 0.45F;
    private static final float JITTER_SPAN = 1.1F;
    /** Two events for one player are never closer than this, whatever the settings (2 s). */
    public static final long MIN_GAP_TICKS = 40L;
    /** The first roll for a player who just joined (or whose schedule was cleared) comes this much later. */
    private static final long FIRST_ROLL_GRACE_TICKS = 20L * 20L;
    private static final int RECENT_MEMORY = 6;
    private static final int ATTEMPTS = 4;
    /** After a quiet spell ends, the next event waits at least this long. */
    private static final long QUIET_GRACE_TICKS = 20L * 20L;

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, PlayerSchedule> schedules = new HashMap<>();
    private final Map<String, Long> globalCooldowns = new HashMap<>();
    private final List<Predicate<ServerPlayer>> quietConditions = new ArrayList<>();

    private RandomEventManager() {
    }

    private static final class PlayerSchedule {
        long nextRoll = -1L;
        final Map<String, Long> cooldowns = new HashMap<>();
        final Deque<String> recent = new ArrayDeque<>();
    }

    @Override
    public String name() {
        return "random_events";
    }

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (ctx.paused() || server.getTickCount() % 10 != 0) {
            return;
        }
        double frequency = NullConfig.get(NullConfig.EVENT_FREQUENCY);
        if (frequency <= 0.0) {
            return;
        }
        long now = server.overworld().getGameTime();
        double speed = EventPacing.speedFactor(ctx);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Nothing happens to spectators or to someone lying on the death screen.
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (isQuiet(player)) {
                // Nothing happens to someone in the middle of a transition, nor right after it.
                PlayerSchedule quiet = schedules.get(player.getUUID());
                if (quiet != null && quiet.nextRoll >= 0L) {
                    quiet.nextRoll = Math.max(quiet.nextRoll, now + QUIET_GRACE_TICKS);
                }
                continue;
            }
            PlayerSchedule schedule = schedules.computeIfAbsent(player.getUUID(), id -> new PlayerSchedule());
            if (schedule.nextRoll < 0L) {
                // First roll after joining comes a little later than usual.
                schedule.nextRoll = now + nextDelay(ctx.stage(), frequency, speed) + FIRST_ROLL_GRACE_TICKS;
                continue;
            }
            if (now < schedule.nextRoll) {
                continue;
            }
            schedule.nextRoll = now + nextDelay(ctx.stage(), frequency, speed);
            rollFor(server, player, ctx, schedule, now);
        }
    }

    /**
     * Adds a condition under which a player gets no random events for now (the NULL dimension
     * uses it for players being moved in or out). Checked on every roll; call during setup.
     */
    public void addQuietCondition(Predicate<ServerPlayer> condition) {
        quietConditions.add(condition);
    }

    private boolean isQuiet(ServerPlayer player) {
        for (Predicate<ServerPlayer> condition : quietConditions) {
            if (condition.test(player)) {
                return true;
            }
        }
        return false;
    }

    private long nextDelay(CorruptionStage stage, double frequency, double speedFactor) {
        float jitter = JITTER_MIN + random.nextFloat() * JITTER_SPAN;
        return EventPacing.scale(baseIntervalSeconds(stage) * jitter * 20.0, frequency, speedFactor, MIN_GAP_TICKS);
    }

    /** Average seconds between two random events for one player at a stage, before any multiplier. */
    public static float baseIntervalSeconds(CorruptionStage stage) {
        return BASE_INTERVAL_SECONDS[Math.min(stage.number(), BASE_INTERVAL_SECONDS.length) - 1];
    }

    /**
     * The average wait in ticks between two random events for one player, for a frequency setting
     * and a speed factor ({@link EventPacing#speedFactor}); the shortest possible wait is
     * {@link #shortestDelayTicks}.
     */
    public static long averageDelayTicks(CorruptionStage stage, double frequency, double speedFactor) {
        float meanJitter = JITTER_MIN + JITTER_SPAN / 2.0F;
        return EventPacing.scale(baseIntervalSeconds(stage) * meanJitter * 20.0, frequency, speedFactor, MIN_GAP_TICKS);
    }

    /** The shortest wait in ticks the jitter can produce for these settings (never below {@link #MIN_GAP_TICKS}). */
    public static long shortestDelayTicks(CorruptionStage stage, double frequency, double speedFactor) {
        return EventPacing.scale(baseIntervalSeconds(stage) * JITTER_MIN * 20.0, frequency, speedFactor, MIN_GAP_TICKS);
    }

    private void rollFor(MinecraftServer server, ServerPlayer player, CorruptionContext ctx, PlayerSchedule schedule, long now) {
        List<NullEventDefinition> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (NullEventDefinition event : NullEventRegistry.all()) {
            int weight = effectiveWeight(event, player, ctx, schedule, now);
            if (weight > 0) {
                pool.add(event);
                weights.add(weight);
                total += weight;
            }
        }
        for (int attempt = 0; attempt < ATTEMPTS && total > 0; attempt++) {
            int pick = random.nextInt(total);
            int index = 0;
            while (pick >= weights.get(index)) {
                pick -= weights.get(index);
                index++;
            }
            NullEventDefinition event = pool.get(index);
            if (execute(server, player, ctx, event, false)) {
                markRan(player, schedule, event, now);
                return;
            }
            // It could not happen here; do not pick it again this roll.
            total -= weights.get(index);
            weights.set(index, 0);
        }
    }

    private int effectiveWeight(NullEventDefinition event, ServerPlayer player, CorruptionContext ctx, PlayerSchedule schedule, long now) {
        int weight = event.weightAt(ctx.stage());
        if (weight <= 0 || NullConfig.eventDisabled(event.id()) || !placeMatches(event.place(), player.level())) {
            return 0;
        }
        if (schedule.cooldowns.getOrDefault(event.id(), Long.MIN_VALUE) > now
                || globalCooldowns.getOrDefault(event.id(), Long.MIN_VALUE) > now
                || schedule.recent.contains(event.id())) {
            return 0;
        }
        if (event.has(EventTag.WORLD) && !NullConfig.mayReplaceNatural()) {
            return 0;
        }
        if (event.has(EventTag.STRUCTURE) && !NullConfig.mayBuildStructures()) {
            return 0;
        }
        double multiplier = 1.0;
        if (event.has(EventTag.VISITOR)) {
            multiplier *= NullConfig.get(NullConfig.VISITOR_FREQUENCY);
        }
        if (event.has(EventTag.NULLROOT)) {
            multiplier *= NullConfig.get(NullConfig.NULLROOT_FREQUENCY);
        }
        if (event.has(EventTag.CHAT) || event.has(EventTag.FAKE_PLAYER)) {
            multiplier *= NullConfig.get(NullConfig.CHAT_FREQUENCY);
        }
        if (event.has(EventTag.WORLD) || event.has(EventTag.STRUCTURE)) {
            multiplier *= NullConfig.get(NullConfig.WORLD_CORRUPTION_INTENSITY);
        }
        if (multiplier <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(weight * Math.min(multiplier, 10.0)));
    }

    private static boolean placeMatches(EventPlace place, Level level) {
        boolean inNull = NullDimensions.isNull(level);
        return switch (place) {
            case ANYWHERE -> true;
            case OUTSIDE_NULL -> !inNull;
            case INSIDE_NULL -> inNull;
            case OVERWORLD -> level.dimension() == Level.OVERWORLD;
        };
    }

    private void markRan(ServerPlayer player, PlayerSchedule schedule, NullEventDefinition event, long now) {
        if (event.cooldown() > 0) {
            schedule.cooldowns.put(event.id(), now + event.cooldown());
        }
        if (event.globalCooldown() > 0) {
            globalCooldowns.put(event.id(), now + event.globalCooldown());
        }
        schedule.recent.addFirst(event.id());
        while (schedule.recent.size() > RECENT_MEMORY) {
            schedule.recent.removeLast();
        }
        WorldMemory.remember(player.server, "event:" + event.id(), player.getUUID(), "");
    }

    /**
     * Runs one event for one player right now. When {@code forced} (from /null event) the
     * stage range and cooldowns are ignored, but config switches still apply: an event on the
     * disabled list never runs.
     */
    public boolean execute(MinecraftServer server, ServerPlayer player, CorruptionContext ctx, NullEventDefinition event, boolean forced) {
        return execute(server, player, ctx, event, forced, false);
    }

    /**
     * Runs one event for one player right now; with {@code showcase} (always forced as well) it
     * carries the showcase hint ({@link EventContext#showcase()}, {@link ShowcaseHint}), so what
     * it places or shows appears in front of the player.
     */
    public boolean execute(MinecraftServer server, ServerPlayer player, CorruptionContext ctx, NullEventDefinition event,
                           boolean forced, boolean showcase) {
        if (NullConfig.eventDisabled(event.id())) {
            return false;
        }
        if (event.has(EventTag.WORLD) && !NullConfig.mayReplaceNatural()) {
            return false;
        }
        if (event.has(EventTag.STRUCTURE) && !NullConfig.mayBuildStructures()) {
            return false;
        }
        float region = RegionCorruption.regionFactor(ctx.seed(), player.getBlockX(), player.getBlockZ());
        float stageBase = (ctx.stage().number() + ctx.stageProgress()) / 5.0F;
        float intensity = Mth.clamp(stageBase * (0.75F + 0.25F * region), 0.15F, 1.0F)
                * (float) NullConfig.get(NullConfig.EVENT_INTENSITY);
        intensity = Mth.clamp(intensity, 0.05F, 1.0F);
        long seed = random.nextLong();
        EventContext context = new EventContext(server, player, ctx, RandomSource.create(seed), intensity,
                event.duration(), seed, event, forced, showcase);
        try {
            EventAction action = event.action();
            if (action != null) {
                boolean happened = showcase ? ShowcaseHint.run(player, () -> action.run(context)) : action.run(context);
                if (!happened) {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL event '{}' failed", event.id(), e);
            return false;
        }
        if (event.clientEffect() != null) {
            context.sendEffect(event.clientEffect());
        }
        if (event.sound() != null) {
            SoundEvent sound = event.sound().get();
            context.playSound(sound, Optional.empty(), event.soundVolume() * Math.max(0.35F, intensity), event.soundPitch());
        }
        return true;
    }

    /** Runs an event for a player from a command, recording it like a natural one. */
    public boolean force(ServerPlayer player, NullEventDefinition event, CorruptionContext ctx) {
        return forceRecorded(player, event, ctx, false);
    }

    /**
     * Runs an event for a player in {@code /null showcase}: forced, with the showcase hint, and
     * recorded like a natural one (so it does not come straight back once the tour is over).
     */
    public boolean showcase(ServerPlayer player, NullEventDefinition event, CorruptionContext ctx) {
        return forceRecorded(player, event, ctx, true);
    }

    private boolean forceRecorded(ServerPlayer player, NullEventDefinition event, CorruptionContext ctx, boolean showcase) {
        boolean ran = execute(player.server, player, ctx, event, true, showcase);
        if (ran) {
            PlayerSchedule schedule = schedules.computeIfAbsent(player.getUUID(), id -> new PlayerSchedule());
            markRan(player, schedule, event, player.server.overworld().getGameTime());
        }
        return ran;
    }

    /** True if the event may happen where the player is now (its {@link EventPlace}). */
    public static boolean canHappenAt(NullEventDefinition event, Level level) {
        return placeMatches(event.place(), level);
    }

    /** Ids of the events that most recently ran for a player, newest first. */
    public List<String> recentEvents(ServerPlayer player) {
        PlayerSchedule schedule = schedules.get(player.getUUID());
        return schedule == null ? List.of() : List.copyOf(schedule.recent);
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        // A new stage only ever brings the next event closer (to its own pace), never pushes it
        // back. Starting every schedule again here (as earlier versions did, with the grace of a
        // first roll) meant that while the stages followed each other faster than one wait, as
        // at a high /null speed, no random event ever ran until NULL.
        double frequency = NullConfig.get(NullConfig.EVENT_FREQUENCY);
        if (frequency <= 0.0) {
            return;
        }
        long now = server.overworld().getGameTime();
        double speed = EventPacing.speedFactor(context);
        for (PlayerSchedule schedule : schedules.values()) {
            if (schedule.nextRoll >= 0L) {
                schedule.nextRoll = Math.min(schedule.nextRoll, now + nextDelay(to, frequency, speed));
            }
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        schedules.remove(player.getUUID());
    }

    @Override
    public void onReset(MinecraftServer server) {
        schedules.clear();
        globalCooldowns.clear();
        WorldMemory.clear();
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        schedules.clear();
        globalCooldowns.clear();
        WorldMemory.clear();
    }
}
