package dev.kristian.nullmod.entity.visitor;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.entity.PlayerLikeSpawns;
import dev.kristian.nullmod.entity.SkinSet;
import dev.kristian.nullmod.entity.UsernameGenerator;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Brings Visitors into the world from Stage 4 on: through the visitor_* random events and a
 * slow background schedule per player (scaled by the visitor frequency setting, capped by the
 * per-player maximum). It only ever places them in loaded chunks and keeps track of every one
 * it spawned, so it can take them all away again when the stage drops below 4, when their
 * player leaves, on {@code /null reset} and when the server stops.
 */
public final class VisitorManager implements CorruptionSystem {
    public static final VisitorManager INSTANCE = new VisitorManager();

    /** Where a Visitor should appear. */
    public enum Placement {
        /** Open ground 30-70 blocks away at the edge of the player's view. */
        EDGE_OF_VIEW,
        /** Like {@link #EDGE_OF_VIEW} but at the distance a follower keeps. */
        FOLLOW,
        /** Directly behind the player, 8-15 blocks away. */
        BEHIND,
        /** Dark cave floors around a player who is underground. */
        CAVE,
        /** Under trees, where there are trees all around. */
        FOREST,
        /** Inside something old and abandoned. */
        STRUCTURE,
        /** Tree tops, the surface of water, bedrock floating in the sky. */
        UNUSUAL
    }

    private final RandomSource random = RandomSource.create();
    private final List<VisitorEntity> visitors = new ArrayList<>();
    private final Map<UUID, Long> nextSpawn = new HashMap<>();

    private VisitorManager() {
    }

