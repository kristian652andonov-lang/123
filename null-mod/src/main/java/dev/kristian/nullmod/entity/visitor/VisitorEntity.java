package dev.kristian.nullmod.entity.visitor;

import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A Visitor (Stage 4+): a figure that looks exactly like another player, standing somewhere it
 * should not be and watching. It never attacks, never speaks and never explains itself. What
 * it does is decided by its {@link VisitorPersonality}; how it got there by
 * {@link VisitorManager}.
 */
public class VisitorEntity extends PlayerLikeEntity {
    private static final double FOLLOW_MIN = 20.0;
    private static final double FOLLOW_MAX = 35.0;
    private static final double FOLLOW_TARGET = 27.0;
    private static final double LOOKED_AT_UP_CLOSE = 12.0;

    private VisitorPersonality personality = VisitorPersonality.OBSERVER;
    private float vanishDistance = 16.0F;
    private boolean glimpse;
    private boolean forced;
    private int glimpseCountdown = -1;
    private int stareTicks;
    private int stareTicksToVanish = 30;
    private int moveCooldown = 40;
    private int seenCloseTicks;
    private boolean breathed;
    private int breathedAt;
    private boolean retreating;
    private int retreatAfterChecks = 14;

    public VisitorEntity(EntityType<? extends VisitorEntity> type, Level level) {
        super(type, level);
        this.setDespawnDistance(112.0);
    }

    /**
     * Sets up a freshly created Visitor before it is added to the level.
     *
     * @param owner          the player it appears for (may be null for tests and commands)
     * @param lifetimeTicks  how long until it leaves on its own (negative: until something else happens)
     * @param vanishDistance it is gone when any player comes closer than this
     * @param glimpse        if true it vanishes a moment after it is first seen
     */
    public void configure(VisitorPersonality personality, @Nullable ServerPlayer owner, int lifetimeTicks,
                          float vanishDistance, boolean glimpse) {
        this.personality = personality;
        this.setOwner(owner);
        this.setLifetime(lifetimeTicks);
        this.vanishDistance = Math.max(1.5F, vanishDistance);
        this.glimpse = glimpse;
        this.stareTicksToVanish = 12 + this.random.nextInt(30);
        this.retreatAfterChecks = 8 + this.random.nextInt(14);
        this.moveCooldown = 20 + this.random.nextInt(60);
    }

    public VisitorPersonality personality() {
        return personality;
    }

    public float vanishDistance() {
        return vanishDistance;
    }

    /** Marks a Visitor started by {@code /null event}, which stays even below Stage 4. */
    void setForced(boolean forced) {
        this.forced = forced;
    }

    boolean isForced() {
        return forced;
    }

