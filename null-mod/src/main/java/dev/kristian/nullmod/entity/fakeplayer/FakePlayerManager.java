package dev.kristian.nullmod.entity.fakeplayer;

import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.entity.PlayerLikeSpawns;
import dev.kristian.nullmod.entity.SkinSet;
import dev.kristian.nullmod.entity.UsernameGenerator;
import dev.kristian.nullmod.event.EventPacing;
import dev.kristian.nullmod.network.FakePlayersPayload;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The simulated players of the final stage. Once the world reaches NULL, "players" start
 * joining: a few at first, then more and more until the list holds
 * {@code FAKE_PLAYER_COUNT} names when NULL reaches full strength. Each one has a random
 * UUID, a generated name, a skin from {@link SkinSet} and a made-up ping. They are never
 * connections and never real game profiles; they exist only as entries in NULL's saved data
 * ({@code CorruptionData.systemTag("fake_players")}), as yellow join/leave lines in chat, in
 * the player list (sent to clients as {@link FakePlayersPayload}) and as the bodies
 * ({@link FakePlayerEntity}) that stand around real players. Below Stage 5 the list is empty.
 */
public final class FakePlayerManager implements CorruptionSystem {
    public static final FakePlayerManager INSTANCE = new FakePlayerManager();
    public static final String TAG_KEY = "fake_players";

    private static final int FLAG_CORRUPTED = 1;
    private static final int FLAG_HIDDEN = 2;
    private static final int MIN_SEND_INTERVAL = 10;
    /** How much faster the list and the bodies move than the original pace (see {@link #listDelay}). */
    private static final double LIST_PACE = 1.5;
    /** Most bodies in the whole world at once, however many players are online. */
    private static final int MAX_TOTAL_BODIES = 160;

    /** One simulated player. */
    private static final class Sim {
        final UUID id;
        final String name;
        final int skin;
        final int baseLatency;
        final boolean corrupted;
        int latency;
        long hiddenUntil = -1L;

        Sim(UUID id, String name, int skin, int baseLatency, boolean corrupted) {
            this.id = id;
            this.name = name;
            this.skin = skin;
            this.baseLatency = baseLatency;
            this.corrupted = corrupted;
            this.latency = baseLatency;
        }

        boolean hidden() {
            return hiddenUntil >= 0L;
        }

        FakePlayersPayload.Entry entry() {
            return new FakePlayersPayload.Entry(id, name, skin, latency, (corrupted ? FLAG_CORRUPTED : 0) | (hidden() ? FLAG_HIDDEN : 0));
        }
    }

    private final RandomSource random = RandomSource.create();
    private final List<Sim> sims = new ArrayList<>();
    private final List<FakePlayerEntity> bodies = new ArrayList<>();
    private final Map<UUID, Long> nextBody = new HashMap<>();
    @Nullable
    private MinecraftServer loadedFor;
    private boolean dirty;
    private long lastSend = Long.MIN_VALUE / 2;
    private long nextJoin = -1L;
    private long nextChurn = -1L;
    private long nextFlicker = -1L;
    private long nextLatency = -1L;
    private long groupCounter;

    private FakePlayerManager() {
    }

    @Override
    public String name() {
        return TAG_KEY;
    }

    // ------------------------------------------------------------------ public API

    /** Names of the simulated players currently in the list; empty before Stage 5. Server thread only. */
    public List<String> names() {
        List<String> out = new ArrayList<>(sims.size());
        for (Sim sim : sims) {
            out.add(sim.name);
        }
        return out;
    }

    /** The list exactly as clients get it. */
    public List<FakePlayersPayload.Entry> entries() {
        List<FakePlayersPayload.Entry> out = new ArrayList<>(sims.size());
        for (Sim sim : sims) {
            out.add(sim.entry());
        }
        return out;
    }

    /**
     * How many simulated players the list grows to at a point of the final stage: a few at
     * the start, all of {@code max} at saturation.
     */
    public static int targetCount(float stageProgress, int max) {
        if (max <= 0) {
            return 0;
        }
        int few = Math.min(max, 3);
        float p = Mth.clamp(stageProgress, 0.0F, 1.0F);
        return Mth.clamp(few + Math.round((max - few) * (float) Math.pow(p, 1.2)), 0, max);
    }

