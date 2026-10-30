package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.ClientStageMemory;
import dev.kristian.nullmod.config.NullClientSettings;

/**
 * Everything the menu needs to know about how corrupted it should look: the highest stage this
 * computer has seen (the menu only ever reads it) combined with the player's menu and
 * accessibility settings. Read once each time the menu is shown.
 */
final class MenuMood {
    // index = stage - 1
    private static final int[] FACES = {2, 3, 4, 5, 6};
    private static final int[] EYES = {1, 3, 5, 7, 8};
    private static final int[] MOUTHS = {0, 1, 2, 3, 4};
    private static final int[] SILHOUETTES = {0, 0, 1, 3, 4};
    private static final float[] FACE_ALPHA = {0.13F, 0.17F, 0.26F, 0.33F, 0.40F};
    private static final float[] EYE_ALPHA = {0.45F, 0.60F, 0.72F, 0.85F, 0.95F};
    private static final float[] MOUTH_ALPHA = {0.0F, 0.16F, 0.28F, 0.36F, 0.45F};
    private static final float[] SILHOUETTE_ALPHA = {0.30F, 0.38F, 0.50F, 0.65F, 0.80F};
    private static final float[] GRAIN = {0.045F, 0.050F, 0.060F, 0.070F, 0.085F};
    private static final float[] FOG = {0.060F, 0.065F, 0.070F, 0.075F, 0.060F};
    private static final int[] BASE = {0x070707, 0x060606, 0x060606, 0x050505, 0x030303};
    private static final float[] VIGNETTE = {0.80F, 0.84F, 0.88F, 0.93F, 1.0F};
    private static final float[] SPAWN_DELAY = {11.0F, 9.0F, 7.0F, 5.5F, 4.0F};
    private static final float[] FLICKER = {0.015F, 0.04F, 0.07F, 0.10F, 0.16F};
    private static final float[] BACKGROUND_EVENTS = {0.5F, 1.0F, 1.6F, 2.4F, 3.5F};
    private static final float[] LOGO_GLITCHES = {0.3F, 0.6F, 2.0F, 3.5F, 5.0F};
    private static final float[] RED_LETTERS = {1.2F, 1.4F, 1.8F, 2.2F, 3.0F};
    private static final float[] WRONG_TEXTS = {0.06F, 0.15F, 0.3F, 0.6F, 1.0F};
    private static final float[] BUTTON_FLICKERS = {0.0F, 0.0F, 0.0F, 1.5F, 3.0F};
    private static final float[] TITLE_SWAPS = {0.0F, 0.0F, 0.0F, 0.0F, 2.2F};
    private static final float[] WHISPERS = {0.0F, 0.35F, 0.6F, 0.9F, 1.4F};
    private static final float[] FLASHES = {0.0F, 0.25F, 0.4F, 0.6F, 0.9F};
    private static final float[] BACKGROUND_FLICKERS = {0.0F, 1.0F, 1.5F, 2.2F, 3.2F};
    private static final float[] WAITING = {0.0F, 0.05F, 0.08F, 0.12F, 0.18F};
    private static final float[] AMBIENCE_VOLUME = {0.28F, 0.32F, 0.36F, 0.42F, 0.50F};
    private static final float[] AMBIENCE_PITCH = {1.0F, 0.99F, 0.97F, 0.95F, 0.92F};

    /** Highest stage ever seen on this computer, 1..5. */
    final int stage;
    /** False: the background is a still composition. */
    final boolean animated;
    /** 0..1 how much the background moves (0 when not animated). */
    final float motion;
    /** 0..1 how often and how strongly the menu glitches. */
    final float glitch;
    /** 0..1 strength allowed for flashes and flicker; near 0 they turn into slow fades. */
    final float flashing;
    /** Strobing, hard flicker and deep darkening may run. */
    final boolean intense;
    /** 0..1 strength allowed for shaking/jitter. */
    final float shake;

