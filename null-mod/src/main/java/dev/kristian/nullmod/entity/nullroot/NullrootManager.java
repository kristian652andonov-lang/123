package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Grows NULLROOT from Stage 3 on: a slow background schedule per player (scaled by the NULLROOT
 * frequency setting, capped by the per-player maximum) and the nullroot_* events. Single
 * organisms sprout on corrupted ground or on natural ground in the dark; now and then a colony
 * grows instead, a central mass with three to six satellites and a black film of residue
 * around it.
 * <p>
 * Every organism loaded on the server registers itself here (also ones loaded from disk or
 * summoned), so the manager can count them per player, find colony centres for captures and
 * take them away again: below Stage 3 the ones NULL grew wither, on {@code /null reset} all of
 * them do, and organisms that are not part of a colony go away by themselves after a while with
 * nobody near.
 */
public final class NullrootManager implements CorruptionSystem {
    public static final NullrootManager INSTANCE = new NullrootManager();
    public static final String NAME = "nullroot";

    /** Organisms within this many blocks of a player count toward that player's cap. */
    public static final double COUNT_RADIUS = 64.0;
    /** No new colony grows within this many blocks of another colony centre. */
    private static final double COLONY_SPACING = 96.0;
    private static final int MIN_SATELLITES = 3;
    private static final int MAX_SATELLITES = 6;
    /** Chance per background growth that a colony is attempted, by stage. */
    private static final float[] COLONY_CHANCE = {0.0F, 0.0F, 0.06F, 0.12F, 0.15F};

    private final List<NullrootEntity> organisms = new ArrayList<>();
    private final Map<UUID, Long> nextGrowth = new HashMap<>();
    private final RandomSource random = RandomSource.create();

    private NullrootManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    // ------------------------------------------------------------------ tracking

    void track(NullrootEntity organism) {
        if (!organisms.contains(organism)) {
            organisms.add(organism);
        }
    }

    void untrack(NullrootEntity organism) {
        organisms.remove(organism);
    }

    /** Every organism currently loaded on the server (a snapshot). */
    public List<NullrootEntity> organisms() {
        organisms.removeIf(NullrootEntity::isRemoved);
        return List.copyOf(organisms);
    }

    /** Living, not withering organisms within {@link #COUNT_RADIUS} of the player. */
    public int countNear(ServerPlayer player) {
        int count = 0;
        for (NullrootEntity organism : organisms) {
            if (counts(organism, player)) {
                count++;
            }
        }
        return count;
    }

    private static boolean counts(NullrootEntity organism, ServerPlayer player) {
        if (organism.isRemoved() || !organism.isAlive() || organism.isWithering() || organism.level() != player.level()) {
            return false;
        }
        double dx = organism.getX() - player.getX();
        double dz = organism.getZ() - player.getZ();
        return dx * dx + dz * dz <= COUNT_RADIUS * COUNT_RADIUS;
    }

