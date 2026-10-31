package dev.kristian.nullmod.entity.it;

import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.network.NullNetworking;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * IT: an enormous, broken, six-block thing that hunts players in the field.
 * <p>
 * Summoned at the horizon by {@link #summon}, it stands and howls, then runs at its target far
 * faster than a player can sprint, steering straight across open ground (stepping over fences and
 * hay, leaping what it cannot step over, swerving around anything taller). It hunts its target,
 * then any other unsheltered player in the level. Players inside a closed room
 * ({@link #isSheltered}) are out of its reach: it is far too big for a door and never breaks a
 * block, so it prowls around their house instead, circling, pressing its face to a window,
 * dragging its blades over the walls and screaming, until the hunt runs out. Then it turns away
 * into the dark and is gone.
 * <p>
 * Whoever it reaches is seized (they become its passenger, held in its claw and unable to get
 * down), raised to its face, run through and devoured, and die with damage type {@code null:it}
 * a little over five seconds later ({@link ItKillPath}). Creative and spectator players are
 * ignored; it cannot be hurt (only {@code /kill} works), never despawns during a hunt and is never
 * saved: a world loaded with it in it simply does not have it any more.
 * <p>
 * Clients learn its phase, what it is doing at a house, who it is after and the kill's progress
 * from synced data, and its loud moments from entity events; everything they draw and play
 * hangs off that ({@code client.it}).
 */
public class ItEntity extends PathfinderMob {
    /** It screams (at a window, or when it finds somebody). */
    public static final byte EVENT_SCREAM = 110;
    /** Its blades drag over a wall. */
    public static final byte EVENT_SCRAPE = 111;
    /** It seizes its victim. */
    public static final byte EVENT_GRAB = 112;
    /** The hand through the chest. */
    public static final byte EVENT_PIERCE = 113;
    public static final byte EVENT_BITE = 114;
    public static final byte EVENT_TEAR = 115;
    /** The victim is swallowed: clients stop drawing their body. */
    public static final byte EVENT_SWALLOW = 116;
    /** The hunt is over and it goes. */
    public static final byte EVENT_RETREAT = 117;
    /** It noticed somebody: the head snaps round and the bones crack. */
    public static final byte EVENT_SNAP = 118;
    /** It was stuck and is suddenly somewhere else. */
    public static final byte EVENT_BLINK = 119;

    public static final int PHASE_ARRIVING = 0;
    public static final int PHASE_CHASING = 1;
    public static final int PHASE_PROWLING = 2;
    public static final int PHASE_KILLING = 3;
    public static final int PHASE_LEAVING = 4;

    /** What it does at a house (only while it stands still for it; moving between them is CIRCLE). */
    public static final int ACTION_NONE = 0;
    public static final int ACTION_CIRCLE = 1;
    public static final int ACTION_WINDOW = 2;
    public static final int ACTION_SCRAPE = 3;
    public static final int ACTION_STARE = 4;

    public static final int KILL_NONE = 0;
    public static final int KILL_SEIZE = 1;
    public static final int KILL_LIFT = 2;
    public static final int KILL_PIERCE = 3;
    public static final int KILL_DEVOUR = 4;

    /** Client effect for the victim when it is seized (data: IT's entity id). */
    public static final String EFFECT_KILL = "it.kill";
    /** Client effect for everybody in the level when it arrives (pos: where it stands). */
    public static final String EFFECT_HOWL = "it.howl";

    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_ACTION = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_QUARRY = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_FOCUS =
            SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Byte> DATA_KILL = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_KILL_TICK = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_KILL_FROM = SynchedEntityData.defineId(ItEntity.class, EntityDataSerializers.VECTOR3);

    private static volatile ItClientHook clientHook = ItClientHook.NONE;

    // ------------------------------------------------------------------ server state
    private boolean begun;
    private boolean fromSave;
    private int huntTicks;
    @Nullable
    private UUID preferred;
    private boolean exclusive;
    private int phaseTicks;
    private int recheck;
    @Nullable
    private ServerPlayer quarry;
    @Nullable
    private Vec3 quarryLast;
    // movement
    private double groundSpeed;
    private double wantedSpeed;
    private int avoidSide = 1;
    private boolean leapWanted;
    @Nullable
    private Vec3 progressFrom;
    private int progressTicks;
    private int stuckSeconds;
    // prowling
    @Nullable
    private ItShelter.Room room;
    private int roomAge;
    private int actionPlan = ACTION_CIRCLE;
    private int actionTicks;
    private int actionLength;
    private double orbitAngle;
    private int orbitDir = 1;
    @Nullable
    private Vec3 standAt;
    private float standYaw;
    @Nullable
    private BlockPos standFocus;
    private boolean arrived;
    // the kill
    @Nullable
    private ServerPlayer victim;
    @Nullable
    private ServerPlayer mounting;
    private boolean releasing;
    // leaving
    private Vec3 leaveDir = Vec3.ZERO;
    private int leaveTimeout = ItTuning.LEAVE_TIMEOUT_TICKS;

    // ------------------------------------------------------------------ both sides
    /** Ticks into the kill (server: authoritative; clients: counted locally, corrected at every stage). */
    private int killTicks;
    @Nullable
    private ItAnimation animation;

    public ItEntity(EntityType<? extends ItEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 400.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 128.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, ItTuning.STEP_HEIGHT);
    }

    /** Installed once by the physical client; common code only ever calls the interface. */
    public static void setClientHook(ItClientHook hook) {
        clientHook = hook == null ? ItClientHook.NONE : hook;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, (byte) PHASE_ARRIVING);
        builder.define(DATA_ACTION, (byte) ACTION_NONE);
        builder.define(DATA_QUARRY, -1);
        builder.define(DATA_FOCUS, Optional.empty());
        builder.define(DATA_KILL, (byte) KILL_NONE);
        builder.define(DATA_KILL_TICK, 0);
        builder.define(DATA_KILL_FROM, new Vector3f(0.0F, 0.0F, 2.5F));
    }

    // ================================================================== the contract with the field

    /**
     * Spawns IT at {@code pos} (moved onto the ground there), hunting {@code target} (then any
     * unsheltered player in the level) for {@code huntTicks}; afterwards it leaves and discards
     * itself. Everybody in the level hears it arrive. Returns null only if the entity type is
     * disabled.
     */
    @Nullable
    public static ItEntity summon(ServerLevel level, Vec3 pos, @Nullable ServerPlayer target, int huntTicks) {
        ItEntity it = ItModule.IT.get().create(level);
        if (it == null) {
            return null;
        }
        Vec3 spot = it.standingSpot(level, reachable(level, pos, target));
        float yaw = target != null ? yawToward(spot, target.position()) : level.random.nextFloat() * 360.0F;
        it.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
        it.setYHeadRot(yaw);
        it.setYBodyRot(yaw);
        it.beginHunt(target, huntTicks);
        if (!level.addFreshEntity(it)) {
            return null;
        }
        it.howl(level);
        return it;
    }

    /** True while IT is hunting (arriving, chasing, prowling or killing); false once it leaves or is gone. */
    public boolean isHunting() {
        return !this.isRemoved() && this.isAlive() && phase() != PHASE_LEAVING;
    }

    /**
     * True when this player is hidden from IT: indoors, under a solid roof and closed in on every
     * side within a few blocks (a field house with its door shut, an ordinary player house, a
     * sealed hole). See {@link ItShelter}.
     */
    public static boolean isSheltered(ServerPlayer player) {
        return ItShelter.isSheltered(player);
    }

    // ================================================================== tests and commands

    /** Hunt only the player it was summoned for (tests: no other player in the level counts). */
    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    /** How long it may take to leave once the hunt is over (tests). */
    public void setLeaveTimeout(int ticks) {
        this.leaveTimeout = Math.max(1, ticks);
    }

    /** Ticks of hunting left. */
    public int huntTicksLeft() {
        return huntTicks;
    }

    /** The player it holds right now (server), or null. */
    @Nullable
    public ServerPlayer victim() {
        return victim;
    }

    // ================================================================== synced state

    public int phase() {
        return this.getEntityData().get(DATA_PHASE);
    }

    public int action() {
        return this.getEntityData().get(DATA_ACTION);
    }

    /** Network id of the player it is after, -1 for none. */
    public int quarryId() {
        return this.getEntityData().get(DATA_QUARRY);
    }

    @Nullable
    public Entity quarry() {
        int id = quarryId();
        return id < 0 ? null : this.level().getEntity(id);
    }

    /** The window or wall it is working at (while it does). */
    public Optional<BlockPos> focus() {
        return this.getEntityData().get(DATA_FOCUS);
    }

    public int killStage() {
        return this.getEntityData().get(DATA_KILL);
    }

    public int killTicks() {
        return killTicks;
    }

    /** Where (right, up, forward) the victim was when seized. */
    public Vector3f killFrom() {
        return this.getEntityData().get(DATA_KILL_FROM);
    }

    private void setPhase(int phase) {
        if (phase() != phase) {
            this.getEntityData().set(DATA_PHASE, (byte) phase);
            this.phaseTicks = 0;
            this.recheck = 0;
        }
    }

    private void setAction(int action) {
        if (action() != action) {
            this.getEntityData().set(DATA_ACTION, (byte) action);
        }
    }

    private void setFocus(@Nullable BlockPos pos) {
        Optional<BlockPos> value = Optional.ofNullable(pos);
        if (!focus().equals(value)) {
            this.getEntityData().set(DATA_FOCUS, value);
        }
    }

    private void setQuarry(@Nullable ServerPlayer player) {
        this.quarry = player;
        this.quarryLast = player == null ? null : player.position();
        int id = player == null ? -1 : player.getId();
        if (quarryId() != id) {
            this.getEntityData().set(DATA_QUARRY, id);
        }
    }

    private void setKillStage(int stage) {
        if (killStage() != stage) {
            this.getEntityData().set(DATA_KILL_TICK, killTicks);
            this.getEntityData().set(DATA_KILL, (byte) stage);
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (this.level().isClientSide()) {
            if (DATA_KILL_TICK.equals(key)) {
                this.killTicks = this.getEntityData().get(DATA_KILL_TICK);
            } else if (DATA_KILL.equals(key) && killStage() == KILL_NONE) {
                this.killTicks = 0;
            }
        }
    }

    /** Client-side animation state; created on first use (the client only). */
    public ItAnimation animation() {
        ItAnimation anim = this.animation;
        if (anim == null) {
            anim = new ItAnimation();
            anim.seed = this.getUUID().getLeastSignificantBits() ^ this.getUUID().getMostSignificantBits();
            this.animation = anim;
        }
        return anim;
    }

    // ================================================================== ticking

    @Override
    public void tick() {
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel level && this.isAlive() && !this.isRemoved()) {
            serverTick(level);
            if (this.isRemoved()) {
                return;
            }
        }
        super.tick();
        if (this.level().isClientSide()) {
            if (killStage() != KILL_NONE) {
                killTicks++;
            }
            clientHook.tick(this);
        }
    }

    private void serverTick(ServerLevel level) {
        if (fromSave) {
            // Loaded from somewhere: a hunt never survives a restart.
            this.discard();
            return;
        }
        if (!begun) {
            // Summoned by a command rather than by the field.
            beginHunt(null, ItTuning.DEFAULT_HUNT_TICKS);
            howl(level);
        }
        dropStrangers();
        phaseTicks++;
        if (huntTicks > 0 && phase() != PHASE_LEAVING) {
            huntTicks--;
        }
        if (huntTicks <= 0 && phase() != PHASE_LEAVING && phase() != PHASE_KILLING) {
            // The hunt is over (a kill in progress is finished first).
            startLeaving(level);
        }
        wantedSpeed = 0.0;
        switch (phase()) {
            case PHASE_ARRIVING -> arriving(level);
            case PHASE_CHASING -> chasing(level);
            case PHASE_PROWLING -> prowling(level);
            case PHASE_KILLING -> killing(level);
            default -> leaving(level);
        }
        if (!this.isRemoved()) {
            trackProgress(level);
        }
    }

    private void beginHunt(@Nullable ServerPlayer target, int ticks) {
        this.begun = true;
        this.huntTicks = Math.max(1, ticks);
        this.preferred = target == null ? null : target.getUUID();
        setQuarry(target);
        setPhase(PHASE_ARRIVING);
    }

    /** Everybody in its world hears it, wherever they are. */
    private void howl(ServerLevel level) {
        BlockPos at = this.blockPosition();
        for (ServerPlayer player : level.players()) {
            NullNetworking.sendEffect(player, EFFECT_HOWL, this.getUUID().getLeastSignificantBits(), 1.0F, 140, Optional.of(at), this.getId(), "");
        }
    }

    // ------------------------------------------------------------------ choosing whom to hunt

    /** A player it hunts now or soon: alive in this world, survival or adventure, not held by another IT. */
    private boolean candidate(ServerPlayer player) {
        return player.level() == this.level() && player.isAlive() && !player.isRemoved() && !player.hasDisconnected()
                && !player.isSpectator() && player.gameMode.isSurvival() && !player.isChangingDimension()
                && (!(player.getVehicle() instanceof ItEntity other) || other == this)
                && (!exclusive || player.getUUID().equals(preferred));
    }

    /** A candidate it may go for right now (not just joined, respawned or arrived). */
    private boolean huntable(ServerPlayer player) {
        return candidate(player) && !ItEvents.inGrace(player);
    }

    /** Picks the nearest unsheltered player (favouring the one it came for); failing that, a hidden one to prowl. */
    private void reconsider(ServerLevel level) {
        if (huntTicks <= 0) {
            startLeaving(level);
            return;
        }
        ServerPlayer open = null;
        ServerPlayer hidden = null;
        boolean waiting = false;
        double openScore = Double.MAX_VALUE;
        double hiddenScore = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (!candidate(player)) {
                continue;
            }
            if (ItEvents.inGrace(player)) {
                waiting = true;
                continue;
            }
            double score = this.distanceToSqr(player);
            if (player.getUUID().equals(preferred)) {
                score *= 0.35;
            }
            if (player == quarry) {
                score *= 0.8;
            }
            if (ItShelter.room(player, ItTuning.SHELTER_CACHE_TICKS).enclosed()) {
                if (score < hiddenScore) {
                    hiddenScore = score;
                    hidden = player;
                }
            } else if (score < openScore) {
                openScore = score;
                open = player;
            }
        }
        if (open != null) {
            if (phase() != PHASE_CHASING || quarry != open) {
                chase(level, open);
            }
        } else if (hidden != null) {
            if (phase() != PHASE_PROWLING || quarry != hidden) {
                prowl(level, hidden);
            }
        } else if (waiting) {
            // Somebody it may take soon (they only just came): it waits where it is.
            if (phase() != PHASE_CHASING || quarry != null) {
                setPhase(PHASE_CHASING);
                setQuarry(null);
                setAction(ACTION_NONE);
                setFocus(null);
                room = null;
            }
        } else {
            startLeaving(level);
        }
    }

    private void notice(ServerLevel level, ServerPlayer player) {
        if (quarry != player) {
            level.broadcastEntityEvent(this, EVENT_SNAP);
        }
        setQuarry(player);
    }

    // ------------------------------------------------------------------ arriving

    private void arriving(ServerLevel level) {
        stopMoving();
        ServerPlayer target = quarry;
        if (target == null || !huntable(target)) {
            target = nearestHuntable(level);
            if (target != null) {
                setQuarry(target);
            }
        }
        if (target != null) {
            lookAt(target);
        }
        boolean close = target != null && this.distanceToSqr(target) < 10.0 * 10.0;
        if (phaseTicks >= ItTuning.ARRIVE_TICKS || close) {
            reconsider(level);
        }
    }

    @Nullable
    private ServerPlayer nearestHuntable(ServerLevel level) {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (huntable(player)) {
                double d = this.distanceToSqr(player);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = player;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the chase

    private void chase(ServerLevel level, ServerPlayer player) {
        notice(level, player);
        setPhase(PHASE_CHASING);
        setAction(ACTION_NONE);
        setFocus(null);
        room = null;
    }

    private void chasing(ServerLevel level) {
        if (++recheck >= ItTuning.RECHECK_TICKS || (quarry != null && !huntable(quarry))) {
            recheck = 0;
            reconsider(level);
            if (phase() != PHASE_CHASING) {
                return;
            }
        }
        ServerPlayer target = quarry;
        if (target == null) {
            // Waiting for somebody it may take.
            stopMoving();
            return;
        }
        if (canSeize(target) && startKill(level, target)) {
            return;
        }
        // Run at where it will be in a moment.
        Vec3 now = target.position();
        Vec3 moved = quarryLast == null ? Vec3.ZERO : now.subtract(quarryLast);
        quarryLast = now;
        if (moved.horizontalDistanceSqr() > 1.0) {
            // A teleport, not a run.
            moved = Vec3.ZERO;
        }
        Vec3 goal = now.add(moved.x * 4.0, 0.0, moved.z * 4.0);
        double dx = now.x - this.getX();
        double dz = now.z - this.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= ItTuning.GRAB_REACH && target.getY() - this.getY() > ItTuning.GRAB_RISE) {
            // Right below somebody out of its reach (on a tower, a ledge): it leaps for them, or waits
            // beneath them, looking up, instead of running into whatever they stand on.
            stopMoving();
            if (this.onGround() && target.getY() - this.getY() <= ItTuning.GRAB_RISE + ItTuning.LEAP_RISE) {
                this.setDeltaMovement(0.0, ItTuning.JUMP_VELOCITY, 0.0);
            }
            lookAt(target);
            return;
        }
        steer(level, goal, chaseSpeed(distance, moved.horizontalDistance()));
        lookAt(target);
    }

    /**
     * How fast it runs at somebody this far away who moves this fast (blocks per tick): flat out,
     * easing off as it closes in so it does not overshoot, but always faster than its prey.
     */
    static double chaseSpeed(double distance, double preySpeed) {
        double top = ItTuning.chaseSpeed();
        double easing = top * Mth.clamp((distance - 1.4) / 6.0, 0.15, 1.0);
        return Math.min(top, Math.max(easing, preySpeed + ItTuning.closingSpeed()));
    }

    /** Within reach, nothing solid in between, and not hidden. */
    private boolean canSeize(ServerPlayer target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double dy = target.getY() - this.getY();
        if (dx * dx + dz * dz > ItTuning.GRAB_REACH * ItTuning.GRAB_REACH || dy > ItTuning.GRAB_RISE || dy < -ItTuning.GRAB_DROP) {
            return false;
        }
        // On foot, on a horse or in a boat: it pulls them out all the same.
        return clearReach(target) && !ItShelter.room(target, 0).enclosed();
    }

    /** Nothing that blocks movement between its chest and the player's body. */
    private boolean clearReach(ServerPlayer target) {
        Vec3 from = new Vec3(this.getX(), this.getY() + Math.min(3.2, this.getBbHeight() * 0.55), this.getZ());
        Level level = this.level();
        Vec3 chest = target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
        if (level.clip(new ClipContext(from, chest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS) {
            return true;
        }
        return level.clip(new ClipContext(from, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ prowling a house

    private void prowl(ServerLevel level, ServerPlayer player) {
        boolean fresh = quarry != player || phase() != PHASE_PROWLING;
        notice(level, player);
        setPhase(PHASE_PROWLING);
        room = ItShelter.room(player, ItTuning.SHELTER_CACHE_TICKS);
        roomAge = 0;
        if (fresh) {
            Vec3 center = room.enclosed() ? room.center() : player.position();
            orbitAngle = Math.atan2(this.getZ() - center.z, this.getX() - center.x);
            orbitDir = this.random.nextBoolean() ? 1 : -1;
            plan(ACTION_CIRCLE, 60 + this.random.nextInt(80));
        }
    }

    private void prowling(ServerLevel level) {
        if (++recheck >= ItTuning.RECHECK_TICKS || quarry == null || !huntable(quarry)) {
            recheck = 0;
            reconsider(level);
            if (phase() != PHASE_PROWLING) {
                if (phase() == PHASE_CHASING && quarry != null) {
                    // It sees one of them come out.
                    level.broadcastEntityEvent(this, EVENT_SCREAM);
                }
                return;
            }
        }
        ServerPlayer target = quarry;
        if (target == null) {
            return;
        }
        if (++roomAge >= 20 || room == null || !room.enclosed()) {
            room = ItShelter.room(target, ItTuning.SHELTER_CACHE_TICKS);
            roomAge = 0;
            if (!room.enclosed()) {
                reconsider(level);
                return;
            }
        }
        actionTicks++;
        switch (actionPlan) {
            case ACTION_WINDOW, ACTION_SCRAPE -> atWall(level, target);
            case ACTION_STARE -> {
                stopMoving();
                setAction(ACTION_STARE);
                lookAt(target);
                if (actionTicks >= actionLength) {
                    nextAction(level, target);
                }
            }
            default -> {
                circle(level, target);
                if (actionTicks >= actionLength) {
                    nextAction(level, target);
                }
            }
        }
    }

    private void plan(int action, int length) {
        actionPlan = action;
        actionTicks = 0;
        actionLength = length;
        arrived = false;
        if (action == ACTION_CIRCLE) {
            standAt = null;
            standFocus = null;
            setAction(ACTION_CIRCLE);
            setFocus(null);
        }
    }

    private double orbitRadius(ItShelter.Room room) {
        return Math.max(room.halfX(), room.halfZ()) + 1.0 + ItTuning.PROWL_GAP + this.getBbWidth() * 0.5;
    }

    private void circle(ServerLevel level, ServerPlayer target) {
        ItShelter.Room r = room;
        if (r == null) {
            return;
        }
        Vec3 center = r.center();
        double radius = orbitRadius(r);
        orbitAngle += orbitDir * (ItTuning.PROWL_SPEED * 0.8 / radius);
        Vec3 goal = new Vec3(center.x + Math.cos(orbitAngle) * radius, this.getY(), center.z + Math.sin(orbitAngle) * radius);
        steer(level, goal, ItTuning.PROWL_SPEED);
        if (horizontalCollision && groundSpeed < 0.05 && this.random.nextInt(20) == 0) {
            // Something in the way of the circle: go round the other way.
            orbitDir = -orbitDir;
        }
        lookAt(target);
    }

    /** Next thing to do at the house: a window, a wall, a long stare, or more circling. */
    private void nextAction(ServerLevel level, ServerPlayer target) {
        ItShelter.Room r = room;
        if (r == null || !r.enclosed()) {
            plan(ACTION_CIRCLE, 60);
            return;
        }
        float roll = this.random.nextFloat();
        if (roll < 0.45F && !r.windows().isEmpty() && planWall(level, r.windows(), ACTION_WINDOW, target)) {
            return;
        }
        if (roll < 0.75F && !r.walls().isEmpty() && planWall(level, r.walls(), ACTION_SCRAPE, target)) {
            return;
        }
        if (roll < 0.9F) {
            plan(ACTION_STARE, 40 + this.random.nextInt(50));
            return;
        }
        if (this.random.nextInt(3) == 0) {
            orbitDir = -orbitDir;
        }
        plan(ACTION_CIRCLE, 60 + this.random.nextInt(100));
    }

    /** Picks an opening (the nearest of a few random ones) whose outside it can stand at. */
    private boolean planWall(ServerLevel level, List<ItShelter.Opening> openings, int action, ServerPlayer target) {
        ItShelter.Opening best = null;
        Vec3 bestStand = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(6, openings.size()); i++) {
            ItShelter.Opening opening = openings.get(this.random.nextInt(openings.size()));
            Vec3 stand = standOutside(level, opening, action == ACTION_WINDOW ? 2.35 : 2.0);
            if (stand == null) {
                continue;
            }
            // Close to it, and for windows close to the player inside.
            double score = stand.distanceToSqr(this.position()) + (action == ACTION_WINDOW ? 4.0 * opening.pos().distSqr(target.blockPosition()) : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = opening;
                bestStand = stand;
            }
        }
        if (best == null) {
            return false;
        }
        plan(action, action == ACTION_WINDOW
                ? ItTuning.WINDOW_MIN_TICKS + this.random.nextInt(ItTuning.WINDOW_MAX_TICKS - ItTuning.WINDOW_MIN_TICKS + 1)
                : 60 + this.random.nextInt(50));
        standAt = bestStand;
        standFocus = best.pos();
        Direction out = best.outward();
        standYaw = Mth.wrapDegrees((float) (Mth.atan2(-out.getStepZ(), -out.getStepX()) * Mth.RAD_TO_DEG) - 90.0F);
        setAction(ACTION_CIRCLE);
        return true;
    }

    /** Where its feet go to put its face (or its blades) against an opening from outside, if it fits there. */
    @Nullable
    private Vec3 standOutside(ServerLevel level, ItShelter.Opening opening, double back) {
        Direction out = opening.outward();
        Vec3 face = Vec3.atBottomCenterOf(opening.pos()).add(out.getStepX() * 0.5, 0.0, out.getStepZ() * 0.5);
        Vec3 guess = face.add(out.getStepX() * back, 0.0, out.getStepZ() * back);
        Vec3 spot = groundAt(level, guess, 4);
        if (spot == null || Math.abs(spot.y - opening.pos().getY()) > 4.0) {
            return null;
        }
        return spot;
    }

    private void atWall(ServerLevel level, ServerPlayer target) {
        Vec3 stand = standAt;
        BlockPos focusPos = standFocus;
        if (stand == null || focusPos == null) {
            plan(ACTION_CIRCLE, 60);
            return;
        }
        if (!arrived) {
            double dx = stand.x - this.getX();
            double dz = stand.z - this.getZ();
            if (dx * dx + dz * dz > 0.6 * 0.6) {
                steer(level, stand, ItTuning.PROWL_SPEED * 1.5);
                lookAt(target);
                if (actionTicks > 240) {
                    // Could not get there.
                    plan(ACTION_CIRCLE, 60);
                }
                return;
            }
            arrived = true;
            actionTicks = 0;
            setAction(actionPlan);
            setFocus(focusPos);
        }
        stopMoving();
        float yaw = Mth.approachDegrees(this.getYRot(), standYaw, 12.0F);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        Vec3 at = Vec3.atCenterOf(focusPos);
        this.getLookControl().setLookAt(at.x, at.y, at.z, 40.0F, 40.0F);
        if (actionPlan == ACTION_WINDOW) {
            if (actionTicks == actionLength * 3 / 5 && this.random.nextInt(10) < 7) {
                level.broadcastEntityEvent(this, EVENT_SCREAM);
            }
        } else if (actionTicks % ItTuning.SCRAPE_INTERVAL == 4) {
            level.broadcastEntityEvent(this, EVENT_SCRAPE);
        }
        if (actionTicks >= actionLength) {
            nextAction(level, target);
        }
    }

    // ------------------------------------------------------------------ the kill

    private boolean startKill(ServerLevel level, ServerPlayer player) {
        if (!this.getPassengers().isEmpty()) {
            return false;
        }
        float yaw = yawToward(this.position(), player.position());
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.setYHeadRot(yaw);
        Vec3 local = toLocal(player.position());
        player.stopRiding();
        if (player.isFallFlying()) {
            player.stopFallFlying();
        }
        if (player.isSleeping()) {
            player.stopSleeping();
        }
        player.setSprinting(false);
        player.stopUsingItem();
        // Set before mounting: mounting places the passenger at once.
        killTicks = 0;
        this.getEntityData().set(DATA_KILL_FROM, new Vector3f((float) Mth.clamp(local.x, -1.5, 1.5),
                (float) Mth.clamp(local.y, -1.0, 6.0), (float) Mth.clamp(local.z, 0.8, 3.6)));
        mounting = player;
        boolean mounted;
        try {
            mounted = player.startRiding(this, true);
        } finally {
            mounting = null;
        }
        if (!mounted || player.getVehicle() != this) {
            return false;
        }
        victim = player;
        stopMoving();
        setQuarry(player);
        setAction(ACTION_NONE);
        setFocus(null);
        setPhase(PHASE_KILLING);
        setKillStage(KILL_SEIZE);
        level.broadcastEntityEvent(this, EVENT_GRAB);
        NullNetworking.sendEffect(player, EFFECT_KILL, this.getUUID().getMostSignificantBits(), 1.0F, ItKillPath.END,
                Optional.empty(), this.getId(), "");
        WorldMemory.remember(level.getServer(), "it:seized", player.getUUID(), "");
        return true;
    }

    private void killing(ServerLevel level) {
        ServerPlayer held = victim;
        if (held == null || !holds(held)) {
            endKill(level, held);
            return;
        }
        killTicks++;
        setKillStage(ItKillPath.stageAt(killTicks));
        switch (killTicks) {
            case ItKillPath.PIERCE_AT -> {
                level.broadcastEntityEvent(this, EVENT_PIERCE);
                float amount = Math.min(ItTuning.pierceDamage(), held.getHealth() - 1.0F);
                if (amount >= 0.5F) {
                    held.invulnerableTime = 0;
                    held.hurt(ItDamage.source(level, this), amount);
                }
            }
            case ItKillPath.BITE_AT -> level.broadcastEntityEvent(this, EVENT_BITE);
            case ItKillPath.TEAR_AT -> level.broadcastEntityEvent(this, EVENT_TEAR);
            case ItKillPath.SWALLOW_AT -> level.broadcastEntityEvent(this, EVENT_SWALLOW);
            default -> {
            }
        }
        if (held.isRemoved() || !held.isAlive()) {
            endKill(level, held);
            return;
        }
        held.resetFallDistance();
        stopMoving();
        this.getLookControl().setLookAt(held.getX(), held.getEyeY(), held.getZ(), 40.0F, 40.0F);
        if (killTicks >= ItKillPath.END) {
            held.invulnerableTime = 0;
            held.hurt(ItDamage.source(level, this), Float.MAX_VALUE);
            if (held.isDeadOrDying()) {
                WorldMemory.remember(level.getServer(), "it:harvested", held.getUUID(), "");
            }
            endKill(level, held);
        }
    }

    /** Still in its claw: alive, connected, in this world, riding it, and someone it may take. */
    private boolean holds(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && player.level() == this.level()
                && player.getVehicle() == this && !player.isSpectator() && player.gameMode.isSurvival() && !player.isChangingDimension();
    }

    /** True while this player may not get down (the dismount guard in {@link ItEvents}). */
    boolean holdsFast(ServerPlayer player) {
        return !releasing && victim == player && this.isAlive() && !this.isRemoved() && holds(player);
    }

    /** Lets go of a player it holds (logout, tests); the hunt goes on. */
    public void release(ServerPlayer player) {
        if (victim == player && this.level() instanceof ServerLevel level) {
            endKill(level, player);
        } else if (player.getVehicle() == this) {
            letGo(player);
        }
    }

    private void endKill(ServerLevel level, @Nullable ServerPlayer held) {
        victim = null;
        killTicks = 0;
        setKillStage(KILL_NONE);
        if (held != null) {
            if (held.getVehicle() == this) {
                letGo(held);
            }
            if (held.isAlive() && !held.isRemoved()) {
                ItEvents.grace(held, ItTuning.RELEASE_GRACE_TICKS);
            }
        }
        if (this.isAlive() && !this.isRemoved()) {
            setPhase(PHASE_CHASING);
            if (huntTicks <= 0) {
                startLeaving(level);
            } else {
                reconsider(level);
            }
        }
    }

    private void letGo(Entity passenger) {
        releasing = true;
        try {
            passenger.stopRiding();
        } finally {
            releasing = false;
        }
    }

    /** Anyone riding it who is not being killed (a command put them there) is shaken off. */
    private void dropStrangers() {
        if (!this.isVehicle()) {
            return;
        }
        for (Entity passenger : List.copyOf(this.getPassengers())) {
            if (passenger != victim) {
                letGo(passenger);
            }
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger == mounting && this.getPassengers().isEmpty();
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!this.hasPassenger(passenger)) {
            return;
        }
        Vector3f from = killFrom();
        Vec3 world = toWorld(ItKillPath.at(killTicks, from.x(), from.y(), from.z()));
        move.accept(passenger, world.x, world.y, world.z);
    }

    /**
     * Whoever it lets go of (a totem saved them, they logged out, they were sent to another world)
     * drops where it held them, onto the ground below, instead of being set on top of its head six
     * blocks up (the default) and falling from there.
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Level level = this.level();
        BlockPos held = passenger.blockPosition();
        for (int down = 0; down <= 8; down++) {
            BlockPos pos = held.below(down);
            Vec3 spot = DismountHelper.findSafeDismountLocation(passenger.getType(), level, pos, true);
            if (spot != null) {
                return spot;
            }
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                // Reached the ground (or something on it) without finding a floor to stand on.
                break;
            }
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    public boolean dismountsUnderwater() {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    // ------------------------------------------------------------------ leaving

    private void startLeaving(ServerLevel level) {
        if (phase() == PHASE_LEAVING || victim != null) {
            return;
        }
        // Away from where the players are.
        double sx = 0.0;
        double sz = 0.0;
        int count = 0;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(this) < 160.0 * 160.0) {
                sx += player.getX();
                sz += player.getZ();
                count++;
            }
        }
        Vec3 away = count == 0 ? Vec3.ZERO : new Vec3(this.getX() - sx / count, 0.0, this.getZ() - sz / count);
        if (away.lengthSqr() < 1.0E-4) {
            double angle = this.random.nextDouble() * Math.PI * 2.0;
            away = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        }
        leaveDir = away.normalize();
        setPhase(PHASE_LEAVING);
        setAction(ACTION_NONE);
        setFocus(null);
        setQuarry(null);
        room = null;
        level.broadcastEntityEvent(this, EVENT_RETREAT);
    }

    private void leaving(ServerLevel level) {
        // Gone once nobody is near, after a while whatever happens, and before it would walk out of
        // the part of the world the server still runs (it would stand frozen there, in plain sight).
        if (phaseTicks >= leaveTimeout || (phaseTicks % 20 == 0 && (nobodyWithin(level, ItTuning.LEAVE_DISTANCE)
                || !level.isPositionEntityTicking(BlockPos.containing(this.position().add(leaveDir.scale(12.0))))))) {
            this.discard();
            return;
        }
        // A short pause as it turns, then away into the dark.
        if (phaseTicks < 15) {
            stopMoving();
            return;
        }
        steer(level, this.position().add(leaveDir.scale(24.0)), ItTuning.LEAVE_SPEED);
    }

    private boolean nobodyWithin(ServerLevel level, double distance) {
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(this) < distance * distance) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ steering

    private void stopMoving() {
        groundSpeed = 0.0;
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(0.0, motion.y, 0.0);
    }

    private void lookAt(Entity entity) {
        this.getLookControl().setLookAt(entity.getX(), entity.getEyeY(), entity.getZ(), 40.0F, 40.0F);
    }

    /**
     * Its own steering, straight at the goal: no path finding (open farmland needs none). It turns
     * at a limited rate (fast when slow), slows down in sharp turns and when it arrives, swerves
     * round anything it can neither step over nor leap, and leaps what is a little too high to
     * step over. The velocity is set every tick; the game's movement code does the collisions.
     */
    private void steer(ServerLevel level, Vec3 goal, double speed) {
        double dx = goal.x - this.getX();
        double dz = goal.z - this.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        float yaw = this.getYRot();
        double want = 0.0;
        if (distance > 0.25) {
            float desired = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            desired = avoid(level, desired, speed, distance);
            float turn = Mth.lerp((float) Mth.clamp(groundSpeed / ItTuning.chaseSpeed(), 0.0, 1.0), ItTuning.TURN_SLOW, ItTuning.TURN_FAST);
            yaw = Mth.approachDegrees(yaw, desired, turn);
            double alignment = Math.cos(Math.toRadians(Mth.degreesDifference(yaw, desired)));
            want = Math.min(speed * Mth.clamp(alignment, 0.2, 1.0), distance * 0.45 + 0.02);
        }
        wantedSpeed = want;
        groundSpeed = want > groundSpeed ? Math.min(want, groundSpeed + ItTuning.ACCELERATION) : Math.max(want, groundSpeed - 0.12);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        double rad = yaw * Mth.DEG_TO_RAD;
        Vec3 motion = this.getDeltaMovement();
        double vy = motion.y;
        if (leapWanted && this.onGround()) {
            vy = ItTuning.JUMP_VELOCITY;
            leapWanted = false;
        }
        this.setDeltaMovement(-Math.sin(rad) * groundSpeed, vy, Math.cos(rad) * groundSpeed);
    }

    private float avoid(ServerLevel level, float desired, double speed, double distance) {
        leapWanted = false;
        // Never look past the goal: walking up to a wall it means to stand at is not an obstacle.
        double probe = Math.min(distance + 0.1, 0.9 + Math.max(0.15, Math.min(speed, groundSpeed + 0.1)) * 4.5);
        if (clearAhead(level, desired, probe)) {
            return desired;
        }
        if (this.onGround() && canLeap(level, desired, probe)) {
            leapWanted = true;
            return desired;
        }
        for (int step = 1; step <= 6; step++) {
            float offset = step * 22.5F;
            float first = desired + offset * avoidSide;
            if (clearAhead(level, first, probe)) {
                return first;
            }
            float second = desired - offset * avoidSide;
            if (clearAhead(level, second, probe)) {
                avoidSide = -avoidSide;
                return second;
            }
        }
        return desired;
    }

    /** Room for its whole body a little ahead, lifted by what it can step over. */
    private boolean clearAhead(ServerLevel level, float yaw, double probe) {
        double rad = yaw * Mth.DEG_TO_RAD;
        double sx = -Math.sin(rad);
        double sz = Math.cos(rad);
        AABB box = this.getBoundingBox();
        double lift = ItTuning.STEP_HEIGHT + 0.05;
        return level.noCollision(this, box.move(sx * probe * 0.5, lift, sz * probe * 0.5))
                && level.noCollision(this, box.move(sx * probe, lift, sz * probe));
    }

    /** Something ahead it cannot step over but can clear in one leap (never a house: those are taller). */
    private boolean canLeap(ServerLevel level, float yaw, double probe) {
        double rad = yaw * Mth.DEG_TO_RAD;
        AABB box = this.getBoundingBox();
        return level.noCollision(this, box.move(0.0, 3.0, 0.0))
                && level.noCollision(this, box.move(-Math.sin(rad) * probe, 3.0, Math.cos(rad) * probe));
    }

    /**
     * Once a second: did it get anywhere while it wanted to? Boxed in for a few seconds, it is
     * simply somewhere else a moment later (or, if it was leaving, gone). It only ever turns up out
     * in the open, never under a roof (inside a house, a barn, a sheltered room); while it prowls a
     * house it turns up further round it, never nearer the one hiding inside; and while it runs
     * somebody down it turns up nearer to them or not at all (a wall between them stays a wall).
     */
    private void trackProgress(ServerLevel level) {
        if (++progressTicks < 20) {
            return;
        }
        progressTicks = 0;
        Vec3 now = this.position();
        boolean stalled = wantedSpeed > 0.08 && progressFrom != null && now.distanceToSqr(progressFrom) < 1.0;
        progressFrom = now;
        stuckSeconds = stalled ? stuckSeconds + 1 : 0;
        if (stuckSeconds < ItTuning.STUCK_SECONDS) {
            return;
        }
        stuckSeconds = 0;
        if (phase() == PHASE_LEAVING) {
            this.discard();
            return;
        }
        ItShelter.Room around = room;
        boolean prowl = phase() == PHASE_PROWLING && around != null && around.enclosed();
        Vec3 toward;
        if (prowl) {
            Vec3 center = around.center();
            double radius = orbitRadius(around);
            orbitAngle += orbitDir * (0.8 + this.random.nextDouble() * 0.8);
            toward = new Vec3(center.x + Math.cos(orbitAngle) * radius, this.getY(), center.z + Math.sin(orbitAngle) * radius);
        } else {
            toward = quarry != null ? quarry.position() : this.position().add(leaveDir.scale(20.0));
        }
        ServerPlayer prey = prowl ? null : quarry;
        double gap = prey == null ? 0.0 : horizontalDistance(this.position(), prey.position());
        Vec3 dir = new Vec3(toward.x - this.getX(), 0.0, toward.z - this.getZ());
        double length = dir.length();
        dir = length < 1.0E-3 ? new Vec3(1.0, 0.0, 0.0) : dir.scale(1.0 / length);
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = (this.random.nextDouble() - 0.5) * (prowl ? 0.6 : 1.6);
            double reach = prowl ? length : Math.min(Math.max(6.0, length - 4.0), 10.0 + this.random.nextDouble() * 12.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 guess = this.position().add((dir.x * cos - dir.z * sin) * reach, 0.0, (dir.x * sin + dir.z * cos) * reach);
            Vec3 spot = groundAt(level, guess, 6);
            if (spot != null && outdoors(level, spot) && (prey == null || horizontalDistance(spot, prey.position()) < gap - 2.0)) {
                level.broadcastEntityEvent(this, EVENT_BLINK);
                this.teleportTo(spot.x, spot.y, spot.z);
                groundSpeed = 0.0;
                return;
            }
        }
    }

    /** Nothing that blocks movement above its feet there (leaves aside): out in the open, not under a roof. */
    private static boolean outdoors(ServerLevel level, Vec3 feet) {
        BlockPos column = BlockPos.containing(feet.x, feet.y + 0.5, feet.z);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) <= column.getY();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ geometry

    /** World point from (right, up, forward) relative to its feet and facing. */
    public Vec3 toWorld(Vec3 local) {
        double rad = this.getYRot() * Mth.DEG_TO_RAD;
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        return new Vec3(this.getX() - cos * local.x - sin * local.z, this.getY() + local.y, this.getZ() - sin * local.x + cos * local.z);
    }

    /** (right, up, forward) of a world point relative to its feet and facing. */
    public Vec3 toLocal(Vec3 world) {
        double rad = this.getYRot() * Mth.DEG_TO_RAD;
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double dx = world.x - this.getX();
        double dz = world.z - this.getZ();
        return new Vec3(-cos * dx - sin * dz, world.y - this.getY(), -sin * dx + cos * dz);
    }

    static float yawToward(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /**
     * Where its body fits standing near {@code guess}: out of anything solid (up to a few blocks),
     * then down onto the ground. Null if there is no room or the ground is far below.
     */
    @Nullable
    private Vec3 groundAt(ServerLevel level, Vec3 guess, int maxRise) {
        if (!level.isLoaded(BlockPos.containing(guess))) {
            return null;
        }
        AABB box = this.getDimensions(this.getPose()).makeBoundingBox(guess);
        int rise = 0;
        while (!level.noCollision(this, box)) {
            if (++rise > maxRise) {
                return null;
            }
            box = box.move(0.0, 1.0, 0.0);
        }
        for (int drop = 0; drop < 24; drop++) {
            AABB lower = box.move(0.0, -1.0, 0.0);
            if (!level.noCollision(this, lower)) {
                return new Vec3(guess.x, box.minY, guess.z);
            }
            box = lower;
        }
        return null;
    }

    /** Its feet's spot for a summon: on the ground there, or right where it was asked to be. */
    private Vec3 standingSpot(ServerLevel level, Vec3 pos) {
        Vec3 spot = groundAt(level, pos, 16);
        return spot != null ? spot : pos;
    }

    /** A summon point in an unloaded (not ticking) chunk is pulled toward the target until it is not. */
    private static Vec3 reachable(ServerLevel level, Vec3 pos, @Nullable ServerPlayer target) {
        Vec3 point = pos;
        for (int i = 0; i < 20 && target != null && !level.isPositionEntityTicking(BlockPos.containing(point)); i++) {
            point = point.lerp(target.position(), 0.15);
        }
        return point;
    }

    // ================================================================== not a normal mob

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // Only what bypasses invulnerability (/kill, the void) touches it.
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel level && victim != null) {
            ServerPlayer held = victim;
            victim = null;
            setKillStage(KILL_NONE);
            if (held.getVehicle() == this) {
                letGo(held);
            }
        }
        super.die(source);
    }

    /** It does not fall over: it is just not there any more. */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (!this.level().isClientSide() && !this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide()) {
            ServerPlayer held = victim;
            victim = null;
            if (held != null && held.getVehicle() == this) {
                letGo(held);
            }
        }
        super.remove(reason);
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (this.level().isClientSide()) {
            clientHook.removed(this);
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id >= EVENT_SCREAM && id <= EVENT_BLINK) {
            clientHook.handleEvent(this, id);
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        // Arms, blades and tentacles reach well outside the body.
        return super.getBoundingBoxForCulling().inflate(3.5, 2.5, 3.5);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // It goes when its hunt is over.
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public int getMaxHeadYRot() {
        return 80;
    }

    @Override
    public int getMaxHeadXRot() {
        return 70;
    }

    @Override
    public int getHeadRotSpeed() {
        return 45;
    }

    @Override
    public boolean isPushable() {
        return false;
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
    public boolean canTrample(BlockState state, BlockPos pos, float fallDistance) {
        return false;
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
    public boolean canFreeze() {
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
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // Its footfalls are played by clients, in step with the animation.
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

    // ------------------------------------------------------------------ saving: never

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        // Its type is never saved with a world; this only shows up in /data and copies.
        tag.putBoolean("ItHunt", true);
        tag.putInt("HuntTicks", huntTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.getBoolean("ItHunt")) {
            this.fromSave = true;
        }
    }
}