    final int faces;
    final int eyes;
    final int mouths;
    final int silhouettes;
    final float faceAlpha;
    final float eyeAlpha;
    final float mouthAlpha;
    final float silhouetteAlpha;
    final float grainAlpha;
    final float fogAlpha;
    final int baseColor;
    final float vignette;
    /** Mean seconds an empty apparition slot waits before something appears in it. */
    final float spawnDelay;
    /** Chance per second that a visible apparition flickers. */
    final float flickerRate;

    // events per minute
    final float backgroundEvents;
    final float logoGlitches;
    final float redLetters;
    final float wrongTexts;
    final float buttonFlickers;
    final float titleSwaps;
    final float whispers;
    final float flashes;
    final float backgroundFlickers;
    /** Chance that a freshly opened menu greets the player with "WE HAVE BEEN WAITING.". */
    final float waitingChance;

    final float ambienceVolume;
    final float ambiencePitch;

    private MenuMood(int stage, boolean animated, float motion, float glitch, float flashing, boolean intense, float shake) {
        int i = stage - 1;
        this.stage = stage;
        this.animated = animated;
        this.motion = animated ? motion : 0.0F;
        this.glitch = glitch;
        this.flashing = flashing;
        this.intense = intense;
        this.shake = shake;
        this.faces = FACES[i];
        this.eyes = EYES[i];
        this.mouths = MOUTHS[i];
        this.silhouettes = SILHOUETTES[i];
        this.faceAlpha = FACE_ALPHA[i];
        this.eyeAlpha = EYE_ALPHA[i];
        this.mouthAlpha = MOUTH_ALPHA[i];
        this.silhouetteAlpha = SILHOUETTE_ALPHA[i];
        this.grainAlpha = GRAIN[i] * (0.55F + 0.45F * glitch);
        this.fogAlpha = FOG[i];
        this.baseColor = BASE[i];
        this.vignette = VIGNETTE[i];
        float pace = 0.35F + 0.65F * this.motion;
        this.spawnDelay = SPAWN_DELAY[i] / pace;
        this.flickerRate = FLICKER[i] * this.motion;
        this.backgroundEvents = animated ? BACKGROUND_EVENTS[i] * pace : 0.0F;
        this.logoGlitches = LOGO_GLITCHES[i] * glitch;
        this.redLetters = RED_LETTERS[i] * (0.3F + 0.7F * glitch);
        this.wrongTexts = WRONG_TEXTS[i] * glitch;
        this.buttonFlickers = intense ? BUTTON_FLICKERS[i] * glitch : 0.0F;
        this.titleSwaps = TITLE_SWAPS[i] * glitch;
        this.whispers = WHISPERS[i];
        this.flashes = animated ? FLASHES[i] * pace : 0.0F;
        this.backgroundFlickers = animated && flashing > 0.05F ? BACKGROUND_FLICKERS[i] * pace : 0.0F;
        this.waitingChance = WAITING[i] * (0.4F + 0.6F * glitch);
        this.ambienceVolume = AMBIENCE_VOLUME[i];
        this.ambiencePitch = AMBIENCE_PITCH[i];
    }

    static MenuMood read() {
        int stage = ClientStageMemory.highestStage().number();
        boolean animated = NullClientSettings.get(NullClientSettings.ANIMATED_BACKGROUND);
        float motion = MenuMath.clamp((float) NullClientSettings.get(NullClientSettings.BACKGROUND_ANIMATION_INTENSITY), 0.0F, 1.0F);
        if (motion < 0.02F) {
            animated = false;
        }
        float glitch = MenuMath.clamp((float) NullClientSettings.get(NullClientSettings.MENU_GLITCH_INTENSITY), 0.0F, 1.0F);
        return new MenuMood(Math.max(1, Math.min(5, stage)), animated, motion, glitch,
                MenuMath.clamp01(ClientLimits.flashing()), ClientLimits.intenseAllowed(), MenuMath.clamp01(ClientLimits.shake()));
    }
}
