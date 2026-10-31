package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.world.LocationFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Runs the figure: one encounter at a time per player, started by the {@code stalker_*} random
 * events or by its own background schedule, which picks what fits the player's situation
 * (underground: the miner; at home at night: the window, the doors, a breaking pane; outdoors: a
 * tree). It is rarer and subtler in Stage 1 and bolder through Stage 4, never in Stage 5 or 6,
 * never in the NULL dimension or the field. After every encounter its player has a quiet spell.
 * <p>
 * Nothing is saved: the figures are never stored with the world, and an encounter cut short by a
 * restart, a logout, a dimension change or a stage change simply ends. What they changed is put
 * back by {@link TemporaryChanges}, which this system ticks and which survives restarts.
 */
public final class StalkerManager implements CorruptionSystem {
    public static final StalkerManager INSTANCE = new StalkerManager();

    /** Average minutes between background encounters in Stages 1-4 (before the frequency setting). */
    private static final float[] MEAN_MINUTES = {8.0F, 5.5F, 4.0F, 3.0F};
    /** Quiet seconds a player gets after an encounter ends, early in Stage 1 and late in Stage 4 (before the frequency setting). */
    private static final float QUIET_SECONDS_EARLY = 150.0F;
    private static final float QUIET_SECONDS_LATE = 55.0F;
    /** Most encounters running in the whole world at once. */
    private static final int MAX_ENCOUNTERS = 32;

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Encounter> active = new HashMap<>();
    private final Map<UUID, Long> nextAmbient = new HashMap<>();
    private final Map<UUID, Long> quietUntil = new HashMap<>();

    private StalkerManager() {
    }

    @Override
    public String name() {
        return "stalkers";
    }

    /** True for the stages the figure belongs to (Stages 1-4). */
    public static boolean stageAllows(CorruptionStage stage) {
        return stage.isBetween(CorruptionStage.START, CorruptionStage.ABOVE_MIDDLE);
    }

