package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The figure: a tall, thin, featureless black humanoid with two pin-prick eyes (model
 * {@code client.stalker.StalkerModel}, about 2.4 blocks tall on a player-sized hitbox; it stoops
 * under anything lower). It never attacks, never drops anything and makes no sound of its own: what
 * it does is driven every tick by the encounter that placed it ({@link StalkerManager}). It only
 * ever stands, leans out from behind things, steps aside, swings its pickaxe and looks at people.
 * <p>
 * Hitting it, touching it or anything else that would hurt it makes it simply not be there any
 * more. It is never saved with the world (the entity type uses {@code noSave()}); a figure whose
 * encounter stopped driving it removes itself, and one placed by a command stands and stares at
 * the nearest player until somebody comes close or two minutes pass.
 */
public class StalkerEntity extends PathfinderMob {
    /** Standing (also what one placed by a command does). */
    public static final byte ACT_STAND = 0;
    /** Behind something, not leaning out. */
    public static final byte ACT_HIDE = 1;
    /** Leaning out from behind something to {@link #peekSide()}. */
    public static final byte ACT_PEEK = 2;
    /** At a window, its face near the glass. */
    public static final byte ACT_WINDOW = 3;
    /** Holding the pickaxe; each {@link #EVENT_SWING} is one blow. */
    public static final byte ACT_DIG = 4;

    /** One hand raised flat against the glass in front of it. */
    public static final int FLAG_HAND_ON_GLASS = 1;

    /** Entity events (vanilla's own stop well below 100; IT uses 110-119). */
    public static final byte EVENT_SWING = 120;
    public static final byte EVENT_TAP = 121;
    public static final byte EVENT_TWITCH = 122;
    /** Length of one pickaxe swing in ticks; the blow lands {@link #SWING_HIT_TICKS} after it starts. */
    public static final int SWING_TICKS = 26;
    public static final int SWING_HIT_TICKS = 18;

    /** Height of its eyes above its feet when it stands upright (the model, not the hitbox). */
    public static final double VISUAL_EYE_HEIGHT = 2.2;
    /** A managed figure that has not been driven for this many ticks removes itself. */
    private static final int UNDRIVEN_LIMIT = 60;
    /** A figure placed by a command leaves after this long. */
    private static final int UNMANAGED_LIFETIME = 20 * 120;

