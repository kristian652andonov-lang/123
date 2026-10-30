package dev.kristian.nullmod.client.menu;

import java.util.List;
import java.util.Random;
import net.minecraft.Util;

/**
 * Random things that happen while the menu is open, scaled by the {@link MenuMood}: faces
 * appearing behind the title, eyes opening, mouths, silhouettes behind the buttons, the
 * background darkening, the logo distorting, red letters, buttons showing the wrong words or
 * flickering, the title turning into something else at Stage 5, whispers, dim flashes and torn
 * scan lines - and, very rarely when the menu opens, "WE HAVE BEEN WAITING.".
 * Nothing here ever blocks a button for long or changes what a button does.
 */
final class MainMenuEventManager {
    static final int TEARS = 3;

    private static final int FACE = 0;
    private static final int EYE = 1;
    private static final int MOUTH = 2;
    private static final int DARKEN = 3;
    private static final int SILHOUETTE = 4;
    private static final int DISTORT = 5;
    private static final int STATIC = 6;
    /** Background event weights per stage: face, eye, mouth, darken, silhouette, distort, static. */
    private static final float[][] WEIGHTS = {
            {3.0F, 4.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
            {3.0F, 4.0F, 1.0F, 2.0F, 0.0F, 2.0F, 2.0F},
            {3.0F, 3.0F, 2.0F, 2.0F, 1.0F, 2.0F, 2.0F},
            {3.0F, 3.0F, 3.0F, 3.0F, 3.0F, 3.0F, 2.0F},
            {3.0F, 3.0F, 3.0F, 3.0F, 3.0F, 3.0F, 3.0F},
    };
    private static final float WAIT_TEXT_IN = 0.35F;
    private static final float WAIT_TEXT_FULL = 0.8F;
    private static final float WAIT_TEXT_FADE = 2.0F;
    private static final float WAIT_RETURN = 2.4F;
    private static final float WAIT_END = 3.6F;
    private static final long WAITING_COOLDOWN_MS = 15L * 60L * 1000L;

    private static boolean waitedBefore;
    private static long lastWaitingMs;

    private final CorruptedMainMenu menu;
    private final Random random;
    private MenuMood mood;

    private float quiet;
    private float nextBackground;
    private float nextLogoGlitch;
    private float nextRed;
    private float nextWrong;
    private float nextFlicker;
    private float nextSwap;
    private float nextWhisper;
    private float nextFlash;
    private float nextTear;
    private float nextBackgroundFlicker;

    private float darkenTime = -1.0F;
    private float darkenIn;
    private float darkenHold;
    private float darkenOut;
    private float darkenMax;

    private float flashTime = -1.0F;
    private float flashLength;
    private float flashMax;

    private float bgFlickerTime = -1.0F;
    private float bgFlickerLength;
    private long bgFlickerSeed;

    private float staticTime = -1.0F;
    private float staticLength;
    private float staticMax;

    private final float[] tearY = new float[TEARS];
    private final float[] tearX = new float[TEARS];
    private final float[] tearWidth = new float[TEARS];
    private final float[] tearLife = new float[TEARS];
    private final float[] tearStrength = new float[TEARS];

    private float waitingTime = -1.0F;
    private boolean waitingSound;

    MainMenuEventManager(CorruptedMainMenu menu, Random random) {
        this.menu = menu;
        this.random = random;
    }

    /**
     * Called each time the menu is shown. {@code fresh} is true only the first time a new menu
     * instance appears (game start, leaving a world), not when coming back from a sub-screen.
     */
    void reset(MenuMood mood, boolean fresh) {
        this.mood = mood;
        quiet = 2.5F;
        darkenTime = -1.0F;
        flashTime = -1.0F;
        bgFlickerTime = -1.0F;
        staticTime = -1.0F;
        waitingTime = -1.0F;
        waitingSound = false;
        for (int i = 0; i < TEARS; i++) {
            tearLife[i] = 0.0F;
        }
        nextBackground = MenuMath.nextInterval(random, mood.backgroundEvents);
        nextLogoGlitch = MenuMath.nextInterval(random, mood.logoGlitches);
        nextRed = 4.0F + MenuMath.nextInterval(random, mood.redLetters);
        nextWrong = 6.0F + MenuMath.nextInterval(random, mood.wrongTexts);
        nextFlicker = MenuMath.nextInterval(random, mood.buttonFlickers);
        nextSwap = 5.0F + MenuMath.nextInterval(random, mood.titleSwaps);
        nextWhisper = 8.0F + MenuMath.nextInterval(random, mood.whispers);
        nextFlash = MenuMath.nextInterval(random, mood.flashes);
        nextTear = MenuMath.nextInterval(random, tearRate());
        nextBackgroundFlicker = MenuMath.nextInterval(random, mood.backgroundFlickers);

        if (fresh && mood.waitingChance > 0.0F && random.nextFloat() < mood.waitingChance) {
            long now = Util.getMillis();
            if (!waitedBefore || now - lastWaitingMs > WAITING_COOLDOWN_MS) {
                waitedBefore = true;
                lastWaitingMs = now;
                waitingTime = 0.0F;
                menu.audio().duck(0.2F, 10);
            }
        }
    }

    private float tearRate() {
        return (mood.stage - 1) * 1.2F * mood.glitch * (mood.animated ? 1.0F : 0.3F);
    }

    void update(float dt) {
        if (mood == null) {
            return;
        }
        updateOverlays(dt);
        if (waitingTime >= 0.0F) {
            updateWaiting(dt);
            return;
        }
        if (quiet > 0.0F) {
            quiet -= dt;
            return;
        }
        if ((nextBackground -= dt) <= 0.0F) {
            backgroundEvent();
            nextBackground = MenuMath.nextInterval(random, mood.backgroundEvents);
        }
        if ((nextLogoGlitch -= dt) <= 0.0F) {
            float strength = (0.2F + 0.15F * mood.stage) * (0.5F + 0.5F * mood.glitch);
            menu.logo().triggerGlitch(random, strength, MenuMath.range(random, 0.12F, 0.45F));
            nextLogoGlitch = MenuMath.nextInterval(random, mood.logoGlitches);
        }
        if ((nextRed -= dt) <= 0.0F) {
            menu.logo().triggerRedLetter(random);
            nextRed = MenuMath.nextInterval(random, mood.redLetters);
        }
        if ((nextWrong -= dt) <= 0.0F) {
            List<NullMenuButton> buttons = menu.mainButtons();
            if (!buttons.isEmpty()) {
                buttons.get(random.nextInt(buttons.size())).showWrongText(MenuMath.range(random, 0.7F, 1.5F));
            }
            nextWrong = MenuMath.nextInterval(random, mood.wrongTexts);
        }
        if ((nextFlicker -= dt) <= 0.0F) {
            List<NullMenuButton> buttons = menu.mainButtons();
            if (!buttons.isEmpty()) {
                buttons.get(random.nextInt(buttons.size())).flicker(MenuMath.range(random, 0.15F, 0.45F));
            }
            nextFlicker = MenuMath.nextInterval(random, mood.buttonFlickers);
        }
        if ((nextSwap -= dt) <= 0.0F) {
            menu.logo().triggerSwap(random, MenuMath.range(random, 0.35F, 1.1F));
            nextSwap = MenuMath.nextInterval(random, mood.titleSwaps);
        }
        if ((nextWhisper -= dt) <= 0.0F) {
            menu.audio().whisper(random);
            nextWhisper = MenuMath.nextInterval(random, mood.whispers);
        }
        if ((nextFlash -= dt) <= 0.0F) {
            startFlash();
            nextFlash = MenuMath.nextInterval(random, mood.flashes);
        }
        if ((nextTear -= dt) <= 0.0F) {
            addTear(MenuMath.range(random, 0.5F, 1.0F));
            nextTear = MenuMath.nextInterval(random, tearRate());
        }
        if ((nextBackgroundFlicker -= dt) <= 0.0F) {
            bgFlickerTime = 0.0F;
            bgFlickerLength = MenuMath.range(random, 0.08F, 0.28F);
            bgFlickerSeed = random.nextLong();
            nextBackgroundFlicker = MenuMath.nextInterval(random, mood.backgroundFlickers);
        }
    }

    private void backgroundEvent() {
        float[] weights = WEIGHTS[mood.stage - 1];
        float total = 0.0F;
        for (float w : weights) {
            total += w;
        }
        float pick = random.nextFloat() * total;
        int event = 0;
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0.0F) {
                event = i;
                break;
            }
        }
        MainMenuAnimationManager animation = menu.animation();
        switch (event) {
            case FACE -> animation.faceBehind(menu.logoLeft(), menu.logoTop(), menu.logoRight(), menu.logoBottom());
            case EYE -> animation.eyeOpensSomewhere();
            case MOUTH -> animation.mouthAppears();
            case DARKEN -> startDarken();
            case SILHOUETTE -> animation.silhouetteBehind(menu.buttonsLeft(), menu.buttonsTop(), menu.buttonsRight(), menu.buttonsBottom());
            case DISTORT -> menu.logo().triggerDistort(random, MenuMath.range(random, 0.4F, 0.9F));
            default -> startStatic();
        }
    }

    private void startDarken() {
        boolean gentle = mood.flashing < 0.3F;
        darkenTime = 0.0F;
        darkenIn = gentle ? MenuMath.range(random, 0.9F, 1.4F) : MenuMath.range(random, 0.15F, 1.0F);
        darkenHold = MenuMath.range(random, 1.0F, 2.5F);
        darkenOut = MenuMath.range(random, 1.5F, 2.5F);
        darkenMax = (mood.intense ? MenuMath.range(random, 0.5F, 0.78F) : 0.45F) * (0.6F + 0.08F * mood.stage);
    }

    private void startFlash() {
        flashTime = 0.0F;
        if (mood.flashing < 0.05F) {
            // no flashing allowed: a slow, faint swell instead
            flashLength = 1.4F;
            flashMax = 0.035F;
        } else {
            flashLength = MenuMath.range(random, 0.05F, 0.09F);
            flashMax = 0.10F * mood.flashing;
        }
    }

    private void startStatic() {
        staticTime = 0.0F;
        staticLength = MenuMath.range(random, 0.15F, 0.4F);
        staticMax = 0.05F + 0.06F * mood.glitch;
        menu.animation().tearAll();
        int lines = 1 + random.nextInt(2);
        for (int i = 0; i < lines; i++) {
            addTear(1.0F);
        }
    }

    private void addTear(float strength) {
        for (int i = 0; i < TEARS; i++) {
            if (tearLife[i] <= 0.0F) {
                tearY[i] = random.nextFloat();
                tearX[i] = random.nextFloat() * 0.3F;
                tearWidth[i] = MenuMath.range(random, 0.4F, 1.0F);
                tearLife[i] = MenuMath.range(random, 0.05F, 0.12F);
                tearStrength[i] = strength * (0.05F + 0.07F * mood.glitch);
                return;
            }
        }
    }

    private void updateOverlays(float dt) {
        if (darkenTime >= 0.0F) {
            darkenTime += dt;
            if (darkenTime > darkenIn + darkenHold + darkenOut) {
                darkenTime = -1.0F;
            }
        }
        if (flashTime >= 0.0F) {
            flashTime += dt;
            if (flashTime > flashLength) {
                flashTime = -1.0F;
            }
        }
        if (bgFlickerTime >= 0.0F) {
            bgFlickerTime += dt;
            if (bgFlickerTime > bgFlickerLength) {
                bgFlickerTime = -1.0F;
            }
        }
        if (staticTime >= 0.0F) {
            staticTime += dt;
            if (staticTime > staticLength) {
                staticTime = -1.0F;
            }
        }
        for (int i = 0; i < TEARS; i++) {
            if (tearLife[i] > 0.0F) {
                tearLife[i] -= dt;
            }
        }
    }

    private void updateWaiting(float dt) {
        waitingTime += dt;
        if (!waitingSound && waitingTime >= WAIT_TEXT_IN) {
            waitingSound = true;
            menu.audio().waiting();
            menu.animation().watchers(0.5F);
        }
        if (waitingTime >= WAIT_END) {
            waitingTime = -1.0F;
            quiet = 3.0F;
            menu.audio().duck(1.0F, 60);
        }
    }

    /** Skips the opening sequence (any click or key). Returns true if there was one to skip. */
    boolean skipWaiting() {
        if (waitingTime >= 0.0F && waitingTime < WAIT_RETURN) {
            waitingTime = WAIT_RETURN;
            if (!waitingSound) {
                waitingSound = true;
            }
            return true;
        }
        return false;
    }

    boolean waitingActive() {
        return waitingTime >= 0.0F;
    }

    /** 0..1 visibility of the buttons and logo while the opening sequence runs. */
    float waitingUiAlpha() {
        if (waitingTime < 0.0F) {
            return 1.0F;
        }
        if (waitingTime < WAIT_RETURN) {
            return 0.0F;
        }
        return MenuMath.smooth((waitingTime - WAIT_RETURN) / (WAIT_END - WAIT_RETURN));
    }

    float waitingTextAlpha() {
        if (waitingTime < WAIT_TEXT_IN || waitingTime >= WAIT_RETURN) {
            return 0.0F;
        }
        float alpha;
        if (waitingTime < WAIT_TEXT_FULL) {
            alpha = MenuMath.smooth((waitingTime - WAIT_TEXT_IN) / (WAIT_TEXT_FULL - WAIT_TEXT_IN));
        } else if (waitingTime < WAIT_TEXT_FADE) {
            alpha = 1.0F;
        } else {
            alpha = 1.0F - MenuMath.smooth((waitingTime - WAIT_TEXT_FADE) / (WAIT_RETURN - WAIT_TEXT_FADE));
        }
        float wobble = MenuMath.hash01((long) (waitingTime * 18.0F) + 99L);
        return alpha * (1.0F - 0.25F * mood.flashing * wobble * wobble);
    }

    /** Extra darkness over the background (darkening events and the opening sequence). */
    float darken() {
        float value = 0.0F;
        if (darkenTime >= 0.0F) {
            if (darkenTime < darkenIn) {
                value = darkenMax * MenuMath.smooth(darkenTime / darkenIn);
            } else if (darkenTime < darkenIn + darkenHold) {
                value = darkenMax;
            } else {
                value = darkenMax * (1.0F - MenuMath.smooth((darkenTime - darkenIn - darkenHold) / darkenOut));
            }
        }
        if (waitingTime >= 0.0F) {
            float w;
            if (waitingTime < WAIT_TEXT_IN) {
                w = 0.85F * MenuMath.smooth(waitingTime / WAIT_TEXT_IN);
            } else if (waitingTime < WAIT_RETURN) {
                w = 0.85F;
            } else {
                w = 0.85F * (1.0F - MenuMath.smooth((waitingTime - WAIT_RETURN) / (WAIT_END - WAIT_RETURN)));
            }
            value = 1.0F - (1.0F - value) * (1.0F - w);
        }
        if (bgFlickerTime >= 0.0F) {
            // a failing light: the background drops out for a frame or two
            float on = MenuMath.hash01(bgFlickerSeed + (long) (bgFlickerTime * 30.0F));
            float dip = on < 0.55F ? (0.25F + 0.3F * on) * mood.flashing : 0.0F;
            value = 1.0F - (1.0F - value) * (1.0F - dip);
        }
        return value;
    }

    float flash() {
        if (flashTime < 0.0F) {
            return 0.0F;
        }
        float t = flashTime / flashLength;
        return flashMax * (float) Math.sin(Math.PI * MenuMath.clamp01(t));
    }

    float grainBoost() {
        if (staticTime < 0.0F) {
            return 0.0F;
        }
        return staticMax * (1.0F - staticTime / staticLength);
    }

    float tearAlpha(int i) {
        return tearLife[i] > 0.0F ? tearStrength[i] : 0.0F;
    }

    float tearY(int i) {
        return tearY[i];
    }

    float tearX(int i) {
        return tearX[i];
    }

    float tearWidth(int i) {
        return tearWidth[i];
    }
}
