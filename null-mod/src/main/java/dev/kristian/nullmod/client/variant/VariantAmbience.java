package dev.kristian.nullmod.client.variant;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.entity.variant.MobVariant;
import dev.kristian.nullmod.registry.NullSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Faint sounds close to variant mobs, on top of what the server makes them do: a corrupted mob
 * hisses with static now and then, a parasite's body squelches. Only within a few blocks,
 * seldom, never two at once, and through {@link AudioCorruptionManager} (static on the static
 * noise channel), so every audio setting applies.
 */
final class VariantAmbience {
    private static final double RANGE = 14.0;
    private static final int CHECK_EVERY = 10;
    private static final int[] IDS = new int[128];
    private static final RandomSource RANDOM = RandomSource.create();
    private static int ticks;
    private static int cursor;
    private static int quietUntil;

    private VariantAmbience() {
    }

    static void clientTick() {
        ticks++;
        if (ticks % CHECK_EVERY != 0 || ticks < quietUntil || ClientMobVariants.size() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused() || ClientLimits.audio() <= 0.0F) {
            return;
        }
        int n = ClientMobVariants.ids(IDS);
        if (n == 0) {
            return;
        }
        int checks = Math.min(4, n);
        for (int c = 0; c < checks; c++) {
            int id = IDS[(cursor + c) % n];
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity.distanceToSqr(player) > RANGE * RANGE) {
                continue;
            }
            ClientMobVariants.State state = ClientMobVariants.get(entity);
            MobVisual visual = MobVisuals.get(id);
            if (state == null || visual == null || ticks < visual.nextSound || RANDOM.nextInt(3) != 0) {
                continue;
            }
            Vec3 at = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ());
            if (state.variant() == MobVariant.CORRUPTED) {
                if (RANDOM.nextInt(3) == 0) {
                    AudioCorruptionManager.playAt(NullSounds.GLITCH.get(), AudioCorruptionManager.Channel.STATIC, at,
                            0.18F + RANDOM.nextFloat() * 0.1F, 0.5F + RANDOM.nextFloat() * 0.3F, 10.0F);
                } else {
                    AudioCorruptionManager.playAt(NullSounds.STATIC_FAINT.get(), AudioCorruptionManager.Channel.STATIC, at,
                            0.12F + RANDOM.nextFloat() * 0.1F, 0.6F + RANDOM.nextFloat() * 0.4F, 10.0F);
                }
            } else if (state.variant() == MobVariant.PARASITE) {
                float size = Math.max(entity.getBbWidth(), entity.getBbHeight());
                AudioCorruptionManager.playAt(NullSounds.NULLROOT_IDLE.get(), AudioCorruptionManager.Channel.CREATURE, at,
                        0.15F + 0.15F * state.growth(), Mth.clamp(1.25F - 0.25F * size, 0.7F, 1.3F), 12.0F);
            } else {
                continue;
            }
            visual.nextSound = ticks + 160 + RANDOM.nextInt(200);
            quietUntil = ticks + 60;
            break;
        }
        cursor += checks;
        if (cursor > 1_000_000) {
            cursor = 0;
        }
    }

    static void reset() {
        quietUntil = 0;
        cursor = 0;
    }
}