    private static final EntityDataAccessor<Byte> DATA_ACT =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_SIDE =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_FLAGS =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_WATCHED =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.INT);

    private static volatile StalkerClientHook clientHook = StalkerClientHook.NONE;

    /** Client only: what the model and the sounds need between ticks. */
    public final StalkerAnimation animation = new StalkerAnimation();

    @Nullable
    private UUID ownerId;
    private boolean managed;
    private int drivenAt;
    private int life;
    private boolean vanished;
    @Nullable
    private Vec3 slideTarget;
    private double slideSpeed;

    public StalkerEntity(EntityType<? extends StalkerEntity> type, Level level) {
        super(type, level);
        this.setSilent(true);
        this.setCanPickUpLoot(false);
        this.setPersistenceRequired();
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    /** Installed once by the physical client during start-up. */
    public static void setClientHook(StalkerClientHook hook) {
        clientHook = hook == null ? StalkerClientHook.NONE : hook;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACT, ACT_STAND);
        builder.define(DATA_SIDE, (byte) 1);
        builder.define(DATA_FLAGS, (byte) 0);
        builder.define(DATA_WATCHED, -1);
    }

    // ------------------------------------------------------------------ synced state

    public byte act() {
        return this.getEntityData().get(DATA_ACT);
    }

    public void setAct(byte act) {
        if (act() != act) {
            this.getEntityData().set(DATA_ACT, act);
        }
    }

    /** +1: leans out to its left, -1: to its right. */
    public int peekSide() {
        return this.getEntityData().get(DATA_SIDE) < 0 ? -1 : 1;
    }

    public void setPeekSide(int side) {
        byte value = (byte) (side < 0 ? -1 : 1);
        if (this.getEntityData().get(DATA_SIDE) != value) {
            this.getEntityData().set(DATA_SIDE, value);
        }
    }

    public boolean hasFlag(int flag) {
        return (this.getEntityData().get(DATA_FLAGS) & flag) != 0;
    }

    public void setFlag(int flag, boolean on) {
        int current = this.getEntityData().get(DATA_FLAGS);
        int next = on ? current | flag : current & ~flag;
        if (next != current) {
            this.getEntityData().set(DATA_FLAGS, (byte) next);
        }
    }

    /** Network id of the player it is looking at, or -1. */
    public int watchedId() {
        return this.getEntityData().get(DATA_WATCHED);
    }

    // ------------------------------------------------------------------ driven by an encounter (server)

    /** Marks it as placed by {@link StalkerManager} for a player. */
    void setManaged(@Nullable ServerPlayer owner) {
        this.managed = true;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.drivenAt = this.tickCount;
    }

    /** Its encounter is still running it; without this every few ticks it leaves. */
    void drive() {
        this.drivenAt = this.tickCount;
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    /** Turns head and body straight to a yaw at once (no turning animation). */
    void faceYaw(float yaw) {
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.setYHeadRot(yaw);
        this.yHeadRotO = yaw;
    }

    /** Looks at an entity's eyes (the head turns within its limits; the body stays). */
    void lookAtEyes(@Nullable Entity target) {
        if (target == null) {
            if (watchedId() != -1) {
                this.getEntityData().set(DATA_WATCHED, -1);
            }
            return;
        }
        this.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 30.0F, 30.0F);
        if (watchedId() != target.getId()) {
            this.getEntityData().set(DATA_WATCHED, target.getId());
        }
    }

    /** Moves straight towards a point (horizontally, colliding with the world) at a fixed speed. */
    void slideTo(Vec3 target, double blocksPerTick) {
        this.slideTarget = target;
        this.slideSpeed = Math.max(0.01, blocksPerTick);
    }

    boolean sliding() {
        return slideTarget != null;
    }

    /** Starts a pickaxe swing on every client (the blow lands {@link #SWING_HIT_TICKS} later). */
    void swing() {
        if (this.level() instanceof ServerLevel level) {
            level.broadcastEntityEvent(this, EVENT_SWING);
        }
    }

    void signal(byte event) {
        if (this.level() instanceof ServerLevel level) {
            level.broadcastEntityEvent(this, event);
        }
    }

    /** True while it stands where entities really tick (same tests as ServerLevel#tick). */
    public boolean inTickingChunk() {
        if (!(this.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = this.blockPosition();
        return level.isPositionEntityTicking(pos) && level.isNaturalSpawningAllowed(pos);
    }

    /** Where its eyes are when it stands upright. */
    public Vec3 visualEyes() {
        return new Vec3(this.getX(), this.getY() + VISUAL_EYE_HEIGHT, this.getZ());
    }

    public boolean hasVanished() {
        return vanished || this.isRemoved();
    }

    /**
     * It is simply not there any more. With a witness (close, and for a cause they caused) that
     * player alone hears the faintest sound through their own audio settings.
     */
    public void vanish(@Nullable ServerPlayer witness) {
        if (vanished || this.isRemoved() || this.level().isClientSide()) {
            return;
        }
        vanished = true;
        if (witness != null && witness.level() == this.level() && witness.distanceToSqr(this) < 32.0 * 32.0) {
            PlayerLikeEntity.sendSound(witness, vanishSound(), this.blockPosition().above(),
                    0.25F + this.random.nextFloat() * 0.1F, 0.8F + this.random.nextFloat() * 0.15F);
        }
        this.discard();
    }

    protected SoundEvent vanishSound() {
        return NullSounds.VISITOR_VANISH.get();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            clientHook.tick(this);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (vanished || this.isRemoved()) {
            return;
        }
        life++;
        if (managed) {
            if (this.tickCount - drivenAt > UNDRIVEN_LIMIT) {
                // Whatever placed it has stopped: it is gone.
                vanish(null);
                return;
            }
        } else if (!unmanagedTick()) {
            return;
        }
        if (slideTarget != null) {
            stepSlide();
        }
    }

    /** One placed by a command or another mod: it stares at the nearest player and leaves when approached. */
    private boolean unmanagedTick() {
        Player nearest = this.level().getNearestPlayer(this, 64.0);
        if (nearest != null && !nearest.isSpectator()) {
            lookAtEyes(nearest);
            if (this.distanceToSqr(nearest) < 6.0 * 6.0) {
                vanish(nearest instanceof ServerPlayer serverPlayer ? serverPlayer : null);
                return false;
            }
        } else {
            lookAtEyes(null);
        }
        if (life > UNMANAGED_LIFETIME) {
            vanish(null);
            return false;
        }
        return true;
    }

    private void stepSlide() {
        Vec3 target = slideTarget;
        if (target == null) {
            return;
        }
        double dx = target.x - this.getX();
        double dz = target.z - this.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.02) {
            slideTarget = null;
            return;
        }
        double step = Math.min(distance, slideSpeed);
        this.move(MoverType.SELF, new Vec3(dx / distance * step, 0.0, dz / distance * step));
        if (this.horizontalCollision) {
            slideTarget = null;
        }
    }

    /** The body always faces exactly where the encounter turned it; only the head moves on its own. */
    @Override
    protected float tickHeadTurn(float yBodyRotTarget, float animStep) {
        this.yBodyRot = this.getYRot();
        return animStep;
    }

    @Override
    public int getMaxHeadYRot() {
        return 80;
    }

    @Override
    public int getMaxHeadXRot() {
        return 55;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_SWING || id == EVENT_TAP || id == EVENT_TWITCH) {
            clientHook.handleEvent(this, id);
            return;
        }
        super.handleEntityEvent(id);
    }

    // ------------------------------------------------------------------ nothing touches it

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide() || hasVanished()) {
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer attacker) {
            vanish(attacker);
        } else if (this.isInWall()) {
            vanish(null);
        }
        // Fire, falls, drowning, cactus...: nothing happens to it.
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            vanish(serverPlayer);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // StalkerManager despawns them.
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

    /** Never presses plates, trips wires or tramples anything. */
    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

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

    /** Seen as far as a player would be on a high render distance (watchers stand 20-40 blocks away). */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double range = 112.0 * getViewScale();
        return distanceSqr < range * range;
    }

    // ------------------------------------------------------------------ saving (only /data; the type is never saved)

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Act", act());
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setAct((byte) Mth.clamp(tag.getByte("Act"), ACT_STAND, ACT_DIG));
        // Whatever drove it is not here any more: it behaves like one placed by a command.
        this.managed = false;
        this.ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }
}
