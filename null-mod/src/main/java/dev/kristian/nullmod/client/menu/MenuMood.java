package dev.kristian.nullmod.client.menu;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.ClientStageMemory;
import dev.kristian.nullmod.config.NullClientSettings;

/**
 * Everything the menu needs to know about how corrupted it should look: the highest stage this
 * computer has seen (the menus only ever read it) combined with the player's menu and
 * accessibility settings. Read once each time a menu is shown.
 * <p>
 * Every table has one row per stage, STATIC to HARVEST (index = stage - 1). HARVEST, the last
 * stage, has its own and harshest row.
 */
final class MenuMood {
    static final int STAGES = 6;

    // the user's faces
    private static final int[] FACES = {1, 2, 2, 3, 3, 3};
    private static final float[] FACE_ALPHA = {0.15F, 0.22F, 0.30F, 0.38F, 0.46F, 0.54F};
    private static final float[] FACE_SIZE_MIN = {0.42F, 0.46F, 0.50F, 0.56F, 0.62F, 0.68F};
    private static final float[] FACE_SIZE_MAX = {0.50F, 0.58F, 0.66F, 0.76F, 0.86F, 0.96F};
    private static final float[] FACE_DELAY = {9.0F, 7.0F, 6.0F, 4.5F, 3.5F, 2.8F};
    private static final float[] FOLLOW = {0.0F, 0.45F, 0.65F, 0.80F, 1.0F, 1.0F};
    private static final float[] GLINT = {0.55F, 0.65F, 0.75F, 0.85F, 0.95F, 1.0F};
    // No dominant face: the faces come one after another, each somewhere else.
    private static final float[] DOMINANT_ALPHA = {0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
    private static final float[] DOMINANT_SIZE = {0.0F, 0.0F, 0.0F, 0.0F, 1.30F, 1.42F};
    private static final float[] CLOSE_UPS = {0.0F, 0.0F, 0.25F, 0.40F, 0.60F, 0.90F};
    private static final float[] CLOSE_UP_ALPHA = {0.0F, 0.0F, 0.40F, 0.48F, 0.56F, 0.64F};
    private static final float[] SCRIM = {0.50F, 0.52F, 0.55F, 0.60F, 0.66F, 0.70F};
    private static final int[] BACKDROP_FACES = {1, 1, 1, 2, 2, 2};
    private static final int[] BACKDROP_EYES = {0, 1, 2, 3, 4, 4};
    // the generated apparitions
    private static final int[] EYES = {1, 3, 5, 7, 8, 8};
    private static final int[] MOUTHS = {0, 1, 2, 3, 4, 4};
    private static final int[] SILHOUETTES = {0, 0, 1, 3, 4, 4};
    private static final float[] EYE_ALPHA = {0.45F, 0.60F, 0.72F, 0.85F, 0.95F, 1.0F};
    private static final float[] MOUTH_ALPHA = {0.0F, 0.16F, 0.28F, 0.36F, 0.45F, 0.50F};
    private static final float[] SILHOUETTE_ALPHA = {0.30F, 0.38F, 0.50F, 0.65F, 0.80F, 0.88F};
    // the room around them
    private static final float[] GRAIN = {0.045F, 0.050F, 0.060F, 0.070F, 0.085F, 0.100F};
    private static final float[] FOG = {0.060F, 0.065F, 0.070F, 0.075F, 0.060F, 0.055F};
    private static final int[] BASE = {0x070707, 0x060606, 0x060606, 0x050505, 0x030303, 0x020202};
    private static final float[] VIGNETTE = {0.80F, 0.84F, 0.88F, 0.93F, 1.0F, 1.0F};
    private static final float[] SPAWN_DELAY = {11.0F, 9.0F, 7.0F, 5.5F, 4.0F, 3.2F};
    private static final float[] FLICKER = {0.015F, 0.04F, 0.07F, 0.10F, 0.16F, 0.20F};
    // events per minute
    private static final float[] BACKGROUND_EVENTS = {0.5F, 1.0F, 1.6F, 2.4F, 3.5F, 4.2F};
    private static final float[] LOGO_GLITCHES = {0.3F, 0.6F, 2.0F, 3.5F, 5.0F, 6.0F};
    private static final float[] RED_LETTERS = {1.2F, 1.4F, 1.8F, 2.2F, 3.0F, 3.6F};
    private static final float[] WRONG_TEXTS = {0.06F, 0.15F, 0.3F, 0.6F, 1.0F, 1.3F};
    private static final float[] BUTTON_FLICKERS = {0.0F, 0.0F, 0.0F, 1.5F, 3.0F, 3.6F};
    private static final float[] TITLE_SWAPS = {0.0F, 0.0F, 0.0F, 0.0F, 2.2F, 3.0F};
    private static final float[] WHISPERS = {0.0F, 0.35F, 0.6F, 0.9F, 1.4F, 1.7F};
    private static final float[] FLASHES = {0.0F, 0.25F, 0.4F, 0.6F, 0.9F, 1.1F};
    private static final float[] BACKGROUND_FLICKERS = {0.0F, 1.0F, 1.5F, 2.2F, 3.2F, 3.8F};
    private static final float[] WAITING = {0.0F, 0.05F, 0.08F, 0.12F, 0.18F, 0.24F};
    private static final float[] AMBIENCE_VOLUME = {0.28F, 0.32F, 0.36F, 0.42F, 0.50F, 0.55F};
    private static final float[] AMBIENCE_PITCH = {1.0F, 0.99F, 0.97F, 0.95F, 0.92F, 0.89F};

    /** Highest stage ever seen on this computer, 1..6. */
    final int stage;
    /** False: the background is a still composition. */
    final boolean animated;
    /** 0..1 how much the background moves (0 when not animated). */
    final float motion;
    /** 0..1 how often and how strongly the menu glitches. */
    final float glitch;
    /** 0..1 strength allowed for flashes and flicker; near 0 they turn into slow fades. */
    final float flashing;
    /** Strobing, hard flicker, sudden close-ups and deep darkening may run. */
    final boolean intense;
    /** 0..1 strength allowed for shaking/jitter. */
    final float shake;

    /** Most of the user's faces visible at once (besides the dominant face and the close-ups). */
    final int faces;
    final float faceAlpha;
    /** On-screen height of a face as a fraction of the screen height. */
    final float faceSizeMin;
    final float faceSizeMax;
    /** Mean seconds an empty face slot waits before another face comes. */
    final float faceDelay;
    /** Stage 1: faces only ever fade in and out slowly (no peeks, no cuts). */
    final boolean gentleFaces;
    /** 0..1 how far the eyes follow the mouse cursor. */
    final float follow;
    /** Brightness of the glints in the faces' eyes. */
    final float glint;
    /** Stages 5 and 6: one face slowly fills much of the screen behind the buttons (0 = never). */
    final float dominantAlpha;
    final float dominantSize;
    /** Sudden close-ups per minute (0 when not animated or intense effects are off). */
    final float closeUps;
    final float closeUpAlpha;
    /** Darkness behind the logo and the buttons, so they always stay readable. */
    final float scrim;
    /** Faces and eye pairs at the edges of every other screen outside a world. */
    final int backdropFaces;
    final int backdropEyes;

    final int eyes;
    final int mouths;
    final int silhouettes;
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
        float pace = 0.35F + 0.65F * this.motion;

        this.faces = FACES[i];
        this.faceAlpha = FACE_ALPHA[i];
        this.faceSizeMin = FACE_SIZE_MIN[i];
        this.faceSizeMax = FACE_SIZE_MAX[i];
        this.faceDelay = FACE_DELAY[i] / pace;
        this.gentleFaces = stage == 1;
        this.follow = animated ? FOLLOW[i] * (0.4F + 0.6F * this.motion) : 0.0F;
        this.glint = GLINT[i];
        this.dominantAlpha = DOMINANT_ALPHA[i];
        this.dominantSize = DOMINANT_SIZE[i];
        this.closeUps = animated && intense ? CLOSE_UPS[i] * (0.4F + 0.6F * glitch) * pace : 0.0F;
        this.closeUpAlpha = CLOSE_UP_ALPHA[i];
        this.scrim = SCRIM[i];
        this.backdropFaces = BACKDROP_FACES[i];
        this.backdropEyes = BACKDROP_EYES[i];

        this.eyes = EYES[i];
        this.mouths = MOUTHS[i];
        this.silhouettes = SILHOUETTES[i];
        this.eyeAlpha = EYE_ALPHA[i];
        this.mouthAlpha = MOUTH_ALPHA[i];
        this.silhouetteAlpha = SILHOUETTE_ALPHA[i];
        this.grainAlpha = GRAIN[i] * (0.55F + 0.45F * glitch);
        this.fogAlpha = FOG[i];
        this.baseColor = BASE[i];
        this.vignette = VIGNETTE[i];
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
        return new MenuMood(Math.max(1, Math.min(STAGES, stage)), animated, motion, glitch,
                MenuMath.clamp01(ClientLimits.flashing()), ClientLimits.intenseAllowed(), MenuMath.clamp01(ClientLimits.shake()));
    }

    /** The same stage and the same kind of animation: a running composition can simply carry on. */
    boolean sameComposition(MenuMood other) {
        return other != null && other.stage == stage && other.animated == animated;
    }
}
