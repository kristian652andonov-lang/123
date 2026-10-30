package dev.kristian.nullmod.entity.fakeplayer;

import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The body of a simulated player (Stage 5): wears the name and skin of somebody in the player
 * list and stands, or runs, near a real player, mostly inside the NULL dimension.
 */
public class FakePlayerEntity extends PlayerLikeEntity {
    public enum Behaviour {
        /** Motionless. Most stare at the player, some stand with their back turned. */
        STILL,
        /** Stays visible far away; goes only when somebody walks up to it. */
        DISTANT,
        /** Gone as soon as somebody gets near or looks straight at it. */
        SHY,
        /** Right behind the player; gone the moment it is seen. */
        BEHIND,
        /** Sprints across the view at an impossible speed, then is gone. */
        RUNNER,
        /** Sprints straight at the player and vanishes before it reaches them. */
        RUSH,
        /** One of a crowd of motionless bodies in the distance; they all go together. */
        CROWD
    }

    private Behaviour behaviour = Behaviour.STILL;
    private float vanishDistance = 6.0F;
    private boolean faceAway;
    private Vec3 runDirection = Vec3.ZERO;
    private double runSpeed = 0.6;
    private int blockedTicks;
    private int seenTicks;
    private long group;
    private boolean forced;

    public FakePlayerEntity(EntityType<? extends FakePlayerEntity> type, Level level) {
        super(type, level);
        this.setDespawnDistance(128.0);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // Runners take full blocks in their stride.
        return PlayerLikeEntity.createAttributes().add(Attributes.STEP_HEIGHT, 1.0);
    }

    /**
     * Sets up a fresh body before it is added to the level.
     *
     * @param vanishDistance it is gone when any player comes closer than this (runners ignore it)
     * @param group          bodies with the same non-zero group vanish together
     */
    public void configure(Behaviour behaviour, @Nullable ServerPlayer owner, int lifetimeTicks, float vanishDistance, long group) {
        this.behaviour = behaviour;
        this.setOwner(owner);
        this.setLifetime(lifetimeTicks);
        this.vanishDistance = Math.max(1.5F, vanishDistance);
        this.group = group;
        this.faceAway = behaviour == Behaviour.STILL && this.random.nextFloat() < 0.3F;
    }

    /** Runners and rushers: horizontal direction (ignored for RUSH, which always heads for its player) and speed in blocks per tick. */
    public void setRun(Vec3 direction, double blocksPerTick) {
        Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
        this.runDirection = flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : flat.normalize();
        this.runSpeed = Mth.clamp(blocksPerTick, 0.05, 1.2);
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    public long group() {
        return group;
    }

    /** Marks a body started by {@code /null event} before Stage 5, which the stage cleanup leaves alone. */
    void setForced(boolean forced) {
        this.forced = forced;
    }

    boolean isForced() {
        return forced;
    }

    @Override
    protected String memoryKey() {
        return "fakeplayer";
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    protected void behaviourTick(@Nullable ServerPlayer owner) {
        switch (behaviour) {
            case RUNNER -> run(owner, false);
            case RUSH -> run(owner, true);
            case BEHIND -> behind(owner);
            default -> standing(owner);
        }
    }

    private void standing(@Nullable ServerPlayer owner) {
        Player close = nearestObserver(vanishDistance);
        if (close != null) {
            vanish(asServerPlayer(close, owner), VanishCause.APPROACHED);
            return;
        }
        if (behaviour == Behaviour.SHY) {
            Player staring = starer();
            if (staring != null) {
                if (++seenTicks > 16) {
                    vanish(asServerPlayer(staring, owner), VanishCause.STARED);
                    return;
                }
            } else {
                seenTicks = 0;
            }
        }
        if (faceAway) {
            stopStaring();
            return;
        }
        Player seen = observer();
        stareAt(seen != null ? seen : owner != null ? owner : nearestObserver(64.0));
    }

    private void behind(@Nullable ServerPlayer owner) {
        Player seen = observer();
        if (seen != null) {
            if (++seenTicks >= 2) {
                vanish(asServerPlayer(seen, owner), VanishCause.LOOKED_AT);
            }
            return;
        }
        Player close = nearestObserver(1.8);
        if (close != null) {
            vanish(asServerPlayer(close, owner), VanishCause.TOUCHED);
            return;
        }
        stareAt(owner != null ? owner : nearestObserver(32.0));
    }

    private void run(@Nullable ServerPlayer owner, boolean rush) {
        if (owner == null) {
            vanish(null, VanishCause.CLEANUP);
            return;
        }
        stopStaring();
        Vec3 direction = runDirection;
        double distance = this.distanceTo(owner);
        if (rush) {
            Vec3 to = owner.position().subtract(this.position());
            direction = new Vec3(to.x, 0.0, to.z);
            if (distance < 2.4) {
                // It never arrives.
                vanish(owner, VanishCause.SCRIPT);
                return;
            }
        } else if (distance < 3.0) {
            vanish(owner, VanishCause.APPROACHED);
            return;
        }
        if (direction.lengthSqr() < 1.0E-6) {
            vanish(null, VanishCause.CLEANUP);
            return;
        }
        direction = direction.normalize();
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * (180.0 / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(direction.x * runSpeed, motion.y, direction.z * runSpeed);
        if (this.horizontalCollision) {
            if (++blockedTicks > 3) {
                vanish(observer() instanceof ServerPlayer seen ? seen : null, VanishCause.LIFETIME);
            }
        } else {
            blockedTicks = 0;
        }
    }

    @Nullable
    private static ServerPlayer asServerPlayer(@Nullable Player player, @Nullable ServerPlayer fallback) {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : fallback;
    }

    @Override
    protected SoundEvent vanishSound(VanishCause cause) {
        return behaviour == Behaviour.BEHIND || behaviour == Behaviour.RUSH
                ? NullSounds.FAKE_PLAYER_WHISPER.get()
                : super.vanishSound(cause);
    }

    @Override
    protected void onVanish(@Nullable ServerPlayer witness, VanishCause cause) {
        if (group != 0L && cause.audible()) {
            FakePlayerManager.INSTANCE.vanishGroup(group, this);
        }
    }

    // ------------------------------------------------------------------ saving

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Behaviour", behaviour.name());
        tag.putFloat("VanishDistance", vanishDistance);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Behaviour", Tag.TAG_STRING)) {
            for (Behaviour value : Behaviour.values()) {
                if (value.name().equalsIgnoreCase(tag.getString("Behaviour"))) {
                    behaviour = value;
                }
            }
        }
        if (tag.contains("VanishDistance", Tag.TAG_ANY_NUMERIC)) {
            vanishDistance = Math.max(1.5F, tag.getFloat("VanishDistance"));
        }
    }
}
