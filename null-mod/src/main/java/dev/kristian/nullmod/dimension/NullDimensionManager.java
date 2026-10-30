package dev.kristian.nullmod.dimension;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.network.TransitionPayload;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

/**
 * The server side of the NULL dimension.
 * <ul>
 * <li><b>Transition.</b> When Stage 5 begins (and automatic transition is on) every player who is
 * not in NULL gets {@link TransitionPayload#PHASE_COLLAPSE}; {@link #COLLAPSE_TICKS} later, with the
 * screen black, they are moved onto the spawn platform (spread over several spots, the chunks
 * loaded in advance) and get {@link TransitionPayload#PHASE_ARRIVE}. Players who join during
 * Stage 5 go through the same. Respawning during Stage 5 brings a player back to NULL (their bed
 * stays their respawn point for later). When an administrator moves the stage below 5, everyone in
 * NULL is sent back to their respawn point (or the world spawn) with
 * {@link TransitionPayload#PHASE_RETURN}. The original world is never touched.</li>
 * <li><b>Decay.</b> While NULL is active, regions of the structure 24-96 blocks from players vanish
 * now and then and come back a few minutes later, exactly as generated (see {@link NullDecay}).
 * The {@code section_vanish} and {@code corridor_shift} events use the same machinery.</li>
 * </ul>
 */
public final class NullDimensionManager implements CorruptionSystem {
    public static final NullDimensionManager INSTANCE = new NullDimensionManager();
    public static final String NAME = "null_dimension";

    /** Ticks from the start of the collapse until the player is moved. */
    public static final int COLLAPSE_TICKS = 100;
    /** Length of the arrival sequence on the client. */
    public static final int ARRIVE_TICKS = 180;
    /** Ticks from the start of the return until the player is moved back. */
    public static final int RETURN_TICKS = 60;
    /** A player who joins in Stage 5 is given a moment before the collapse starts. */
    private static final int JOIN_DELAY = 60;
    private static final int RETRY_DELAY = 20;
    private static final int MAX_ATTEMPTS = 6;
    /** Sounds of the dimension changing reach players this far away. */
    private static final double ANNOUNCE_RANGE = 112.0;

    /** Spots on the spawn platform as {x, z, yaw}: several arriving players stand apart, facing outwards. */
    private static final int[][] SPOTS = {
            {0, 0, -90}, {6, 0, -90}, {-6, 0, 90}, {0, 6, 0}, {0, -6, 180},
            {6, 6, -45}, {-6, -6, 135}, {6, -6, -135}, {-6, 6, 45},
            {9, 3, -90}, {-9, -3, 90}, {3, -9, 180}, {-3, 9, 0}};

    private enum Kind { ENTER, RETURN }

    private record Destination(ResourceKey<Level> dimension, BlockPos pos, float yaw, boolean nearRespawnPoint) {
    }

    private static final class Transition {
        final Kind kind;
        final long start;
        long execute;
        boolean announced;
        int attempts;
        @Nullable
        Destination destination;

        Transition(Kind kind, long start, long execute) {
            this.kind = kind;
            this.start = start;
            this.execute = execute;
        }
    }

    private final Map<UUID, Transition> transitions = new HashMap<>();
    private final Set<UUID> arriveAfterRespawn = new HashSet<>();
    private final NullDecay decay = new NullDecay();
    private final RandomSource random = RandomSource.create();
    private int arrivals;
    private long nextDecay = -1L;
    @Nullable
    private MinecraftServer loadedFor;
    private boolean warnedMissing;