    // ------------------------------------------------------------------ CorruptionSystem

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (server.getTickCount() % 20 != 11) {
            return;
        }
        organisms.removeIf(NullrootEntity::isRemoved);
        if (!ctx.atLeast(CorruptionStage.MIDDLE)) {
            witherIf(organism -> organism.isManaged() && !organism.isForced());
            nextGrowth.clear();
            return;
        }
        double frequency = NullConfig.get(NullConfig.NULLROOT_FREQUENCY);
        int max = NullConfig.get(NullConfig.MAX_NULLROOTS_PER_PLAYER);
        if (ctx.paused() || frequency <= 0.0 || max <= 0) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Nothing grows in NULL's dimension or in the field (Stage 6), which nothing may change.
            if (player.isSpectator() || !player.isAlive() || NullDimensions.isNull(player.level())
                    || FieldDimension.isField(player.level())) {
                continue;
            }
            Long next = nextGrowth.get(player.getUUID());
            if (next == null) {
                nextGrowth.put(player.getUUID(), now + nextDelay(ctx, frequency));
                continue;
            }
            if (now < next) {
                continue;
            }
            nextGrowth.put(player.getUUID(), now + nextDelay(ctx, frequency));
            if (countNear(player) < max) {
                growBackground(player, ctx, max);
            }
        }
    }

    /** Average wait between background growths: about three minutes early in Stage 3, ninety seconds in NULL. */
    private long nextDelay(CorruptionContext ctx, double frequency) {
        float seconds = switch (ctx.stage()) {
            case MIDDLE -> Mth.lerp(ctx.stageProgress(), 200.0F, 130.0F);
            case ABOVE_MIDDLE -> 115.0F;
            default -> 90.0F;
        };
        seconds *= 0.6F + random.nextFloat() * 0.8F;
        return Math.max(20L * 30L, (long) (seconds * 20.0F / frequency));
    }

    private void growBackground(ServerPlayer player, CorruptionContext ctx, int max) {
        float colonyChance = COLONY_CHANCE[ctx.stage().tableIndex()];
        if (random.nextFloat() < colonyChance && countNear(player) + 1 + MIN_SATELLITES <= max
                && colony(player, ctx, random, false).isPresent()) {
            return;
        }
        sprout(player, ctx, random, false);
    }

    // ------------------------------------------------------------------ growing

    /**
     * Grows one organism near the player.
     *
     * @param forced from {@code /null event}: the spot rules relax, it works at any stage and
     *               the per-player cap makes room by withering the oldest stray organism
     * @return the organism, or empty if there was no place for it (or NULLROOT is switched off)
     */
    public Optional<NullrootEntity> sprout(ServerPlayer player, CorruptionContext ctx, RandomSource rnd, boolean forced) {
        int max = NullConfig.get(NullConfig.MAX_NULLROOTS_PER_PLAYER);
        if (max <= 0 || player.isSpectator()) {
            return Optional.empty();
        }
        if (countNear(player) >= max) {
            if (!forced || !makeRoom(player)) {
                return Optional.empty();
            }
        }
        ServerLevel level = player.serverLevel();
        Optional<NullrootSpots.Spot> spot = NullrootSpots.sprout(level, player, rnd, ctx.stage(), forced);
        if (spot.isEmpty()) {
            return Optional.empty();
        }
        NullrootEntity organism = spawn(level, spot.get(), null, player, forced && !ctx.atLeast(CorruptionStage.MIDDLE));
        if (organism != null) {
            WorldMemory.remember(player.server, "nullroot:grown", player.getUUID(), organism.variant().id());
        }
        return Optional.ofNullable(organism);
    }

    /**
     * Grows a colony near the player: a central mass, three to six satellites around it and (when
     * structures are allowed) residue on the ground between them.
     *
     * @return the central mass, or empty if no place was found
     */
    public Optional<NullrootEntity> colony(ServerPlayer player, CorruptionContext ctx, RandomSource rnd, boolean forced) {
        int max = NullConfig.get(NullConfig.MAX_NULLROOTS_PER_PLAYER);
        if (max <= 0 || player.isSpectator()) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        if (!forced && (countNear(player) + 1 + MIN_SATELLITES > max || colonyNear(level, player))) {
            return Optional.empty();
        }
        Optional<NullrootSpots.Spot> center = NullrootSpots.colonyCenter(level, player, rnd, forced);
        if (center.isEmpty()) {
            return Optional.empty();
        }
        BlockPos centerRoot = center.get().root();
        boolean underground = LocationFinder.isUnderground(player) || !LocationFinder.canHaveSurface(level);
        int satellites = MIN_SATELLITES + rnd.nextInt(MAX_SATELLITES - MIN_SATELLITES + 1);
        if (!forced) {
            satellites = Math.min(satellites, max - countNear(player) - 1);
        }
        List<NullrootSpots.Spot> spots = new ArrayList<>();
        spots.add(center.get());
        for (int i = 0; i < satellites; i++) {
            NullrootSpots.Spot satellite = NullrootSpots.satellite(level, centerRoot, underground, rnd, ctx.stage(), spots, forced);
            if (satellite != null) {
                spots.add(satellite);
            }
        }
        if (spots.size() - 1 < MIN_SATELLITES && !forced) {
            return Optional.empty();
        }
        boolean lowStage = forced && !ctx.atLeast(CorruptionStage.MIDDLE);
        NullrootEntity central = spawn(level, center.get(), null, player, lowStage);
        if (central == null) {
            return Optional.empty();
        }
        UUID colonyId = central.getUUID();
        central.joinColony(colonyId);
        for (int i = 1; i < spots.size(); i++) {
            spawn(level, spots.get(i), colonyId, player, lowStage);
        }
        if (NullConfig.mayBuildStructures()) {
            PlacementJob residue = NullrootSpots.residue(level, centerRoot, rnd);
            if (!residue.isEmpty()) {
                SafeBlockPlacer.INSTANCE.submit(residue);
            }
        }
        WorldMemory.remember(player.server, "nullroot:colony", player.getUUID(), Integer.toString(spots.size()));
        NullMod.LOGGER.debug("NULLROOT colony of {} grew at {} near {}", spots.size(), centerRoot, player.getScoreboardName());
        return Optional.of(central);
    }

    @Nullable
    private NullrootEntity spawn(ServerLevel level, NullrootSpots.Spot spot, @Nullable UUID colony, ServerPlayer owner, boolean forced) {
        NullrootEntity organism = NullrootEntity.create(level, spot.variant(), spot.root(), spot.face(), colony, owner.getUUID(), true, forced);
        if (organism == null) {
            return null;
        }
        // Grown facing the player it grew for.
        double dx = owner.getX() - organism.getX();
        double dz = owner.getZ() - organism.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        organism.setYRot(yaw);
        organism.setYHeadRot(yaw);
        if (!level.addFreshEntity(organism)) {
            return null;
        }
        track(organism);
        return organism;
    }

    /** True if a colony centre already grows within {@link #COLONY_SPACING} of the player. */
    private boolean colonyNear(ServerLevel level, ServerPlayer player) {
        for (NullrootEntity organism : organisms) {
            if (organism.variant() == NullrootVariant.CENTRAL && organism.level() == level && !organism.isRemoved()
                    && organism.distanceToSqr(player) < COLONY_SPACING * COLONY_SPACING) {
                return true;
            }
        }
        return false;
    }

    /** Withers the stray (non-colony) organism near the player that has been there longest. */
    private boolean makeRoom(ServerPlayer player) {
        NullrootEntity oldest = null;
        for (NullrootEntity organism : organisms) {
            if (counts(organism, player) && organism.colonyId() == null && NullrootCaptures.heldBy(organism) == null
                    && (oldest == null || organism.tickCount > oldest.tickCount)) {
                oldest = organism;
            }
        }
        if (oldest == null) {
            return false;
        }
        oldest.startWithering();
        return true;
    }

    /** Organisms near the player, closest first (events). */
    public List<NullrootEntity> near(ServerPlayer player, double radius) {
        List<NullrootEntity> out = new ArrayList<>();
        for (NullrootEntity organism : organisms) {
            if (!organism.isRemoved() && organism.isAlive() && organism.level() == player.level()
                    && organism.distanceToSqr(player) <= radius * radius) {
                out.add(organism);
            }
        }
        out.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
        return out;
    }

    // ------------------------------------------------------------------ clean-up

    private void witherIf(Predicate<NullrootEntity> filter) {
        for (NullrootEntity organism : List.copyOf(organisms)) {
            if (!organism.isRemoved() && filter.test(organism)) {
                organism.startWithering();
            }
        }
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        if (!to.atLeast(CorruptionStage.MIDDLE)) {
            witherIf(organism -> organism.isManaged() && !organism.isForced());
        }
        nextGrowth.clear();
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        NullrootCaptures.release(player, NullrootCaptures.Reason.PLAYER_GONE);
        nextGrowth.remove(player.getUUID());
    }

    @Override
    public void onReset(MinecraftServer server) {
        NullrootCaptures.releaseAll(NullrootCaptures.Reason.RESET);
        witherIf(organism -> true);
        nextGrowth.clear();
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        NullrootCaptures.releaseAll(NullrootCaptures.Reason.RESET);
        organisms.clear();
        nextGrowth.clear();
    }
}
