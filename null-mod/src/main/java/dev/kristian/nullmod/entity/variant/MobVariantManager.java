package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.RegionCorruption;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.dimension.NullDimensionManager;
import dev.kristian.nullmod.event.EventPacing;
import dev.kristian.nullmod.field.FieldManager;
import dev.kristian.nullmod.network.MobVariantPayload;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.registry.NullAttachments;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Corrupted and parasite mobs on the server: which loaded mobs are variants, how new ones appear,
 * the caps, the stage rules and keeping clients in sync.
 * <p>
 * New variants come from four places, all from Stage 2 (corrupted) or Stage 3 (parasites) on,
 * never in the NULL dimension or the field and never around a player who is being moved there:
 * <ol>
 * <li>a mob that spawns naturally near a player (or with a new chunk, a spawner, a patrol...)
 * gets one roll when it joins the level;</li>
 * <li>"corruption spreads": a slow schedule per player turns one mob around them every
 * half a minute to a minute (out of their sight when it can);</li>
 * <li>parasites that have grown reach for a neighbour now and then, and one may leave a dying
 * host for a mob nearby (with the {@code variant.parasite_burst} effect for everyone close); a
 * variant parent may pass it on to its young, and a mob that converts (a zombie drowning, a
 * mooshroom sheared) keeps it;</li>
 * <li>the random events in {@link MobVariantEvents} and the {@code /null mob} command.</li>
 * </ol>
 * Every automatic conversion respects two caps: variants within {@link #AREA_RADIUS} blocks of a
 * player (the config's maximum, less in the early stages) and variants loaded in the whole world.
 * <p>
 * Loaded variant mobs are registered here when they join a level and dropped when they leave, so
 * nothing ever scans the world's entity lists: the background passes look only at small boxes
 * around players, a few mobs a second at most. When the stage moves back (or NULL is reset) the
 * variants that no longer belong turn back to normal at once, and unloaded ones when they load
 * ({@link MobVariantWorldData} counts the moves).
 */
public final class MobVariantManager implements CorruptionSystem {
    public static final MobVariantManager INSTANCE = new MobVariantManager();
    public static final String NAME = "mob_variants";

    /** Variants this close to a player (horizontally) count toward that player's area cap. */
    public static final double AREA_RADIUS = 64.0;
    /** How far from a player the background spread looks for a mob, sideways and up or down. */
    static final double SPREAD_REACH = 40.0;
    static final double SPREAD_REACH_Y = 20.0;
    /** A fresh spawn further than this from every player is left alone. */
    private static final double SPAWN_REACH = 112.0;
    /** How far a grown parasite reaches for a new host, and how far one leaving a dying host can go. */
    private static final double INFEST_REACH = 6.0;
    private static final double ESCAPE_REACH = 9.0;
    /** Players this close see a parasite burst out of its dead host. */
    private static final double BURST_VIEW = 48.0;

    /** Share of {@code mobs.maxPerPlayerArea} allowed in each stage (Stage 1 has none). */
    private static final float[] AREA_SHARE = {0.0F, 0.4F, 0.65F, 0.85F, 1.0F};
    /** Average seconds between two background conversions around one player, per stage. */
    private static final float[] SPREAD_SECONDS = {0.0F, 70.0F, 50.0F, 38.0F, 30.0F};
    /** Chance that a fresh natural spawn near a player is a variant, per stage. */
    private static final float[] SPAWN_CHANCE = {0.0F, 0.05F, 0.08F, 0.11F, 0.14F};
    /** How much each kind takes part in each stage (times its frequency setting). */
    private static final float[] CORRUPTED_SHARE = {0.0F, 1.0F, 0.7F, 0.6F, 0.55F};
    private static final float[] PARASITE_SHARE = {0.0F, 0.0F, 0.45F, 0.6F, 0.7F};
    /** Never two background conversions around one player closer together than this, whatever the speed. */
    private static final long MIN_SPREAD_GAP = 20L * 12L;
    /** Ticks of a host's life between two attempts of its parasite to reach a neighbour. */
    private static final int INFEST_GAP_MIN = 20 * 120;
    private static final int INFEST_GAP_SPREAD = 20 * 120;
    /** How grown a parasite must be before it reaches for a neighbour, and its chance when it tries. */
    private static final float INFEST_GROWTH = 0.5F;
    private static final float INFEST_CHANCE = 0.4F;
    /** Parasite hosts looked at per infestation pass (every two seconds). */
    private static final int INFEST_BUDGET = 3;
    /** Growth updates to clients: at least this much change, at most one every this many ticks per mob. */
    private static final float GROWTH_SYNC_STEP = 0.05F;
    private static final int GROWTH_SYNC_GAP = 100;

    /** Spawns that get a roll; spawn eggs, buckets, commands, breeding and conversions do not. */
    private static final Set<MobSpawnType> ROLLED_SPAWNS = EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.SPAWNER, MobSpawnType.STRUCTURE, MobSpawnType.PATROL, MobSpawnType.REINFORCEMENT,
            MobSpawnType.JOCKEY, MobSpawnType.EVENT, MobSpawnType.TRIAL_SPAWNER);

    private final Set<Mob> loaded = new LinkedHashSet<>();
    private final Set<Mob> goalQueue = new LinkedHashSet<>();
    private final Map<UUID, Long> nextSpread = new HashMap<>();
    private final RandomSource random = RandomSource.create();
    private int infestCursor;

    private MobVariantManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    // ------------------------------------------------------------------ reading a mob

    /** The mob's variant data, or null for an ordinary mob (or anything that is not a mob). */
    @Nullable
    public static VariantData data(Entity entity) {
        return entity instanceof Mob mob ? mob.getExistingDataOrNull(NullAttachments.MOB_VARIANT.get()) : null;
    }

    /** What NULL has done to this entity ({@link MobVariant#NONE} for ordinary mobs and anything else). */
    public static MobVariant variantOf(Entity entity) {
        VariantData data = data(entity);
        return data == null ? MobVariant.NONE : data.variant();
    }

    /** True if the mob carries the variant behaviour goals (they follow a change by the end of the tick). */
    public static boolean behaviourInstalled(Mob mob) {
        return VariantGoals.installed(mob);
    }

    // ------------------------------------------------------------------ counting

    /** Every variant mob loaded on the server right now (a snapshot). */
    public List<Mob> loaded() {
        purge();
        return List.copyOf(loaded);
    }

    public int loadedCount() {
        purge();
        return loaded.size();
    }

    /** Cheap: whether any variant mob is registered at all (the sound hook skips everything otherwise). */
    boolean anyLoaded() {
        return !loaded.isEmpty();
    }

    /** Loaded variant mobs in the level within {@link #AREA_RADIUS} blocks (horizontally) of the spot. */
    public int countNear(Level level, double x, double z) {
        int count = 0;
        for (Mob mob : loaded) {
            if (mob.level() == level && !mob.isRemoved()) {
                double dx = mob.getX() - x;
                double dz = mob.getZ() - z;
                if (dx * dx + dz * dz <= AREA_RADIUS * AREA_RADIUS) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Most variants NULL makes by itself around one player in this stage. */
    public static int areaCap(CorruptionStage stage) {
        int max = VariantConfig.maxPerArea();
        float share = AREA_SHARE[stage.tableIndex()];
        if (max <= 0 || share <= 0.0F) {
            return 0;
        }
        return Math.max(1, Math.round(max * share));
    }

    /** True once as many variants are loaded as the whole world may have. */
    public boolean worldFull() {
        return loadedCount() >= VariantConfig.maxPerWorld();
    }

    /**
     * How many more mobs NULL may turn around this player now: what is left of the area cap
     * (unless {@code ignoreAreaCap}, for operators' forced events) and of the world's cap.
     */
    public int room(ServerPlayer player, CorruptionStage stage, boolean ignoreAreaCap) {
        int world = VariantConfig.maxPerWorld() - loadedCount();
        if (ignoreAreaCap) {
            return Math.max(0, world);
        }
        int area = areaCap(stage) - countNear(player.level(), player.getX(), player.getZ());
        return Math.max(0, Math.min(area, world));
    }

    /** Variant mobs of the given kinds in the player's level within {@code range}, nearest first. */
    public List<Mob> variantsNear(ServerPlayer player, double range, Set<MobVariant> kinds) {
        List<Mob> out = new ArrayList<>();
        for (Mob mob : loaded) {
            if (mob.level() == player.level() && !mob.isRemoved() && mob.isAlive() && kinds.contains(variantOf(mob))
                    && mob.distanceToSqr(player) <= range * range) {
                out.add(mob);
            }
        }
        out.sort(Comparator.comparingDouble(mob -> mob.distanceToSqr(player)));
        return out;
    }

    /** Ordinary mobs around the player that NULL may turn by itself (and that pass the filter). */
    public List<Mob> candidatesNear(ServerPlayer player, double reach, double reachY, Predicate<Mob> filter) {
        return player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(reach, reachY, reach),
                mob -> variantOf(mob) == MobVariant.NONE && filter.test(mob) && MobVariantRules.canBecomeVariant(mob, true));
    }

    /** The variant mob whose own sounds are played at exactly this spot (the sound hook), or null. */
    @Nullable
    Mob variantAt(ServerLevel level, Vec3 position) {
        for (Mob mob : loaded) {
            if (mob.level() == level && VariantSounds.playedBy(mob, position)) {
                return mob;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ changing a mob

    /**
     * Makes the mob this variant right now, or ordinary again with {@link MobVariant#NONE}. No
     * stage, cap or eligibility checks: callers make those. Keeps the mob's look seed when it
     * already was a variant, tells nearby clients and installs (or removes) the goals at the end
     * of the tick.
     *
     * @param growth parasite growth to start from, 0 to 1 (corrupted mobs ignore it)
     * @param forced made by an operator: it stays at any stage until the stage is moved back
     * @return true if the mob's variant changed
     */
    public boolean set(Mob mob, MobVariant variant, float growth, boolean forced) {
        VariantData old = data(mob);
        long seed = old != null && old.variant() != MobVariant.NONE ? old.seed() : random.nextLong();
        return set(mob, variant, growth, forced, seed);
    }

    boolean set(Mob mob, MobVariant variant, float growth, boolean forced, long seed) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        VariantData old = data(mob);
        MobVariant before = old == null ? MobVariant.NONE : old.variant();
        if (variant == MobVariant.NONE) {
            if (old != null) {
                mob.removeData(NullAttachments.MOB_VARIANT.get());
            }
            loaded.remove(mob);
            goalQueue.add(mob);
            VariantMotion.release(mob);
            if (before != MobVariant.NONE) {
                sync(mob, null);
            }
            return before != MobVariant.NONE;
        }
        if (old != null && before == variant) {
            // The same kind again: it stays as it is, but an operator's touch makes it theirs.
            if (forced) {
                old.setForced(true);
            }
            if (variant == MobVariant.PARASITE && growth > old.growth()) {
                old.setGrowth(growth);
                sync(mob, old);
            }
            return false;
        }
        VariantData data = new VariantData(variant, seed, growth, forced);
        MobVariantWorldData world = MobVariantWorldData.get(level.getServer());
        data.setEpochs(world.resetEpoch(), world.stageEpoch());
        data.setNextSpread(INFEST_GAP_MIN + random.nextInt(INFEST_GAP_SPREAD));
        mob.setData(NullAttachments.MOB_VARIANT.get(), data);
        if (mob.isAddedToLevel()) {
            loaded.add(mob);
        }
        goalQueue.add(mob);
        sync(mob, data);
        return true;
    }

    /** Every loaded variant mob turns back to normal ({@code /null reset}). */
    public int clearAll() {
        int cleared = 0;
        for (Mob mob : loaded()) {
            if (set(mob, MobVariant.NONE, 0.0F, false)) {
                cleared++;
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------ sync

    private void sync(Mob mob, @Nullable VariantData data) {
        if (!mob.isAddedToLevel()) {
            // Not in a level yet: the clients hear about it when they start tracking it.
            return;
        }
        NullNetworking.sendToNear(mob, payload(mob, data));
        if (data != null) {
            data.syncedGrowth = data.growth();
            data.syncedAt = mob.level().getGameTime();
        }
    }

    static MobVariantPayload payload(Mob mob, @Nullable VariantData data) {
        if (data == null || data.variant() == MobVariant.NONE) {
            return new MobVariantPayload(mob.getId(), MobVariant.NONE.id(), 0L, 0.0F);
        }
        return new MobVariantPayload(mob.getId(), data.variant().id(), data.seed(), data.growth());
    }

    /** A host's pulse reports its growth; clients hear about noticeable changes, a few times a minute at most. */
    void growthChanged(Mob mob, VariantData data, long now) {
        float growth = data.growth();
        boolean step = Math.abs(growth - data.syncedGrowth) >= GROWTH_SYNC_STEP || (growth >= 1.0F && data.syncedGrowth < 1.0F);
        if (step && now - data.syncedAt >= GROWTH_SYNC_GAP) {
            sync(mob, data);
        }
    }

    // ------------------------------------------------------------------ game events (MobVariantModule)

    /** A mob joins a level: a variant is checked and gets its goals back, a fresh natural spawn gets its roll. */
    void onJoin(Mob mob, ServerLevel level, boolean loadedFromDisk) {
        VariantData data = data(mob);
        if (data != null) {
            if (data.variant() != MobVariant.NONE && stillBelongs(mob, data, level.getServer())) {
                loaded.add(mob);
                goalQueue.add(mob);
            } else {
                // Nobody has seen it as a variant yet: nothing to tell the clients.
                mob.removeData(NullAttachments.MOB_VARIANT.get());
            }
            return;
        }
        if (!loadedFromDisk) {
            rollFreshSpawn(mob, level);
        }
    }

    void onLeave(Mob mob) {
        loaded.remove(mob);
        goalQueue.remove(mob);
    }

    /** A player starts seeing a mob: tell them if it is a variant. */
    void onStartTracking(ServerPlayer player, Entity target) {
        VariantData data = data(target);
        if (data != null && data.variant() != MobVariant.NONE && target instanceof Mob mob) {
            NullNetworking.sendToPlayer(player, payload(mob, data));
        }
    }

    /** A host dies: the parasite may burst out of it and look for a new host nearby (never a player). */
    void onDeath(Mob host) {
        VariantData data = data(host);
        if (data == null || data.variant() != MobVariant.PARASITE || !(host.level() instanceof ServerLevel level)) {
            return;
        }
        float growth = data.growth();
        boolean forced = data.forced();
        if (random.nextFloat() >= 0.35F + 0.55F * growth) {
            // It dies with its host.
            return;
        }
        burst(level, host, growth);
        set(host, MobVariant.NONE, 0.0F, false);
        if (!VariantConfig.enabled() || !VariantConfig.parasiteSpreading() || !MobVariantRules.levelAllows(level)) {
            return;
        }
        CorruptionStage stage = CorruptionManager.stage(level.getServer());
        boolean allowed = MobVariantRules.allowedAt(MobVariant.PARASITE, stage);
        if ((!allowed && !forced) || worldFull() || transitionNear(level, host.getX(), host.getZ(), AREA_RADIUS)) {
            return;
        }
        ServerPlayer anchor = nearestAnchor(level, host.getX(), host.getZ(), AREA_RADIUS);
        if (anchor != null && !forced && countNear(level, anchor.getX(), anchor.getZ()) >= areaCap(stage)) {
            return;
        }
        Mob next = nearestCandidate(host, ESCAPE_REACH);
        if (next != null && set(next, MobVariant.PARASITE, Math.min(0.6F, 0.15F + 0.3F * growth), forced && !allowed)) {
            WorldMemory.remember(level.getServer(), "variant:parasite_moved", anchor == null ? null : anchor.getUUID(), typeName(next));
        }
    }

    /** A mob turned into another kind (a zombie drowning, a mooshroom sheared...): the variant goes with it. */
    void onConverted(Mob from, Mob to) {
        VariantData data = data(from);
        if (data == null || data.variant() == MobVariant.NONE || !(to.level() instanceof ServerLevel)) {
            return;
        }
        if (!VariantConfig.enabled() || !MobVariantRules.canBecomeVariant(to, false)) {
            return;
        }
        set(to, data.variant(), data.growth(), data.forced(), data.seed());
    }

    /** Animals breed: a corrupted or infested parent may pass it on (the child is not in the level yet). */
    void onBabyBorn(Mob parentA, Mob parentB, AgeableMob child) {
        MobVariant a = variantOf(parentA);
        MobVariant b = variantOf(parentB);
        if (a == MobVariant.NONE && b == MobVariant.NONE) {
            return;
        }
        if (!(child.level() instanceof ServerLevel level) || !VariantConfig.enabled() || !MobVariantRules.levelAllows(level)
                || !MobVariantRules.canBecomeVariant(child, true)) {
            return;
        }
        MobVariant inherited = MobVariant.NONE;
        if ((a == MobVariant.PARASITE || b == MobVariant.PARASITE) && random.nextFloat() < 0.5F) {
            inherited = MobVariant.PARASITE;
        } else if ((a == MobVariant.CORRUPTED || b == MobVariant.CORRUPTED) && random.nextFloat() < 0.5F) {
            inherited = MobVariant.CORRUPTED;
        }
        CorruptionStage stage = CorruptionManager.stage(level.getServer());
        if (inherited == MobVariant.NONE || !MobVariantRules.allowedAt(inherited, stage) || worldFull()
                || transitionNear(level, parentA.getX(), parentA.getZ(), AREA_RADIUS)) {
            return;
        }
        // The child is not placed yet: it is born where its parent stands.
        ServerPlayer anchor = nearestAnchor(level, parentA.getX(), parentA.getZ(), AREA_RADIUS);
        if (anchor != null && countNear(level, anchor.getX(), anchor.getZ()) >= areaCap(stage)) {
            return;
        }
        set(child, inherited, 0.0F, false);
    }

    // ------------------------------------------------------------------ CorruptionSystem

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        processGoalQueue();
        int tick = server.getTickCount();
        if (tick % 20 == 7) {
            maintain();
        }
        if (ctx.paused()) {
            return;
        }
        if (tick % 20 == 13) {
            spreadPass(server, ctx);
        }
        if (tick % 40 == 31) {
            infestPass(ctx);
        }
    }

    /** Goals are only ever added or removed here, at the end of a server tick, never inside a goal selector's loop. */
    private void processGoalQueue() {
        if (goalQueue.isEmpty()) {
            return;
        }
        List<Mob> batch = List.copyOf(goalQueue);
        goalQueue.clear();
        for (Mob mob : batch) {
            if (!mob.isRemoved() && mob.isAddedToLevel()) {
                VariantGoals.sync(mob, variantOf(mob) != MobVariant.NONE);
            }
        }
    }

    /** Once a second: forgets mobs that left, and turns back mobs the config no longer allows. */
    private void maintain() {
        purge();
        if (loaded.isEmpty()) {
            return;
        }
        boolean enabled = VariantConfig.enabled();
        for (Mob mob : List.copyOf(loaded)) {
            if (variantOf(mob) == MobVariant.NONE) {
                loaded.remove(mob);
            } else if (!enabled || MobVariantRules.isExcludedType(mob.getType())) {
                set(mob, MobVariant.NONE, 0.0F, false);
            }
        }
    }

    private void purge() {
        loaded.removeIf(mob -> mob.isRemoved() || !mob.isAddedToLevel());
    }

    /** "Corruption spreads": every player's own slow schedule turns one mob around them now and then. */
    private void spreadPass(MinecraftServer server, CorruptionContext ctx) {
        if (!VariantConfig.enabled() || !ctx.atLeast(CorruptionStage.UNDER_MIDDLE)) {
            nextSpread.clear();
            return;
        }
        double rate = rate(ctx.stage());
        if (rate <= 0.0) {
            return;
        }
        long now = server.overworld().getGameTime();
        double speed = EventPacing.speedFactor(ctx);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!anchorOk(player)) {
                continue;
            }
            UUID id = player.getUUID();
            Long next = nextSpread.get(id);
            if (next == null) {
                // The first one comes sooner, so a new stage (or a fresh login) shows quickly.
                nextSpread.put(id, now + spreadDelay(ctx, player, rate, speed) / 2L);
                continue;
            }
            if (now < next) {
                continue;
            }
            nextSpread.put(id, now + spreadDelay(ctx, player, rate, speed));
            spreadNear(player, ctx.stage(), false);
        }
    }

    private long spreadDelay(CorruptionContext ctx, ServerPlayer player, double rate, double speed) {
        double ticks = SPREAD_SECONDS[ctx.stage().tableIndex()] * 20.0 * (0.6 + random.nextDouble() * 0.8);
        double frequency = rate * regionSpeed(ctx, player.getBlockX(), player.getBlockZ()) * withinStage(ctx);
        return EventPacing.scale(ticks, frequency, speed, MIN_SPREAD_GAP);
    }

    /**
     * The background spread for one player: one mob around them (out of their sight when it can)
     * turns corrupted or into a parasite host, if the caps allow.
     *
     * @return the mob that changed, or null
     */
    @Nullable
    public Mob spreadNear(ServerPlayer player, CorruptionStage stage, boolean ignoreAreaCap) {
        ServerLevel level = player.serverLevel();
        if (!VariantConfig.enabled() || !MobVariantRules.levelAllows(level) || room(player, stage, ignoreAreaCap) <= 0
                || transitionNear(level, player.getX(), player.getZ(), SPREAD_REACH)) {
            return null;
        }
        MobVariant variant = pick(stage);
        if (variant == MobVariant.NONE) {
            return null;
        }
        List<Mob> candidates = candidatesNear(player, SPREAD_REACH, SPREAD_REACH_Y, mob -> true);
        if (candidates.isEmpty()) {
            return null;
        }
        List<Mob> unseen = new ArrayList<>();
        for (Mob mob : candidates) {
            if (!VariantSight.inView(player, mob, 60.0)) {
                unseen.add(mob);
            }
        }
        List<Mob> pool = !unseen.isEmpty() && random.nextFloat() < 0.75F ? unseen : candidates;
        Mob chosen = pool.get(random.nextInt(pool.size()));
        float growth = variant == MobVariant.PARASITE ? 0.05F + random.nextFloat() * 0.15F : 1.0F;
        if (!set(chosen, variant, growth, false)) {
            return null;
        }
        WorldMemory.remember(player.server, variant == MobVariant.PARASITE ? "variant:infested" : "variant:corrupted",
                player.getUUID(), typeName(chosen));
        return chosen;
    }

    /** Grown parasites reach for a neighbour now and then: a few hosts every two seconds. */
    private void infestPass(CorruptionContext ctx) {
        if (!VariantConfig.enabled() || !VariantConfig.parasiteSpreading() || !ctx.atLeast(CorruptionStage.MIDDLE)
                || VariantConfig.parasiteFrequency() <= 0.0) {
            return;
        }
        purge();
        List<Mob> hosts = new ArrayList<>();
        for (Mob mob : loaded) {
            if (variantOf(mob) == MobVariant.PARASITE) {
                hosts.add(mob);
            }
        }
        if (hosts.isEmpty()) {
            return;
        }
        int budget = Math.min(INFEST_BUDGET, hosts.size());
        for (int i = 0; i < budget; i++) {
            Mob host = hosts.get(Math.floorMod(infestCursor++, hosts.size()));
            infestFrom(host, ctx.stage());
        }
    }

    /**
     * One parasite's attempt to reach a neighbour: only once it has grown, only every few minutes
     * of its host's life, only near a player and within the caps.
     *
     * @return the new host, or null
     */
    @Nullable
    Mob infestFrom(Mob host, CorruptionStage stage) {
        VariantData data = data(host);
        if (data == null || data.variant() != MobVariant.PARASITE || data.growth() < INFEST_GROWTH || data.age() < data.nextSpread()) {
            return null;
        }
        double gap = (INFEST_GAP_MIN + random.nextInt(INFEST_GAP_SPREAD)) / Math.max(0.1, VariantConfig.parasiteFrequency());
        data.setNextSpread((int) Math.min(Integer.MAX_VALUE, data.age() + (long) gap));
        if (!(host.level() instanceof ServerLevel level) || !MobVariantRules.levelAllows(level) || random.nextFloat() >= INFEST_CHANCE) {
            return null;
        }
        ServerPlayer anchor = nearestAnchor(level, host.getX(), host.getZ(), AREA_RADIUS);
        if (anchor == null || worldFull() || countNear(level, anchor.getX(), anchor.getZ()) >= areaCap(stage)
                || transitionNear(level, host.getX(), host.getZ(), AREA_RADIUS)) {
            return null;
        }
        Mob victim = nearestCandidate(host, INFEST_REACH);
        if (victim == null || !set(victim, MobVariant.PARASITE, 0.05F, false)) {
            return null;
        }
        VariantSounds.playNear(victim, NullSounds.NULLROOT_GROW.get(), 0.45F, 0.8F, 24.0);
        WorldMemory.remember(level.getServer(), "variant:infested", anchor.getUUID(), typeName(victim));
        return victim;
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        nextSpread.clear();
        if (to.number() >= from.number()) {
            return;
        }
        // Moving back: what does not belong to the new stage turns back to normal (operators' too);
        // unloaded mobs are checked against the new count when they load.
        MobVariantWorldData world = MobVariantWorldData.get(server);
        world.stageMovedBack();
        for (Mob mob : loaded()) {
            VariantData data = data(mob);
            if (data == null) {
                continue;
            }
            if (MobVariantRules.allowedAt(data.variant(), to)) {
                data.setEpochs(world.resetEpoch(), world.stageEpoch());
            } else {
                set(mob, MobVariant.NONE, 0.0F, false);
            }
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        nextSpread.remove(player.getUUID());
    }

    @Override
    public void onReset(MinecraftServer server) {
        MobVariantWorldData.get(server).reset();
        clearAll();
        nextSpread.clear();
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        loaded.clear();
        goalQueue.clear();
        nextSpread.clear();
        infestCursor = 0;
    }

    // ------------------------------------------------------------------ internals

    /** Whether a variant mob that is joining a level still belongs: config, exclusions, stage and resets. */
    private boolean stillBelongs(Mob mob, VariantData data, MinecraftServer server) {
        if (!VariantConfig.enabled() || MobVariantRules.isExcludedType(mob.getType())) {
            return false;
        }
        MobVariantWorldData world = MobVariantWorldData.get(server);
        if (data.resetEpoch() != world.resetEpoch()) {
            return false;
        }
        boolean allowed = MobVariantRules.allowedAt(data.variant(), CorruptionManager.stage(server));
        if (data.stageEpoch() != world.stageEpoch()) {
            // The stage went back while it was unloaded: it stays only if it belongs to this stage.
            if (!allowed) {
                return false;
            }
            data.setEpochs(world.resetEpoch(), world.stageEpoch());
        }
        return allowed || data.forced();
    }

    /** A fresh natural spawn near a player gets one roll. Runs inside the join event: no world access here. */
    private void rollFreshSpawn(Mob mob, ServerLevel level) {
        MobSpawnType spawnType = mob.getSpawnType();
        if (spawnType == null || !ROLLED_SPAWNS.contains(spawnType) || !VariantConfig.enabled() || !MobVariantRules.levelAllows(level)) {
            return;
        }
        CorruptionContext ctx = CorruptionManager.context(level.getServer());
        if (ctx.paused() || !ctx.atLeast(CorruptionStage.UNDER_MIDDLE) || !MobVariantRules.canBecomeVariant(mob, true)) {
            return;
        }
        ServerPlayer anchor = nearestAnchor(level, mob.getX(), mob.getZ(), SPAWN_REACH);
        if (anchor == null || worldFull() || countNear(level, anchor.getX(), anchor.getZ()) >= areaCap(ctx.stage())
                || transitionNear(level, mob.getX(), mob.getZ(), SPAWN_REACH)) {
            return;
        }
        float chance = SPAWN_CHANCE[ctx.stage().tableIndex()] * (float) rate(ctx.stage()) * regionSpeed(ctx, mob.getBlockX(), mob.getBlockZ())
                * withinStage(ctx);
        if (random.nextFloat() >= chance) {
            return;
        }
        MobVariant variant = pick(ctx.stage());
        if (variant != MobVariant.NONE && set(mob, variant, variant == MobVariant.PARASITE ? 0.05F + random.nextFloat() * 0.3F : 1.0F, false)) {
            // set() leaves mobs that are still joining to the join itself: this one is.
            loaded.add(mob);
            NullMod.LOGGER.debug("NULL: a {} spawned as a {} variant near {}", typeName(mob), variant, anchor.getScoreboardName());
        }
    }

    /** How much variants are switched on in this stage: each kind's share times its frequency setting. */
    static double rate(CorruptionStage stage) {
        int i = stage.tableIndex();
        return VariantConfig.corruptedFrequency() * CORRUPTED_SHARE[i] + VariantConfig.parasiteFrequency() * PARASITE_SHARE[i];
    }

    /** Which kind the next automatic conversion makes, by the stage's shares and the frequency settings. */
    MobVariant pick(CorruptionStage stage) {
        int i = stage.tableIndex();
        double corrupted = VariantConfig.corruptedFrequency() * CORRUPTED_SHARE[i];
        double parasite = VariantConfig.parasiteFrequency() * PARASITE_SHARE[i];
        double total = corrupted + parasite;
        if (!(total > 0.0)) {
            return MobVariant.NONE;
        }
        return random.nextDouble() * total < parasite ? MobVariant.PARASITE : MobVariant.CORRUPTED;
    }

    /** 0.85 at the start of a stage to 1.15 at its end: variants grow more common through each stage too. */
    private static float withinStage(CorruptionContext ctx) {
        return 0.85F + 0.3F * Math.max(0.0F, Math.min(1.0F, ctx.stageProgress()));
    }

    /** 0.8 to 1.2: regions that fall apart faster see more variants. */
    private static float regionSpeed(CorruptionContext ctx, int x, int z) {
        return 0.7F + 0.3F * RegionCorruption.regionFactor(ctx.seed(), x, z);
    }

    /** A player variants can appear around: alive, playing, outside NULL and the field, and not being moved there. */
    static boolean anchorOk(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && MobVariantRules.levelAllows(player.level())
                && !NullDimensionManager.INSTANCE.inTransition(player) && !FieldManager.INSTANCE.inTransition(player);
    }

    @Nullable
    private static ServerPlayer nearestAnchor(ServerLevel level, double x, double z, double reach) {
        ServerPlayer best = null;
        double bestDistance = reach * reach;
        for (ServerPlayer player : level.players()) {
            if (!anchorOk(player)) {
                continue;
            }
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** True if a player within {@code reach} is being moved into or out of NULL or the field: nothing turns near them then. */
    private static boolean transitionNear(ServerLevel level, double x, double z, double reach) {
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz <= reach * reach
                    && (NullDimensionManager.INSTANCE.inTransition(player) || FieldManager.INSTANCE.inTransition(player))) {
                return true;
            }
        }
        return false;
    }

    /** The nearest ordinary mob around {@code from} that NULL may turn by itself, or null. */
    @Nullable
    private static Mob nearestCandidate(Mob from, double reach) {
        List<Mob> mobs = from.level().getEntitiesOfClass(Mob.class, from.getBoundingBox().inflate(reach),
                mob -> mob != from && variantOf(mob) == MobVariant.NONE && MobVariantRules.canBecomeVariant(mob, true));
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob mob : mobs) {
            double distance = mob.distanceToSqr(from);
            if (distance < bestDistance) {
                best = mob;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The {@code variant.parasite_burst} effect for every player close enough to see the host. */
    private void burst(ServerLevel level, Mob host, float growth) {
        BlockPos pos = host.blockPosition();
        long seed = random.nextLong();
        // data: the host's size in hundredths of a block, so the tentacles fit a chicken or a ravager.
        int size = Math.round(Math.max(host.getBbHeight(), host.getBbWidth()) * 100.0F);
        float intensity = Math.max(0.25F, growth);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(host) <= BURST_VIEW * BURST_VIEW) {
                NullNetworking.sendEffect(player, "variant.parasite_burst", seed, intensity, 30, Optional.of(pos), size, "");
            }
        }
        WorldMemory.remember(level.getServer(), "variant:parasite_left", null, typeName(host));
    }

    static String typeName(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }
}