    private NullDimensionManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================== ticking

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        long now = server.getTickCount();
        if (loadedFor != server) {
            loadedFor = server;
            decay.load(ctx.data().systemTag(NAME), now);
            nextDecay = -1L;
        }
        if (!transitions.isEmpty()) {
            tickTransitions(server, now);
        }
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level == null) {
            return;
        }
        long seed = NullChunkGenerator.seedOf(level);
        decay.tick(level, seed, now);
        if (ctx.stage() == CorruptionStage.NULL && !ctx.paused()) {
            scheduleDecay(level, seed, ctx, now);
        }
        if (decay.takeDirty()) {
            save(ctx.data());
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
                announce(server, player, t);
                continue;
            }
            if (now < t.execute) {
                continue;
            }
            if (!player.isAlive()) {
                // Died while the screen went dark: give them their death screen back. If NULL has
                // begun, respawning brings them in.
                transitions.remove(id);
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
                continue;
            }
            boolean done = t.kind == Kind.ENTER ? enter(server, player, t) : leave(server, player, t);
            if (done) {
                transitions.remove(id);
            } else if (++t.attempts >= MAX_ATTEMPTS) {
                transitions.remove(id);
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
                NullMod.LOGGER.warn("NULL could not move {} {} the NULL dimension", player.getGameProfile().getName(),
                        t.kind == Kind.ENTER ? "into" : "out of");
            } else {
                t.execute = now + RETRY_DELAY;
            }
        }
    }

    // ================================================================== transitions

    /** Starts moving a player into NULL now (collapse, then the move, then the arrival). */
    public void beginEnter(ServerPlayer player) {
        schedule(player, Kind.ENTER, 0);
    }

    /** Starts sending a player in NULL back to their respawn point or the world spawn. */
    public void beginReturn(ServerPlayer player) {
        schedule(player, Kind.RETURN, 0);
    }

    /** True while a move into or out of NULL is pending for this player. */
    public boolean inTransition(ServerPlayer player) {
        return transitions.containsKey(player.getUUID());
    }

    private void schedule(ServerPlayer player, Kind kind, int delay) {
        Transition existing = transitions.get(player.getUUID());
        if (existing != null) {
            if (existing.kind == kind) {
                return;
            }
            cancel(player, existing);
        }
        long start = (long) player.server.getTickCount() + delay;
        long execute = start + (kind == Kind.ENTER ? COLLAPSE_TICKS : RETURN_TICKS);
        transitions.put(player.getUUID(), new Transition(kind, start, execute));
    }

    private void cancel(ServerPlayer player, Transition t) {
        transitions.remove(player.getUUID());
        if (t.announced) {
            sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
        }
    }

    private void announce(MinecraftServer server, ServerPlayer player, Transition t) {
        t.announced = true;
        if (t.kind == Kind.ENTER) {
            ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
            if (level == null) {
                warnMissing();
                transitions.remove(player.getUUID());
                return;
            }
            int[] spot = nextSpot();
            BlockPos pos = new BlockPos(spot[0], NullWorldGeneration.SPAWN_Y, spot[1]);
            t.destination = new Destination(NullDimensions.NULL_LEVEL, pos, spot[2], false);
            // Start loading (and on first use generating) the platform while the screen fades.
            level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(pos), 3, pos);
            sendPhase(player, TransitionPayload.PHASE_COLLAPSE, COLLAPSE_TICKS);
            WorldMemory.remember(server, "dimension:collapse", player.getUUID(), "");
        } else {
            Destination destination = returnDestination(server, player);
            t.destination = destination;
            ServerLevel level = server.getLevel(destination.dimension());
            if (level != null) {
                level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(destination.pos()), 2, destination.pos());
            }
            sendPhase(player, TransitionPayload.PHASE_RETURN, RETURN_TICKS);
            WorldMemory.remember(server, "dimension:return", player.getUUID(), "");
        }
    }

    /** Moves a player onto the spawn platform. False if the move did not happen (retried later). */
    private boolean enter(MinecraftServer server, ServerPlayer player, Transition t) {
        if (NullDimensions.isNull(player.level())) {
            sendPhase(player, TransitionPayload.PHASE_ARRIVE, ARRIVE_TICKS);
            return true;
        }
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level == null) {
            warnMissing();
            sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
            return true;
        }
        Destination destination = t.destination;
        BlockPos preferred = destination != null ? destination.pos() : new BlockPos(0, NullWorldGeneration.SPAWN_Y, 0);
        float yaw = destination != null ? destination.yaw() : -90.0F;
        BlockPos pos = safeSpot(level, preferred);
        wake(player);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
        if (player.level() != level) {
            return false;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        sendPhase(player, TransitionPayload.PHASE_ARRIVE, ARRIVE_TICKS);
        WorldMemory.remember(server, "dimension:arrived", player.getUUID(), "");
        return true;
    }

    /** Sends a player back to the world. False if the move did not happen (retried later). */
    private boolean leave(MinecraftServer server, ServerPlayer player, Transition t) {
        if (!NullDimensions.isNull(player.level())) {
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
        if (NullDimensions.isNull(player.level())) {
            return false;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    private Destination returnDestination(MinecraftServer server, ServerPlayer player) {
        BlockPos respawn = player.getRespawnPosition();
        ResourceKey<Level> dimension = player.getRespawnDimension();
        if (respawn != null && !NullDimensions.NULL_LEVEL.equals(dimension) && server.getLevel(dimension) != null) {
            return new Destination(dimension, respawn, player.getRespawnAngle(), true);
        }
        ServerLevel overworld = server.overworld();
        return new Destination(Level.OVERWORLD, overworld.getSharedSpawnPos(), overworld.getSharedSpawnAngle(), false);
    }

    private int[] nextSpot() {
        return SPOTS[Math.floorMod(arrivals++, SPOTS.length)];
    }

    /** One of the arrival spots on the spawn platform (feet position). */
    public static BlockPos platformSpot(int index) {
        int[] spot = SPOTS[Math.floorMod(index, SPOTS.length)];
        return new BlockPos(spot[0], NullWorldGeneration.SPAWN_Y, spot[1]);
    }

    /** The preferred spot if a player can stand there, otherwise another platform spot, otherwise above the centre. */
    static BlockPos safeSpot(ServerLevel level, BlockPos preferred) {
        level.getChunk(preferred.getX() >> 4, preferred.getZ() >> 4);
        if (standable(level, preferred)) {
            return preferred;
        }
        for (int[] spot : SPOTS) {
            BlockPos pos = new BlockPos(spot[0], NullWorldGeneration.SPAWN_Y, spot[1]);
            if (standable(level, pos)) {
                return pos;
            }
        }
        // Players have built over the whole platform: stand on top of whatever is in the middle.
        for (int y = NullWorldGeneration.SPAWN_Y; y < level.getMaxBuildHeight() - 2; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            if (free(level, pos) && free(level, pos.above())) {
                return pos;
            }
        }
        return preferred;
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
    private static boolean standable(ServerLevel level, BlockPos pos) {
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
            NullMod.LOGGER.error("The NULL dimension ({}) does not exist; is NULL's data pack disabled?", NullDimensions.NULL_ID);
        }
    }

    /**
     * Beds and respawn anchors simply do nothing in NULL. Its dimension type does not let them
     * work, and vanilla would blow them up; here using one is quietly refused (on both sides, so
     * the hand does not even swing). An item in hand is still used as usual. Respawning is not
     * affected.
     */
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!NullDimensions.isNull(level)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (state.is(BlockTags.BEDS) && !level.dimensionType().bedWorks()
                || state.is(Blocks.RESPAWN_ANCHOR) && !level.dimensionType().respawnAnchorWorks()) {
            event.setUseBlock(TriState.FALSE);
        }
    }

    // ================================================================== respawning

    /** Stage 5: whoever dies (or leaves the End) respawns on the platform in NULL. */
    static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            INSTANCE.redirectRespawn(player, event);
        }
    }

    /** A player who respawned into NULL from somewhere else gets the arrival sequence. */
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && INSTANCE.arriveAfterRespawn.remove(player.getUUID())
                && NullDimensions.isNull(player.level())) {
            sendPhase(player, TransitionPayload.PHASE_ARRIVE, ARRIVE_TICKS);
        }
    }

    private void redirectRespawn(ServerPlayer player, PlayerRespawnPositionEvent event) {
        MinecraftServer server = player.getServer();
        if (server == null || CorruptionManager.stage(server) != CorruptionStage.NULL) {
            return;
        }
        boolean diedInNull = NullDimensions.isNull(player.level());
        if (!diedInNull && !NullConfig.get(NullConfig.AUTO_NULL_TRANSITION)) {
            return;
        }
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level == null) {
            warnMissing();
            return;
        }
        int[] spot = nextSpot();
        BlockPos pos = safeSpot(level, new BlockPos(spot[0], NullWorldGeneration.SPAWN_Y, spot[1]));
        event.setDimensionTransition(new DimensionTransition(level, Vec3.atBottomCenterOf(pos), Vec3.ZERO, spot[2], 0.0F,
                DimensionTransition.DO_NOTHING));
        transitions.remove(player.getUUID());
        if (!diedInNull) {
            arriveAfterRespawn.add(player.getUUID());
        }
    }

    // ================================================================== decay

    private void scheduleDecay(ServerLevel level, long seed, CorruptionContext ctx, long now) {
        if (!NullConfig.get(NullConfig.NULL_DIMENSION_DECAY)) {
            return;
        }
        if (nextDecay < 0L) {
            nextDecay = now + 400 + random.nextInt(400);
            return;
        }
        if (now < nextDecay) {
            return;
        }
        float progress = Mth.clamp(ctx.stageProgress(), 0.0F, 1.0F);
        nextDecay = now + (long) ((300 + random.nextInt(500)) * (1.0F - 0.5F * progress));
        if (decay.damagedCount() >= NullDecay.MAX_DAMAGED) {
            return;
        }
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.isAlive()) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ServerPlayer player = candidates.get(random.nextInt(candidates.size()));
        NullDecay.Region region = decay.find(level, seed, player, random, 24.0, 96.0, 6, 14, 5, 12, 0L,
                now + restoreDelay(random));
        if (region != null) {
            decay.start(region);
            announceRegion(level, region);
        }
    }

    /**
     * The {@code section_vanish} event: a piece of the structure 24-96 blocks from the player
     * disappears and regenerates a few minutes later.
     */
    public boolean vanishNear(ServerPlayer player, RandomSource rnd, boolean forced) {
        if (!NullConfig.get(NullConfig.NULL_DIMENSION_DECAY) || !NullDimensions.isNull(player.level())
                || decay.damagedCount() >= NullDecay.MAX_DAMAGED) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        long seed = NullChunkGenerator.seedOf(level);
        long now = player.server.getTickCount();
        NullDecay.Region region = decay.find(level, seed, player, rnd, forced ? 12.0 : 24.0, 96.0, 6, 14, 5, 12, 0L,
                now + restoreDelay(rnd));
        if (region == null) {
            return false;
        }
        decay.start(region);
        announceRegion(level, region);
        WorldMemory.remember(player.server, "dimension:vanished", player.getUUID(), "");
        return true;
    }

    /**
     * The {@code corridor_shift} event: a distant region is rebuilt from a different seed (the
     * geometry rearranges) and put back as it was a few minutes later.
     */
    public boolean shiftNear(ServerPlayer player, RandomSource rnd, boolean forced) {
        if (!NullConfig.get(NullConfig.NULL_DIMENSION_DECAY) || !NullDimensions.isNull(player.level())
                || decay.damagedCount() >= NullDecay.MAX_DAMAGED) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        long seed = NullChunkGenerator.seedOf(level);
        long now = player.server.getTickCount();
        long variant = NullWorldGeneration.mix(seed ^ rnd.nextLong()) | 1L;
        NullDecay.Region region = decay.find(level, seed, player, rnd, forced ? 20.0 : 40.0, 96.0, 12, 22, 8, 16, variant,
                now + 1800 + rnd.nextInt(3000));
        if (region == null) {
            return false;
        }
        decay.start(region);
        announceRegion(level, region);
        WorldMemory.remember(player.server, "dimension:shifted", player.getUUID(), "");
        return true;
    }

    /**
     * Takes one specific box of the NULL dimension apart; it regenerates after {@code restoreAfter}
     * ticks (or when the server stops). Like all decay it leaves protected positions and anything
     * players built alone, and waits while a player is within 6 blocks.
     *
     * @return false if the level is not NULL, the box is too large or overlaps a damaged region
     */
    public boolean vanishRegion(ServerLevel level, BlockPos min, BlockPos max, int restoreAfter) {
        if (!NullDimensions.isNull(level)) {
            return false;
        }
        int x0 = Math.min(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ());
        int x1 = Math.max(min.getX(), max.getX());
        int y1 = Math.max(min.getY(), max.getY());
        int z1 = Math.max(min.getZ(), max.getZ());
        if (x1 - x0 > 63 || y1 - y0 > 63 || z1 - z0 > 63) {
            return false;
        }
        long now = level.getServer().getTickCount();
        NullDecay.Region region = new NullDecay.Region(x0, y0, z0, x1, y1, z1, 0L, now + Math.max(1, restoreAfter));
        if (region.maxY < region.minY || decay.overlapsDamaged(region)) {
            return false;
        }
        decay.start(region);
        return true;
    }

    private static long restoreDelay(RandomSource rnd) {
        return 1200L + rnd.nextInt(2400);
    }

    /** Players near a region hear it go (or rearrange), from where it happens. */
    private void announceRegion(ServerLevel level, NullDecay.Region region) {
        BlockPos center = region.center();
        Vec3 point = Vec3.atCenterOf(center);
        String sound = NullSounds.DIMENSION_SHIFT.get().getLocation().toString();
        int pitch = region.shifted() ? 70 : 100;
        for (ServerPlayer player : level.players()) {
            double distance = Math.sqrt(player.distanceToSqr(point));
            if (distance > ANNOUNCE_RANGE) {
                continue;
            }
            float volume = Mth.clamp(1.2F - (float) distance / 120.0F, 0.3F, 1.0F);
            NullNetworking.sendEffect(player, "audio.sound", random.nextLong(), volume, 60, Optional.of(center), pitch, sound);
        }
    }

    /** Number of regions currently missing or shifted (tests, status). */
    public int damagedRegions() {
        return decay.damagedCount();
    }

    /** Puts every missing or shifted region back right now. */
    public void restoreAll(MinecraftServer server) {
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level != null) {
            decay.restoreAllNow(level, NullChunkGenerator.seedOf(level));
            save(CorruptionManager.data(server));
        }
    }

    private void save(CorruptionData data) {
        decay.save(data.systemTag(NAME));
        data.setDirty();
    }

    // ================================================================== lifecycle

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        if (to == CorruptionStage.NULL) {
            boolean auto = NullConfig.get(NullConfig.AUTO_NULL_TRANSITION);
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                Transition pending = transitions.get(player.getUUID());
                if (pending != null && pending.kind == Kind.RETURN) {
                    cancel(player, pending);
                }
                if (auto && !NullDimensions.isNull(player.level())) {
                    schedule(player, Kind.ENTER, 0);
                }
            }
            nextDecay = -1L;
        } else if (from == CorruptionStage.NULL) {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                Transition pending = transitions.get(player.getUUID());
                if (pending != null && pending.kind == Kind.ENTER) {
                    cancel(player, pending);
                }
                if (NullDimensions.isNull(player.level())) {
                    schedule(player, Kind.RETURN, 0);
                }
            }
            arriveAfterRespawn.clear();
            // Nobody should find holes if they ever come back.
            decay.restoreAllSoon(server.getTickCount());
        }
    }

    @Override
    public void onPlayerJoin(ServerPlayer player, CorruptionContext context) {
        boolean inNull = NullDimensions.isNull(player.level());
        if (context.stage() == CorruptionStage.NULL) {
            if (!inNull && NullConfig.get(NullConfig.AUTO_NULL_TRANSITION)) {
                schedule(player, Kind.ENTER, JOIN_DELAY);
            }
        } else if (inNull) {
            schedule(player, Kind.RETURN, JOIN_DELAY);
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
            if (entry.getValue().kind == Kind.RETURN && player != null && NullDimensions.isNull(player.level())) {
                return false;
            }
            if (player != null && entry.getValue().announced) {
                sendPhase(player, TransitionPayload.PHASE_CLEAR, 0);
            }
            return true;
        });
        // Nobody stays in NULL, also when the stage had already been moved below 5 a moment ago.
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (NullDimensions.isNull(player.level())) {
                schedule(player, Kind.RETURN, 0);
            }
        }
        arriveAfterRespawn.clear();
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level != null) {
            decay.restoreAllNow(level, NullChunkGenerator.seedOf(level));
        } else {
            decay.clear();
        }
        nextDecay = -1L;
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        ServerLevel level = server.getLevel(NullDimensions.NULL_LEVEL);
        if (level != null && decay.damagedCount() > 0) {
            try {
                decay.restoreAllNow(level, NullChunkGenerator.seedOf(level));
                save(CorruptionManager.data(server));
            } catch (RuntimeException e) {
                NullMod.LOGGER.error("NULL could not restore the decayed parts of the NULL dimension; they come back on the next start", e);
            }
        }
        decay.clear();
        transitions.clear();
        arriveAfterRespawn.clear();
        loadedFor = null;
        nextDecay = -1L;
        arrivals = 0;
    }
}
