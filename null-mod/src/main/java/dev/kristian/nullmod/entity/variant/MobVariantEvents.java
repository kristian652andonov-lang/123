package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * The mob variant random events. Each works when forced with {@code /null event} at any stage:
 * when there is nothing to act on, a forced run first turns a mob or two nearby (marked as an
 * operator's, so they stay until the stage is moved back).
 * <ul>
 * <li>{@code mob_herd_corruption} (2+): two to four animals standing together turn corrupted at
 * the same moment and look up at the player together.</li>
 * <li>{@code mob_infestation} (3+): a mob the player is looking at is infested; its tentacles
 * are already showing.</li>
 * <li>{@code mob_stare} (2+): every variant mob nearby stops and turns to stare at the player at
 * the same moment.</li>
 * <li>{@code mob_wrong_voices} (2+): the corrupted mobs nearby all make the same wrong sound at
 * once (another creature's voice), heard by that player only.</li>
 * <li>{@code mob_hosts_approach} (4+): the passive parasite hosts nearby all start walking at the
 * player, stopping dead whenever they are looked at.</li>
 * </ul>
 */
public final class MobVariantEvents {
    private static final Set<MobVariant> ANY = EnumSet.of(MobVariant.CORRUPTED, MobVariant.PARASITE);
    private static final Set<MobVariant> CORRUPTED = EnumSet.of(MobVariant.CORRUPTED);
    private static final Set<MobVariant> PARASITE = EnumSet.of(MobVariant.PARASITE);

    private MobVariantEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("mob_herd_corruption")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .stageWeights(0, 120, 100, 80, 50)
                .cooldown(Ticks.minutes(6))
                .globalCooldown(Ticks.minutes(2))
                .duration(60)
                .place(EventPlace.OUTSIDE_NULL)
                .action(MobVariantEvents::herdCorruption)
                .register();
        NullEventDefinition.builder("mob_infestation")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .stageWeights(0, 0, 120, 100, 70)
                .cooldown(Ticks.minutes(5))
                .duration(60)
                .place(EventPlace.OUTSIDE_NULL)
                .action(MobVariantEvents::infestation)
                .register();
        NullEventDefinition.builder("mob_stare")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .stageWeights(0, 100, 100, 100, 70)
                .cooldown(Ticks.minutes(4))
                .duration(100)
                .place(EventPlace.OUTSIDE_NULL)
                .action(MobVariantEvents::stare)
                .register();
        NullEventDefinition.builder("mob_wrong_voices")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(7))
                .duration(40)
                .tags(EventTag.AUDIO)
                .place(EventPlace.OUTSIDE_NULL)
                .action(MobVariantEvents::wrongVoices)
                .register();
        NullEventDefinition.builder("mob_hosts_approach")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(8))
                .duration(Ticks.seconds(20))
                .place(EventPlace.OUTSIDE_NULL)
                .action(MobVariantEvents::hostsApproach)
                .register();
    }

    // ------------------------------------------------------------------ actions

    private static boolean herdCorruption(EventContext ctx) {
        if (!usable(ctx, MobVariant.CORRUPTED)) {
            return false;
        }
        ServerPlayer player = ctx.player();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        int least = ctx.forced() ? 1 : 2;
        int room = manager.room(player, ctx.corruption().stage(), ctx.forced());
        if (room < least) {
            return false;
        }
        List<Mob> herd = herd(ctx, Math.min(room, 2 + ctx.random().nextInt(3)));
        if (herd.size() < least) {
            return false;
        }
        boolean operators = operators(ctx, MobVariant.CORRUPTED);
        long until = ctx.level().getGameTime() + 40 + ctx.random().nextInt(40);
        int turned = 0;
        for (Mob mob : herd) {
            if (manager.set(mob, MobVariant.CORRUPTED, 1.0F, operators)) {
                turned++;
                stareAt(mob, player, until);
            }
        }
        if (turned == 0) {
            return false;
        }
        ctx.playSound(NullSounds.STATIC_FAINT.get(), Optional.of(herd.get(0).blockPosition()), 0.35F, 0.6F);
        WorldMemory.remember(ctx.server(), "variant:herd", player.getUUID(), MobVariantManager.typeName(herd.get(0)));
        return true;
    }

    private static boolean infestation(EventContext ctx) {
        if (!usable(ctx, MobVariant.PARASITE)) {
            return false;
        }
        ServerPlayer player = ctx.player();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        if (manager.room(player, ctx.corruption().stage(), ctx.forced()) < 1) {
            return false;
        }
        Mob target = inSight(ctx);
        if (target == null || !manager.set(target, MobVariant.PARASITE, 0.3F, operators(ctx, MobVariant.PARASITE))) {
            return false;
        }
        ctx.playSound(NullSounds.NULLROOT_GROW.get(), Optional.of(target.blockPosition()), 0.8F, 0.75F);
        WorldMemory.remember(ctx.server(), "variant:infested", player.getUUID(), MobVariantManager.typeName(target));
        return true;
    }

    private static boolean stare(EventContext ctx) {
        ServerPlayer player = ctx.player();
        if (!VariantConfig.enabled() || !MobVariantManager.anchorOk(player)) {
            return false;
        }
        List<Mob> starers = new ArrayList<>();
        for (Mob mob : MobVariantManager.INSTANCE.variantsNear(player, 32.0, ANY)) {
            if (VariantGoals.Stare.mayHold(mob)) {
                starers.add(mob);
            }
        }
        if (starers.isEmpty()) {
            if (!ctx.forced()) {
                return false;
            }
            starers.addAll(turnForced(ctx, MobVariant.CORRUPTED, 3, false));
            if (starers.isEmpty()) {
                return false;
            }
        }
        long until = ctx.level().getGameTime() + 50 + ctx.random().nextInt(50);
        for (Mob mob : starers) {
            stareAt(mob, player, until);
        }
        WorldMemory.remember(ctx.server(), "variant:stare", player.getUUID(), Integer.toString(starers.size()));
        return true;
    }

    private static boolean wrongVoices(EventContext ctx) {
        ServerPlayer player = ctx.player();
        if (!VariantConfig.enabled() || !MobVariantManager.anchorOk(player)) {
            return false;
        }
        List<Mob> voices = new ArrayList<>(MobVariantManager.INSTANCE.variantsNear(player, 24.0, CORRUPTED));
        if (voices.isEmpty()) {
            if (!ctx.forced()) {
                return false;
            }
            voices.addAll(turnForced(ctx, MobVariant.CORRUPTED, 1, false));
            if (voices.isEmpty()) {
                return false;
            }
        }
        SoundEvent voice = VariantSounds.wrongVoice(ctx.random(), null);
        float pitch = 0.72F + ctx.random().nextFloat() * 0.2F;
        int count = Math.min(6, voices.size());
        for (int i = 0; i < count; i++) {
            Mob mob = voices.get(i);
            VariantSounds.play(player, voice, mob.blockPosition(), 0.9F, pitch + i * 0.015F);
        }
        WorldMemory.remember(ctx.server(), "variant:voices", player.getUUID(), voice.getLocation().toString());
        return true;
    }

    private static boolean hostsApproach(EventContext ctx) {
        ServerPlayer player = ctx.player();
        if (!VariantConfig.enabled() || !MobVariantManager.anchorOk(player)) {
            return false;
        }
        List<Mob> hosts = new ArrayList<>();
        for (Mob mob : MobVariantManager.INSTANCE.variantsNear(player, 32.0, PARASITE)) {
            if (!(mob instanceof Enemy) && VariantGoals.Stare.mayHold(mob) && !mob.isLeashed()) {
                hosts.add(mob);
            }
        }
        if (hosts.isEmpty()) {
            if (!ctx.forced()) {
                return false;
            }
            hosts.addAll(turnForced(ctx, MobVariant.PARASITE, 1, true));
            if (hosts.isEmpty()) {
                return false;
            }
        }
        long until = ctx.level().getGameTime() + ctx.duration();
        for (Mob mob : hosts) {
            VariantData data = MobVariantManager.data(mob);
            if (data != null) {
                data.endStare();
                data.approachUntil = until;
                data.approachTarget = player.getId();
            }
        }
        WorldMemory.remember(ctx.server(), "variant:approach", player.getUUID(), Integer.toString(hosts.size()));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** Variants are on, the player is somewhere they can appear, and (unless forced) this kind is not switched off. */
    private static boolean usable(EventContext ctx, MobVariant variant) {
        if (!VariantConfig.enabled() || !MobVariantManager.anchorOk(ctx.player())) {
            return false;
        }
        double frequency = variant == MobVariant.PARASITE ? VariantConfig.parasiteFrequency() : VariantConfig.corruptedFrequency();
        return ctx.forced() || frequency > 0.0;
    }

    /** Forced at a stage the variant does not belong to yet: the mobs are marked as an operator's. */
    private static boolean operators(EventContext ctx, MobVariant variant) {
        return ctx.forced() && !MobVariantRules.allowedAt(variant, ctx.corruption().stage());
    }

    /** Two to four animals standing together, the player's view preferred; at least one of them seen when possible. */
    private static List<Mob> herd(EventContext ctx, int count) {
        ServerPlayer player = ctx.player();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        List<Mob> animals = manager.candidatesNear(player, 28.0, 12.0, mob -> !(mob instanceof Enemy));
        if (animals.isEmpty() && ctx.forced()) {
            animals = manager.candidatesNear(player, 40.0, 16.0, mob -> true);
        }
        if (animals.isEmpty()) {
            return List.of();
        }
        Mob anchor = null;
        for (Mob mob : animals) {
            if (mob.distanceToSqr(player) >= 5.0 * 5.0 && VariantSight.sees(player, mob, 60.0)) {
                anchor = mob;
                break;
            }
        }
        if (anchor == null) {
            anchor = animals.get(ctx.random().nextInt(animals.size()));
        }
        Mob center = anchor;
        List<Mob> group = new ArrayList<>();
        for (Mob mob : animals) {
            if (mob.distanceToSqr(center) <= 10.0 * 10.0) {
                group.add(mob);
            }
        }
        group.sort(Comparator.comparingDouble(mob -> mob.distanceToSqr(center)));
        return group.subList(0, Math.min(count, group.size()));
    }

    /** The mob nearest the middle of the player's view, 4 to 24 blocks away (forced: any nearby mob as a last resort). */
    @Nullable
    private static Mob inSight(EventContext ctx) {
        ServerPlayer player = ctx.player();
        List<Mob> mobs = MobVariantManager.INSTANCE.candidatesNear(player, ctx.forced() ? 32.0 : 24.0, 12.0, mob -> true);
        Mob best = null;
        double bestAngle = -2.0;
        for (Mob mob : mobs) {
            if (mob.distanceToSqr(player) < 4.0 * 4.0 || !VariantSight.inView(player, mob, 45.0)) {
                continue;
            }
            double angle = alignment(player, mob);
            if (angle > bestAngle && player.hasLineOfSight(mob)) {
                best = mob;
                bestAngle = angle;
            }
        }
        if (best == null && ctx.forced() && !mobs.isEmpty()) {
            best = mobs.stream().min(Comparator.comparingDouble(mob -> mob.distanceToSqr(player))).orElse(null);
        }
        return best;
    }

    /** Cosine between the player's view and the direction to the mob. */
    private static double alignment(ServerPlayer player, Mob mob) {
        double dx = mob.getX() - player.getX();
        double dy = mob.getY(0.5) - player.getEyeY();
        double dz = mob.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-3) {
            return 1.0;
        }
        Vec3 look = player.getViewVector(1.0F);
        return (look.x * dx + look.y * dy + look.z * dz) / length;
    }

    /** A forced run with nothing to act on: turns up to {@code count} mobs nearby first. */
    private static List<Mob> turnForced(EventContext ctx, MobVariant variant, int count, boolean passiveOnly) {
        ServerPlayer player = ctx.player();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        List<Mob> candidates = new ArrayList<>(manager.candidatesNear(player, 24.0, 12.0,
                mob -> !passiveOnly || !(mob instanceof Enemy)));
        candidates.sort(Comparator.comparingDouble(mob -> mob.distanceToSqr(player)));
        boolean operators = operators(ctx, variant);
        List<Mob> turned = new ArrayList<>();
        for (Mob mob : candidates) {
            if (turned.size() >= count || manager.room(player, ctx.corruption().stage(), true) <= 0) {
                break;
            }
            if (manager.set(mob, variant, variant == MobVariant.PARASITE ? 0.5F : 1.0F, operators)) {
                turned.add(mob);
            }
        }
        return turned;
    }

    /** One mob stops and stares at the player until {@code until} (everything of an event starts at once). */
    static void stareAt(Mob mob, ServerPlayer player, long until) {
        VariantData data = MobVariantManager.data(mob);
        if (data == null || !VariantGoals.Stare.mayHold(mob)) {
            return;
        }
        data.stare(until, player.getId(), true, false);
        VariantMotion.face(mob, player);
        mob.getNavigation().stop();
        // Held at once; from the next tick on the mob's pulse keeps (or ends) it.
        VariantMotion.hold(mob, VariantMotion.HOLD, true);
    }
}
