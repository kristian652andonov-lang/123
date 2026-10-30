package dev.kristian.nullmod.core;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.network.StageSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Owns the corruption clock. Every server tick it advances the clock, moves the stage
 * forward when a threshold is crossed, ticks all registered {@link CorruptionSystem}s and
 * keeps clients in sync. Everything authoritative about NULL goes through here.
 */
public final class CorruptionManager {
    private static final int PERIODIC_SYNC_TICKS = 20 * 20;
    private static final int MAX_SYSTEM_FAILURES = 20;

    private static final List<CorruptionSystem> SYSTEMS = new ArrayList<>();
    private static final Map<String, Integer> FAILURES = new HashMap<>();

    @Nullable
    private static CorruptionContext current;
    private static int syncCountdown = PERIODIC_SYNC_TICKS;
    private static float lastSyncedMoon = -1.0F;

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

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        CorruptionData data = data(server);

        boolean playersOnline = !server.getPlayerList().getPlayers().isEmpty();
        boolean counting = NullConfig.get(NullConfig.PROGRESSION_ENABLED)
                && !data.paused()
                && (playersOnline || NullConfig.get(NullConfig.COUNT_WITHOUT_PLAYERS));
        if (counting) {
            data.advance(NullConfig.get(NullConfig.PROGRESSION_SPEED) * data.speed());
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

    // ------------------------------------------------------------------ admin operations

    /**
     * Moves the world to a stage, forwards or backwards (used by /null stage). The clock is
     * set to the start of that stage so progression carries on naturally from there.
     */
    public static void setStage(MinecraftServer server, CorruptionStage stage) {
        CorruptionData data = data(server);
        data.setCorruptionTicks(CorruptionStageManager.threshold(stage));
        changeStage(server, data, stage, true);
    }

    /** Back to the very beginning: Stage 1, empty clock, every system forgets its state. */
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