    /**
     * True while the list is (about) where the growth curve wants it or below, so random join
     * events never make the list fill up faster than the final stage progresses.
     */
    public boolean belowTarget(MinecraftServer server) {
        ensureLoaded(server);
        int max = Math.min(NullConfig.get(NullConfig.FAKE_PLAYER_COUNT), FakePlayersPayload.MAX_ENTRIES);
        return sims.size() < Math.min(max, targetCount(CorruptionManager.context(server).stageProgress(), max) + 2);
    }

    /**
     * Adds one simulated player (Stage 5 only), with a yellow "joined the game" line when
     * {@code announce}. Returns the name, or empty at the configured maximum.
     */
    public Optional<String> addSimulated(MinecraftServer server, RandomSource rnd, boolean announce) {
        ensureLoaded(server);
        if (CorruptionManager.stage(server) != CorruptionStage.NULL) {
            return Optional.empty();
        }
        int max = Math.min(NullConfig.get(NullConfig.FAKE_PLAYER_COUNT), FakePlayersPayload.MAX_ENTRIES);
        if (sims.size() >= max) {
            return Optional.empty();
        }
        Set<String> taken = new HashSet<>();
        for (Sim sim : sims) {
            taken.add(sim.name.toLowerCase(Locale.ROOT));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            taken.add(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
        }
        String name = UsernameGenerator.unique(rnd, taken);
        float progress = CorruptionManager.context(server).stageProgress();
        Sim sim = new Sim(Mth.createInsecureUUID(rnd), name, SkinSet.weighted(rnd, 14, 14, 22, 50),
                rollLatency(rnd), rnd.nextFloat() < 0.12F + 0.25F * progress);
        sims.add(sim);
        save(server);
        dirty = true;
        if (announce) {
            broadcast(server, Component.translatable("multiplayer.player.joined", name).withStyle(ChatFormatting.YELLOW));
        }
        WorldMemory.remember(server, "fakeplayer:joined", null, name);
        return Optional.of(name);
    }

    /** Removes one random simulated player, with a yellow "left the game" line when {@code announce}. */
    public Optional<String> removeSimulated(MinecraftServer server, RandomSource rnd, boolean announce) {
        ensureLoaded(server);
        if (sims.isEmpty()) {
            return Optional.empty();
        }
        Sim sim = sims.remove(rnd.nextInt(sims.size()));
        save(server);
        dirty = true;
        if (announce) {
            broadcast(server, Component.translatable("multiplayer.player.left", sim.name).withStyle(ChatFormatting.YELLOW));
        }
        WorldMemory.remember(server, "fakeplayer:left", null, sim.name);
        // Whoever was wearing that name is not there any more either.
        removeBodies(body -> body.username().equals(sim.name));
        return Optional.of(sim.name);
    }

    /** Forgets the in-memory list and reads it back from the world's saved data. */
    public void reloadFromData(MinecraftServer server) {
        loadedFor = null;
        ensureLoaded(server);
    }

    /** The saved list (for tests): names in the stored order. */
    public static List<String> storedNames(MinecraftServer server) {
        ListTag list = CorruptionManager.data(server).systemTag(TAG_KEY).getList("players", Tag.TAG_COMPOUND);
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getCompound(i).getString("name"));
        }
        return out;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (server.getTickCount() % 10 != 3) {
            return;
        }
        ensureLoaded(server);
        long now = server.overworld().getGameTime();
        if (ctx.stage() != CorruptionStage.NULL) {
            if (!sims.isEmpty()) {
                clearList(server);
            }
            if (!bodies.isEmpty()) {
                // Only bodies an administrator forced may exist before Stage 5.
                bodies.removeIf(PlayerLikeEntity::hasVanished);
                removeBodies(body -> !body.isForced());
            }
            flush(server, now);
            return;
        }
        expireHidden(now);
        if (!ctx.paused()) {
            double speed = EventPacing.speedFactor(ctx);
            listTick(server, ctx, now, speed);
            if (server.getTickCount() % 20 == 13) {
                bodyTick(server, ctx, now, speed);
            }
        }
        flush(server, now);
    }

    /**
     * The pace of the player list in seconds, about 1.5x faster than before the pacing was raised
     * (joins 20-90 s apart near the target, churn every 1.5-6.5 minutes, flickers every 12-52 s),
     * and faster again when corruption time is sped up ({@link EventPacing}).
     */
    private long listDelay(int seconds, double speedFactor) {
        return EventPacing.scale(seconds * 20.0 / LIST_PACE, 1.0, speedFactor, 20L * 2L);
    }

    private void listTick(MinecraftServer server, CorruptionContext ctx, long now, double speed) {
        int max = Math.min(NullConfig.get(NullConfig.FAKE_PLAYER_COUNT), FakePlayersPayload.MAX_ENTRIES);
        int target = targetCount(ctx.stageProgress(), max);
        if (nextJoin < 0L) {
            nextJoin = now + listDelay(10 + random.nextInt(30), speed);
        }
        if (now >= nextJoin) {
            if (sims.size() < target) {
                addSimulated(server, random, true);
                int missing = target - sims.size();
                nextJoin = now + listDelay(missing > 5 ? 3 + random.nextInt(10) : 20 + random.nextInt(70), speed);
            } else if (sims.size() > max) {
                // The setting was lowered: people drift away.
                removeSimulated(server, random, true);
                nextJoin = now + 20L * (4 + random.nextInt(8));
            } else {
                nextJoin = now + 20L * 10;
            }
        }
        if (nextChurn < 0L) {
            nextChurn = now + listDelay(120 + random.nextInt(240), speed);
        }
        if (now >= nextChurn) {
            // Somebody leaves; somebody else will join to take their place.
            if (sims.size() > 3) {
                removeSimulated(server, random, true);
            }
            nextChurn = now + listDelay(90 + random.nextInt(300), speed);
        }
        if (nextFlicker < 0L) {
            nextFlicker = now + listDelay(15 + random.nextInt(30), speed);
        }
        if (now >= nextFlicker && !sims.isEmpty()) {
            // A few names drop out of the list for a moment, as if their connection hiccuped.
            int count = 1 + random.nextInt(Math.min(3, sims.size()));
            for (int i = 0; i < count; i++) {
                Sim sim = sims.get(random.nextInt(sims.size()));
                sim.hiddenUntil = now + 20L * (2 + random.nextInt(5));
            }
            dirty = true;
            float pace = 1.0F - 0.6F * ctx.stageProgress();
            nextFlicker = now + (long) (listDelay(12 + random.nextInt(40), speed) * pace);
        }
        if ((nextLatency < 0L || now >= nextLatency) && !sims.isEmpty()) {
            for (Sim sim : sims) {
                if (sim.baseLatency < 0) {
                    continue;
                }
                int jitter = (int) Math.round(random.nextGaussian() * Math.max(6.0, sim.baseLatency * 0.15));
                sim.latency = Math.max(1, sim.baseLatency + jitter);
            }
            dirty = true;
            nextLatency = now + 600L;
        }
    }

    private void expireHidden(long now) {
        for (Sim sim : sims) {
            if (sim.hidden() && now >= sim.hiddenUntil) {
                sim.hiddenUntil = -1L;
                dirty = true;
            }
        }
    }

    private static int rollLatency(RandomSource rnd) {
        float roll = rnd.nextFloat();
        if (roll < 0.70F) {
            return 18 + rnd.nextInt(160);
        }
        if (roll < 0.85F) {
            return 200 + rnd.nextInt(500);
        }
        if (roll < 0.95F) {
            return 1000 + rnd.nextInt(1600);
        }
        return -1;
    }

    // ------------------------------------------------------------------ bodies

    private void bodyTick(MinecraftServer server, CorruptionContext ctx, long now, double speed) {
        bodies.removeIf(PlayerLikeEntity::hasVanished);
        // Left behind in a chunk that stopped ticking: frozen in place, never running out its lifetime.
        removeBodies(body -> !body.inTickingChunk());
        int cap = NullConfig.get(NullConfig.FAKE_PLAYER_BODIES);
        if (cap <= 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            boolean inNull = NullDimensions.isNull(player.level());
            int target = Math.round(cap * (0.3F + 0.7F * ctx.stageProgress()) * (inNull ? 1.0F : 0.35F));
            if (bodyCount(player) >= target) {
                continue;
            }
            Long next = nextBody.get(player.getUUID());
            if (next == null) {
                nextBody.put(player.getUUID(), now + listDelay(8 + random.nextInt(20), speed));
                continue;
            }
            if (now < next) {
                continue;
            }
            // Bodies stand around until the target count is reached; they come about 1.5x as
            // quickly as before the pacing was raised (6-22 s apart in NULL, 6-46 s outside).
            nextBody.put(player.getUUID(), now + listDelay(6 + random.nextInt(inNull ? 16 : 40), speed));
            spawnBackgroundBody(player, ctx);
        }
    }

    private void spawnBackgroundBody(ServerPlayer player, CorruptionContext ctx) {
        float roll = random.nextFloat();
        if (roll < 0.30F) {
            PlayerLikeSpawns.edgeOfView(player, random, 18.0, 44.0, false)
                    .or(() -> PlayerLikeSpawns.around(player, random, 16.0, 40.0, 20))
                    .ifPresent(spot -> spawnBody(player, FakePlayerEntity.Behaviour.STILL, spot, 0L, random, false));
        } else if (roll < 0.52F) {
            PlayerLikeSpawns.edgeOfView(player, random, 40.0, 70.0, false)
                    .ifPresent(spot -> spawnBody(player, FakePlayerEntity.Behaviour.DISTANT, spot, 0L, random, false));
        } else if (roll < 0.70F) {
            PlayerLikeSpawns.edgeOfView(player, random, 16.0, 30.0, false)
                    .ifPresent(spot -> spawnBody(player, FakePlayerEntity.Behaviour.SHY, spot, 0L, random, false));
        } else if (roll < 0.82F) {
            PlayerLikeSpawns.behind(player, random, 5.0, 10.0, false)
                    .ifPresent(spot -> spawnBody(player, FakePlayerEntity.Behaviour.BEHIND, spot, 0L, random, false));
        } else {
            spawnRunner(player, random, false);
        }
    }

    /**
     * A body that sprints across the player's view and is gone.
     *
     * @param relaxed from {@code /null event}: looser placement, and it also works before Stage 5
     */
    public boolean spawnRunner(ServerPlayer player, RandomSource rnd, boolean relaxed) {
        Optional<PlayerLikeSpawns.Spot> spot = PlayerLikeSpawns.toTheSide(player, rnd, 16.0, 30.0, relaxed);
        if (spot.isEmpty()) {
            return false;
        }
        Vec3 start = spot.get().pos();
        // Run to the mirror image of the start on the other side of the player's view.
        Vec3 look = player.getViewVector(1.0F);
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        forward = forward.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : forward.normalize();
        Vec3 rel = start.subtract(player.position());
        double along = rel.x * forward.x + rel.z * forward.z;
        Vec3 alongVec = forward.scale(along);
        Vec3 across = new Vec3(rel.x - alongVec.x, 0.0, rel.z - alongVec.z);
        Vec3 target = player.position().add(alongVec).subtract(across);
        FakePlayerEntity body = spawnBody(player, FakePlayerEntity.Behaviour.RUNNER, spot.get(), 0L, rnd, relaxed);
        if (body == null) {
            return false;
        }
        body.setRun(target.subtract(start), 0.55 + rnd.nextDouble() * 0.2);
        return true;
    }

    /** A body that sprints straight at the player and vanishes before it gets there. */
    public boolean spawnRush(ServerPlayer player, RandomSource rnd, boolean relaxed) {
        Optional<PlayerLikeSpawns.Spot> spot = PlayerLikeSpawns.inFront(player, rnd, 14.0, 24.0, relaxed);
        if (spot.isEmpty()) {
            return false;
        }
        FakePlayerEntity body = spawnBody(player, FakePlayerEntity.Behaviour.RUSH, spot.get(), 0L, rnd, relaxed);
        if (body == null) {
            return false;
        }
        body.setRun(player.position().subtract(spot.get().pos()), 0.65 + rnd.nextDouble() * 0.15);
        return true;
    }

    /** A crowd of motionless bodies in the distance that all vanish together. Returns how many appeared. */
    public int spawnCrowd(ServerPlayer player, RandomSource rnd, boolean relaxed) {
        int cap = Math.max(3, NullConfig.get(NullConfig.FAKE_PLAYER_BODIES));
        int size = Math.min(cap, 5 + rnd.nextInt(8));
        Optional<PlayerLikeSpawns.Spot> center = PlayerLikeSpawns.inFront(player, rnd, 30.0, 50.0, true)
                .or(() -> PlayerLikeSpawns.edgeOfView(player, rnd, 30.0, 50.0, relaxed));
        if (center.isEmpty()) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        long group = ++groupCounter;
        BlockPos c = center.get().blockPos();
        int placed = 0;
        for (int attempt = 0; attempt < size * 4 && placed < size; attempt++) {
            int x = c.getX() + rnd.nextInt(13) - 6;
            int z = c.getZ() + rnd.nextInt(13) - 6;
            OptionalInt y = PlayerLikeSpawns.standableNear(level, x, z, c.getY(), 4, 6);
            if (y.isEmpty()) {
                continue;
            }
            Vec3 pos = new Vec3(x + 0.5, y.getAsInt(), z + 0.5);
            boolean crowded = false;
            for (FakePlayerEntity other : bodies) {
                if (other.group() == group && other.position().distanceToSqr(pos) < 1.5) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded && spawnBody(player, FakePlayerEntity.Behaviour.CROWD, new PlayerLikeSpawns.Spot(pos, false), group, rnd, relaxed) != null) {
                placed++;
            }
        }
        return placed;
    }

    @Nullable
    private FakePlayerEntity spawnBody(ServerPlayer player, FakePlayerEntity.Behaviour behaviour, PlayerLikeSpawns.Spot spot, long group,
                                       RandomSource rnd, boolean forced) {
        if (!forced) {
            bodies.removeIf(PlayerLikeEntity::hasVanished);
            if (bodies.size() >= MAX_TOTAL_BODIES) {
                return null;
            }
        }
        ServerLevel level = player.serverLevel();
        FakePlayerEntity body = FakePlayerModule.FAKE_PLAYER.get().create(level);
        if (body == null) {
            return null;
        }
        Vec3 pos = spot.pos();
        float yaw = (float) (Mth.atan2(player.getZ() - pos.z, player.getX() - pos.x) * (180.0 / Math.PI)) - 90.0F;
        int lifetime;
        float vanishDistance;
        switch (behaviour) {
            case STILL -> {
                lifetime = 20 * (60 + rnd.nextInt(180));
                vanishDistance = 3.0F + rnd.nextFloat() * 6.0F;
            }
            case DISTANT -> {
                lifetime = 20 * (120 + rnd.nextInt(180));
                vanishDistance = 22.0F + rnd.nextFloat() * 6.0F;
            }
            case SHY -> {
                lifetime = 20 * (40 + rnd.nextInt(120));
                vanishDistance = 12.0F + rnd.nextFloat() * 5.0F;
            }
            case BEHIND -> {
                lifetime = 20 * (20 + rnd.nextInt(30));
                vanishDistance = 1.8F;
            }
            case RUNNER -> {
                lifetime = 50 + rnd.nextInt(40);
                vanishDistance = 3.0F;
            }
            case RUSH -> {
                lifetime = 60 + rnd.nextInt(20);
                vanishDistance = 2.4F;
            }
            default -> {
                lifetime = 20 * (40 + rnd.nextInt(60));
                vanishDistance = 16.0F + rnd.nextFloat() * 6.0F;
            }
        }
        body.configure(behaviour, player, lifetime, vanishDistance, group);
        if (behaviour == FakePlayerEntity.Behaviour.STILL && rnd.nextFloat() < 0.3F) {
            yaw += 180.0F;
        }
        body.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        if (!body.inTickingChunk()) {
            // Beyond the simulation distance it would only stand frozen until the next cleanup.
            return null;
        }
        body.setYHeadRot(yaw);
        body.yBodyRot = yaw;
        if (spot.floating()) {
            body.setNoGravity(true);
        }
        applyIdentity(body, rnd, player.server);
        body.setForced(forced && CorruptionManager.stage(player.server) != CorruptionStage.NULL);
        if (!level.addFreshEntity(body)) {
            return null;
        }
        bodies.add(body);
        return body;
    }

    /**
     * Wears the name and skin of somebody in the list (or a fresh one if the list is empty),
     * never the name of a real player who is online.
     */
    private void applyIdentity(FakePlayerEntity body, RandomSource rnd, MinecraftServer server) {
        Set<String> online = onlineNames(server);
        List<Sim> visible = new ArrayList<>();
        for (Sim sim : sims) {
            if (!sim.hidden() && !online.contains(sim.name.toLowerCase(Locale.ROOT))) {
                visible.add(sim);
            }
        }
        int flags = 0;
        String name;
        int skin;
        if (visible.isEmpty()) {
            name = UsernameGenerator.unique(rnd, online);
            skin = SkinSet.weighted(rnd, 14, 14, 22, 50);
        } else {
            Sim sim = visible.get(rnd.nextInt(visible.size()));
            name = sim.name;
            skin = sim.skin;
            if (sim.corrupted) {
                flags |= PlayerLikeEntity.FLAG_CORRUPT_NAME;
            }
        }
        if (rnd.nextFloat() < 0.1F) {
            flags |= PlayerLikeEntity.FLAG_HIDE_NAME;
        }
        if (rnd.nextFloat() < 0.15F) {
            flags |= PlayerLikeEntity.FLAG_FLICKER;
        }
        body.setIdentity(name, skin, flags);
    }

    /** Called by a crowd member that vanished: the rest of its crowd goes with it. */
    void vanishGroup(long group, FakePlayerEntity source) {
        List<FakePlayerEntity> members = new ArrayList<>();
        for (FakePlayerEntity body : bodies) {
            if (body != source && body.group() == group && !body.hasVanished()) {
                members.add(body);
            }
        }
        for (FakePlayerEntity member : members) {
            member.vanish(null, PlayerLikeEntity.VanishCause.CLEANUP);
        }
    }

    private int bodyCount(ServerPlayer player) {
        int n = 0;
        for (FakePlayerEntity body : bodies) {
            if (!body.hasVanished() && player.getUUID().equals(body.ownerId())) {
                n++;
            }
        }
        return n;
    }

    private void removeBodies(Predicate<FakePlayerEntity> filter) {
        List<FakePlayerEntity> doomed = new ArrayList<>();
        for (FakePlayerEntity body : bodies) {
            if (filter.test(body)) {
                doomed.add(body);
            }
        }
        for (FakePlayerEntity body : doomed) {
            body.vanish(null, PlayerLikeEntity.VanishCause.CLEANUP);
        }
        bodies.removeAll(doomed);
    }

    /** Every live body this manager spawned. */
    public List<FakePlayerEntity> bodies() {
        bodies.removeIf(PlayerLikeEntity::hasVanished);
        return List.copyOf(bodies);
    }

    // ------------------------------------------------------------------ persistence and sync

    private void ensureLoaded(MinecraftServer server) {
        if (loadedFor == server) {
            return;
        }
        loadedFor = server;
        sims.clear();
        ListTag list = CorruptionManager.data(server).systemTag(TAG_KEY).getList("players", Tag.TAG_COMPOUND);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size() && sims.size() < FakePlayersPayload.MAX_ENTRIES; i++) {
            CompoundTag tag = list.getCompound(i);
            String name = tag.getString("name");
            if (!tag.hasUUID("id") || !UsernameGenerator.isAcceptable(name) || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            sims.add(new Sim(tag.getUUID("id"), name, SkinSet.sanitize(tag.getInt("skin")), tag.getInt("latency"),
                    tag.getBoolean("corrupted")));
        }
        dirty = true;
    }

    private void save(MinecraftServer server) {
        CorruptionData data = CorruptionManager.data(server);
        CompoundTag root = data.systemTag(TAG_KEY);
        ListTag list = new ListTag();
        for (Sim sim : sims) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", sim.id);
            tag.putString("name", sim.name);
            tag.putInt("skin", sim.skin);
            tag.putInt("latency", sim.baseLatency);
            tag.putBoolean("corrupted", sim.corrupted);
            list.add(tag);
        }
        root.put("players", list);
        data.setDirty();
    }

    private void clearList(MinecraftServer server) {
        sims.clear();
        save(server);
        dirty = true;
        nextJoin = -1L;
        nextChurn = -1L;
        nextFlicker = -1L;
        nextLatency = -1L;
    }

    private void flush(MinecraftServer server, long now) {
        if (dirty && now - lastSend >= MIN_SEND_INTERVAL) {
            sendAll(server);
        }
    }

    private void sendAll(MinecraftServer server) {
        FakePlayersPayload payload = payload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, payload);
        }
        dirty = false;
        lastSend = server.overworld().getGameTime();
    }

    private FakePlayersPayload payload(MinecraftServer server) {
        if (CorruptionManager.stage(server) != CorruptionStage.NULL) {
            return new FakePlayersPayload(List.of());
        }
        return new FakePlayersPayload(List.copyOf(entries()));
    }

    private static void sendTo(ServerPlayer player, FakePlayersPayload payload) {
        if (player.connection != null && player.connection.hasChannel(FakePlayersPayload.TYPE)) {
            NullNetworking.sendToPlayer(player, payload);
        }
    }

    /**
     * A yellow join or leave line for everyone online. Sent to the players only: unlike
     * {@code PlayerList#broadcastSystemMessage} it is never written to the server log, where it
     * would read exactly like somebody really connecting.
     */
    static void broadcast(MinecraftServer server, Component line) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(line);
        }
    }

    /** Lower-case names of the real players online, which nothing simulated may wear. */
    public static Set<String> onlineNames(MinecraftServer server) {
        Set<String> out = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.add(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onPlayerJoin(ServerPlayer player, CorruptionContext context) {
        ensureLoaded(player.server);
        // A real player now has this name: the simulated one quietly goes, so the list never shows
        // the name twice and no body wears a real player's name.
        String joined = player.getGameProfile().getName();
        if (sims.removeIf(sim -> sim.name.equalsIgnoreCase(joined))) {
            save(player.server);
            dirty = true;
            removeBodies(body -> body.username().equalsIgnoreCase(joined));
        }
        sendTo(player, payload(player.server));
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        UUID id = player.getUUID();
        removeBodies(body -> id.equals(body.ownerId()));
        nextBody.remove(id);
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        ensureLoaded(server);
        if (to != CorruptionStage.NULL) {
            clearList(server);
            removeBodies(body -> true);
            nextBody.clear();
        } else {
            long now = server.overworld().getGameTime();
            nextJoin = now + 20L * (12 + random.nextInt(30));
            nextChurn = -1L;
            nextFlicker = -1L;
        }
        sendAll(server);
    }

    @Override
    public void onReset(MinecraftServer server) {
        sims.clear();
        removeBodies(body -> true);
        nextBody.clear();
        nextJoin = -1L;
        nextChurn = -1L;
        nextFlicker = -1L;
        nextLatency = -1L;
        loadedFor = server;
        dirty = true;
        // The stored list goes with the rest of the system data when the reset finishes.
        FakePlayersPayload empty = new FakePlayersPayload(List.of());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, empty);
        }
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        removeBodies(body -> true);
        bodies.clear();
        sims.clear();
        nextBody.clear();
        loadedFor = null;
        dirty = false;
        nextJoin = -1L;
        nextChurn = -1L;
        nextFlicker = -1L;
        nextLatency = -1L;
        lastSend = Long.MIN_VALUE / 2;
    }
}