    @Override
    protected String memoryKey() {
        return "visitor";
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    protected void behaviourTick(@Nullable ServerPlayer owner) {
        Player close = nearestObserver(vanishDistance);
        if (close != null) {
            vanish(asServerPlayer(close, owner), VanishCause.APPROACHED);
            return;
        }
        Player seen = observer();
        if (glimpse && seen != null) {
            if (glimpseCountdown < 0) {
                glimpseCountdown = 3 + this.random.nextInt(12);
            } else if (--glimpseCountdown <= 0) {
                vanish(asServerPlayer(seen, owner), VanishCause.LOOKED_AT);
                return;
            }
        }
        Player focus = owner != null ? owner : nearestObserver(64.0);
        switch (personality) {
            case OBSERVER -> stareAt(seen != null ? seen : focus);
            case DISAPPEARING -> disappearing(owner, focus);
            case FOLLOWER -> follower(focus);
            case STALKER -> stalker(owner, focus);
            case UNDERGROUND -> underground(owner, focus);
        }
    }

    private void disappearing(@Nullable ServerPlayer owner, @Nullable Player focus) {
        Player staring = starer();
        stareAt(staring != null ? staring : focus);
        if (staring == null) {
            stareTicks = Math.max(0, stareTicks - 2);
            return;
        }
        if (++stareTicks >= stareTicksToVanish) {
            vanish(asServerPlayer(staring, owner), VanishCause.STARED);
        }
    }

    private void follower(@Nullable Player focus) {
        if (focus == null) {
            stopStaring();
            return;
        }
        Player seen = observer();
        if (seen != null) {
            // Never moves while anyone is looking.
            if (this.getNavigation().isInProgress()) {
                this.getNavigation().stop();
            }
            stareAt(seen);
            return;
        }
        if (this.getNavigation().isInProgress()) {
            stopStaring();
            return;
        }
        stareAt(focus);
        if (--moveCooldown > 0) {
            return;
        }
        moveCooldown = 15 + this.random.nextInt(30);
        double distance = this.distanceTo(focus);
        if (distance > FOLLOW_MAX || distance < FOLLOW_MIN) {
            Vec3 away = this.position().subtract(focus.position());
            Vec3 flat = new Vec3(away.x, 0.0, away.z);
            if (flat.lengthSqr() < 1.0E-4) {
                flat = new Vec3(1.0, 0.0, 0.0);
            }
            Vec3 target = focus.position().add(flat.normalize().scale(FOLLOW_TARGET));
            if (this.getNavigation().moveTo(target.x, target.y, target.z, 1.25)) {
                stopStaring();
            }
        }
    }

    private void stalker(@Nullable ServerPlayer owner, @Nullable Player focus) {
        if (focus == null) {
            stopStaring();
            return;
        }
        Player seen = observer();
        if (seen != null) {
            this.getNavigation().stop();
            stareAt(seen);
            if (this.distanceTo(seen) < LOOKED_AT_UP_CLOSE) {
                if (++seenCloseTicks > 5) {
                    vanish(asServerPlayer(seen, owner), VanishCause.LOOKED_AT);
                }
            } else {
                seenCloseTicks = 0;
            }
            return;
        }
        seenCloseTicks = 0;
        if (this.getNavigation().isInProgress()) {
            stopStaring();
            return;
        }
        stareAt(focus);
        double distance = this.distanceTo(focus);
        if (owner != null && !breathed && distance < 4.5) {
            breathed = true;
            breathedAt = life();
            sendSound(owner, NullSounds.VISITOR_BREATH.get(), this.blockPosition().above(), 0.3F, 0.9F + this.random.nextFloat() * 0.15F);
        }
        if (breathed && life() - breathedAt > 200) {
            // It was right behind them for ten seconds. Now it is not.
            vanish(null, VanishCause.CLEANUP);
            return;
        }
        if (--moveCooldown > 0) {
            return;
        }
        moveCooldown = 40 + this.random.nextInt(70);
        if (distance > 4.0 && this.random.nextFloat() < 0.65F) {
            double step = 2.0 + this.random.nextInt(4);
            Vec3 target = pointBehind(focus, Math.max(3.0, distance - step));
            if (this.getNavigation().moveTo(target.x, target.y, target.z, 0.85)) {
                stopStaring();
            }
        }
    }

    private void underground(@Nullable ServerPlayer owner, @Nullable Player focus) {
        Player seen = observer();
        if (retreating) {
            stopStaring();
            if ((seen == null && unobservedChecks() >= 3) || !this.getNavigation().isInProgress()) {
                vanish(seen != null ? asServerPlayer(seen, owner) : null, seen != null ? VanishCause.LOOKED_AT : VanishCause.CLEANUP);
            }
            return;
        }
        stareAt(seen != null ? seen : focus);
        if (seen != null && observedChecks() >= retreatAfterChecks) {
            // Watched for too long: it turns around and walks off into the dark.
            Vec3 away = this.position().subtract(seen.position());
            Vec3 flat = new Vec3(away.x, 0.0, away.z);
            if (flat.lengthSqr() < 1.0E-4) {
                flat = new Vec3(0.0, 0.0, 1.0);
            }
            Vec3 target = this.position().add(flat.normalize().scale(8.0 + this.random.nextInt(7)));
            if (this.getNavigation().moveTo(target.x, target.y, target.z, 1.0)) {
                retreating = true;
                stopStaring();
            } else {
                vanish(asServerPlayer(seen, owner), VanishCause.LOOKED_AT);
            }
        }
    }

    /** A point {@code distance} blocks straight behind where the player is looking. */
    private static Vec3 pointBehind(Player player, double distance) {
        float yaw = player.getYHeadRot() * Mth.DEG_TO_RAD;
        return player.position().add(Mth.sin(yaw) * distance, 0.0, -Mth.cos(yaw) * distance);
    }

    @Nullable
    private static ServerPlayer asServerPlayer(@Nullable Player player, @Nullable ServerPlayer fallback) {
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : fallback;
    }

    // ------------------------------------------------------------------ saving

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Personality", personality.name());
        tag.putFloat("VanishDistance", vanishDistance);
        tag.putBoolean("Glimpse", glimpse);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Personality", Tag.TAG_STRING)) {
            personality = VisitorPersonality.byName(tag.getString("Personality"), VisitorPersonality.OBSERVER);
        }
        if (tag.contains("VanishDistance", Tag.TAG_ANY_NUMERIC)) {
            vanishDistance = Math.max(1.5F, tag.getFloat("VanishDistance"));
        }
        glimpse = tag.getBoolean("Glimpse");
    }
}
