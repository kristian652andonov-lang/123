package dev.kristian.nullmod.client.moon;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.moon.MoonGeometry;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The moon's client effects and their running animations: a crack flashing as it runs across
 * the moon, a piece snapping off, the moon warping, the shattering and the final
 * disappearance. Times are in client ticks (plus partial ticks while rendering).
 */
final class MoonEffects {
    static final int PIECE_TICKS = 110;
    static final int CHIP_TICKS = 90;
    /** How long the explosion of the final disappearance is visible in the sky. */
    static final int GONE_VISIBLE_TICKS = 70;
    private static final int GONE_SEQUENCE_TICKS = 100;
    private static final int MAX_PIECES = 24;
    private static final int MAX_CHIPS = 12;

    private static long ticks;

    private static float flashStart = -1.0F;
    private static float flashRise;
    private static float flashHold;
    private static float flashFall;
    private static float flashPeak;

    private static final int[] PIECE_SHARD = new int[MAX_PIECES];
    private static final float[] PIECE_START = new float[MAX_PIECES];
    private static int pieces;

    private static final float[] CHIP_START = new float[MAX_CHIPS];
    private static final float[] CHIP_ANGLE = new float[MAX_CHIPS];
    private static final float[] CHIP_SPIN = new float[MAX_CHIPS];
    private static final int[] CHIP_SPRITE = new int[MAX_CHIPS];
    private static int chips;

    private static float distortStart = -1.0F;
    private static float distortLength;
    private static float distortStrength;
    private static boolean distortGhost;

    private static float goneStart = -1.0F;
    private static boolean goneWatching;
    private static int goneStep;

    /** A crack flash that arrived before the moon textures existed (the very first crack). */
    private static int[] pendingGlow;
    private static boolean pendingGlowAll;
    private static float pendingGlowPeak;
    private static float pendingGlowFall;
    private static int pendingGlowTicks;

    private MoonEffects() {
    }

    // ------------------------------------------------------------------ clock

    static void tick() {
        ticks++;
        float now = ticks;
        if (flashStart >= 0.0F && now - flashStart > flashRise + flashHold + flashFall) {
            flashStart = -1.0F;
        }
        int keep = 0;
        for (int i = 0; i < pieces; i++) {
            if (now - PIECE_START[i] <= PIECE_TICKS) {
                PIECE_SHARD[keep] = PIECE_SHARD[i];
                PIECE_START[keep] = PIECE_START[i];
                keep++;
            }
        }
        pieces = keep;
        keep = 0;
        for (int i = 0; i < chips; i++) {
            if (now - CHIP_START[i] <= CHIP_TICKS) {
                CHIP_START[keep] = CHIP_START[i];
                CHIP_ANGLE[keep] = CHIP_ANGLE[i];
                CHIP_SPIN[keep] = CHIP_SPIN[i];
                CHIP_SPRITE[keep] = CHIP_SPRITE[i];
                keep++;
            }
        }
        chips = keep;
        boolean wasHiding = hidesMoon();
        if (distortStart >= 0.0F && now - distortStart > distortLength) {
            distortStart = -1.0F;
        }
        if (goneStart >= 0.0F) {
            runGoneSequence(now - goneStart);
        }
        if (wasHiding != hidesMoon()) {
            MoonTextureManager.invalidate();
        }
        if (pendingGlowTicks > 0) {
            pendingGlowTicks--;
            ClientLevel level = overworld();
            if (level != null && MoonTextureManager.prepareGlow(pendingGlowAll ? null : pendingGlow,
                    ClientCorruptionState.moonProgress(), level.getMoonPhase())) {
                startFlash(pendingGlowPeak, pendingGlowFall);
                pendingGlowTicks = 0;
            }
        }
    }

    static float now(float partialTick) {
        return ticks + partialTick;
    }

    static void reset() {
        flashStart = -1.0F;
        pieces = 0;
        chips = 0;
        distortStart = -1.0F;
        goneStart = -1.0F;
        goneStep = 0;
        pendingGlowTicks = 0;
        pendingGlow = null;
    }

