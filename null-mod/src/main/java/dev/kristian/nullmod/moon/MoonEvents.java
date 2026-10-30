package dev.kristian.nullmod.moon;

import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/**
 * Random events of the moon. The moon is shared by everyone, so when an event changes it (a new
 * crack, a piece breaking away) everybody looking at the sky sees it happen, not only the
 * player the event was rolled for.
 */
public final class MoonEvents {
    /** How far ahead of schedule an event may push the moon to show a new crack. */
    private static final float CRACK_REACH = 0.05F;
    /** The same for a piece breaking away. */
    private static final float PIECE_REACH = 0.08F;

    private MoonEvents() {
    }

    public static void register() {
        NullEventDefinition.builder("moon_crack")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(6))
                .globalCooldown(Ticks.minutes(3))
                .duration(50)
                .tags(EventTag.MOON)
                .place(EventPlace.OVERWORLD)
                .action(MoonEvents::crack)
                .register();
        NullEventDefinition.builder("moon_piece")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(10))
                .globalCooldown(Ticks.minutes(6))
                .duration(140)
                .tags(EventTag.MOON)
                .place(EventPlace.OVERWORLD)
                .action(MoonEvents::piece)
                .register();
        NullEventDefinition.builder("moon_distortion")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(8))
                .duration(90)
                .tags(EventTag.MOON, EventTag.VISUAL)
                .place(EventPlace.OVERWORLD)
                .action(MoonEvents::distort)
                .register();
    }

    /** A new crack runs across the moon (or, if none is due, the newest one glints). */
    private static boolean crack(EventContext ctx) {
        MinecraftServer server = ctx.server();
        CorruptionData data = ctx.corruption().data();
        if (!MoonCorruptionManager.enabled() || data.moonGone()) {
            return false;
        }
        if (!ctx.forced() && !MoonCorruptionManager.canSeeMoon(ctx.player())) {
            return false;
        }
        MoonGeometry geometry = MoonGeometry.forSeed(data.seed());
        float progress = data.moonProgress();
        int shown = -1;
        int next = geometry.nextCrack(progress);
        if (next >= 0 && progress < MoonGeometry.SHATTER) {
            MoonGeometry.Crack crack = geometry.crack(next);
            if (crack.fullAt() - progress <= CRACK_REACH) {
                MoonCorruptionManager.INSTANCE.bump(server, data, crack.fullAt() + 0.001F);
                shown = next;
            }
        }
        if (shown < 0) {
            shown = geometry.newestCrack(data.moonProgress());
            if (shown < 0 && !ctx.forced()) {
                return false;
            }
        }
        MoonCorruptionManager.broadcast(server, ctx.player(), MoonCorruptionManager.EFFECT_CRACK, ctx.seed(),
                Math.max(0.5F, ctx.intensity()), ctx.duration(), shown);
        return true;
    }

    /** A piece visibly breaks away and drifts off. */
    private static boolean piece(EventContext ctx) {
        MinecraftServer server = ctx.server();
        CorruptionData data = ctx.corruption().data();
        if (!MoonCorruptionManager.enabled() || data.moonGone()) {
            return false;
        }
        if (!ctx.forced() && !MoonCorruptionManager.canSeeMoon(ctx.player())) {
            return false;
        }
        MoonGeometry geometry = MoonGeometry.forSeed(data.seed());
        float progress = data.moonProgress();
        int next = geometry.nextHole(progress);
        int shown = -1;
        if (next >= 0) {
            float breakAt = geometry.shard(next).breakAt();
            if (breakAt - progress <= PIECE_REACH) {
                MoonCorruptionManager.INSTANCE.bump(server, data, breakAt + 0.001F);
                shown = next;
            }
        }
        if (shown < 0 && !ctx.forced()) {
            return false;
        }
        // Forced with no piece due: a small chip comes off the rim without changing the moon (-1).
        MoonCorruptionManager.broadcast(server, ctx.player(), MoonCorruptionManager.EFFECT_PIECE, ctx.seed(),
                Math.max(0.5F, ctx.intensity()), ctx.duration(), shown);
        return true;
    }

    /** The moon wobbles and warps for a moment; once it is gone, a ghost of it flickers back. */
    private static boolean distort(EventContext ctx) {
        CorruptionData data = ctx.corruption().data();
        if (!ctx.forced() && !MoonCorruptionManager.canSeeMoon(ctx.player())) {
            return false;
        }
        if (!ctx.forced() && !data.moonGone() && data.moonProgress() <= 0.0F) {
            return false;
        }
        ctx.sendEffect(MoonCorruptionManager.EFFECT_DISTORT, Optional.empty(), data.moonGone() ? 1 : 0, "");
        return true;
    }
}
