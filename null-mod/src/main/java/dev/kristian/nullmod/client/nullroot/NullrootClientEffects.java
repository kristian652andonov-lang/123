package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.entity.nullroot.NullrootEvents;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The client halves of the NULLROOT events: {@code nullroot.glimpse} (a tendril at the edge of
 * the screen, {@link NullrootGlimpseLayer}) and {@code nullroot.pulse} (the ground pulses: two
 * organic thumps like a slow heartbeat from below, each with a short darkening and the faintest
 * shake).
 */
final class NullrootClientEffects {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Beat> PENDING = new ArrayList<>();

    /** The second beat of a pulse, a few ticks after the first. */
    private static final class Beat {
        int ticks;
        final Vec3 pos;
        final float strength;

        Beat(int ticks, Vec3 pos, float strength) {
            this.ticks = ticks;
            this.pos = pos;
            this.strength = strength;
        }
    }

    private NullrootClientEffects() {
    }

    static void register() {
        ClientEffects.register(NullrootEvents.EFFECT_GLIMPSE,
                effect -> NullrootGlimpseLayer.start(effect.seed(), effect.intensity(), effect.duration()));
        ClientEffects.register(NullrootEvents.EFFECT_PULSE, NullrootClientEffects::pulse);
    }

    private static void pulse(ClientEffectPayload effect) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Vec3 pos = effect.pos().map(Vec3::atCenterOf).orElse(minecraft.player.position().add(0.0, -2.0, 0.0));
        float strength = Mth.clamp(effect.intensity(), 0.2F, 1.0F);
        thump(pos, strength, 1.0F);
        PENDING.add(new Beat(9 + RANDOM.nextInt(4), pos, strength * 0.65F));
    }

    private static void thump(Vec3 pos, float intensity, float weight) {
        AudioCorruptionManager.playAt(NullSounds.NULLROOT_PULSE.get(), AudioCorruptionManager.Channel.CREATURE, pos,
                (0.55F + 0.45F * intensity) * weight, 0.78F + RANDOM.nextFloat() * 0.12F, 32.0F);
        VisualDistortionManager.add(VisualDistortionManager.Kind.DARKEN, (0.16F + 0.3F * intensity) * weight, 14);
        VisualDistortionManager.shake((0.05F + 0.1F * intensity) * weight, 8);
    }

    static void clientTick() {
        if (PENDING.isEmpty() || Minecraft.getInstance().isPaused()) {
            return;
        }
        for (Iterator<Beat> it = PENDING.iterator(); it.hasNext(); ) {
            Beat beat = it.next();
            if (--beat.ticks <= 0) {
                it.remove();
                thump(beat.pos, beat.strength, 0.7F);
            }
        }
    }

    static void reset() {
        PENDING.clear();
    }
}
