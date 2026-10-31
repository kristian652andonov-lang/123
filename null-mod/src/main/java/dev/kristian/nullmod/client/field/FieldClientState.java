package dev.kristian.nullmod.client.field;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.field.FieldCycle;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.network.FieldPayload;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * The field's day and night as this client sees it: the last {@link FieldPayload} from the server,
 * counted on locally between syncs (and frozen while the game or NULL is paused), and everything the
 * sky, fog, light, HUD and sound derive from it. Render thread only.
 */
final class FieldClientState {
    /** How much sky light the night leaves: enough to make out shapes right next to you. */
    static final float NIGHT_SKY_LIGHT = 0.035F;

    private static boolean active;
    @Nullable
    private static ClientLevel level;
    private static FieldCycle.Phase phase = FieldCycle.Phase.DAY;
    private static int elapsedAtSync;
    private static int length = FieldCycle.length(FieldCycle.Phase.DAY);
    private static int cycle;
    private static long seed;
    /** Client ticks counted while the field's clock runs. */
    private static long clock;
    private static long syncClock;

    private FieldClientState() {
    }

    /** A payload from the server. Calls back into the ambience and the sky when a phase begins. */
    static void accept(FieldPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        FieldCycle.Phase next = FieldCycle.Phase.byId(payload.phase());
        boolean changed = !active || next != phase || payload.cycle() != cycle || mc.level != level;
        // Seen live (rather than joined into): the moment itself gets its sound and shock.
        boolean live = active && mc.level == level && changed && payload.elapsed() < 40;
        phase = next;
        elapsedAtSync = Math.max(0, payload.elapsed());
        length = Math.max(1, payload.length());
        cycle = payload.cycle();
        seed = payload.seed();
        syncClock = clock;
        active = true;
        level = mc.level;
        if (changed) {
            FieldAmbience.onPhase(next, live);
            FieldAtmosphere.onPhase(next, live);
        }
    }

    static void tick(Minecraft mc) {
        if (mc.level != level) {
            reset();
            return;
        }
        if (active && !mc.isPaused() && !ClientCorruptionState.paused()) {
            clock++;
        }
    }

    /** Leaving the field, a new world or logging out: nothing carries over. */
    static void reset() {
        active = false;
        level = null;
        phase = FieldCycle.Phase.DAY;
        elapsedAtSync = 0;
        length = FieldCycle.length(FieldCycle.Phase.DAY);
        syncClock = clock;
    }

    // ------------------------------------------------------------------ queries

    /** True while this client is in the field and knows where its cycle stands. */
    static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return active && mc.level != null && mc.level == level && FieldDimension.isField(mc.level);
    }

    static FieldCycle.Phase phase() {
        return phase;
    }

    static int cycle() {
        return cycle;
    }

    static long seed() {
        return seed;
    }

    /** The field's own clock in ticks (smoothed), for animation. */
    static double time(float partial) {
        return clock + partial;
    }

    /** Ticks into the current phase. */
    static float elapsed(float partial) {
        return elapsedAtSync + (clock - syncClock) + partial;
    }

    /** 0..1 through the current phase. */
    static float progress(float partial) {
        return Mth.clamp(elapsed(partial) / length, 0.0F, 1.0F);
    }

    /** Seconds left on the countdown (NIGHT only). */
    static float countdownSeconds(float partial) {
        return active() && phase == FieldCycle.Phase.NIGHT ? Math.max(0.0F, (length - elapsed(partial)) / 20.0F) : 0.0F;
    }

    /** How far the sun has cracked: grows through the day, whole again after dawn. */
    static float crack(float partial) {
        if (!active()) {
            return 0.0F;
        }
        return switch (phase) {
            case DAY -> smooth(0.03F, 0.97F, progress(partial));
            case SHATTER, NIGHT, HUNT -> 1.0F;
            case DAWN -> 0.0F;
        };
    }

    /** 0..1 how far the pieces of the sun have flown apart (SHATTER), held through the night. */
    static float shatter(float partial) {
        if (!active()) {
            return 0.0F;
        }
        return switch (phase) {
            case SHATTER -> progress(partial);
            case NIGHT, HUNT -> 1.0F;
            default -> 0.0F;
        };
    }

    /** 0..1 how far the sun has come back together (DAWN). */
    static float reform(float partial) {
        return active() && phase == FieldCycle.Phase.DAWN ? smooth(0.0F, 0.75F, progress(partial)) : 1.0F;
    }

    /** 1 in full daylight, 0 in the night. Dims as the sun cracks and dies within seconds when it breaks. */
    static float daylight(float partial) {
        if (!active()) {
            return 1.0F;
        }
        return switch (phase) {
            case DAY -> 1.0F - 0.24F * crack(partial);
            case SHATTER -> 0.76F * (1.0F - smooth(0.04F, 0.8F, progress(partial)));
            case NIGHT, HUNT -> 0.0F;
            case DAWN -> smooth(0.12F, 0.95F, progress(partial));
        };
    }

    /** 0..1 how close the night fog has come (it closes in as the light dies, lifts at dawn). */
    static float nightFog(float partial) {
        if (!active()) {
            return 0.0F;
        }
        return switch (phase) {
            case DAY -> 0.0F;
            case SHATTER -> smooth(0.2F, 1.0F, progress(partial));
            case NIGHT, HUNT -> 1.0F;
            case DAWN -> 1.0F - smooth(0.05F, 0.85F, progress(partial));
        };
    }

    static float smooth(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** Deterministic value in [0, 1) for (seed, index). */
    static float hash01(long seed, long index) {
        long h = seed + index * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }
}
