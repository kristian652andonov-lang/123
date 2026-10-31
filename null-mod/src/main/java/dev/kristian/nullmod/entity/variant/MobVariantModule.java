package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.gametest.MobVariantGameTests;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Corrupted and parasite versions of every mob (server side): the {@link MobVariantManager}
 * system, its game-bus listeners, the sound hook, the mob_* random events, {@code /null mob} and
 * the GameTests. The variant itself is the {@code null:mob_variant} attachment
 * ({@code NullAttachments.MOB_VARIANT}); clients learn about it through {@code MobVariantPayload}
 * and draw it in {@code client.variant}. Nothing here touches client classes.
 * <p>
 * The listeners run inside chunk loading, deaths and sounds: a failure in them is logged and
 * swallowed, never allowed to stop a world from loading or a mob from dying.
 */
public final class MobVariantModule {
    private static int failures;

    private MobVariantModule() {
    }

    public static void init(IEventBus modBus, IEventBus gameBus) {
        CorruptionManager.registerSystem(MobVariantManager.INSTANCE);
        MobVariantEvents.register();

        // Last, so a join another mod cancels never reaches us.
        gameBus.addListener(EventPriority.LOWEST, MobVariantModule::onJoinLevel);
        gameBus.addListener(MobVariantModule::onLeaveLevel);
        gameBus.addListener(MobVariantModule::onStartTracking);
        gameBus.addListener(EventPriority.LOWEST, MobVariantModule::onLivingDeath);
        gameBus.addListener(EventPriority.LOWEST, MobVariantModule::onConversion);
        gameBus.addListener(EventPriority.LOWEST, MobVariantModule::onBabySpawn);
        gameBus.addListener(EventPriority.LOW, MobVariantModule::onPlaySound);
        gameBus.addListener(MobVariantCommand::onRegisterCommands);
        modBus.addListener(MobVariantModule::onRegisterGameTests);
    }

    private static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob mob && event.getLevel() instanceof ServerLevel level) {
            guard("join", () -> MobVariantManager.INSTANCE.onJoin(mob, level, event.loadedFromDisk()));
        }
    }

    private static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob && !event.getLevel().isClientSide()) {
            MobVariantManager.INSTANCE.onLeave(mob);
        }
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            guard("tracking", () -> MobVariantManager.INSTANCE.onStartTracking(player, event.getTarget()));
        }
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && !mob.level().isClientSide()) {
            guard("death", () -> MobVariantManager.INSTANCE.onDeath(mob));
        }
    }

    private static void onConversion(LivingConversionEvent.Post event) {
        if (event.getEntity() instanceof Mob from && event.getOutcome() instanceof Mob to && !to.level().isClientSide()) {
            guard("conversion", () -> MobVariantManager.INSTANCE.onConverted(from, to));
        }
    }

    private static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (event.getChild() != null && !event.getChild().level().isClientSide()) {
            guard("breeding", () -> MobVariantManager.INSTANCE.onBabyBorn(event.getParentA(), event.getParentB(), event.getChild()));
        }
    }

    private static void onPlaySound(PlayLevelSoundEvent.AtPosition event) {
        // Every sound of the world passes here: nothing to do (and nothing allocated) without variants.
        if (event.getLevel() instanceof ServerLevel && MobVariantManager.INSTANCE.anyLoaded()) {
            guard("sound", () -> VariantSounds.onPlaySound(event));
        }
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(MobVariantGameTests.class);
    }

    private static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Logged in full a few times, then quietly: a broken mob must not flood the log either.
            if (failures++ < 10) {
                NullMod.LOGGER.error("NULL mob variants failed on {}", what, e);
            }
        }
    }
}
