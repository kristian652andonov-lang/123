package dev.kristian.nullmod.field;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Nothing in the field can be broken or built: the houses are the only shelter, so their walls,
 * windows and doors hold, and no one can dig in or wall themselves off. Players in creative mode
 * (operators testing) are left alone.
 */
final class FieldProtection {
    private FieldProtection() {
    }

    static void init(IEventBus gameBus) {
        gameBus.addListener(FieldProtection::onBreak);
        gameBus.addListener(FieldProtection::onPlace);
        gameBus.addListener(FieldProtection::onExplosion);
    }

    private static boolean inField(LevelAccessor level) {
        return level instanceof Level real && FieldDimension.isField(real);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (inField(event.getLevel()) && (player == null || !player.isCreative())) {
            event.setCanceled(true);
        }
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (inField(event.getLevel()) && !(entity instanceof Player player && player.isCreative())) {
            event.setCanceled(true);
        }
    }

    private static void onExplosion(ExplosionEvent.Detonate event) {
        if (inField(event.getLevel())) {
            event.getAffectedBlocks().clear();
        }
    }
}