    /** True while an effect needs NULL's moon textures, even if the moon is not broken yet. */
    static boolean needsTextures() {
        return distortStart >= 0.0F || flashStart >= 0.0F || pendingGlowTicks > 0 || pieces > 0 || chips > 0 || goneStart >= 0.0F;
    }

    // ------------------------------------------------------------------ queries for the renderer

    /** The real moon must be hidden because the renderer draws it (warping, or blowing up). */
    static boolean hidesMoon() {
        if (distortStart >= 0.0F && !distortGhost) {
            return true;
        }
        return goneStart >= 0.0F && ticks - goneStart <= GONE_VISIBLE_TICKS;
    }

    /** 0..1 strength of the crack flash overlay. */
    static float flash(float now) {
        if (flashStart < 0.0F) {
            return 0.0F;
        }
        float t = now - flashStart;
        if (t < 0.0F) {
            return 0.0F;
        }
        if (t < flashRise) {
            return flashPeak * t / flashRise;
        }
        t -= flashRise;
        if (t < flashHold) {
            return flashPeak;
        }
        t -= flashHold;
        if (t < flashFall) {
            float f = 1.0F - t / flashFall;
            return flashPeak * f * f;
        }
        return 0.0F;
    }

    /** 0..1 progress of a piece's breaking-off animation, 1 if it is not animating. */
    static float pieceAnimation(int shard, float now) {
        for (int i = 0; i < pieces; i++) {
            if (PIECE_SHARD[i] == shard) {
                return Math.max(0.0F, Math.min(1.0F, (now - PIECE_START[i]) / PIECE_TICKS));
            }
        }
        return 1.0F;
    }

    static int chipCount() {
        return chips;
    }

    /** 0..1 age of a chip, the angle on the rim it flies off from, its spin and sprite. */
    static float chipAge(int index, float now) {
        return Math.max(0.0F, Math.min(1.0F, (now - CHIP_START[index]) / CHIP_TICKS));
    }

    static float chipAngle(int index) {
        return CHIP_ANGLE[index];
    }

    static float chipSpin(int index) {
        return CHIP_SPIN[index];
    }

    static int chipSprite(int index) {
        return CHIP_SPRITE[index];
    }

    /** 0..1 strength of the warp right now (smooth in and out). */
    static float distortion(float now) {
        if (distortStart < 0.0F) {
            return 0.0F;
        }
        float t = now - distortStart;
        if (t < 0.0F || t > distortLength) {
            return 0.0F;
        }
        float ramp = Math.min(1.0F, Math.min(t / 12.0F, (distortLength - t) / 18.0F));
        return distortStrength * ramp * ramp * (3.0F - 2.0F * ramp);
    }

    static boolean distortionGhost() {
        return distortGhost;
    }

    /** Ticks since the final disappearance began, or -1. */
    static float goneTime(float now) {
        return goneStart < 0.0F ? -1.0F : now - goneStart;
    }

    static boolean goneWatching() {
        return goneWatching;
    }

    // ------------------------------------------------------------------ effect handlers

    /** moon.crack: data = crack index (-1 = the newest crack). */
    static void crack(ClientEffectPayload effect) {
        ClientLevel level = overworld();
        if (level == null) {
            return;
        }
        float progress = ClientCorruptionState.moonProgress();
        MoonGeometry geometry = MoonGeometry.forSeed(ClientCorruptionState.seed());
        int index = effect.data();
        if (index < 0 || index >= geometry.crackCount()) {
            index = geometry.newestCrack(progress);
        }
        if (index >= 0) {
            requestGlow(new int[] {index}, false, 0.55F + 0.45F * effect.intensity(), 26.0F, level);
        }
        float pitch = 0.9F + RandomSource.create(effect.seed()).nextFloat() * 0.2F;
        playFromMoon(level, NullSounds.MOON_CRACK.get(), 0.55F + 0.45F * effect.intensity(), pitch);
    }

