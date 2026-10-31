package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;

/**
 * What a variant mob does differently. Goals are not saved with a mob, so
 * {@link MobVariantManager} installs them whenever a variant mob joins a level or becomes a
 * variant, and takes them away when it turns back to normal (always at the end of a server tick,
 * never while a goal selector is running). Three goals:
 * <ul>
 * <li>{@link Pulse} (no flags, so it runs beside the mob's own AI): ages a parasite, holds the
 * mob still while it stares, the stutter of a corrupted mob (it stops dead for a few ticks, then
 * skips ahead along its path as if frames were dropped), its spontaneous stares (freezing to stare
 * at the nearest player, or turning to face a player who is not looking), a host's twitching and
 * a hostile host's sudden short lunges.</li>
 * <li>{@link Stare} (move and look): stands still, head turned to the player.</li>
 * <li>{@link Drift} (move and look): a passive parasite host walks toward a player while nobody
 * watches, and stops dead the moment it is looked at.</li>
 * </ul>
 * Passive mobs never attack: nothing here gives a mob a target or a weapon.
 */
final class VariantGoals {
    static final int PULSE_PRIORITY = 0;
    static final int STARE_PRIORITY = 1;
    static final int DRIFT_PRIORITY = 2;

    private VariantGoals() {
    }

    /** Installs or removes the variant goals so they match the mob (only at the end of a tick). */
    static void sync(Mob mob, boolean variant) {
        List<WrappedGoal> ours = new ArrayList<>();
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof VariantGoal) {
                ours.add(wrapped);
            }
        }
        if (variant) {
            if (ours.isEmpty()) {
                mob.goalSelector.addGoal(PULSE_PRIORITY, new Pulse(mob));
                mob.goalSelector.addGoal(STARE_PRIORITY, new Stare(mob));
                mob.goalSelector.addGoal(DRIFT_PRIORITY, new Drift(mob));
            }
            return;
        }
        for (WrappedGoal wrapped : ours) {
            // Stopped first: a running goal removed without stopping would keep its flags locked.
            if (wrapped.isRunning()) {
                wrapped.stop();
            }
            mob.goalSelector.removeGoal(wrapped.getGoal());
        }
        VariantMotion.release(mob);
        VariantMotion.forgetLook(mob);
    }

    /** True if the mob carries the variant goals (tests). */
    static boolean installed(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof VariantGoal) {
                return true;
            }
        }
        return false;
    }

    abstract static class VariantGoal extends Goal {
        final Mob mob;

        VariantGoal(Mob mob) {
            this.mob = mob;
        }

        @Nullable
        final VariantData data() {
            VariantData data = MobVariantManager.data(mob);
            return data == null || data.variant() == MobVariant.NONE ? null : data;
        }

        final long now() {
            return mob.level().getGameTime();
        }

        final int random(int bound) {
            return mob.getRandom().nextInt(bound);
        }
    }

    // ------------------------------------------------------------------ pulse

    static final class Pulse extends VariantGoal {
        private int stutterIn;
        private int hitch;
        private int stareIn;
        private int twitchIn;
        private int lungeIn;

        Pulse(Mob mob) {
            super(mob);
            setFlags(EnumSet.noneOf(Goal.Flag.class));
            stutterIn = 60 + random(160);
            stareIn = 100 + random(240);
            twitchIn = 40 + random(120);
            lungeIn = 40;
        }

        @Override
        public boolean canUse() {
            return mob.isAlive() && data() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            hitch = 0;
            VariantMotion.release(mob);
        }

        @Override
        public void tick() {
            VariantData data = data();
            if (data == null) {
                return;
            }
            long now = now();
            data.age(growthPerTick(data));
            if (data.variant() == MobVariant.PARASITE) {
                MobVariantManager.INSTANCE.growthChanged(mob, data, now);
            }
            if (hitch > 0 && data.variant() != MobVariant.CORRUPTED) {
                // It changed kind in the middle of a stutter (an operator): let go of it.
                hitch = 0;
                VariantMotion.hold(mob, VariantMotion.HITCH, false);
            }
            boolean staring = data.staring(now) && Stare.mayHold(mob);
            if (data.staring(now) && !staring) {
                // Hurt, burning, ridden or after something: the stare is over.
                data.endStare();
            }
            VariantMotion.hold(mob, VariantMotion.HOLD, staring || data.driftHold);
            if (data.variant() == MobVariant.CORRUPTED) {
                corrupted(data, now, staring);
            } else {
                parasite(data);
            }
        }

        private void corrupted(VariantData data, long now, boolean staring) {
            // The stutter: stops dead for a few ticks, then skips ahead along its path.
            if (hitch > 0) {
                if (--hitch == 0) {
                    VariantMotion.hold(mob, VariantMotion.HITCH, false);
                    VariantMotion.skipAhead(mob, 0.7 + mob.getRandom().nextDouble() * 0.8);
                }
            } else if (--stutterIn <= 0) {
                stutterIn = 50 + random(170);
                if (!staring && VariantMotion.walking(mob)) {
                    hitch = 4 + random(5);
                    VariantMotion.hold(mob, VariantMotion.HITCH, true);
                }
            }
            // Now and then it freezes and stares at the nearest player, or, if that player is not
            // looking, turns to face them and waits to be noticed.
            if (!staring && hitch == 0 && --stareIn <= 0) {
                stareIn = 140 + random(260);
                if (Stare.mayHold(mob) && mob.getRandom().nextFloat() < 0.4F) {
                    ServerPlayer player = VariantSight.nearestWatcher(mob, 20.0);
                    if (player != null && mob.hasLineOfSight(player)) {
                        if (VariantSight.inView(player, mob, 55.0)) {
                            data.stare(now + 50 + random(70), player.getId(), false, false);
                        } else {
                            data.stare(now + 160 + random(140), player.getId(), false, true);
                        }
                    }
                }
            }
        }

        private void parasite(VariantData data) {
            float growth = data.growth();
            if (--twitchIn <= 0) {
                twitchIn = Math.max(16, (int) ((40 + random(110)) * (1.35F - growth)));
                if (!mob.isVehicle()) {
                    VariantMotion.twitch(mob, mob.getRandom(), 0.35F + 0.65F * growth);
                }
            }
            // Hostile hosts lunge; a creeper never does (a lunge before it explodes would make it deadlier than it is).
            if (mob instanceof Enemy && !(mob instanceof Creeper) && --lungeIn <= 0) {
                lungeIn = 10;
                LivingEntity target = mob.getTarget();
                if (target != null && target.isAlive() && mob.onGround() && !mob.isPassenger() && !mob.isVehicle()) {
                    double distance = mob.distanceToSqr(target);
                    if (distance >= 2.5 * 2.5 && distance <= 7.0 * 7.0
                            && mob.getRandom().nextFloat() < 0.15F + 0.2F * growth && mob.hasLineOfSight(target)) {
                        VariantMotion.lunge(mob, target, 0.55 + 0.35 * growth);
                        lungeIn = 80 + random(100);
                        VariantSounds.playNear(mob, NullSounds.NULLROOT_REACH.get(), 0.6F, 0.85F + mob.getRandom().nextFloat() * 0.3F, 24.0);
                    }
                }
            }
        }

        /** A parasite takes five to eight minutes of its host's life to grow fully (per mob). */
        private static float growthPerTick(VariantData data) {
            float minutes = 5.0F + ((data.seed() >>> 8) & 0xFF) / 255.0F * 3.0F;
            return 1.0F / (minutes * 1200.0F);
        }
    }

    // ------------------------------------------------------------------ stare

    static final class Stare extends VariantGoal {
        @Nullable
        private ServerPlayer target;
        private boolean noticed;

        Stare(Mob mob) {
            super(mob);
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        /** It may hold still: nothing is after it nor it after anything, and it is not hurt, burning or ridden. */
        static boolean mayHold(Mob mob) {
            return mob.isAlive() && mob.getTarget() == null && mob.hurtTime == 0 && !mob.isOnFire() && !mob.isInLava()
                    && !mob.isVehicle() && !mob.isPassenger();
        }

        @Override
        public boolean canUse() {
            VariantData data = data();
            if (data == null || !data.staring(now()) || !mayHold(mob)) {
                return false;
            }
            target = resolve(data);
            return target != null;
        }

        @Override
        public boolean canContinueToUse() {
            VariantData data = data();
            return data != null && target != null && data.staring(now()) && mayHold(mob) && target.isAlive()
                    && target.level() == mob.level() && target.distanceToSqr(mob) <= 40.0 * 40.0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            noticed = false;
            mob.getNavigation().stop();
            VariantData data = data();
            if (data != null && target != null && (data.watching || data.stareHeld)) {
                // Unseen (or every mob of an event at once): it simply is facing you now.
                VariantMotion.face(mob, target);
            }
        }

        @Override
        public void tick() {
            VariantData data = data();
            if (data == null || target == null) {
                return;
            }
            mob.getNavigation().stop();
            VariantMotion.lookAt(mob, target, data.stareHeld || data.watching ? 60.0F : 12.0F);
            if (data.watching && !noticed && mob.tickCount % 4 == 0 && VariantSight.sees(target, mob, 30.0)) {
                // Caught staring: it holds the look a moment longer, then goes back to what it did.
                noticed = true;
                data.stareUntil = Math.min(data.stareUntil, now() + 25 + random(30));
            }
        }

        @Override
        public void stop() {
            target = null;
            VariantData data = data();
            if (data != null) {
                data.endStare();
            }
            VariantMotion.forgetLook(mob);
        }

        @Nullable
        private ServerPlayer resolve(VariantData data) {
            ServerPlayer player = VariantSight.watcherById(mob, data.stareTarget, 40.0);
            return player != null ? player : VariantSight.nearestWatcher(mob, 24.0);
        }
    }

    // ------------------------------------------------------------------ drift

    static final class Drift extends VariantGoal {
        @Nullable
        private ServerPlayer target;
        private int left;
        private int repath;
        private int rollIn;
        private boolean watched;

        Drift(Mob mob) {
            super(mob);
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
            rollIn = 100 + random(300);
        }

        private boolean may(VariantData data) {
            return data.variant() == MobVariant.PARASITE && !(mob instanceof Enemy) && Stare.mayHold(mob) && !mob.isLeashed()
                    && !data.staring(now());
        }

        @Override
        public boolean canUse() {
            VariantData data = data();
            if (data == null || !may(data)) {
                return false;
            }
            long now = now();
            boolean asked = data.approachUntil > now;
            if (!asked) {
                // Rolled every couple of seconds: the further it has grown, the more it wanders at you.
                rollIn -= 2;
                if (rollIn > 0) {
                    return false;
                }
                rollIn = 160 + random(340);
                if (data.growth() < 0.2F || mob.getRandom().nextFloat() >= 0.25F + 0.5F * data.growth()) {
                    return false;
                }
            }
            ServerPlayer player = asked ? VariantSight.watcherById(mob, data.approachTarget, 48.0) : VariantSight.nearestWatcher(mob, 24.0);
            if (player == null || (!asked && VariantSight.sees(player, mob, 40.0))) {
                // It only sets off while nobody is looking.
                return false;
            }
            target = player;
            left = asked ? (int) Math.min(20L * 60L, data.approachUntil - now) : 200 + random(300);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            VariantData data = data();
            return data != null && target != null && left > 0 && may(data) && target.isAlive() && target.level() == mob.level()
                    && target.distanceToSqr(mob) <= 48.0 * 48.0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            repath = 0;
            watched = false;
        }

        @Override
        public void tick() {
            VariantData data = data();
            if (data == null || target == null) {
                return;
            }
            left--;
            if (mob.tickCount % 3 == 0) {
                watched = VariantSight.sees(target, mob, 40.0);
            }
            if (watched) {
                // Stops dead and stares back until the player looks away.
                data.driftHold = true;
                VariantMotion.stopWalking(mob);
                VariantMotion.lookAt(mob, target, 30.0F);
                return;
            }
            data.driftHold = false;
            if (mob.distanceToSqr(target) > 2.5 * 2.5) {
                if (--repath <= 0) {
                    repath = 10;
                    VariantMotion.walkTo(mob, target, 0.75 + 0.25 * data.growth());
                }
            } else {
                VariantMotion.stopWalking(mob);
                VariantMotion.lookAt(mob, target, 30.0F);
            }
        }

        @Override
        public void stop() {
            VariantData data = data();
            if (data != null) {
                data.driftHold = false;
                data.approachUntil = 0L;
                data.approachTarget = -1;
            }
            target = null;
            VariantMotion.stopWalking(mob);
            VariantMotion.forgetLook(mob);
        }
    }
}