    /** 0 at the start of Stage 1 .. 1 at the end of Stage 4 (and beyond, when forced). */
    static float boldness(CorruptionContext ctx) {
        int stage = ctx.stage().number();
        if (stage >= CorruptionStage.NULL.number()) {
            return 1.0F;
        }
        return Mth.clamp(((stage - 1) + ctx.stageProgress()) / 3.0F, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------ starting encounters

    /**
     * Starts an encounter of this kind for a player.
     *
     * @param forced from {@code /null event}: any stage and time, relaxed placement, and it replaces
     *               the player's running encounter; the settings still apply (switched off is off)
     * @return false if it could not happen here and now
     */
    public boolean begin(ServerPlayer player, StalkerKind kind, boolean forced, CorruptionContext ctx, RandomSource rnd) {
        return begin(player, kind, forced, false, ctx, rnd);
    }

    /**
     * As {@link #begin(ServerPlayer, StalkerKind, boolean, CorruptionContext, RandomSource)}; with
     * {@code showcase} (an operator watching through {@code /null showcase}, always forced) it
     * happens in front of the player and without the slow build-up.
     */
    public boolean begin(ServerPlayer player, StalkerKind kind, boolean forced, boolean showcase, CorruptionContext ctx,
                         RandomSource rnd) {
        forced |= showcase;
        if (!StalkerSettings.enabled() || player.isSpectator() || !player.isAlive()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (!StalkerSpots.allowedLevel(level)) {
            return false;
        }
        if (!forced && (!kind.allowedAt(ctx.stage()) || ctx.paused())) {
            return false;
        }
        UUID id = player.getUUID();
        long now = player.server.overworld().getGameTime();
        Encounter running = active.get(id);
        if (running != null) {
            if (!forced) {
                return false;
            }
            finish(id, running, now, false);
        }
        if (!forced && (active.size() >= MAX_ENCOUNTERS || now < quietUntil.getOrDefault(id, Long.MIN_VALUE))) {
            return false;
        }
        Encounter encounter = create(kind, player, showcase ? 1.0F : boldness(ctx), forced, rnd);
        encounter.showcase = showcase;
        boolean started;
        try {
            started = encounter.begin(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL: the figure's '{}' encounter failed to start", kind.id(), e);
            started = false;
        }
        if (!started) {
            encounter.end();
            return false;
        }
        active.put(id, encounter);
        WorldMemory.remember(player.server, "stalker:" + kind.id(), id, "");
        return true;
    }

    private static Encounter create(StalkerKind kind, ServerPlayer player, float boldness, boolean forced, RandomSource rnd) {
        return switch (kind) {
            case TREE_WATCHER -> new TreeWatch(player, boldness, forced, rnd);
            case WINDOW_WATCHER -> new WindowWatch(player, boldness, forced, rnd);
            case DOOR_OPENER -> new DoorOpening(player, boldness, forced, rnd);
            case WINDOW_BREAKER -> new WindowBreak(player, boldness, forced, rnd);
            case MINER -> new MinerDig(player, boldness, forced, rnd);
        };
    }

    /** The kind running for a player right now, or null. */
    @Nullable
    public StalkerKind running(ServerPlayer player) {
        Encounter encounter = active.get(player.getUUID());
        return encounter == null ? null : encounter.kind;
    }

    /** Number of encounters running in the world. */
    public int runningCount() {
        return active.size();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        TemporaryChanges.INSTANCE.tick(server);
        long now = server.overworld().getGameTime();
        tickEncounters(server, now);
        if (server.getTickCount() % 20 == 11) {
            schedule(server, ctx, now);
        }
    }

    private void tickEncounters(MinecraftServer server, long now) {
        if (active.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Encounter>> it = active.entrySet().iterator();
        List<Map.Entry<UUID, Encounter>> over = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Encounter> entry = it.next();
            Encounter encounter = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && (!player.isAlive() || player.isSpectator() || player.level() != encounter.level)) {
                player = null;
            }
            boolean going;
            try {
                going = encounter.tick(player);
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL: the figure's '{}' encounter failed", encounter.kind.id(), e);
                going = false;
            }
            if (!going) {
                over.add(entry);
            }
        }
        for (Map.Entry<UUID, Encounter> entry : over) {
            finish(entry.getKey(), entry.getValue(), now, true);
        }
    }

    private void finish(UUID id, Encounter encounter, long now, boolean quietAfter) {
        active.remove(id, encounter);
        try {
            encounter.end();
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL: the figure's '{}' encounter failed to end", encounter.kind.id(), e);
        }
        if (quietAfter) {
            // Quieter for longer early on: 150 s after one in Stage 1, under a minute late in Stage 4.
            float seconds = Mth.lerp(encounter.boldness, QUIET_SECONDS_EARLY, QUIET_SECONDS_LATE);
            double frequency = Math.max(0.25, StalkerSettings.frequency());
            quietUntil.put(id, now + Math.max(20L * 20L, (long) (seconds * 20.0 / frequency)));
        }
    }

    // ------------------------------------------------------------------ the background schedule

    private void schedule(MinecraftServer server, CorruptionContext ctx, long now) {
        double frequency = StalkerSettings.frequency();
        if (frequency <= 0.0 || ctx.paused() || !stageAllows(ctx.stage())) {
            nextAmbient.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !player.isAlive() || player.isSleeping()) {
                continue;
            }
            UUID id = player.getUUID();
            Long next = nextAmbient.get(id);
            if (next == null) {
                nextAmbient.put(id, now + nextDelay(ctx.stage(), frequency));
                continue;
            }
            if (now < next || active.containsKey(id) || now < quietUntil.getOrDefault(id, Long.MIN_VALUE)) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            boolean started = StalkerSpots.allowedLevel(level) && startFitting(player, level, ctx);
            // Nothing fitted here: look again soon instead of waiting a whole interval.
            nextAmbient.put(id, now + (started ? nextDelay(ctx.stage(), frequency) : 20L * (30 + random.nextInt(40))));
        }
    }

    private long nextDelay(CorruptionStage stage, double frequency) {
        float minutes = MEAN_MINUTES[Math.min(stage.tableIndex(), MEAN_MINUTES.length - 1)];
        float jitter = 0.6F + random.nextFloat() * 0.8F;
        return Math.max(20L * 30L, (long) (minutes * jitter * 1200.0F / frequency));
    }

    /** Tries the kinds that fit where the player is, in a weighted random order. */
    private boolean startFitting(ServerPlayer player, ServerLevel level, CorruptionContext ctx) {
        List<StalkerKind> kinds = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        if (LocationFinder.isUnderground(player)) {
            add(kinds, weights, StalkerKind.MINER, 100, ctx);
        } else {
            boolean night = level.isNight();
            boolean indoors = StalkerSpots.indoors(level, player);
            if (night && indoors) {
                add(kinds, weights, StalkerKind.WINDOW_WATCHER, 40, ctx);
                add(kinds, weights, StalkerKind.DOOR_OPENER, 30, ctx);
                add(kinds, weights, StalkerKind.WINDOW_BREAKER, 20, ctx);
            } else if (night) {
                add(kinds, weights, StalkerKind.DOOR_OPENER, 35, ctx);
                add(kinds, weights, StalkerKind.TREE_WATCHER, 35, ctx);
                add(kinds, weights, StalkerKind.WINDOW_BREAKER, 15, ctx);
            } else if (indoors) {
                add(kinds, weights, StalkerKind.WINDOW_BREAKER, 30, ctx);
                add(kinds, weights, StalkerKind.TREE_WATCHER, 25, ctx);
            } else {
                add(kinds, weights, StalkerKind.TREE_WATCHER, 80, ctx);
                add(kinds, weights, StalkerKind.WINDOW_BREAKER, 20, ctx);
            }
        }
        while (!kinds.isEmpty()) {
            int total = 0;
            for (int weight : weights) {
                total += weight;
            }
            int pick = random.nextInt(Math.max(1, total));
            int index = 0;
            while (index < kinds.size() - 1 && pick >= weights.get(index)) {
                pick -= weights.get(index);
                index++;
            }
            StalkerKind kind = kinds.remove(index);
            weights.remove(index);
            if (begin(player, kind, false, ctx, random)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<StalkerKind> kinds, List<Integer> weights, StalkerKind kind, int weight, CorruptionContext ctx) {
        if (kind.allowedAt(ctx.stage())) {
            kinds.add(kind);
            weights.add(weight);
        }
    }

    // ------------------------------------------------------------------ cleanup

    private void endAll() {
        for (Encounter encounter : List.copyOf(active.values())) {
            try {
                encounter.end();
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL: the figure's '{}' encounter failed to end", encounter.kind.id(), e);
            }
        }
        active.clear();
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        if (!stageAllows(to)) {
            endAll();
        }
        nextAmbient.clear();
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        UUID id = player.getUUID();
        Encounter encounter = active.remove(id);
        if (encounter != null) {
            encounter.end();
        }
        nextAmbient.remove(id);
        quietUntil.remove(id);
        TemporaryChanges.INSTANCE.undoOwnedBy(player.server, id);
    }

    @Override
    public void onReset(MinecraftServer server) {
        endAll();
        nextAmbient.clear();
        quietUntil.clear();
        TemporaryChanges.INSTANCE.undoAll(server);
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        endAll();
        nextAmbient.clear();
        quietUntil.clear();
        // Before the world is saved: every pane back, every door closed.
        TemporaryChanges.INSTANCE.undoAll(server);
        TemporaryChanges.INSTANCE.clear();
    }
}