    /** moon.piece: data = shard index that just broke away (-1 = a small chip off the rim). */
    static void piece(ClientEffectPayload effect) {
        ClientLevel level = overworld();
        if (level == null) {
            return;
        }
        RandomSource random = RandomSource.create(effect.seed());
        MoonGeometry geometry = MoonGeometry.forSeed(ClientCorruptionState.seed());
        int shard = effect.data();
        if (shard >= 0 && shard < geometry.shardCount()) {
            startPiece(shard);
            int[] border = borderCracks(geometry, shard);
            if (border.length > 0) {
                requestGlow(border, false, 0.45F + 0.35F * effect.intensity(), 30.0F, level);
            }
        } else {
            startChip(random);
        }
        playFromMoon(level, NullSounds.MOON_FRAGMENT.get(), 0.6F + 0.4F * effect.intensity(), 0.85F + random.nextFloat() * 0.25F);
    }

    /** moon.distort: the moon wobbles and warps (data 1 or no moon: a ghost of it flickers). */
    static void distort(ClientEffectPayload effect) {
        ClientLevel level = overworld();
        if (level == null) {
            return;
        }
        boolean ghost = effect.data() == 1 || ClientCorruptionState.moonGone();
        boolean wasHiding = hidesMoon();
        distortStart = ticks;
        distortLength = Math.max(40, Math.min(200, effect.duration()));
        distortStrength = 0.4F + 0.6F * Math.max(0.0F, Math.min(1.0F, effect.intensity()));
        distortGhost = ghost;
        if (wasHiding != hidesMoon()) {
            MoonTextureManager.invalidate();
        }
        playFromMoon(level, NullSounds.DIGITAL_INTERFERENCE.get(), 0.3F + 0.2F * effect.intensity(), 0.55F);
    }

    /** moon.shatter: the whole crack network lights up and pieces fly apart. */
    static void shatter(ClientEffectPayload effect) {
        ClientLevel level = overworld();
        if (level == null) {
            return;
        }
        float progress = ClientCorruptionState.moonProgress();
        MoonGeometry geometry = MoonGeometry.forSeed(ClientCorruptionState.seed());
        requestGlow(null, true, 0.8F, 40.0F, level);
        for (int k = 0; k < geometry.holeCount(); k++) {
            MoonGeometry.Shard shard = geometry.shard(geometry.hole(k));
            if (shard.breakAt() <= progress && shard.breakAt() > progress - 0.03F) {
                startPiece(shard.index());
            }
        }
        playFromMoon(level, NullSounds.MOON_SHATTER.get(), 0.9F, 1.0F);
        VisualDistortionManager.shake(0.2F + 0.25F * effect.intensity(), 30);
    }

    /** moon.gone: the final disappearance (data 1 = this player can see the sky). */
    static void gone(ClientEffectPayload effect) {
        ClientLevel level = overworld();
        if (level == null) {
            return;
        }
        goneStart = ticks;
        goneStep = 0;
        goneWatching = effect.data() == 1;
        distortStart = -1.0F;
        flashStart = -1.0F;
        MoonTextureManager.invalidate();
        AudioCorruptionManager.playGlobal(NullSounds.MOON_GONE.get(), AudioCorruptionManager.Channel.EVENT, 1.0F, 1.0F);
        if (goneWatching) {
            playFromMoon(level, NullSounds.MOON_SHATTER.get(), 1.0F, 0.8F);
            VisualDistortionManager.add(VisualDistortionManager.Kind.FLASH, 0.65F, 14);
        }
    }

    // ------------------------------------------------------------------ internals

    private static void runGoneSequence(float t) {
        if (goneStep == 0 && t >= 4.0F) {
            goneStep = 1;
            VisualDistortionManager.shake(0.9F, 80);
        }
        if (goneStep == 1 && t >= 36.0F) {
            goneStep = 2;
            VisualDistortionManager.add(VisualDistortionManager.Kind.BLACKOUT, 0.95F, 46);
        }
        if (t > GONE_SEQUENCE_TICKS) {
            goneStart = -1.0F;
            goneStep = 0;
        }
    }