    @Override
    public String name() {
        return "visitors";
    }

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        visitors.removeIf(PlayerLikeEntity::hasVanished);
        if (!ctx.atLeast(CorruptionStage.ABOVE_MIDDLE)) {
            // Only Visitors forced by an administrator may exist before Stage 4.
            removeIf(visitor -> !visitor.isForced());
            nextSpawn.clear();
            return;
        }
        double frequency = NullConfig.get(NullConfig.VISITOR_FREQUENCY);
        int max = NullConfig.get(NullConfig.MAX_VISITORS_PER_PLAYER);
        if (ctx.paused() || frequency <= 0.0 || max <= 0) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            Long next = nextSpawn.get(player.getUUID());
            if (next == null) {
                nextSpawn.put(player.getUUID(), now + nextDelay(ctx, frequency));
                continue;
            }
            if (now < next) {
                continue;
            }
            nextSpawn.put(player.getUUID(), now + nextDelay(ctx, frequency));
            if (count(player) < max) {
                spawnBackground(player, ctx);
            }
        }
    }

    /** Average wait between background Visitors: about 4-5 minutes early in Stage 4, ~2.5 later. */
    private long nextDelay(CorruptionContext ctx, double frequency) {
        float seconds = ctx.stage() == CorruptionStage.NULL ? 150.0F : Mth.lerp(ctx.stageProgress(), 330.0F, 170.0F);
        seconds *= 0.5F + random.nextFloat();
        return Math.max(20L * 30L, (long) (seconds * 20.0F / frequency));
    }

    private void spawnBackground(ServerPlayer player, CorruptionContext ctx) {
        VisitorPersonality personality;
        Placement placement;
        if (PlayerLikeSpawns.isUnderground(player) && random.nextFloat() < 0.7F) {
            personality = VisitorPersonality.UNDERGROUND;
            placement = random.nextFloat() < 0.25F ? Placement.STRUCTURE : Placement.CAVE;
        } else {
            float roll = random.nextFloat();
            if (roll < 0.34F) {
                personality = VisitorPersonality.OBSERVER;
                placement = random.nextFloat() < 0.3F ? Placement.FOREST : Placement.EDGE_OF_VIEW;
            } else if (roll < 0.62F) {
                personality = VisitorPersonality.DISAPPEARING;
                placement = Placement.EDGE_OF_VIEW;
            } else if (roll < 0.76F) {
                personality = VisitorPersonality.FOLLOWER;
                placement = Placement.FOLLOW;
            } else if (roll < 0.88F) {
                personality = VisitorPersonality.STALKER;
                placement = Placement.BEHIND;
            } else if (roll < 0.94F) {
                personality = VisitorPersonality.OBSERVER;
                placement = Placement.STRUCTURE;
            } else {
                personality = random.nextBoolean() ? VisitorPersonality.OBSERVER : VisitorPersonality.DISAPPEARING;
                placement = Placement.UNUSUAL;
            }
        }
        if (spawn(player, personality, placement, false, ctx, random).isEmpty() && placement != Placement.EDGE_OF_VIEW) {
            // The special place was not there; it can always just stand in the distance.
            spawn(player, VisitorPersonality.OBSERVER, Placement.EDGE_OF_VIEW, false, ctx, random);
        }
    }

    /**
     * Spawns one Visitor for a player.
     *
     * @param forced from {@code /null event}: placement rules relax, the per-player cap makes
     *               room by sending the oldest one away, and it works at any stage
     * @return the Visitor, or empty if there was no place for it (or Visitors are disabled)
     */
    public Optional<VisitorEntity> spawn(ServerPlayer player, VisitorPersonality personality, Placement placement,
                                         boolean forced, CorruptionContext ctx, RandomSource rnd) {
        int max = NullConfig.get(NullConfig.MAX_VISITORS_PER_PLAYER);
        if (max <= 0 || player.isSpectator()) {
            return Optional.empty();
        }
        visitors.removeIf(PlayerLikeEntity::hasVanished);
        if (count(player) >= max) {
            if (!forced) {
                return Optional.empty();
            }
            oldestOf(player).ifPresent(oldest -> oldest.vanish(null, PlayerLikeEntity.VanishCause.CLEANUP));
        }
        Optional<PlayerLikeSpawns.Spot> spot = find(player, placement, forced, rnd);
        if (spot.isEmpty() && forced) {
            spot = PlayerLikeSpawns.edgeOfView(player, rnd, 16.0, 40.0, true)
                    .or(() -> PlayerLikeSpawns.around(player, rnd, 8.0, 24.0, 40));
        }
        if (spot.isEmpty()) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        VisitorEntity visitor = VisitorModule.VISITOR.get().create(level);
        if (visitor == null) {
            return Optional.empty();
        }
        Vec3 pos = spot.get().pos();
        float yaw = (float) (Mth.atan2(player.getZ() - pos.z, player.getX() - pos.x) * (180.0 / Math.PI)) - 90.0F;
        visitor.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        visitor.setYHeadRot(yaw);
        visitor.yBodyRot = yaw;
        if (spot.get().floating()) {
            visitor.setNoGravity(true);
        }
        boolean finalStage = ctx.stage() == CorruptionStage.NULL;
        int skin = personality.rollSkin(rnd, finalStage);
        visitor.setIdentity(pickName(rnd, finalStage), skin, pickFlags(rnd, skin, finalStage));
        boolean glimpse = personality != VisitorPersonality.STALKER && personality != VisitorPersonality.FOLLOWER
                && rnd.nextFloat() < 0.18F;
        visitor.configure(personality, player, personality.rollLifetime(rnd), personality.rollVanishDistance(rnd), glimpse);
        visitor.setForced(forced && !ctx.atLeast(CorruptionStage.ABOVE_MIDDLE));
        if (!level.addFreshEntity(visitor)) {
            return Optional.empty();
        }
        visitors.add(visitor);
        return Optional.of(visitor);
    }

    private Optional<PlayerLikeSpawns.Spot> find(ServerPlayer player, Placement placement, boolean relaxed, RandomSource rnd) {
        return switch (placement) {
            case EDGE_OF_VIEW -> PlayerLikeSpawns.edgeOfView(player, rnd, 30.0, 70.0, relaxed);
            case FOLLOW -> PlayerLikeSpawns.edgeOfView(player, rnd, 34.0, 48.0, relaxed);
            case BEHIND -> PlayerLikeSpawns.behind(player, rnd, 8.0, 15.0, relaxed);
            case CAVE -> PlayerLikeSpawns.cave(player, rnd, relaxed);
            case FOREST -> PlayerLikeSpawns.forest(player, rnd, relaxed);
            case STRUCTURE -> PlayerLikeSpawns.abandonedStructure(player, rnd, relaxed);
            case UNUSUAL -> PlayerLikeSpawns.unusual(player, rnd, relaxed);
        };
    }

    /** In the final stage half of them wear the name of somebody in the player list. */
    private static String pickName(RandomSource rnd, boolean finalStage) {
        if (finalStage && rnd.nextBoolean()) {
            List<String> names = FakePlayerManager.INSTANCE.names();
            if (!names.isEmpty()) {
                return names.get(rnd.nextInt(names.size()));
            }
        }
        return UsernameGenerator.random(rnd);
    }

    private static int pickFlags(RandomSource rnd, int skin, boolean finalStage) {
        int flags = 0;
        boolean black = SkinSet.category(skin) == SkinSet.Category.BLACK;
        if (rnd.nextFloat() < (black ? 0.4F : 0.16F)) {
            flags |= PlayerLikeEntity.FLAG_HIDE_NAME;
        } else if (rnd.nextFloat() < (finalStage ? 0.3F : 0.14F)) {
            flags |= PlayerLikeEntity.FLAG_CORRUPT_NAME;
        }
        if (rnd.nextFloat() < (finalStage ? 0.25F : 0.1F)) {
            flags |= PlayerLikeEntity.FLAG_FLICKER;
        }
        return flags;
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Visitors currently around a player (spawned for them). */
    public int count(ServerPlayer player) {
        int n = 0;
        for (VisitorEntity visitor : visitors) {
            if (!visitor.hasVanished() && player.getUUID().equals(visitor.ownerId())) {
                n++;
            }
        }
        return n;
    }

    /** Every live Visitor this manager spawned. */
    public List<VisitorEntity> visitors() {
        visitors.removeIf(PlayerLikeEntity::hasVanished);
        return List.copyOf(visitors);
    }

    private Optional<VisitorEntity> oldestOf(ServerPlayer player) {
        VisitorEntity oldest = null;
        for (VisitorEntity visitor : visitors) {
            if (!visitor.hasVanished() && player.getUUID().equals(visitor.ownerId())
                    && (oldest == null || visitor.life() > oldest.life())) {
                oldest = visitor;
            }
        }
        return Optional.ofNullable(oldest);
    }

    private void removeIf(Predicate<VisitorEntity> filter) {
        List<VisitorEntity> doomed = new ArrayList<>();
        for (VisitorEntity visitor : visitors) {
            if (filter.test(visitor)) {
                doomed.add(visitor);
            }
        }
        for (VisitorEntity visitor : doomed) {
            visitor.vanish(null, PlayerLikeEntity.VanishCause.CLEANUP);
        }
        visitors.removeAll(doomed);
    }

    private void removeAll() {
        removeIf(visitor -> true);
        visitors.clear();
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        if (!to.atLeast(CorruptionStage.ABOVE_MIDDLE)) {
            removeAll();
        }
        nextSpawn.clear();
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        UUID id = player.getUUID();
        removeIf(visitor -> id.equals(visitor.ownerId()));
        nextSpawn.remove(id);
    }

    @Override
    public void onReset(MinecraftServer server) {
        removeAll();
        nextSpawn.clear();
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        removeAll();
        nextSpawn.clear();
    }
}
