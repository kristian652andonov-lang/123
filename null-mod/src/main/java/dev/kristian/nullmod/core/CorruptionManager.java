package dev.kristian.nullmod.core;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.network.StageSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Owns the corruption clock. Every server tick it advances the clock, moves the stage
 * forward when a threshold is crossed, ticks all registered {@link CorruptionSystem}s and
 * keeps clients in sync. Everything authoritative about NULL goes through here.
 * <p>
 * The clock counts in-game nights: each tick it adds how far the overworld's day time moved
 * ({@link CorruptionClock}: the day passing, a night slept through, {@code /time}; never back),
 * times the speed settings ({@code progression.speed} and {@code /null speed}), while progression
 * is enabled, NULL is not paused and someone is online (or {@code countWithoutPlayers}). With the
 * daylight cycle switched off it counts real ticks instead, so the world is never stuck. Stages 1-5
 * begin after the configured number of nights; Stage 6 (HARVEST) begins when the NULL dimension
 * reports that a player survived long enough there ({@link #requestHarvest}).
 */
public final class CorruptionManager {
    private static final int PERIODIC_SYNC_TICKS = 20 * 20;
    private static final int MAX_SYSTEM_FAILURES = 20;

    private static final List<CorruptionSystem> SYSTEMS = new ArrayList<>();
    private static final Map<String, Integer> FAILURES = new HashMap<>();
    /** Follows the overworld's day time from one tick to the next. */
    private static final CorruptionClock CLOCK = new CorruptionClock();

    @Nullable
    private static CorruptionContext current;
    private static int syncCountdown = PERIODIC_SYNC_TICKS;
    private static float lastSyncedMoon = -1.0F;
    /** A player survived long enough in the NULL dimension: HARVEST begins on the next tick. */
    private static boolean harvestRequested;

    private CorruptionManager() {
    }

    /** Registers a system. Call during mod construction; order of registration is tick order. */
    public static void registerSystem(CorruptionSystem system) {
        for (CorruptionSystem existing : SYSTEMS) {
            if (existing.name().equals(system.name())) {
                throw new IllegalStateException("Duplicate corruption system " + system.name());
            }
        }
        SYSTEMS.add(system);
    }

    public static List<CorruptionSystem> systems() {
        return Collections.unmodifiableList(SYSTEMS);
    }

    public static CorruptionData data(MinecraftServer server) {
        CorruptionData data = server.overworld().getDataStorage().computeIfAbsent(CorruptionData.factory(), CorruptionData.NAME);
        if (!data.seeded()) {
            // Seeded on first use (a player can be synced before the first tick). The seed is sent
            // to every client, so it must not give the world seed away (structures, bases): it is
            // derived from the same one-way hash vanilla itself sends to clients.
            data.setSeed(BiomeManager.obfuscateSeed(server.overworld().getSeed()) ^ 0x4E554C4C2D4E554CL);
        }
        if (data.clockNeedsSetup()) {
            setUpClock(server, data);
        }
        return data;
    }

    /** The snapshot for the current tick (built on demand outside of the tick loop). */
    public static CorruptionContext context(MinecraftServer server) {
        CorruptionContext ctx = current;
        if (ctx == null || ctx.server() != server) {
            ctx = buildContext(server, data(server));
            current = ctx;
        }
        return ctx;
    }

    public static CorruptionStage stage(MinecraftServer server) {
        return data(server).stage();
    }

    /**
     * True while the corruption clock counts: progression is enabled, NULL is not paused and
     * someone is online (or the config counts without players).
     */
    public static boolean counting(MinecraftServer server) {
        return counting(server, data(server));
    }

    /** How fast the clock runs: {@code progression.speed} times {@code /null speed}. */
    public static double effectiveSpeed(MinecraftServer server) {
        return effectiveSpeed(data(server));
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        CorruptionData data = data(server);
        // The day time is followed on every tick, counted or not, so time that passed while NULL was
        // paused (or nobody was online) is never counted later.
        long dayTicks = clockAdvance(server);

        if (harvestRequested) {
            harvestRequested = false;
            if (data.stage() == CorruptionStage.NULL) {
                changeStage(server, data, CorruptionStage.HARVEST, false);
            }
        }

        if (counting(server, data)) {
            data.advance(dayTicks * effectiveSpeed(data), server.tickRateManager().runsNormally());
            CorruptionStage reached = CorruptionStageManager.stageFor(data.corruptionTicks());
            // The clock can only move the stage forward. Lowering a threshold in the config
            // later never takes a world back to an earlier stage.
            if (reached.number() > data.stage().number()) {
                changeStage(server, data, reached, false);
            }
        }

        CorruptionContext ctx = buildContext(server, data);
        current = ctx;

        for (CorruptionSystem system : SYSTEMS) {
            if (FAILURES.getOrDefault(system.name(), 0) >= MAX_SYSTEM_FAILURES) {
                continue;
            }
            try {
                system.tick(server, ctx);
            } catch (RuntimeException e) {
                int failures = FAILURES.merge(system.name(), 1, Integer::sum);
                NullMod.LOGGER.error("NULL system '{}' failed while ticking ({} of {})", system.name(), failures, MAX_SYSTEM_FAILURES, e);
                if (failures >= MAX_SYSTEM_FAILURES) {
                    NullMod.LOGGER.error("NULL system '{}' is disabled until the server restarts", system.name());
                }
            }
        }

        if (--syncCountdown <= 0 || Math.abs(data.moonProgress() - lastSyncedMoon) >= 0.01F) {
            syncAll(server);
        }
    }

    /** Day-time ticks the clock may count this tick (before the speed settings). */
    private static long clockAdvance(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        long observed = CLOCK.step(overworld.getDayTime());
        if (!overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            // The sun stands still: count real time instead, so the world is never stuck.
            CLOCK.clearWait();
            return server.tickRateManager().runsNormally() ? 1L : 0L;
        }
        return observed;
    }

    // ------------------------------------------------------------------ admin operations

    /**
     * Moves the world to a stage, forwards or backwards (used by /null stage). The clock is set to
     * the start of that stage, at the world's time of day, so progression carries on naturally from
     * there and the next stage still begins at dawn.
     */
    public static void setStage(MinecraftServer server, CorruptionStage stage) {
        CorruptionData data = data(server);
        data.setCorruptionTicks(CorruptionStageManager.stageStart(stage, server.overworld().getDayTime()));
        CLOCK.clearWait();
        if (stage != CorruptionStage.HARVEST) {
            harvestRequested = false;
        }
        changeStage(server, data, stage, true);
    }

    /**
     * Stage 6 (HARVEST) begins on the next tick, if the world is still at Stage 5 (NULL) then.
     * Called by the NULL dimension when a player has survived long enough in it.
     */
    public static void requestHarvest(MinecraftServer server) {
        if (data(server).stage() == CorruptionStage.NULL) {
            harvestRequested = true;
        }
    }

    /**
     * The pace in the config changed (/null pace): progress is recomputed and sent to everyone. The
     * stage itself only ever moves forward on a counted tick, so a stage whose start is now behind
     * the clock begins on the next one.
     */
    public static void paceChanged(MinecraftServer server) {
        current = buildContext(server, data(server));
        syncAll(server);
    }

    /** Back to the very beginning: Stage 1, the clock at the world's time of day, every system forgets its state. */
    public static void reset(MinecraftServer server) {
        CorruptionData data = data(server);
        CorruptionStage before = data.stage();
        for (CorruptionSystem system : SYSTEMS) {
            try {
                system.onReset(server);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL system '{}' failed to reset", system.name(), e);
            }
        }
        long seed = data.seed();
        data.resetAll();
        data.setSeed(seed);
        data.setCorruptionTicks(CorruptionStageManager.stageStart(CorruptionStage.START, server.overworld().getDayTime()));
        CLOCK.clearWait();
        harvestRequested = false;
        current = buildContext(server, data);
        notifyStageChanged(server, before, CorruptionStage.START);
        syncAll(server);
    }

    public static void setPaused(MinecraftServer server, boolean paused) {
        data(server).setPaused(paused);
        current = buildContext(server, data(server));
        syncAll(server);
    }

    public static void setSpeed(MinecraftServer server, double speed) {
        data(server).setSpeed(speed);
        current = buildContext(server, data(server));
    }

    // ------------------------------------------------------------------ players

    public static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        sync(player);
        CorruptionContext ctx = context(server);
        for (CorruptionSystem system : SYSTEMS) {
            try {
                system.onPlayerJoin(player, ctx);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL system '{}' failed on player join", system.name(), e);
            }
        }
    }

    public static void onPlayerLeave(ServerPlayer player) {
        for (CorruptionSystem system : SYSTEMS) {
            try {
                system.onPlayerLeave(player);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL system '{}' failed on player leave", system.name(), e);
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        for (CorruptionSystem system : SYSTEMS) {
            try {
                system.onServerStopping(server);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL system '{}' failed while stopping", system.name(), e);
            }
        }
        current = null;
        FAILURES.clear();
        lastSyncedMoon = -1.0F;
        CLOCK.reset();
        harvestRequested = false;
    }

    // ------------------------------------------------------------------ sync

    public static void sync(ServerPlayer player) {
        NullNetworking.sendToPlayer(player, syncPayload(player.server));
    }

    public static void syncAll(MinecraftServer server) {
        StageSyncPayload payload = syncPayload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NullNetworking.sendToPlayer(player, payload);
        }
        lastSyncedMoon = data(server).moonProgress();
        syncCountdown = PERIODIC_SYNC_TICKS;
    }

    private static StageSyncPayload syncPayload(MinecraftServer server) {
        CorruptionData data = data(server);
        CorruptionStage stage = data.stage();
        return new StageSyncPayload(
                stage.number(),
                data.highestStage().number(),
                CorruptionStageManager.progressWithin(stage, data.corruptionTicks()),
                CorruptionStageManager.overallProgress(data.corruptionTicks()),
                data.moonProgress(),
                data.moonGone(),
                data.paused(),
                data.seed());
    }

    // ------------------------------------------------------------------ internals

    private static boolean counting(MinecraftServer server, CorruptionData data) {
        return NullConfig.get(NullConfig.PROGRESSION_ENABLED)
                && !data.paused()
                && (!server.getPlayerList().getPlayers().isEmpty() || NullConfig.get(NullConfig.COUNT_WITHOUT_PLAYERS));
    }

    private static double effectiveSpeed(CorruptionData data) {
        return NullConfig.get(NullConfig.PROGRESSION_SPEED) * data.speed();
    }

    /**
     * A world NULL has just begun in gets its clock at the world's time of day, so its nights end at
     * dawn. A world saved by a build that counted minutes keeps its stage and gets a night clock as
     * far into that stage as it was.
     */
    private static void setUpClock(MinecraftServer server, CorruptionData data) {
        long dayTime = server.overworld().getDayTime();
        if (data.minuteClock()) {
            double minutes = data.corruptionTicks() / Ticks.PER_MINUTE;
            data.setUpClock(CorruptionStageManager.migrateLegacyClock(data.stage(), data.corruptionTicks(), dayTime));
            NullMod.LOGGER.info("NULL now counts in-game nights: this world stays at stage {} ({} minutes of the old clock), {} nights counted",
                    data.stage().number(), String.format(Locale.ROOT, "%.1f", minutes),
                    String.format(Locale.ROOT, "%.2f", data.corruptionTicks() / CorruptionStageManager.TICKS_PER_NIGHT));
        } else {
            data.setUpClock(CorruptionStageManager.stageStart(CorruptionStage.START, dayTime));
        }
    }

    private static void changeStage(MinecraftServer server, CorruptionData data, CorruptionStage to, boolean manual) {
        CorruptionStage from = data.stage();
        if (from == to) {
            syncAll(server);
            return;
        }
        data.setStage(to);
        NullMod.LOGGER.info("NULL stage {} -> {}{}", from.number(), to.number(), manual ? " (set by command)" : "");
        current = buildContext(server, data);
        notifyStageChanged(server, from, to);
        syncAll(server);
    }

    private static void notifyStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to) {
        CorruptionContext ctx = context(server);
        for (CorruptionSystem system : SYSTEMS) {
            try {
                system.onStageChanged(server, from, to, ctx);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL system '{}' failed on stage change", system.name(), e);
            }
        }
    }

    private static CorruptionContext buildContext(MinecraftServer server, CorruptionData data) {
        CorruptionStage stage = data.stage();
        double ticks = data.corruptionTicks();
        float stageProgress = CorruptionStageManager.progressWithin(stage, ticks);
        float overall = CorruptionStageManager.overallProgress(ticks);
        float base = Math.min(1.0F, ((stage.number() - 1) + stageProgress) / 4.0F);
        float intensity = (float) Math.min(1.0, base * NullConfig.get(NullConfig.EVENT_INTENSITY));
        return new CorruptionContext(server, data, stage, stageProgress, overall, intensity, ticks, data.paused(), data.seed());
    }
}
