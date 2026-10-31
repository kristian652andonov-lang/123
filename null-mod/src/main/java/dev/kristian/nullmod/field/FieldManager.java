package dev.kristian.nullmod.field;

import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.InteractionResult;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.network.FieldPayload;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.network.TransitionPayload;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The server side of the field (Stage 6, HARVEST).
 * <ul>
 * <li><b>The cycle</b> ({@link FieldCycle}): one shared day and night per field, persisted in
 * {@code CorruptionData.systemTag("field")}. It runs while someone is in the field (and NULL is not
 * paused): the sun cracks through the day, shatters, the countdown runs, IT is summoned at the
 * horizon in the direction of the sun ({@code ItEntity.summon}) and hunts until it stops; then dawn
 * comes and the next day begins. Clients get the phase and its timing ({@link FieldPayload}) on
 * arrival, on joining, on every phase change and every few seconds, and interpolate.</li>
 * <li><b>Transitions</b>: {@link #beginEnter} moves a player into the field with the black-screen
 * sequence of the NULL dimension ({@link TransitionPayload} COLLAPSE, the move, ARRIVE); they arrive
 * in the wheat beside the home house, facing the sun. {@link #beginReturn} sends them back to their
 * respawn point (or the world spawn). When the stage reaches 6 everyone is moved in; when an
 * administrator moves it below 6 everyone in the field is sent back (or, for Stage 5 with the
 * automatic transition on, left to the NULL dimension, which takes them itself).</li>
 * <li><b>Respawning</b>: whoever dies while the stage is 6 or higher respawns in the field in front
 * of a house's door (their own respawn point stays theirs for later). Beds and respawn anchors do
 * nothing there, and the wheat cannot be trampled.</li>
 * </ul>
 * Nothing here decides when HARVEST begins: the core does ({@code CorruptionManager}), and this
 * reacts to the stage change, to joining players and to respawns, once each.
 */
public final class FieldManager implements CorruptionSystem {
    public static final FieldManager INSTANCE = new FieldManager();
    public static final String NAME = "field";

    /** IT may still be on its way in from the horizon this long after being summoned. */
    private static final int HUNT_GRACE_TICKS = 200;
    private static final int SAVE_INTERVAL = 100;
    /** Sounds and memory of the field reach players this far from where they happen. */
    private static final int SEARCH_RADIUS = 192;

    private enum Kind { ENTER, RETURN }

    private record Destination(ResourceKey<Level> dimension, BlockPos pos, float yaw, boolean nearRespawnPoint) {
    }

    private static final class Transition {
        final Kind kind;
        final long start;
        long execute;
        boolean announced;
        int attempts;
        int waited;
        @Nullable
        Destination destination;

        Transition(Kind kind, long start) {
            this.kind = kind;
            this.start = start;
        }
    }

    private final Map<UUID, Transition> transitions = new HashMap<>();
    private final Set<UUID> arriveAfterRespawn = new HashSet<>();
    private final FieldCycle cycle = new FieldCycle();
    @Nullable
    private ItEntity hunter;
    @Nullable
    private MinecraftServer loadedFor;
    private boolean occupied;
    private int arrivals;
    private int syncCountdown;
    private int saveCountdown = SAVE_INTERVAL;
    private boolean warnedMissing;
    private boolean warnedSummon;

    private FieldManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    /** True for Stage 6 (HARVEST), the stage of the field. */
    public static boolean fieldStage(CorruptionStage stage) {
        return stage == CorruptionStage.HARVEST;
    }

    // ================================================================== public API

    /**
     * Starts moving a player into the field now (collapse, then the move, then the arrival). For a
     * player already in the field it only cancels a pending return and tells them the cycle.
     */
    public void beginEnter(ServerPlayer player) {
        if (FieldDimension.isField(player.level())) {
            Transition pending = transitions.get(player.getUUID());
            if (pending != null && pending.kind == Kind.RETURN) {
                cancel(player, pending);
            }
            sync(player);
            return;
        }
        schedule(player, Kind.ENTER, 0);
    }

    /**
     * Starts sending a player in the field back to their respawn point or the world spawn. For a
     * player who is not in the field it only cancels a pending move into it.
     */
    public void beginReturn(ServerPlayer player) {
        if (!FieldDimension.isField(player.level())) {
            Transition pending = transitions.get(player.getUUID());
            if (pending != null && pending.kind == Kind.ENTER) {
                cancel(player, pending);
            }
            return;
        }
        schedule(player, Kind.RETURN, 0);
    }

    /** True while a move into or out of the field is pending for this player. */
    public boolean inTransition(ServerPlayer player) {
        return transitions.containsKey(player.getUUID());
    }

    /** The current phase of the field's cycle. */
    public FieldCycle.Phase phase() {
        return cycle.phase();
    }

    /** Ticks left in the current phase. */
    public int remaining() {
        return cycle.remaining();
    }

    /** Random events leave players alone while they are being moved, and in the field once the sun breaks. */
    public boolean quiet(ServerPlayer player) {
        return inTransition(player) || FieldDimension.isField(player.level()) && cycle.phase() != FieldCycle.Phase.DAY;
    }

    /**
     * Jumps the field's cycle to the start of a phase (for administrators and tests): the hunt
     * summons IT at once; every player in the field is told.
     */
    public void skipTo(MinecraftServer server, FieldCycle.Phase phase) {
        skipTo(server, phase, 0);
    }

    /**
     * Like {@link #skipTo(MinecraftServer, FieldCycle.Phase)}, but {@code elapsed} ticks into the
     * phase (for example late in the day, when the sun is badly cracked).
     */
    public void skipTo(MinecraftServer server, FieldCycle.Phase phase, int elapsed) {
        cycle.set(phase, Math.max(0, Math.min(elapsed, FieldCycle.length(phase) - 1)));
        if (phase != FieldCycle.Phase.HUNT) {
            // A hunt under way runs out on its own; the field stops waiting for it.
            hunter = null;
        }
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        if (level != null) {
            onPhaseBegan(level, phase);
        }
        save(CorruptionManager.data(server));
    }

    // ================================================================== ticking

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (loadedFor != server) {
            loadedFor = server;
            cycle.load(ctx.data().systemTag(NAME));
            hunter = null;
            occupied = false;
        }
        if (!transitions.isEmpty()) {
            tickTransitions(server, server.getTickCount());
        }
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        if (level == null) {
            return;
        }
        if (level.players().isEmpty()) {
            // The field waits for its people: nothing moves while nobody is there.
            occupied = false;
            return;
        }
        if (!occupied) {
            occupied = true;
            onOccupied(level);
        }
        if (!ctx.paused()) {
            FieldCycle.Phase began = cycle.tick(cycle.phase() == FieldCycle.Phase.HUNT && huntOver());
            if (began != null) {
                onPhaseBegan(level, began);
            }
        }
        if (--syncCountdown <= 0) {
            syncAll(level);
        }
        if (--saveCountdown <= 0) {
            save(ctx.data());
        }
    }

    /** IT is done: gone, or no longer hunting once it has had time to come in from the horizon. */
    private boolean huntOver() {
        ItEntity it = hunter;
        if (it == null || it.isRemoved()) {
            return true;
        }
        if (cycle.elapsed() < HUNT_GRACE_TICKS) {
            return false;
        }
        try {
            return !it.isHunting();
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("IT could not say whether it is still hunting; ending the night", e);
            return true;
        }
    }

    /** Someone is in the field again after it stood empty (or after a restart). */
    private void onOccupied(ServerLevel level) {
        if (cycle.phase() == FieldCycle.Phase.HUNT && (hunter == null || hunter.isRemoved())) {
            // IT is gone (the server restarted or everyone left): the countdown runs again, briefly.
            hunter = null;
            cycle.set(FieldCycle.Phase.NIGHT, rejoinElapsed());
        } else if (cycle.phase() == FieldCycle.Phase.NIGHT && cycle.remaining() < Math.min(FieldTuning.REJOIN_MIN_COUNTDOWN, FieldTuning.nightTicks())) {
            cycle.set(FieldCycle.Phase.NIGHT, rejoinElapsed());
        }
        syncAll(level);
    }

    /** How far into the night someone coming back into an empty field starts: with at least the minimum countdown left. */
    private static int rejoinElapsed() {
        int night = FieldTuning.nightTicks();
        return Math.max(0, night - Math.min(FieldTuning.REJOIN_MIN_COUNTDOWN, night));
    }

    private void onPhaseBegan(ServerLevel level, FieldCycle.Phase phase) {
        MinecraftServer server = level.getServer();
        switch (phase) {
            case SHATTER -> WorldMemory.remember(server, "field:shattered", null, "");
            case NIGHT -> WorldMemory.remember(server, "field:night", null, "");
            case HUNT -> summon(level);
            case DAWN -> {
                hunter = null;
                for (ServerPlayer player : level.players()) {
                    if (player.isAlive() && !player.isSpectator()) {
                        WorldMemory.remember(server, "field:survived", player.getUUID(), "");
                    }
                }
            }
            case DAY -> WorldMemory.remember(server, "field:day", null, String.valueOf(cycle.cycle()));
        }
        syncAll(level);
        save(CorruptionManager.data(server));
    }

    // ================================================================== IT

    /** IT comes from the horizon in front of the players (where the sun was) and hunts. */
    private void summon(ServerLevel level) {
        hunter = null;
        List<ServerPlayer> present = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator()) {
                present.add(player);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        double cx = 0.0;
        double cz = 0.0;
        for (ServerPlayer player : present) {
            cx += player.getX();
            cz += player.getZ();
        }
        cx /= present.size();
        cz /= present.size();
        Vec3 pos = summonPosition(level, cx, cz);
        ServerPlayer target = present.get(0);
        int bestRank = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : present) {
            // Prefer someone IT can take: not in creative, and out in the open.
            int rank = (player.isCreative() ? 2 : 0) + (sheltered(player) ? 1 : 0);
            double distance = player.distanceToSqr(pos);
            if (rank < bestRank || rank == bestRank && distance < bestDistance) {
                bestRank = rank;
                bestDistance = distance;
                target = player;
            }
        }
        try {
            hunter = ItEntity.summon(level, pos, target, FieldTuning.huntTicks());
        } catch (RuntimeException e) {
            hunter = null;
            if (!warnedSummon) {
                warnedSummon = true;
                NullMod.LOGGER.error("IT could not be summoned into the field; the night passes without it", e);
            }
        }
        WorldMemory.remember(level.getServer(), "field:hunt", target.getUUID(), "");
    }

    private static boolean sheltered(ServerPlayer player) {
        try {
            return ItEntity.isSheltered(player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A point at the horizon towards the sun, as far out as the server still simulates entities
     * (so IT really comes, instead of standing frozen in a chunk nobody ticks), and outside every
     * house.
     */
    private static Vec3 summonPosition(ServerLevel level, double cx, double cz) {
        int simulated = level.getServer().getPlayerList().getSimulationDistance() * 16 - 16;
        int distance = Math.max(FieldTuning.IT_MIN_DISTANCE, Math.min(FieldTuning.itDistance(), simulated));
        long seed = FieldChunkGenerator.seedOf(level);
        BlockPos chosen = null;
        for (; distance >= FieldTuning.IT_MIN_DISTANCE; distance -= 16) {
            int x = (int) Math.floor(cx) + FieldDimension.SUN_DIR_X * distance;
            int z = (int) Math.floor(cz) + FieldDimension.SUN_DIR_Z * distance;
            // Step sideways out of any building.
            for (FieldHouse house : FieldGeneration.houses(seed, x - 16, z - 16, x + 16, z + 16)) {
                if (Math.abs(x - house.x()) <= house.halfWidthX() + 2 && Math.abs(z - house.z()) <= house.halfWidthZ() + 2) {
                    x = house.x() + house.halfWidthX() + 4;
                }
            }
            BlockPos pos = new BlockPos(x, FieldGeneration.groundY(seed, x, z) + 1, z);
            if (chosen == null) {
                chosen = pos;
            }
            if (level.isPositionEntityTicking(pos)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                return new Vec3(x + 0.5, Math.max(y, pos.getY()), z + 0.5);
            }
        }
        return Vec3.atBottomCenterOf(chosen);
    }

    // ================================================================== sync and storage

    private FieldPayload payload(MinecraftServer server) {
        long seed = FieldGeneration.mix(CorruptionManager.data(server).seed() ^ 0x53554E4C49474854L);
        return new FieldPayload(cycle.phase().id(), cycle.elapsed(), cycle.length(), cycle.cycle(), seed);
    }

    /** Tells one player in the field where the cycle stands. */
    public void sync(ServerPlayer player) {
        if (FieldDimension.isField(player.level())) {
            NullNetworking.sendToPlayer(player, payload(player.server));
        }
    }

    private void syncAll(ServerLevel level) {
        FieldPayload payload = payload(level.getServer());
        for (ServerPlayer player : level.players()) {
            NullNetworking.sendToPlayer(player, payload);
        }
        syncCountdown = FieldTuning.RESYNC_TICKS;
    }

    private void save(CorruptionData data) {
        cycle.save(data.systemTag(NAME));
        data.setDirty();
        saveCountdown = SAVE_INTERVAL;
    }

    // ================================================================== transitions

    private void schedule(ServerPlayer player, Kind kind, int delay) {
        Transition existing = transitions.get(player.getUUID());
        if (existing != null) {
            if (existing.kind == kind) {
                return;
            }
            cancel(player, existing);
        }
        transitions.put(player.getUUID(), new Transition(kind, (long) player.server.getTickCount() + delay));
    }

    private void cancel(ServerPlayer player, Transition t) {
        transitions.remove(player.getUUID());
        if (t.announced) {
            sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
        }
    }

    private void tickTransitions(MinecraftServer server, long now) {
        for (UUID id : List.copyOf(transitions.keySet())) {
            Transition t = transitions.get(id);
            if (t == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.hasDisconnected()) {
                transitions.remove(id);
                continue;
            }
            if (!t.announced) {
                if (now < t.start) {
                    continue;
                }
                if (!player.isAlive()) {
                    // Never darken a death screen; respawning takes the player where they belong.
                    transitions.remove(id);
                    continue;
                }
                if (NullDimensionManager.INSTANCE.inTransition(player) && ++t.waited < FieldTuning.MAX_WAIT_FOR_OTHER_TRANSITION) {
                    // Let a move of the NULL dimension finish first, so the two never overlap on screen.
                    continue;
                }
                announce(server, player, t, now);
                continue;
            }
            if (now < t.execute) {
                continue;
            }
            if (!player.isAlive()) {
                transitions.remove(id);
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
                continue;
            }
            boolean done = t.kind == Kind.ENTER ? enter(server, player, t) : leave(server, player, t);
            if (done) {
                transitions.remove(id);
            } else if (++t.attempts >= FieldTuning.MAX_ATTEMPTS) {
                transitions.remove(id);
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
                NullMod.LOGGER.warn("NULL could not move {} {} the field", player.getGameProfile().getName(),
                        t.kind == Kind.ENTER ? "into" : "out of");
            } else {
                t.execute = now + FieldTuning.RETRY_DELAY_TICKS;
            }
        }
    }

    private void announce(MinecraftServer server, ServerPlayer player, Transition t, long now) {
        t.announced = true;
        if (t.kind == Kind.ENTER) {
            ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
            if (level == null) {
                warnMissing();
                transitions.remove(player.getUUID());
                return;
            }
            List<int[]> spots = FieldGeneration.arrivalSpots(FieldChunkGenerator.seedOf(level));
            int[] spot = spots.get(Math.floorMod(arrivals++, spots.size()));
            BlockPos pos = new BlockPos(spot[0], spot[1], spot[2]);
            t.destination = new Destination(FieldDimension.FIELD_LEVEL, pos, FieldDimension.SUN_YAW, false);
            t.execute = now + NullDimensionManager.COLLAPSE_TICKS;
            // Start loading (and on first use generating) the arrival area while the screen fades.
            level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(pos), 3, pos);
            sendPhase(player, TransitionPayload.PHASE_COLLAPSE, NullDimensionManager.COLLAPSE_TICKS);
            WorldMemory.remember(server, "field:collapse", player.getUUID(), "");
        } else {
            Destination destination = returnDestination(server, player);
            t.destination = destination;
            t.execute = now + NullDimensionManager.RETURN_TICKS;
            ServerLevel level = server.getLevel(destination.dimension());
            if (level != null) {
                level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(destination.pos()), 2, destination.pos());
            }
            sendPhase(player, TransitionPayload.PHASE_RETURN, NullDimensionManager.RETURN_TICKS);
            WorldMemory.remember(server, "field:return", player.getUUID(), "");
        }
    }

    /** Moves a player into the field. False if the move did not happen (retried later). */
    private boolean enter(MinecraftServer server, ServerPlayer player, Transition t) {
        if (FieldDimension.isField(player.level())) {
            arrive(player);
            return true;
        }
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        if (level == null) {
            warnMissing();
            sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
            return true;
        }
        BlockPos preferred = t.destination != null ? t.destination.pos() : null;
        BlockPos pos = arrivalSpot(level, preferred);
        wake(player);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, FieldDimension.SUN_YAW, 0.0F);
        if (player.level() != level) {
            return false;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        arrive(player);
        WorldMemory.remember(server, "field:arrived", player.getUUID(), "");
        return true;
    }

    private void arrive(ServerPlayer player) {
        sendPhase(player, TransitionPayload.PHASE_ARRIVE, NullDimensionManager.ARRIVE_TICKS);
        sync(player);
    }

    /** Sends a player back to the world. False if the move did not happen (retried later). */
    private boolean leave(MinecraftServer server, ServerPlayer player, Transition t) {
        if (!FieldDimension.isField(player.level())) {
            sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
            return true;
        }
        Destination destination = t.destination != null ? t.destination : returnDestination(server, player);
        ServerLevel level = server.getLevel(destination.dimension());
        BlockPos pos = null;
        if (level != null && destination.nearRespawnPoint()) {
            pos = standableNear(level, destination.pos());
        }
        if (pos == null) {
            level = server.overworld();
            // Where vanilla itself respawns a player without a home: dry ground near the world spawn.
            pos = player.adjustSpawnLocation(level, level.getSharedSpawnPos());
        }
        wake(player);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, destination.yaw(), 0.0F);
        if (FieldDimension.isField(player.level())) {
            return false;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    private Destination returnDestination(MinecraftServer server, ServerPlayer player) {
        BlockPos respawn = player.getRespawnPosition();
        ResourceKey<Level> dimension = player.getRespawnDimension();
        if (respawn != null && !FieldDimension.FIELD_LEVEL.equals(dimension) && !NullDimensions.NULL_LEVEL.equals(dimension)
                && server.getLevel(dimension) != null) {
            return new Destination(dimension, respawn, player.getRespawnAngle(), true);
        }
        ServerLevel overworld = server.overworld();
        return new Destination(Level.OVERWORLD, overworld.getSharedSpawnPos(), overworld.getSharedSpawnAngle(), false);
    }

    /** The preferred arrival spot if a player can stand there, otherwise the next free one, never inside anything. */
    private static BlockPos arrivalSpot(ServerLevel level, @Nullable BlockPos preferred) {
        if (preferred != null && standable(level, preferred)) {
            return preferred;
        }
        List<int[]> spots = FieldGeneration.arrivalSpots(FieldChunkGenerator.seedOf(level));
        for (int[] spot : spots) {
            BlockPos pos = new BlockPos(spot[0], spot[1], spot[2]);
            if (standable(level, pos)) {
                return pos;
            }
        }
        // Players have built over every spot: stand on top of whatever is at the first one.
        int[] first = spots.get(0);
        return onTop(level, first[0], first[2], first[1]);
    }

    /** The first free spot at or above {@code fromY} in a column, or its surface. */
    private static BlockPos onTop(ServerLevel level, int x, int z, int fromY) {
        level.getChunk(x >> 4, z >> 4);
        for (int y = Math.max(fromY, level.getMinBuildHeight() + 1); y < level.getMaxBuildHeight() - 2; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (standable(level, pos)) {
                return pos;
            }
        }
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
    }

    /** A free spot next to a respawn point (bed, anchor or set position), or null. */
    @Nullable
    private static BlockPos standableNear(ServerLevel level, BlockPos center) {
        level.getChunk(center.getX() >> 4, center.getZ() >> 4);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isInWorldBounds(pos) || !standable(level, pos)) {
                        continue;
                    }
                    double distance = dx * dx + dz * dz + dy * dy * 0.5;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** Room for a player on a solid floor, with nothing that burns or hurts at their feet, head or under them. */
    static boolean standable(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) {
            return false;
        }
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        return free(level, pos) && free(level, pos.above()) && !floor.getCollisionShape(level, below).isEmpty()
                && !EntityType.PLAYER.isBlockDangerous(floor);
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                && !EntityType.PLAYER.isBlockDangerous(state);
    }

    /** Like /tp, a sleeping player wakes up before being moved. */
    private static void wake(ServerPlayer player) {
        if (player.isSleeping()) {
            player.stopSleepInBed(true, true);
        }
    }

    private static void sendPhase(ServerPlayer player, int phase, int duration) {
        NullNetworking.sendToPlayer(player, new TransitionPayload(phase, duration));
    }

    private void warnMissing() {
        if (!warnedMissing) {
            warnedMissing = true;
            NullMod.LOGGER.error("The field ({}) does not exist; is NULL's data pack disabled?", FieldDimension.FIELD_ID);
        }
    }

    // ================================================================== respawning, beds, wheat

    /** Stage 6: whoever dies respawns in the field, in front of a house. */
    static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            INSTANCE.redirectRespawn(player, event);
        }
    }

    /** A player who respawned into the field from somewhere else gets the arrival sequence. */
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && FieldDimension.isField(player.level())) {
            if (INSTANCE.arriveAfterRespawn.remove(player.getUUID())) {
                sendPhase(player, TransitionPayload.PHASE_ARRIVE, NullDimensionManager.ARRIVE_TICKS);
            }
            INSTANCE.sync(player);
        }
    }

    private void redirectRespawn(ServerPlayer player, PlayerRespawnPositionEvent event) {
        MinecraftServer server = player.getServer();
        if (server == null || !fieldStage(CorruptionManager.stage(server))) {
            return;
        }
        ServerLevel level = server.getLevel(FieldDimension.FIELD_LEVEL);
        if (level == null) {
            warnMissing();
            return;
        }
        boolean diedInField = FieldDimension.isField(player.level());
        BlockPos pos = respawnSpot(level, diedInField ? player.blockPosition() : null);
        event.setDimensionTransition(new DimensionTransition(level, Vec3.atBottomCenterOf(pos), Vec3.ZERO, FieldDimension.SUN_YAW, 0.0F,
                DimensionTransition.DO_NOTHING));
        transitions.remove(player.getUUID());
        if (!diedInField) {
            arriveAfterRespawn.add(player.getUUID());
        }
    }

    /**
     * In front of the door of the house nearest to where the player died (the home house for
     * players who died elsewhere), avoiding a house IT is standing next to.
     */
    private BlockPos respawnSpot(ServerLevel level, @Nullable BlockPos died) {
        long seed = FieldChunkGenerator.seedOf(level);
        int x = died != null ? died.getX() : FieldGeneration.homeHouse(seed).x();
        int z = died != null ? died.getZ() : FieldGeneration.homeHouse(seed).z();
        List<FieldHouse> houses = FieldGeneration.houses(seed, x - SEARCH_RADIUS, z - SEARCH_RADIUS, x + SEARCH_RADIUS, z + SEARCH_RADIUS);
        houses.sort(Comparator.comparingDouble(house -> (double) (house.x() - x) * (house.x() - x) + (double) (house.z() - z) * (house.z() - z)));
        ItEntity it = hunter;
        List<FieldHouse> ordered = new ArrayList<>(houses.size());
        List<FieldHouse> nearIt = new ArrayList<>();
        for (FieldHouse house : houses) {
            boolean threatened = it != null && !it.isRemoved() && it.distanceToSqr(house.x() + 0.5, it.getY(), house.z() + 0.5) < 40.0 * 40.0;
            (threatened ? nearIt : ordered).add(house);
        }
        ordered.addAll(nearIt);
        for (int i = 0; i < Math.min(6, ordered.size()); i++) {
            for (int[] spot : FieldGeneration.doorSpots(seed, ordered.get(i))) {
                BlockPos pos = new BlockPos(spot[0], spot[1], spot[2]);
                if (standable(level, pos)) {
                    return pos;
                }
            }
        }
        return arrivalSpot(level, null);
    }

    /**
     * Beds and respawn anchors simply do nothing in the field (its dimension type does not let them
     * work, and vanilla would blow them up); using one is quietly refused on both sides. An item in
     * hand is still used as usual.
     */
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!FieldDimension.isField(level)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (state.is(BlockTags.BEDS) && !level.dimensionType().bedWorks()
                || state.is(Blocks.RESPAWN_ANCHOR) && !level.dimensionType().respawnAnchorWorks()) {
            event.setUseBlock(TriState.FALSE);
        } else if (state.getBlock() instanceof DoorBlock && FieldManager.locked(event.getPos(), state)) {
            // Most of the houses are locked: the handle rattles and the door stays shut.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
            if (!level.isClientSide()) {
                level.playSound(null, event.getPos(), SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 0.6F);
                event.getEntity().displayClientMessage(Component.translatable("null.field.locked").withStyle(ChatFormatting.DARK_RED), true);
            }
        }
    }

    /**
     * Whether a field house's door is locked: decided by the door's position alone, so the client
     * and the server always agree (no door ever flickers open). {@link FieldTuning#LOCKED_PERCENT} of them are.
     */
    public static boolean locked(BlockPos pos, BlockState state) {
        BlockPos base = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        long h = base.getX() * 0x9E3779B97F4A7C15L ^ base.getZ() * 0xC2B2AE3D27D4EB4FL ^ base.getY() * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return Math.floorMod(h, 100L) < FieldTuning.LOCKED_PERCENT;
    }

    /** The wheat stays: nothing tramples the field's farmland (IT included). */
    static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getLevel() instanceof Level level && FieldDimension.isField(level)) {
            event.setCanceled(true);
        }
    }

    // ================================================================== lifecycle

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        if (fieldStage(to) && !fieldStage(from)) {
            // A new harvest: the first morning, and everyone is taken into the field.
            cycle.reset();
            hunter = null;
            save(context.data());
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                Transition pending = transitions.get(player.getUUID());
                if (pending != null && pending.kind == Kind.RETURN) {
                    cancel(player, pending);
                }
                if (FieldDimension.isField(player.level())) {
                    sync(player);
                } else {
                    schedule(player, Kind.ENTER, 0);
                }
            }
        } else if (fieldStage(from) && !fieldStage(to)) {
            // Moved back by an administrator. For Stage 5 with the automatic transition on, the NULL
            // dimension takes everyone in itself, straight from the field: one black screen.
            boolean nullTakesOver = to == CorruptionStage.NULL && NullConfig.get(NullConfig.AUTO_NULL_TRANSITION);
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                Transition pending = transitions.get(player.getUUID());
                if (pending != null && pending.kind == Kind.ENTER) {
                    if (nullTakesOver && NullDimensionManager.INSTANCE.inTransition(player)) {
                        // The NULL dimension's move continues the dark screen this one began.
                        transitions.remove(player.getUUID());
                    } else {
                        cancel(player, pending);
                    }
                }
                if (FieldDimension.isField(player.level()) && !nullTakesOver) {
                    schedule(player, Kind.RETURN, 0);
                }
            }
            arriveAfterRespawn.clear();
        }
    }

    @Override
    public void onPlayerJoin(ServerPlayer player, CorruptionContext context) {
        boolean inField = FieldDimension.isField(player.level());
        if (fieldStage(context.stage())) {
            if (inField) {
                sync(player);
            } else {
                schedule(player, Kind.ENTER, FieldTuning.JOIN_DELAY_TICKS);
            }
        } else if (inField) {
            boolean nullTakesOver = context.stage() == CorruptionStage.NULL && NullConfig.get(NullConfig.AUTO_NULL_TRANSITION);
            if (!nullTakesOver) {
                schedule(player, Kind.RETURN, FieldTuning.JOIN_DELAY_TICKS);
            }
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        transitions.remove(player.getUUID());
        arriveAfterRespawn.remove(player.getUUID());
    }

    @Override
    public void onReset(MinecraftServer server) {
        // A reset always ends at Stage 1: a return that is under way goes on, everything else stops.
        transitions.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (entry.getValue().kind == Kind.RETURN && player != null && FieldDimension.isField(player.level())) {
                return false;
            }
            if (player != null && entry.getValue().announced) {
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
            }
            return true;
        });
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (FieldDimension.isField(player.level())) {
                schedule(player, Kind.RETURN, 0);
            }
        }
        arriveAfterRespawn.clear();
        cycle.reset();
        hunter = null;
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        if (loadedFor == server) {
            try {
                save(CorruptionManager.data(server));
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL could not save the field's cycle", e);
            }
        }
        transitions.clear();
        arriveAfterRespawn.clear();
        hunter = null;
        loadedFor = null;
        occupied = false;
        arrivals = 0;
        syncCountdown = 0;
    }
}
