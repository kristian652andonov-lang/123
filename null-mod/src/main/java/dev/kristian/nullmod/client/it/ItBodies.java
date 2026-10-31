package dev.kristian.nullmod.client.it;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Whoever IT swallowed is gone: their dead body is not drawn (a player's body otherwise lies
 * where they died until they respawn). Only dead entities are hidden, so somebody a totem saved
 * stays visible; a respawned player keeps their entity id but is alive again. Entries expire on
 * their own. Render thread only.
 */
final class ItBodies {
    /** How long a swallowed body stays hidden, client ticks. */
    private static final int HIDE_TICKS = 20 * 60;
    private static final Map<Integer, Integer> SWALLOWED = new HashMap<>();
    private static int now;
    @Nullable
    private static ClientLevel lastLevel;

    private ItBodies() {
    }

    static void swallowed(@Nullable Entity victim) {
        if (victim != null) {
            SWALLOWED.put(victim.getId(), now + HIDE_TICKS);
        }
    }

    static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (SWALLOWED.isEmpty()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (entity.isDeadOrDying() && SWALLOWED.containsKey(entity.getId())) {
            event.setCanceled(true);
        }
    }

    static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            // Entity ids mean nothing in another world.
            lastLevel = mc.level;
            SWALLOWED.clear();
        }
        now++;
        if (SWALLOWED.isEmpty() || now % 40 != 0) {
            return;
        }
        for (Iterator<Map.Entry<Integer, Integer>> it = SWALLOWED.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    static void reset() {
        SWALLOWED.clear();
        lastLevel = null;
    }
}
