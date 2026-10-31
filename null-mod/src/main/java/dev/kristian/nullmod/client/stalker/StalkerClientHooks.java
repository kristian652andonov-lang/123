package dev.kristian.nullmod.client.stalker;

import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.entity.stalker.StalkerAnimation;
import dev.kristian.nullmod.entity.stalker.StalkerClientHook;
import dev.kristian.nullmod.entity.stalker.StalkerEntity;
import dev.kristian.nullmod.entity.stalker.StalkerSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The figure on the client: the smoothed animation values the model reads (leaning out, the hand
 * on the glass, the pickaxe, stooping under low ceilings, the twitch) and the sounds that belong
 * to its body (heavy footfalls as it moves, breathing against the glass, a fingertip tapping).
 * Every sound goes through {@link AudioCorruptionManager}, so the player's audio settings apply.
 */
final class StalkerClientHooks implements StalkerClientHook {
    /** Blocks walked per footfall: a long stride. */
    private static final float STRIDE = 1.35F;
    private final RandomSource random = RandomSource.create();

    @Override
    public void tick(StalkerEntity stalker) {
        StalkerAnimation a = stalker.animation;
        a.rollOver();
        byte act = stalker.act();
        boolean peeking = act == StalkerEntity.ACT_PEEK;
        // It leans out slowly and is back behind the trunk in a moment.
        a.peek = Mth.approach(a.peek, peeking ? stalker.peekSide() : 0.0F, peeking ? 0.1F : 0.3F);
        a.glass = Mth.approach(a.glass, stalker.hasFlag(StalkerEntity.FLAG_HAND_ON_GLASS) ? 1.0F : 0.0F, 0.05F);
        a.dig = Mth.approach(a.dig, act == StalkerEntity.ACT_DIG ? 1.0F : 0.0F, 0.12F);
        if (a.swingTicks >= 0 && ++a.swingTicks >= StalkerEntity.SWING_TICKS) {
            a.swingTicks = -1;
        }
        a.hunch = Mth.approach(a.hunch, lowCeiling(stalker) ? 1.0F : 0.0F, 0.15F);
        a.twitch *= 0.72F;
        sounds(stalker, a, act);
    }

    /** True when something solid is where its head would be standing upright. */
    private static boolean lowCeiling(StalkerEntity stalker) {
        BlockPos above = stalker.blockPosition().above(2);
        BlockState state = stalker.level().getBlockState(above);
        return !state.getCollisionShape(stalker.level(), above).isEmpty();
    }

    private void sounds(StalkerEntity stalker, StalkerAnimation a, byte act) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.level() != stalker.level()) {
            return;
        }
        double dx = stalker.getX() - stalker.xo;
        double dz = stalker.getZ() - stalker.zo;
        float moved = (float) Math.sqrt(dx * dx + dz * dz);
        if (moved > 0.004F && moved < 2.0F && stalker.onGround()) {
            a.stepDistance += moved;
            if (a.stepDistance >= STRIDE) {
                a.stepDistance = 0.0F;
                AudioCorruptionManager.play(StalkerSounds.FOOTSTEPS.get(), AudioCorruptionManager.Channel.FOOTSTEPS,
                        stalker.position(), 0.75F, 0.86F + random.nextFloat() * 0.1F);
            }
        }
        if (act == StalkerEntity.ACT_WINDOW && player.distanceToSqr(stalker) < 12.0 * 12.0) {
            if (--a.breathCooldown <= 0) {
                a.breathCooldown = 90 + random.nextInt(70);
                AudioCorruptionManager.play(StalkerSounds.BREATH_GLASS.get(), AudioCorruptionManager.Channel.CREATURE,
                        stalker.visualEyes(), 0.6F, 0.92F + random.nextFloat() * 0.12F);
            }
        } else {
            a.breathCooldown = Math.max(a.breathCooldown, 20 + random.nextInt(40));
        }
    }

    @Override
    public void handleEvent(StalkerEntity stalker, byte event) {
        StalkerAnimation a = stalker.animation;
        if (event == StalkerEntity.EVENT_SWING) {
            a.swingTicks = 0;
        } else if (event == StalkerEntity.EVENT_TWITCH) {
            a.twitch = 1.0F;
        } else if (event == StalkerEntity.EVENT_TAP) {
            // A fingertip on the glass, just in front of its face.
            float yaw = stalker.yBodyRot * Mth.DEG_TO_RAD;
            Vec3 at = stalker.visualEyes().add(-Mth.sin(yaw) * 0.45, -0.1, Mth.cos(yaw) * 0.45);
            AudioCorruptionManager.play(StalkerSounds.GLASS_TAP.get(), AudioCorruptionManager.Channel.EVENT, at,
                    0.55F, 0.95F + random.nextFloat() * 0.1F);
        }
    }
}
