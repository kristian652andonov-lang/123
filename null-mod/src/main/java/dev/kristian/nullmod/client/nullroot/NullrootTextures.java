package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.entity.nullroot.NullrootAnimation;
import dev.kristian.nullmod.entity.nullroot.NullrootEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * What a NULLROOT looks like this frame. Most of them are completely black; some show the
 * purple-and-black missing texture or a distorted error texture instead, and every one of them
 * now and then glitches into another of those looks for a tick or two (more often while it
 * holds somebody, always when hit). The glitching scales with the flashing setting and stops
 * entirely at zero. Textures come from {@code tools/gen_nullroot.py}.
 */
final class NullrootTextures {
    static final ResourceLocation BLACK = texture("nullroot_black");
    static final ResourceLocation MISSING = texture("nullroot_missing");
    static final ResourceLocation ERROR = texture("nullroot_error");
    static final ResourceLocation STATIC = texture("nullroot_static");
    /** Emissive veins, drawn additively over the body while it pulses. */
    static final ResourceLocation VEINS = texture("nullroot_veins");

    private static final ResourceLocation[] LOOKS = {BLACK, MISSING, ERROR, STATIC};

    private NullrootTextures() {
    }

    private static ResourceLocation texture(String name) {
        return NullMod.id("textures/entity/nullroot/" + name + ".png");
    }

    /** The look an organism keeps between glitches: black for four in five of them. */
    static int baseLook(long seed) {
        long r = NullrootGeometry.mix(seed ^ 0x6E756C6C726F6F74L) & 1023L;
        if (r < 820L) {
            return 0;
        }
        if (r < 930L) {
            return 1;
        }
        return r < 995L ? 2 : 3;
    }

    static ResourceLocation pick(NullrootEntity organism, NullrootAnimation anim) {
        int base = baseLook(anim.seed);
        float flashing = ClientLimits.flashing();
        if (flashing <= 0.05F) {
            return LOOKS[base];
        }
        // Two-tick slots: a glitch is a short wrong frame, never a strobe.
        long slot = anim.age / 2;
        long h = NullrootGeometry.mix(slot * 0x9E3779B97F4A7C15L ^ anim.seed);
        float chance = 0.018F + 0.1F * anim.capture;
        if (organism.hurtTime > 0) {
            chance += 0.55F;
        }
        if (organism.deathTime > 0) {
            chance += 0.4F;
        }
        if ((h & 1023L) < (long) (Math.min(0.85F, chance) * flashing * 1024.0F)) {
            int other = (int) ((h >>> 10) % 3L);
            return LOOKS[(base + 1 + other) % LOOKS.length];
        }
        return LOOKS[base];
    }
}
