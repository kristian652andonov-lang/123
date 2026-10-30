package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.WorldMemory;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A NULLROOT organism: a parasitic growth rooted in one face of one block. It never walks,
 * never fights in melee, drops nothing and gives no experience. Observers only turn toward
 * players; hunters slowly grow a tendril toward a player in range and grab whoever it touches
 * ({@link NullrootCaptures} owns the capture itself); a colony's central mass pulses and pulls
 * captured players into itself.
 * <p>
 * Everything a client needs to draw it is synced entity data: variant, the face it grows out of,
 * its behaviour state, the entity it is focused on (the watched or captured player), reach,
 * capture progress and growth. Its position is derived from the root block, the face and the
 * variant's size, and it snaps back there every tick, so nothing can push it around.
 * <p>
 * When no player is within {@value #NEAR_RANGE} blocks the server tick does almost nothing:
 * growth, a slow check every second, and the vanilla mob tick.
 */
public class NullrootEntity extends Mob {
    /** A hunter starts reaching for its target. */
    public static final byte EVENT_REACH = 100;
    /** The tendril caught someone. */
    public static final byte EVENT_GRAB = 101;
    /** It was hurt: the tendril snaps back and it twitches. */
    public static final byte EVENT_RECOIL = 102;
    /** A pulse runs through it. */
    public static final byte EVENT_PULSE = 103;
    /** A capture ended in death. */
    public static final byte EVENT_CONSUME = 104;
    /** A capture ended and the victim was let go. */
    public static final byte EVENT_RELEASE = 105;
    /** Client only: the vanilla damage event arrived (the hurt sound). */
    public static final byte EVENT_HURT = 106;
    /** Client only: the vanilla death event arrived (the death sound). */
    public static final byte EVENT_DEATH = 107;

    /** Ticks the death animation (sinking back into the block) lasts before removal. */
    public static final int DEATH_TICKS = 24;
    /** Ticks it takes to wither back into the block. */
    public static final int WITHER_TICKS = 50;
    /** Players closer than this keep it fully awake. */
    public static final double NEAR_RANGE = 24.0;
    /** A player this close counts as "somebody is around" for the despawn timer. */
    private static final double SEEN_RANGE = 48.0;
    /** Organisms that are not part of a colony go away after this long with nobody around. */
    private static final long DESPAWN_TICKS = 20L * 60L * 6L;
    /** How close the tendril tip has to get to a player's legs to take hold. */
    private static final double TOUCH = 0.5;

    private static final EntityDataAccessor<Byte> DATA_VARIANT =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Direction> DATA_FACE =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.DIRECTION);
    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_FOCUS =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_REACH =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_CAPTURE =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_GROWTH =
            SynchedEntityData.defineId(NullrootEntity.class, EntityDataSerializers.FLOAT);

    private static volatile NullrootClientHook clientHook = NullrootClientHook.NONE;

    @Nullable
    private BlockPos root;
    /** Root and face are known (set up by the manager, loaded, or found on the first tick). */
    private boolean attached;
    /** Summoned somewhere with nothing to grow out of: it stays, but never checks its root. */
    private boolean freeFloating;
    @Nullable
    private UUID colony;
    @Nullable
    private UUID owner;
    private boolean managed;
    private boolean forced;
    private long lastSeen = -1L;
    private float growthValue;
    private float reachLength;
    private int stateTicks;
    private int cooldown;
    private int losFailures;
    private boolean playerNear = true;
    private int pulseTimer = 40;
    @Nullable
    private NullrootAnimation animation;

    public NullrootEntity(EntityType<? extends NullrootEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, NullrootVariant.SMALL.maxHealth())
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    /** Installed once by the physical client; common code only ever calls the interface. */
    public static void setClientHook(NullrootClientHook hook) {
        clientHook = hook == null ? NullrootClientHook.NONE : hook;
    }

    /**
     * Creates an organism rooted in {@code root}, growing out of its {@code face}, ready to be
     * added to the level (it starts at size 0 and grows).
     */
    @Nullable
    public static NullrootEntity create(ServerLevel level, NullrootVariant variant, BlockPos root, Direction face,
                                        @Nullable UUID colony, @Nullable UUID owner, boolean managed, boolean forced) {
        NullrootEntity organism = NullrootModule.NULLROOT.get().create(level);
        if (organism == null) {
            return null;
        }
        organism.setup(variant, root, face, colony, owner, managed, forced);
        return organism;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, (byte) NullrootVariant.SMALL.ordinal());
        builder.define(DATA_FACE, Direction.UP);
        builder.define(DATA_STATE, (byte) NullrootState.IDLE.ordinal());
        builder.define(DATA_FOCUS, -1);
        builder.define(DATA_REACH, 0.0F);
        builder.define(DATA_CAPTURE, 0.0F);
        builder.define(DATA_GROWTH, 0.0F);
    }

    // ------------------------------------------------------------------ set-up

    /** Everything a new organism needs before it is added to the level. */
    public void setup(NullrootVariant variant, BlockPos root, Direction face, @Nullable UUID colony, @Nullable UUID owner,
                      boolean managed, boolean forced) {
        this.getEntityData().set(DATA_FACE, face);
        setVariantInternal(variant, true);
        this.root = root.immutable();
        this.attached = true;
        this.freeFloating = false;
        this.colony = colony;
        this.owner = owner;
        this.managed = managed;
        this.forced = forced;
        this.growthValue = 0.0F;
        this.getEntityData().set(DATA_GROWTH, 0.0F);
        Vec3 pos = anchoredPosition();
        this.moveTo(pos.x, pos.y, pos.z, this.random.nextFloat() * 360.0F, 0.0F);
    }

    private void setVariantInternal(NullrootVariant variant, boolean resetHealth) {
        this.getEntityData().set(DATA_VARIANT, (byte) variant.ordinal());
        AttributeInstance health = this.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(variant.maxHealth());
        }
        if (resetHealth) {
            this.setHealth(this.getMaxHealth());
        } else if (this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }
        this.refreshDimensions();
    }

    /** Instantly sets how far it has grown (tests, commands); 1 is full size. */
    public void setGrowth(float growth) {
        this.growthValue = Mth.clamp(growth, 0.0F, 1.0F);
        this.getEntityData().set(DATA_GROWTH, this.growthValue);
    }

    // ------------------------------------------------------------------ synced state

    public NullrootVariant variant() {
        return NullrootVariant.byIndex(this.getEntityData().get(DATA_VARIANT));
    }

    /** The face of the root block it grows out of: the direction it grows in. */
    public Direction face() {
        return this.getEntityData().get(DATA_FACE);
    }

    public NullrootState state() {
        return NullrootState.byIndex(this.getEntityData().get(DATA_STATE));
    }

    /** Network id of the entity it watches, reaches for or holds; -1 for none. */
    public int focusId() {
        return this.getEntityData().get(DATA_FOCUS);
    }

    /** 0..1 how far the hunting tendril is out (of the variant's maximum reach). */
    public float reach() {
        return this.getEntityData().get(DATA_REACH);
    }

    /** 0..1 progress of the capture it is making or anchoring. */
    public float captureProgress() {
        return this.getEntityData().get(DATA_CAPTURE);
    }

    /** 0..1 how far it has grown out of its block (shrinks again while withering). */
    public float growth() {
        return this.getEntityData().get(DATA_GROWTH);
    }

    @Nullable
    public Entity focus() {
        int id = focusId();
        return id < 0 ? null : this.level().getEntity(id);
    }

    void setState(NullrootState state) {
        if (state() != state) {
            this.getEntityData().set(DATA_STATE, (byte) state.ordinal());
            this.stateTicks = 0;
        }
    }

    void setFocus(@Nullable Entity entity) {
        int id = entity == null ? -1 : entity.getId();
        if (focusId() != id) {
            this.getEntityData().set(DATA_FOCUS, id);
        }
    }

    void setReachLength(float length) {
        float max = Math.max(0.01F, variant().maxReach());
        this.reachLength = Math.max(0.0F, length);
        float synced = Mth.clamp(this.reachLength / max, 0.0F, 1.0F);
        float current = reach();
        if (Math.abs(synced - current) >= 0.01F || (synced == 0.0F) != (current == 0.0F) || (synced >= 1.0F && current < 1.0F)) {
            this.getEntityData().set(DATA_REACH, synced);
        }
    }

    void setCaptureProgress(float progress) {
        float value = Mth.clamp(progress, 0.0F, 1.0F);
        float current = captureProgress();
        if (Math.abs(value - current) >= 0.01F || (value == 0.0F) != (current == 0.0F) || (value >= 1.0F && current < 1.0F)) {
            this.getEntityData().set(DATA_CAPTURE, value);
        }
    }

    private void syncGrowth() {
        if (growth() != growthValue) {
            this.getEntityData().set(DATA_GROWTH, growthValue);
        }
    }

    /** Client-side animation state; created on first use (the client only). */
    public NullrootAnimation animation() {
        NullrootAnimation anim = this.animation;
        if (anim == null) {
            anim = new NullrootAnimation();
            anim.seed = this.getUUID().getLeastSignificantBits() ^ this.getUUID().getMostSignificantBits();
            this.animation = anim;
        }
        return anim;
    }

    // ------------------------------------------------------------------ bookkeeping

    @Nullable
    public UUID colonyId() {
        return colony;
    }

    @Nullable
    public UUID ownerId() {
        return owner;
    }

    /** Makes it a member (or, for a centre, the founder) of a colony. Colony members never despawn on their own. */
    void joinColony(@Nullable UUID colony) {
        this.colony = colony;
    }

    /** Grown by NULL (not summoned): subject to the stage, despawn and root rules. */
    public boolean isManaged() {
        return managed;
    }

    /** Grown by a forced event: not removed when the world is below Stage 3. */
    public boolean isForced() {
        return forced;
    }

    public boolean isWithering() {
        return state() == NullrootState.WITHERING;
    }

    /** True if both belong to the same colony (a centre belongs to the colony named after it). */
    public boolean sameColony(NullrootEntity other) {
        UUID mine = colony != null ? colony : (variant() == NullrootVariant.CENTRAL ? this.getUUID() : null);
        UUID theirs = other.colony != null ? other.colony : (other.variant() == NullrootVariant.CENTRAL ? other.getUUID() : null);
        return mine != null && mine.equals(theirs);
    }

    /** Game time a player was last nearby (server). */
    public long lastSeen() {
        return lastSeen;
    }

    // ------------------------------------------------------------------ geometry

    /** The block it grows out of. */
    public BlockPos rootPos() {
        if (root != null) {
            return root;
        }
        // Not known here (clients, or before the first tick): it is behind the face it grows out of.
        Direction f = face();
        Vec3 attach = this.position().add(attachOffset(f, variant()));
        return BlockPos.containing(attach.x - f.getStepX() * 0.5, attach.y - f.getStepY() * 0.5, attach.z - f.getStepZ() * 0.5);
    }

    /**
     * From an organism's position (the bottom centre of its box) to the centre of the face it
     * grows out of. Clients draw the model from there; it needs nothing but the synced face and
     * variant.
     */
    public static Vec3 attachOffset(Direction face, NullrootVariant variant) {
        return switch (face) {
            case UP -> Vec3.ZERO;
            case DOWN -> new Vec3(0.0, variant.height(), 0.0);
            default -> new Vec3(-face.getStepX() * variant.width() * 0.5, variant.height() * 0.5, -face.getStepZ() * variant.width() * 0.5);
        };
    }

    /** Centre of the block face it grows out of. */
    public Vec3 attachPoint() {
        return attachPoint(rootPos(), face());
    }

    /** Where its position belongs for its root, face and size (the bottom centre of its box). */
    public Vec3 anchoredPosition() {
        return anchoredPosition(rootPos(), face(), variant());
    }

    /** Centre of the {@code face} side of the block at {@code root}. */
    public static Vec3 attachPoint(BlockPos root, Direction face) {
        return new Vec3(root.getX() + 0.5 + face.getStepX() * 0.5, root.getY() + 0.5 + face.getStepY() * 0.5,
                root.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /**
     * The position (bottom centre of the box) of an organism of this variant growing out of that
     * face: standing on floors, hanging from ceilings, sticking out sideways from walls.
     */
    public static Vec3 anchoredPosition(BlockPos root, Direction face, NullrootVariant variant) {
        Vec3 a = attachPoint(root, face);
        return switch (face) {
            case UP -> a;
            case DOWN -> a.subtract(0.0, variant.height(), 0.0);
            default -> new Vec3(a.x + face.getStepX() * variant.width() * 0.5, a.y - variant.height() * 0.5,
                    a.z + face.getStepZ() * variant.width() * 0.5);
        };
    }

    /** The box an organism of this variant would take up there. */
    public static AABB boxAt(BlockPos root, Direction face, NullrootVariant variant) {
        return variant.dimensions().makeBoundingBox(anchoredPosition(root, face, variant));
    }

    /** Where its tendrils come out: the face centre moved out along the face by the variant's root offset. */
    public Vec3 rootPoint() {
        Vec3 a = attachPoint();
        Direction f = face();
        double off = variant().rootOffset();
        return a.add(f.getStepX() * off, f.getStepY() * off, f.getStepZ() * off);
    }

    /** The part of a player a tendril goes for: the legs. */
    public static Vec3 legsOf(Entity entity) {
        return entity.position().add(0.0, Math.min(0.5, entity.getBbHeight() * 0.3), 0.0);
    }

    /** True if a captured player dangles from it rather than being dragged along the ground. */
    public boolean liftsCaptives() {
        return face() == Direction.DOWN;
    }

    /** Where a player it holds is dragged to (feet position). */
    public Vec3 holdPoint(Player player) {
        Vec3 a = attachPoint();
        Direction f = face();
        if (variant() == NullrootVariant.CENTRAL && f == Direction.UP) {
            // Into the middle of the mass.
            return new Vec3(this.getX(), a.y, this.getZ());
        }
        switch (f) {
            case UP -> {
                double dx = player.getX() - this.getX();
                double dz = player.getZ() - this.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1.0E-3) {
                    dx = 1.0;
                    dz = 0.0;
                    len = 1.0;
                }
                double r = this.getBbWidth() * 0.5 + 0.3;
                return new Vec3(this.getX() + dx / len * r, a.y, this.getZ() + dz / len * r);
            }
            case DOWN -> {
                return new Vec3(this.getX(), this.getY() - player.getBbHeight() * 0.85, this.getZ());
            }
            default -> {
                double out = this.getBbWidth() + 0.3;
                return new Vec3(a.x + f.getStepX() * out, a.y - 1.0, a.z + f.getStepZ() * out);
            }
        }
    }

    /** The organism's box grows from the face it is rooted in; per variant. */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return variant().dimensions();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_VARIANT.equals(key)) {
            this.refreshDimensions();
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            clientHook.tick(this);
        } else if (!this.isRemoved() && !this.isDeadOrDying() && this.level() instanceof ServerLevel level) {
            serverTick(level);
        }
    }

    private void serverTick(ServerLevel level) {
        if (!attached) {
            autoAttach(level);
        }
        keepAnchored();
        if (state() == NullrootState.WITHERING) {
            witherTick();
            return;
        }
        if (growthValue < 1.0F) {
            growthValue = Math.min(1.0F, growthValue + 1.0F / variant().growTicks());
            if (growthValue >= 1.0F || this.tickCount % 2 == 0) {
                syncGrowth();
            }
        }
        if ((this.tickCount + this.getId()) % 20 == 0 && slowChecks(level)) {
            return;
        }
        boolean busy = state() != NullrootState.IDLE || NullrootCaptures.heldBy(this) != null;
        if (!playerNear && !busy) {
            return;
        }
        stateTicks++;
        if (cooldown > 0) {
            cooldown--;
        }
        behave(level);
        pulse(level);
    }

    /** Never moves: whatever pushed it (pistons, explosions, commands), it goes back to its root. */
    private void keepAnchored() {
        Vec3 anchor = anchoredPosition();
        if (this.position().distanceToSqr(anchor) > 1.0E-6) {
            this.setPos(anchor.x, anchor.y, anchor.z);
        }
        if (this.getDeltaMovement().lengthSqr() > 0.0) {
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Once a second: is its root still there, is anyone around, and (for organisms NULL grew)
     * the stage and despawn rules. Returns true if it started withering or was removed.
     */
    private boolean slowChecks(ServerLevel level) {
        if (attached && !freeFloating && !rootIntact(level)) {
            startWithering();
            return true;
        }
        long now = level.getGameTime();
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.isAlive()) {
                nearest = Math.min(nearest, this.distanceToSqr(player));
            }
        }
        double near = Math.max(NEAR_RANGE, variant().detectRange() + 4.0);
        playerNear = nearest <= near * near;
        if (nearest <= SEEN_RANGE * SEEN_RANGE || lastSeen < 0L) {
            lastSeen = now;
        }
        if (managed) {
            CorruptionContext ctx = CorruptionManager.context(level.getServer());
            if (!forced && !ctx.atLeast(CorruptionStage.MIDDLE)) {
                startWithering();
                return true;
            }
            if ((colony == null || forced) && now - lastSeen > DESPAWN_TICKS && NullrootCaptures.heldBy(this) == null) {
                // Nobody has been anywhere near for minutes: it is simply gone when they come back.
                this.discard();
                return true;
            }
        }
        return false;
    }

    private boolean rootIntact(ServerLevel level) {
        BlockPos r = rootPos();
        if (!level.isLoaded(r)) {
            return true;
        }
        BlockState state = level.getBlockState(r);
        return !state.isAir() && state.isFaceSturdy(level, r, face());
    }

    /**
     * Summoned without a root: grow out of the nearest solid face around where it was put,
     * preferring the face its variant grows from.
     */
    private void autoAttach(ServerLevel level) {
        attached = true;
        BlockPos at = BlockPos.containing(this.getX(), this.getY() + 0.05, this.getZ());
        Direction[] order = switch (variant()) {
            case CEILING -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case WALL -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
            default -> new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        };
        for (Direction face : order) {
            BlockPos candidate = at.relative(face.getOpposite());
            if (level.isLoaded(candidate) && level.getBlockState(candidate).isFaceSturdy(level, candidate, face)) {
                this.root = candidate.immutable();
                this.getEntityData().set(DATA_FACE, face);
                return;
            }
        }
        this.root = at.below().immutable();
        this.getEntityData().set(DATA_FACE, Direction.UP);
        this.freeFloating = true;
    }

    private void behave(ServerLevel level) {
        NullrootCaptures.Capture capture = NullrootCaptures.grabbedBy(this);
        if (capture != null) {
            NullrootCaptures.tick(capture);
            if (NullrootCaptures.grabbedBy(this) == capture) {
                setReachLength((float) rootPoint().distanceTo(legsOf(capture.player())));
                return;
            }
        }
        switch (state()) {
            case IDLE, WATCHING -> watch(level);
            case REACHING -> reachTick(level);
            case GRABBING -> startRetracting(80);
            case RETRACTING -> retractTick(2.5F);
            case RECOIL -> recoilTick();
            case ANCHORING -> {
                if (NullrootCaptures.heldBy(this) == null) {
                    onAnchorEnded();
                }
            }
            case WITHERING -> {
            }
        }
    }

    /** Every half second: turn toward the closest player in range, and hunters decide to reach. */
    private void watch(ServerLevel level) {
        if ((this.tickCount + this.getId()) % 10 != 0) {
            return;
        }
        NullrootVariant v = variant();
        double range = v.detectRange();
        ServerPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double d = this.distanceToSqr(player);
            if (d > range * range) {
                continue;
            }
            double score = v.reaches() && NullrootCaptures.canCapture(player) ? d * 0.5 : d;
            if (score < bestScore) {
                bestScore = score;
                best = player;
            }
        }
        setFocus(best);
        setState(best == null ? NullrootState.IDLE : NullrootState.WATCHING);
        if (best == null || !v.reaches() || growthValue < 1.0F || cooldown > 0
                || !NullrootCaptures.canCapture(best) || NullrootCaptures.inGrace(best)) {
            return;
        }
        double distance = rootPoint().distanceTo(legsOf(best));
        if (distance <= v.maxReach() + 0.75 && canReach(best)) {
            losFailures = 0;
            setState(NullrootState.REACHING);
            level.broadcastEntityEvent(this, EVENT_REACH);
        }
    }

    private void reachTick(ServerLevel level) {
        ServerPlayer target = focusedPlayer();
        NullrootVariant v = variant();
        if (target == null || !NullrootCaptures.canCapture(target) || NullrootCaptures.inGrace(target)) {
            startRetracting(40);
            return;
        }
        Vec3 legs = legsOf(target);
        double distance = rootPoint().distanceTo(legs);
        if (distance > v.maxReach() + 1.5) {
            startRetracting(40);
            return;
        }
        if (stateTicks % 5 == 0) {
            if (canReach(target)) {
                losFailures = 0;
            } else if (++losFailures >= 2) {
                startRetracting(60);
                return;
            }
        }
        setReachLength(Math.min(v.maxReach(), reachLength + v.reachSpeed() * reachBoost(level)));
        if (reachLength >= distance - TOUCH && !NullrootCaptures.start(this, target)) {
            startRetracting(60);
        }
    }

    /** Later stages reach a little faster. */
    private static float reachBoost(ServerLevel level) {
        CorruptionStage stage = CorruptionManager.context(level.getServer()).stage();
        return stage == CorruptionStage.NULL ? 1.3F : stage == CorruptionStage.ABOVE_MIDDLE ? 1.15F : 1.0F;
    }

    private void startRetracting(int cooldownTicks) {
        setState(NullrootState.RETRACTING);
        cooldown = Math.max(cooldown, cooldownTicks);
    }

    private void retractTick(float speedFactor) {
        setReachLength(reachLength - variant().reachSpeed() * speedFactor - 0.02F);
        if (reachLength <= 0.0F) {
            setReachLength(0.0F);
            setState(focusId() >= 0 ? NullrootState.WATCHING : NullrootState.IDLE);
        }
    }

    private void recoilTick() {
        setReachLength(reachLength - 0.5F);
        if (stateTicks > 30) {
            setReachLength(0.0F);
            setState(focusId() >= 0 ? NullrootState.WATCHING : NullrootState.IDLE);
        }
    }

    /** Pulses run through it now and then, fast while it holds somebody; the centre of a colony pulses often. */
    private void pulse(ServerLevel level) {
        if (!playerNear || growthValue < 1.0F || --pulseTimer > 0) {
            return;
        }
        NullrootState state = state();
        boolean holding = state == NullrootState.ANCHORING || state == NullrootState.GRABBING;
        if (holding) {
            pulseTimer = 16 + this.random.nextInt(10);
        } else if (variant() == NullrootVariant.CENTRAL) {
            pulseTimer = 50 + this.random.nextInt(50);
        } else {
            pulseTimer = 140 + this.random.nextInt(180);
        }
        level.broadcastEntityEvent(this, EVENT_PULSE);
    }

    /** Makes everyone near it see a pulse right now (events). */
    public void pulseNow() {
        if (!this.level().isClientSide() && isAlive() && !isWithering()) {
            this.level().broadcastEntityEvent(this, EVENT_PULSE);
            pulseTimer = Math.max(pulseTimer, 30);
        }
    }

    /** True if nothing solid is between where its tendril comes out and the player. */
    public boolean canReach(Entity target) {
        Vec3 from = rootPoint();
        Level level = this.level();
        if (level.clip(new ClipContext(from, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS) {
            return true;
        }
        return level.clip(new ClipContext(from, legsOf(target), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    @Nullable
    private ServerPlayer focusedPlayer() {
        Entity entity = focus();
        return entity instanceof ServerPlayer player && player.level() == this.level() ? player : null;
    }

    // ------------------------------------------------------------------ withering

    /** Shrinks back into its block and disappears. Lets go of anyone it holds. */
    public void startWithering() {
        if (this.level().isClientSide() || isWithering()) {
            return;
        }
        NullrootCaptures.releaseFor(this, NullrootCaptures.Reason.ORGANISM_GONE);
        setState(NullrootState.WITHERING);
        setFocus(null);
        setReachLength(0.0F);
        setCaptureProgress(0.0F);
        if (!playerNear) {
            // Nobody would see it go.
            this.discard();
        }
    }

    private void witherTick() {
        growthValue = Math.max(0.0F, growthValue - 1.0F / WITHER_TICKS);
        if (growthValue <= 0.0F) {
            this.discard();
            return;
        }
        if (this.tickCount % 2 == 0) {
            syncGrowth();
        }
    }

    // ------------------------------------------------------------------ captures (called by NullrootCaptures)

    int captureDuration() {
        return Mth.clamp(variant().captureTicks() - 20 + this.random.nextInt(41), 160, 240);
    }

    void onCaptureStarted(ServerPlayer player) {
        setFocus(player);
        setState(NullrootState.GRABBING);
        setCaptureProgress(0.0F);
        cooldown = 0;
        pulseTimer = 6;
        this.level().broadcastEntityEvent(this, EVENT_GRAB);
    }

    void onCaptureEnded(NullrootCaptures.Reason reason) {
        setCaptureProgress(0.0F);
        if (isWithering() || !isAlive()) {
            return;
        }
        if (reason == NullrootCaptures.Reason.DAMAGE) {
            setState(NullrootState.RECOIL);
            cooldown = Math.max(cooldown, 140);
            this.level().broadcastEntityEvent(this, EVENT_RECOIL);
        } else {
            startRetracting(100);
            if (reason != NullrootCaptures.Reason.CONSUMED) {
                this.level().broadcastEntityEvent(this, EVENT_RELEASE);
            }
        }
    }

    void onAnchorStarted(ServerPlayer player) {
        if (state() == NullrootState.GRABBING || isWithering()) {
            return;
        }
        setFocus(player);
        setState(NullrootState.ANCHORING);
        setReachLength(0.0F);
        pulseTimer = 4;
    }

    void onAnchorEnded() {
        setCaptureProgress(0.0F);
        if (state() == NullrootState.ANCHORING) {
            setFocus(null);
            setState(NullrootState.IDLE);
        }
    }

    void consumeEffect() {
        this.level().broadcastEntityEvent(this, EVENT_CONSUME);
    }

    // ------------------------------------------------------------------ damage and death

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isWithering() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide() && this.isAlive()) {
            NullrootCaptures.onOrganismDamaged(this, amount);
            NullrootState state = state();
            if (state == NullrootState.IDLE || state == NullrootState.WATCHING || state == NullrootState.REACHING
                    || state == NullrootState.RETRACTING) {
                setState(NullrootState.RECOIL);
                cooldown = Math.max(cooldown, 100);
            }
            this.level().broadcastEntityEvent(this, EVENT_RECOIL);
        }
        return hurt;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.CRAMMING)
                || source.is(DamageTypes.FLY_INTO_WALL)
                || source.is(NullrootDamage.NULLROOT);
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide()) {
            // Killing what holds you is how you get out.
            NullrootCaptures.releaseFor(this, NullrootCaptures.Reason.DAMAGE);
            if (source.getEntity() instanceof ServerPlayer killer) {
                WorldMemory.remember(killer.server, "nullroot:destroyed", killer.getUUID(), variant().id());
            }
        }
        super.die(source);
    }

    /** Sinks back into its block instead of falling over; no particles. */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime >= DEATH_TICKS && !this.level().isClientSide() && !this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide()) {
            NullrootCaptures.releaseFor(this, NullrootCaptures.Reason.ORGANISM_GONE);
        }
        super.remove(reason);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!this.level().isClientSide()) {
            NullrootManager.INSTANCE.track(this);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (this.level().isClientSide()) {
            clientHook.removed(this);
        } else {
            NullrootCaptures.releaseFor(this, NullrootCaptures.Reason.ORGANISM_GONE);
            NullrootManager.INSTANCE.untrack(this);
        }
    }

    // ------------------------------------------------------------------ client events

    @Override
    public void handleEntityEvent(byte id) {
        if (id >= EVENT_REACH && id <= EVENT_RELEASE) {
            clientHook.handleEvent(this, id);
            return;
        }
        if (id == EntityEvent.DEATH) {
            clientHook.handleEvent(this, EVENT_DEATH);
        }
        super.handleEntityEvent(id);
    }

    @Override
    public void handleDamageEvent(DamageSource source) {
        super.handleDamageEvent(source);
        clientHook.handleEvent(this, EVENT_HURT);
    }

    // ------------------------------------------------------------------ rendering helpers (both sides)

    /** While a tendril is out its target counts as part of it, so the tendril is drawn when only the victim is in view. */
    @Override
    public AABB getBoundingBoxForCulling() {
        AABB box = super.getBoundingBoxForCulling();
        NullrootState state = state();
        if (state.tendrilOut() || state.holding()) {
            Entity target = focus();
            if (target != null) {
                box = box.minmax(target.getBoundingBox());
            }
        }
        return box;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double size = Math.max(1.0, this.getBoundingBox().getSize()) * 64.0 * getViewScale();
        if (state().holding()) {
            size = Math.max(size, 128.0 * getViewScale());
        }
        return distanceSqr < size * size;
    }

    @Override
    public boolean isPickable() {
        return super.isPickable() && growth() >= 0.3F && !isWithering();
    }

    // ------------------------------------------------------------------ not a normal mob

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // NullrootManager and the organism's own checks decide when it goes.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
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
    public boolean isIgnoringBlockTriggers() {
        return true;
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

    // ------------------------------------------------------------------ saving

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Variant", variant().id());
        tag.putString("Face", face().getSerializedName());
        if (attached && root != null) {
            tag.putLong("Root", root.asLong());
        }
        tag.putBoolean("FreeFloating", freeFloating);
        tag.putFloat("Growth", growthValue);
        if (colony != null) {
            tag.putUUID("Colony", colony);
        }
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putBoolean("Managed", managed);
        tag.putBoolean("Forced", forced);
        tag.putLong("LastSeen", lastSeen);
        tag.putBoolean("Withering", isWithering());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Face", Tag.TAG_STRING)) {
            Direction face = Direction.byName(tag.getString("Face"));
            if (face != null) {
                this.getEntityData().set(DATA_FACE, face);
            }
        }
        if (tag.contains("Variant", Tag.TAG_STRING)) {
            setVariantInternal(NullrootVariant.byId(tag.getString("Variant")), !tag.contains("Health", Tag.TAG_ANY_NUMERIC));
        }
        if (tag.contains("Root", Tag.TAG_LONG)) {
            this.root = BlockPos.of(tag.getLong("Root"));
            this.attached = true;
        }
        this.freeFloating = tag.getBoolean("FreeFloating");
        this.growthValue = tag.contains("Growth", Tag.TAG_ANY_NUMERIC) ? Mth.clamp(tag.getFloat("Growth"), 0.0F, 1.0F) : 0.0F;
        this.getEntityData().set(DATA_GROWTH, this.growthValue);
        this.colony = tag.hasUUID("Colony") ? tag.getUUID("Colony") : null;
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.managed = tag.getBoolean("Managed");
        this.forced = tag.getBoolean("Forced");
        this.lastSeen = tag.contains("LastSeen", Tag.TAG_ANY_NUMERIC) ? tag.getLong("LastSeen") : -1L;
        this.getEntityData().set(DATA_STATE, (byte) (tag.getBoolean("Withering") ? NullrootState.WITHERING : NullrootState.IDLE).ordinal());
        this.getEntityData().set(DATA_FOCUS, -1);
        this.getEntityData().set(DATA_REACH, 0.0F);
        this.getEntityData().set(DATA_CAPTURE, 0.0F);
        this.reachLength = 0.0F;
    }
}