    /** Lights up cracks now, or as soon as the moon textures exist (within a second). */
    private static void requestGlow(int[] cracks, boolean all, float peak, float fall, ClientLevel level) {
        if (MoonTextureManager.prepareGlow(all ? null : cracks, ClientCorruptionState.moonProgress(), level.getMoonPhase())) {
            startFlash(peak, fall);
            pendingGlowTicks = 0;
            return;
        }
        pendingGlow = cracks;
        pendingGlowAll = all;
        pendingGlowPeak = peak;
        pendingGlowFall = fall;
        pendingGlowTicks = 20;
    }

    private static void startFlash(float peak, float fall) {
        float flashing = ClientLimits.flashing();
        flashPeak = Math.max(0.0F, Math.min(1.0F, peak)) * (0.3F + 0.7F * flashing);
        // Without flashing allowed the glow fades in and out slowly instead of popping.
        flashRise = flashing < 0.3F ? 12.0F : 2.0F;
        flashHold = 5.0F;
        flashFall = flashing < 0.3F ? fall * 1.5F : fall;
        flashStart = ticks;
    }

    private static void startPiece(int shard) {
        for (int i = 0; i < pieces; i++) {
            if (PIECE_SHARD[i] == shard) {
                PIECE_START[i] = ticks;
                return;
            }
        }
        if (pieces < MAX_PIECES) {
            PIECE_SHARD[pieces] = shard;
            PIECE_START[pieces] = ticks;
            pieces++;
        }
    }

    private static void startChip(RandomSource random) {
        if (chips >= MAX_CHIPS) {
            return;
        }
        CHIP_START[chips] = ticks;
        CHIP_ANGLE[chips] = random.nextFloat() * (float) (Math.PI * 2.0);
        CHIP_SPIN[chips] = (random.nextFloat() - 0.5F) * 2.0F;
        CHIP_SPRITE[chips] = random.nextInt(16);
        chips++;
    }

    /** The cracks around a piece (a shard and the corner shards that break away with it), not those inside it. */
    private static int[] borderCracks(MoonGeometry geometry, int shard) {
        int count = 0;
        for (int i = 0; i < geometry.crackCount(); i++) {
            if (outlines(geometry, geometry.crack(i), shard)) {
                count++;
            }
        }
        int[] out = new int[count];
        for (int i = 0, k = 0; i < geometry.crackCount(); i++) {
            if (outlines(geometry, geometry.crack(i), shard)) {
                out[k++] = i;
            }
        }
        return out;
    }

    private static boolean outlines(MoonGeometry geometry, MoonGeometry.Crack crack, int piece) {
        if (crack.shardA() < 0 || crack.shardB() < 0) {
            return false;
        }
        return (pieceOf(geometry, crack.shardA()) == piece) != (pieceOf(geometry, crack.shardB()) == piece);
    }

    private static int pieceOf(MoonGeometry geometry, int shard) {
        int leader = geometry.shard(shard).leader();
        return leader >= 0 ? leader : shard;
    }

    private static ClientLevel overworld() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension() == Level.OVERWORLD ? level : null;
    }

    /** Unit vector from the player towards the moon, matching the sky's own rotation. */
    static Vec3 moonDirection(ClientLevel level, float partialTick) {
        double angle = level.getTimeOfDay(partialTick) * Math.PI * 2.0;
        return new Vec3(Math.sin(angle), -Math.cos(angle), 0.0);
    }

    private static void playFromMoon(ClientLevel level, SoundEvent sound, float volume, float pitch) {
        AudioCorruptionManager.playFromDirection(sound, AudioCorruptionManager.Channel.EVENT, moonDirection(level, 1.0F), volume, pitch);
    }
}
