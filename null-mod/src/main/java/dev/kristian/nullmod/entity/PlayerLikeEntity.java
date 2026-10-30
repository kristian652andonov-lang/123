package dev.kristian.nullmod.entity;

import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Something that looks exactly like another player: the player model with a NULL skin, a
 * username above its head, standing, walking and running like a player. It is not one. It
 * never fights, never drops anything and makes no sound of its own. Hitting it, touching it
 * or (depending on what it is) getting close or staring at it makes it vanish: it is simply
 * gone, with at most a faint sound for the player who caused it.
 * <p>
 * The base class owns the shared mechanics: the synced identity (username, skin index,
 * flags, the player it is watching), the "who can see me" checks, the owner it was spawned
 * for, its lifetime and vanishing. Subclasses supply the behaviour in {@link #behaviourTick}.
 * Instances are never saved with the world (their entity types use {@code noSave()}); the
 * systems that spawn them keep track of them in memory.
 */
public abstract class PlayerLikeEntity extends PathfinderMob {
    /** Head and body are turned straight at the watched player. */
    public static final int FLAG_STARE = 1;
    /** No name tag at all. */
    public static final int FLAG_HIDE_NAME = 2;
    /** The name tag is partly unreadable. */
    public static final int FLAG_CORRUPT_NAME = 4;
    /** The body drops out for single frames now and then. */
    public static final int FLAG_FLICKER = 8;

    /** Roughly the part of the view that is on screen. */
    public static final double IN_VIEW_DOT = 0.62;
    /** Players further away than this never count as seeing it. */
    public static final double MAX_SIGHT = 128.0;
    private static final int VIEW_CHECK_INTERVAL = 4;

    private static final EntityDataAccessor<String> DATA_USERNAME =
            SynchedEntityData.defineId(PlayerLikeEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(PlayerLikeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> DATA_FLAGS =
            SynchedEntityData.defineId(PlayerLikeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_WATCHED =
            SynchedEntityData.defineId(PlayerLikeEntity.class, EntityDataSerializers.INT);

    /** Why something vanished; decides whether anyone hears it. */
    public enum VanishCause {
        APPROACHED(true), STARED(true), LOOKED_AT(true), HIT(true), TOUCHED(true), SCRIPT(true),
        LIFETIME(true), FAR(false), LOST(false), CLEANUP(false);

        private final boolean audible;

        VanishCause(boolean audible) {
            this.audible = audible;
        }

        public boolean audible() {
            return audible;
        }
    }

    @Nullable
    private UUID ownerId;
    private int lifetime = -1;
    private int life;
    private boolean vanished;
    private final Set<UUID> seenBy = new HashSet<>();
    @Nullable
    private Player observer;
    @Nullable
    private Player starer;
    private int observedChecks;
    private int unobservedChecks;
    private double despawnDistance = 96.0;

    protected PlayerLikeEntity(EntityType<? extends PlayerLikeEntity> type, Level level) {
        super(type, level);
        this.setSilent(true);
        this.setCanPickUpLoot(false);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_USERNAME, "");
        builder.define(DATA_SKIN, 0);
        builder.define(DATA_FLAGS, (byte) 0);
        builder.define(DATA_WATCHED, -1);
    }

    // ------------------------------------------------------------------ identity

    public String username() {
        return this.getEntityData().get(DATA_USERNAME);
    }

    public int skinIndex() {
        return SkinSet.sanitize(this.getEntityData().get(DATA_SKIN));
    }

    public int flags() {
        return this.getEntityData().get(DATA_FLAGS) & 0xFF;
    }

    public boolean hasFlag(int flag) {
        return (flags() & flag) != 0;
    }

    /** Network id of the player this one is staring at, or -1. */
    public int watchedId() {
        return this.getEntityData().get(DATA_WATCHED);
    }

    public void setIdentity(String username, int skin, int flags) {
        String name = username == null ? "" : username;
        this.getEntityData().set(DATA_USERNAME, name.length() > 32 ? name.substring(0, 32) : name);
        this.getEntityData().set(DATA_SKIN, SkinSet.sanitize(skin));
        this.getEntityData().set(DATA_FLAGS, (byte) flags);
    }

    public void setFlag(int flag, boolean on) {
        int current = flags();
        int next = on ? current | flag : current & ~flag;
        if (next != current) {
            this.getEntityData().set(DATA_FLAGS, (byte) next);
        }
    }

    protected void setWatched(@Nullable Entity entity) {
        int id = entity == null ? -1 : entity.getId();
        if (watchedId() != id) {
            this.getEntityData().set(DATA_WATCHED, id);
        }
    }

    @Override
    public Component getName() {
        if (this.hasCustomName()) {
            return super.getName();
        }
        String name = username();
        return name.isEmpty() ? super.getName() : Component.literal(name);
    }

    // ------------------------------------------------------------------ ownership and lifetime

    /** The player this one was spawned for. Others can still see it and make it vanish. */
    public void setOwner(@Nullable Player owner) {
        this.ownerId = owner == null ? null : owner.getUUID();
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    /** The owner if they are online and in this dimension. */
    @Nullable
    public ServerPlayer owner() {
        if (ownerId == null || !(this.level() instanceof ServerLevel level)) {
            return null;
        }
        Player player = level.getPlayerByUUID(ownerId);
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    /** Ticks until it vanishes on its own; negative means it stays until something else happens. */
    public void setLifetime(int ticks) {
        this.lifetime = ticks;
        this.life = 0;
    }

    public int lifetime() {
        return lifetime;
    }

    public int life() {
        return life;
    }

    public void setDespawnDistance(double blocks) {
        this.despawnDistance = Math.max(16.0, blocks);
    }

    public boolean hasVanished() {
        return vanished || this.isRemoved();
    }

    /**
     * True while it stands where entities really tick: the same two tests ServerLevel#tick makes,
     * a chunk at entity-ticking level (view distance) that is also within the simulation distance.
     * Elsewhere it neither behaves nor runs out its lifetime, so the systems that spawned it send it away.
     */
    public boolean inTickingChunk() {
        if (!(this.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = this.blockPosition();
        return level.isPositionEntityTicking(pos) && level.isNaturalSpawningAllowed(pos);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && !this.isRemoved() && username().isEmpty()) {
            // Spawned by a command without an identity: give it one like any other.
            setIdentity(UsernameGenerator.random(this.random), SkinSet.weighted(this.random, 30, 10, 25, 35), flags());
        }
    }

    @Override
    protected final void customServerAiStep() {
        super.customServerAiStep();
        if (vanished || this.isRemoved()) {
            return;
        }
        life++;
        ServerPlayer owner = owner();
        if (ownerId != null) {
            if (owner == null || !owner.isAlive()) {
                vanish(null, VanishCause.LOST);
                return;
            }
            if (this.distanceToSqr(owner) > despawnDistance * despawnDistance) {
                vanish(null, VanishCause.FAR);
                return;
            }
        }
        if ((this.tickCount + this.getId()) % VIEW_CHECK_INTERVAL == 0) {
            updateObservation(owner);
        }
        if (lifetime >= 0 && life > lifetime) {
            vanish(observer instanceof ServerPlayer seen ? seen : null, VanishCause.LIFETIME);
            return;
        }
        behaviourTick(owner);
    }

    /**
     * Runs every server tick after the shared checks. {@link #observer()}, {@link #starer()}
     * and the observation counters are refreshed every few ticks before this is called.
     *
     * @param owner the player it was spawned for, or null for ones placed by commands or tests
     */
    protected abstract void behaviourTick(@Nullable ServerPlayer owner);

    /** Prefix for {@link WorldMemory} keys, e.g. {@code "visitor"} gives {@code "visitor:seen"}. */
    protected abstract String memoryKey();

    /** Called once when it vanishes, before it is removed. */
    protected void onVanish(@Nullable ServerPlayer witness, VanishCause cause) {
    }

    // ------------------------------------------------------------------ being seen

    private void updateObservation(@Nullable ServerPlayer owner) {
        Player seen = null;
        Player staring = null;
        for (Player player : this.level().players()) {
            if (!isObserverCandidate(player) || this.distanceToSqr(player) > MAX_SIGHT * MAX_SIGHT) {
                continue;
            }
            if (!canSee(player, IN_VIEW_DOT)) {
                continue;
            }
            if (seen == null || player == owner) {
                seen = player;
            }
            // The view is already known to be clear; the stare test is just a narrower cone.
            if (inStareCone(player) && (staring == null || player == owner)) {
                staring = player;
            }
            if (player instanceof ServerPlayer serverPlayer && seenBy.add(player.getUUID())) {
                WorldMemory.remember(serverPlayer.server, memoryKey() + ":seen", player.getUUID(), username());
                onFirstSeen(serverPlayer);
            }
        }
        this.observer = seen;
        this.starer = staring;
        if (seen != null) {
            observedChecks++;
            unobservedChecks = 0;
        } else {
            unobservedChecks++;
            observedChecks = 0;
        }
    }

    /** Called the first time a player actually has it in view. */
    protected void onFirstSeen(ServerPlayer player) {
    }

    /** A player whose view is on it right now (refreshed every few ticks), preferring the owner. */
    @Nullable
    public Player observer() {
        return observer;
    }

    /** A player looking straight at it right now, preferring the owner. */
    @Nullable
    public Player starer() {
        return starer;
    }

    /** Consecutive view checks (one every 4 ticks) in which somebody saw it. */
    public int observedChecks() {
        return observedChecks;
    }

    /** Consecutive view checks in which nobody saw it. */
    public int unobservedChecks() {
        return unobservedChecks;
    }

    public static boolean isObserverCandidate(Player player) {
        return player.isAlive() && !player.isSpectator();
    }

    /** True if {@code player} has this entity within the given view cone and nothing blocks the view. */
    public boolean canSee(Player player, double coneDot) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 body = new Vec3(this.getX(), this.getY(0.6), this.getZ());
        Vec3 toBody = body.subtract(eye);
        double distance = toBody.length();
        if (distance < 1.0E-3) {
            return true;
        }
        if (look.dot(toBody.scale(1.0 / distance)) < coneDot) {
            return false;
        }
        return clearLine(player, eye, new Vec3(this.getX(), this.getEyeY(), this.getZ())) || clearLine(player, eye, body);
    }

    /** Looking straight at it (a narrow cone that shrinks with distance), with a clear view. */
    public boolean isStaredAtBy(Player player) {
        return inStareCone(player) && canSee(player, IN_VIEW_DOT);
    }

    /** True if the player's view points straight at it. Ignores walls. */
    public boolean inStareCone(Player player) {
        Vec3 to = new Vec3(this.getX(), this.getY(0.75), this.getZ()).subtract(player.getEyePosition());
        double distance = Math.max(1.0, to.length());
        double threshold = Math.max(0.985, 1.0 - 0.05 / distance);
        return to.lengthSqr() > 1.0E-6 && player.getViewVector(1.0F).dot(to.normalize()) > threshold;
    }

    private boolean clearLine(Player player, Vec3 from, Vec3 to) {
        return this.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    /** The closest player that can make it vanish by coming near, or null. */
    @Nullable
    protected Player nearestObserver(double maxDistance) {
        Player best = null;
        double bestSqr = maxDistance * maxDistance;
        for (Player player : this.level().players()) {
            if (!isObserverCandidate(player)) {
                continue;
            }
            double d = this.distanceToSqr(player);
            if (d < bestSqr) {
                bestSqr = d;
                best = player;
            }
        }
        return best;
    }

    /** Turns head and body to look straight at an entity's eyes (server side). */
    protected void stareAt(@Nullable Entity target) {
        if (target == null) {
            setFlag(FLAG_STARE, false);
            setWatched(null);
            return;
        }
        setFlag(FLAG_STARE, true);
        setWatched(target);
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 360.0F, 360.0F);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
    }

    /** Stops staring; head and body go back to following its movement. */
    protected void stopStaring() {
        setFlag(FLAG_STARE, false);
        setWatched(null);
    }

    @Override
    protected float tickHeadTurn(float yBodyRotTarget, float animStep) {
        if (hasFlag(FLAG_STARE)) {
            // Both sides: the body turns with the head instead of lagging behind it.
            this.yBodyRot = this.yHeadRot;
            return animStep;
        }
        return super.tickHeadTurn(yBodyRotTarget, animStep);
    }

    // ------------------------------------------------------------------ vanishing

    /**
     * Removes it. The witness (if any, and if the cause is audible and they are close) hears a
     * faint sound through their own audio settings; nobody else notices anything.
     */
    public final void vanish(@Nullable ServerPlayer witness, VanishCause cause) {
        if (vanished || this.isRemoved() || this.level().isClientSide()) {
            return;
        }
        vanished = true;
        try {
            onVanish(witness, cause);
        } finally {
            if (witness != null && cause.audible() && witness.level() == this.level() && witness.distanceToSqr(this) < 48.0 * 48.0) {
                sendSound(witness, vanishSound(cause), this.blockPosition().above(),
                        0.32F + this.random.nextFloat() * 0.12F, 0.9F + this.random.nextFloat() * 0.2F);
                WorldMemory.remember(witness.server, memoryKey() + ":vanished", witness.getUUID(), username());
            }
            this.discard();
        }
    }

    /** The faint sound the witness hears when it vanishes. */
    protected SoundEvent vanishSound(VanishCause cause) {
        return NullSounds.VISITOR_VANISH.get();
    }

    /**
     * Plays one of NULL's sounds for a single player through their client's audio corruption
     * system (so their audio settings apply). Silently does nothing for clients without NULL.
     */
    public static void sendSound(ServerPlayer player, SoundEvent sound, @Nullable BlockPos pos, float volume, float pitch) {
        if (player.connection == null || !player.connection.hasChannel(ClientEffectPayload.TYPE)) {
            return;
        }
        NullNetworking.sendEffect(player, "audio.sound", player.getRandom().nextLong(), volume, 40,
                Optional.ofNullable(pos), Math.round(pitch * 100.0F), sound.getLocation().toString());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide() || hasVanished()) {
            return false;
        }
        // Nothing hurts it. Anything that tries makes it go away.
        ServerPlayer witness = source.getEntity() instanceof ServerPlayer attacker ? attacker : owner();
        vanish(witness, VanishCause.HIT);
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            vanish(serverPlayer, VanishCause.TOUCHED);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    // ------------------------------------------------------------------ not a normal mob

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // The systems that spawn these despawn them.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** Never turns farmland back into dirt (VisitorModule also denies it every other kind of mob griefing). */
    @Override
    public boolean canTrample(BlockState state, BlockPos pos, float fallDistance) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return null;
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return null;
    }

    /** Visible as far as a real player would be on a high render distance. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double range = 128.0 * getViewScale();
        return distanceSqr < range * range;
    }

    // ------------------------------------------------------------------ saving (commands, /data)

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Username", username());
        tag.putInt("Skin", skinIndex());
        tag.putByte("LookFlags", (byte) flags());
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
        tag.putInt("Lifetime", lifetime);
        tag.putInt("Life", life);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        String name = tag.contains("Username", Tag.TAG_STRING) ? tag.getString("Username") : username();
        int skin = tag.contains("Skin", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Skin") : skinIndex();
        int lookFlags = tag.contains("LookFlags", Tag.TAG_ANY_NUMERIC) ? tag.getByte("LookFlags") & 0xFF : flags();
        setIdentity(name, skin, lookFlags);
        this.ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        if (tag.contains("Lifetime", Tag.TAG_ANY_NUMERIC)) {
            this.lifetime = tag.getInt("Lifetime");
        }
        if (tag.contains("Life", Tag.TAG_ANY_NUMERIC)) {
            this.life = tag.getInt("Life");
        }
    }
}
