package dev.kristian.nullmod.client.menu;

import java.util.Random;

/**
 * The things in the dark behind the menu: faces, pairs of eyes, mouths and silhouettes.
 * <p>
 * A fixed pool of {@link Apparition}s is allocated once; each one cycles through
 * hidden → fading in → visible → fading out, drifting slowly, flickering, blinking, sometimes
 * only peeking for a moment or cutting out completely. How many may be visible, how bright they
 * get and how fast they come back follows the {@link MenuMood}. With the animated background
 * switched off the pool is arranged once into a still composition.
 * <p>
 * Positions are stored as fractions of the screen (x of the width, y of the height) and sizes
 * as a fraction of the screen height, so resizing the window never disturbs them.
 */
final class MainMenuAnimationManager {
    static final int FACE = 0;
    static final int EYES = 1;
    static final int MOUTH = 2;
    static final int SILHOUETTE = 3;

    private static final int HIDDEN = 0;
    private static final int IN = 1;
    private static final int HOLD = 2;
    private static final int OUT = 3;

    /** One thing in the dark. All fields are package-visible for the background renderer. */
    static final class Apparition {
        int kind;
        int sprite;
        boolean mirror;
        /** Centre, as fractions of the screen width / height. */
        float x;
        float y;
        /** On-screen height as a fraction of the screen height. */
        float size;
        float vx;
        float vy;
        int phase = HIDDEN;
        float t;
        float inTime;
        float holdTime;
        float outTime;
        float maxAlpha;
        float wait;
        /** Current visibility 0..1 (already multiplied by maxAlpha), updated every frame. */
        float alpha;
        float flicker;
        long flickerSeed;
        /** 0..1 eyelid / jaw opening. */
        float open = 1.0F;
        float blink = -1.0F;
        float nextBlink;
        /** Brightness of glowing pupils (faces, silhouettes); 0 = empty sockets. */
        float glow;
        /** 0..1 horizontal tearing, decays quickly. */
        float tear;
        long tearSeed;
        /** Opening animation for eyes/mouths spawned by events (0 → 1). */
        boolean opening;
        boolean eventSpawned;
        boolean still;

        boolean visible() {
            return phase != HIDDEN && alpha > 0.002F;
        }
    }

    private final Apparition[] faces = pool(6, FACE);
    private final Apparition[] eyes = pool(8, EYES);
    private final Apparition[] mouths = pool(4, MOUTH);
    private final Apparition[] silhouettes = pool(4, SILHOUETTE);
    /** Apparitions summoned by background events (never part of the regular rotation). */
    private final Apparition[] extras = pool(6, FACE);
    private final Apparition[][] regular = {silhouettes, faces, mouths, eyes};

    private final Random random;
    private MenuMood mood;
    private MenuArt art = MenuArt.EMPTY;
    private float time;
    private float aspect = 16.0F / 9.0F;

    MainMenuAnimationManager(Random random) {
        this.random = random;
    }

    private static Apparition[] pool(int size, int kind) {
        Apparition[] pool = new Apparition[size];
        for (int i = 0; i < size; i++) {
            pool[i] = new Apparition();
            pool[i].kind = kind;
        }
        return pool;
    }

    Apparition[] faces() {
        return faces;
    }

    Apparition[] eyes() {
        return eyes;
    }

    Apparition[] mouths() {
        return mouths;
    }

    Apparition[] silhouettes() {
        return silhouettes;
    }

    Apparition[] extras() {
        return extras;
    }

    /** Swaps in freshly loaded art (after a resource reload); sprites that no longer exist are skipped. */
    void setArt(MenuArt art) {
        this.art = art;
    }

    void resize(int width, int height) {
        aspect = height <= 0 ? 1.0F : (float) width / height;
    }

    /** Called each time the menu is shown. */
    void reset(MenuMood mood, MenuArt art) {
        this.mood = mood;
        this.art = art;
        this.time = 0.0F;
        for (Apparition[] group : regular) {
            for (Apparition a : group) {
                clear(a);
            }
        }
        for (Apparition a : extras) {
            clear(a);
        }
        if (!art.hasApparitions()) {
            return;
        }
        if (!mood.animated) {
            arrangeStill();
            return;
        }
        // stagger the first appearances; a couple of faces start fading in straight away
        for (int i = 0; i < faces.length; i++) {
            faces[i].wait = i < Math.min(2, mood.faces) ? random.nextFloat() * 1.5F : random.nextFloat() * mood.spawnDelay * 1.4F;
        }
        for (Apparition a : eyes) {
            a.wait = 0.8F + random.nextFloat() * mood.spawnDelay;
        }
        for (Apparition a : mouths) {
            a.wait = 2.0F + random.nextFloat() * mood.spawnDelay * 1.5F;
        }
        for (Apparition a : silhouettes) {
            a.wait = 3.0F + random.nextFloat() * mood.spawnDelay * 1.8F;
        }
    }

    private static void clear(Apparition a) {
        a.phase = HIDDEN;
        a.alpha = 0.0F;
        a.t = 0.0F;
        a.wait = 0.0F;
        a.flicker = 0.0F;
        a.tear = 0.0F;
        a.opening = false;
        a.eventSpawned = false;
        a.still = false;
        a.open = 1.0F;
        a.blink = -1.0F;
    }

    // ---------------------------------------------------------------------------------------
    // still composition

    private void arrangeStill() {
        Random fixed = new Random(0x4E554C4CL + mood.stage);
        int faceCount = Math.min(3, mood.faces);
        float[][] facePlaces = {{0.70F, 0.36F, 0.62F}, {0.90F, 0.70F, 0.48F}, {0.48F, 0.78F, 0.40F}};
        for (int i = 0; i < faceCount; i++) {
            placeStill(faces[i], FACE, fixed, facePlaces[i][0], facePlaces[i][1], facePlaces[i][2], mood.faceAlpha * 0.75F);
        }
        float[][] eyePlaces = {{0.86F, 0.18F, 0.035F}, {0.56F, 0.56F, 0.03F}, {0.95F, 0.48F, 0.028F}};
        for (int i = 0; i < Math.min(3, mood.eyes); i++) {
            placeStill(eyes[i], EYES, fixed, eyePlaces[i][0], eyePlaces[i][1], eyePlaces[i][2], mood.eyeAlpha * 0.7F);
        }
        if (mood.mouths > 0) {
            placeStill(mouths[0], MOUTH, fixed, 0.74F, 0.86F, 0.09F, mood.mouthAlpha * 0.75F);
        }
        if (mood.silhouettes > 0) {
            placeStill(silhouettes[0], SILHOUETTE, fixed, 0.58F, 0.62F, 0.80F, mood.silhouetteAlpha * 0.7F);
        }
    }

    private void placeStill(Apparition a, int kind, Random fixed, float x, float y, float size, float alpha) {
        int count = spriteCount(kind);
        if (count == 0) {
            return;
        }
        a.kind = kind;
        a.sprite = fixed.nextInt(count);
        a.mirror = fixed.nextBoolean();
        a.x = x;
        a.y = y;
        a.size = size;
        a.vx = 0.0F;
        a.vy = 0.0F;
        a.phase = HOLD;
        a.still = true;
        a.maxAlpha = alpha;
        a.alpha = alpha;
        a.glow = kind == SILHOUETTE ? (mood.stage >= 4 ? 0.8F : 0.0F) : 1.0F;
        a.open = 1.0F;
    }

    // ---------------------------------------------------------------------------------------
    // per frame

    void update(float dt) {
        if (mood == null || !art.hasApparitions()) {
            return;
        }
        time += dt;
        boolean animated = mood.animated;
        updateGroup(faces, mood.faces, dt, animated);
        updateGroup(eyes, mood.eyes, dt, animated);
        updateGroup(mouths, mood.mouths, dt, animated);
        updateGroup(silhouettes, mood.silhouettes, dt, animated);
        for (Apparition a : extras) {
            if (a.phase != HIDDEN) {
                step(a, dt, animated);
            }
        }
    }

    private void updateGroup(Apparition[] group, int allowed, float dt, boolean animated) {
        for (int i = 0; i < group.length; i++) {
            Apparition a = group[i];
            if (a.still) {
                continue;
            }
            if (a.phase == HIDDEN) {
                if (!animated || i >= allowed) {
                    continue;
                }
                a.wait -= dt;
                if (a.wait <= 0.0F) {
                    spawnRegular(a);
                }
                continue;
            }
            step(a, dt, animated);
        }
    }

    private void step(Apparition a, float dt, boolean animated) {
        a.t += dt;
        float visibility;
        switch (a.phase) {
            case IN -> {
                visibility = MenuMath.smooth(a.t / a.inTime);
                if (a.t >= a.inTime) {
                    a.phase = HOLD;
                    a.t = 0.0F;
                }
            }
            case HOLD -> {
                visibility = 1.0F;
                if (a.t >= a.holdTime) {
                    a.phase = OUT;
                    a.t = 0.0F;
                } else if (animated && !a.eventSpawned && a.kind != SILHOUETTE
                        && random.nextFloat() < dt * 0.02F * mood.motion) {
                    // it was somewhere else a moment ago
                    jump(a);
                }
            }
            case OUT -> {
                visibility = 1.0F - MenuMath.smooth(a.t / a.outTime);
                if (a.t >= a.outTime) {
                    a.phase = HIDDEN;
                    a.alpha = 0.0F;
                    a.wait = mood.spawnDelay * MenuMath.range(random, 0.5F, 1.5F);
                    a.eventSpawned = false;
                    a.opening = false;
                    return;
                }
            }
            default -> visibility = 0.0F;
        }

        if (animated) {
            float motion = mood.motion;
            a.x += a.vx * dt * motion;
            a.y += a.vy * dt * motion;
            // flicker
            if (a.flicker > 0.0F) {
                a.flicker -= dt;
            } else if (random.nextFloat() < dt * mood.flickerRate) {
                a.flicker = MenuMath.range(random, 0.08F, 0.35F);
                a.flickerSeed = random.nextLong();
            }
            // tearing
            if (a.tear > 0.0F) {
                a.tear = Math.max(0.0F, a.tear - dt * 6.0F);
            } else if (random.nextFloat() < dt * 0.05F * mood.glitch * (mood.stage / 5.0F)) {
                a.tear = 1.0F;
                a.tearSeed = random.nextLong();
            }
        }
        // eyes blink; event eyes and mouths open slowly as they appear and half close as they go
        float blinkFactor = 1.0F;
        if (animated && (a.kind == EYES || a.kind == FACE)) {
            a.nextBlink -= dt;
            if (a.blink < 0.0F && a.nextBlink <= 0.0F) {
                a.blink = 0.0F;
                a.nextBlink = MenuMath.range(random, 2.0F, 7.0F);
            }
            if (a.blink >= 0.0F) {
                a.blink += dt;
                float k = a.blink / 0.22F;
                if (k >= 1.0F) {
                    a.blink = -1.0F;
                } else {
                    blinkFactor = 1.0F - (float) Math.sin(Math.PI * k);
                }
            }
        }
        float lid = 1.0F;
        if (a.opening) {
            if (a.phase == IN) {
                lid = MenuMath.smooth(a.t / a.inTime);
            } else if (a.phase == OUT) {
                lid = 1.0F - 0.6F * MenuMath.smooth(a.t / a.outTime);
            }
        }
        a.open = lid * blinkFactor;

        float flicker = 1.0F;
        if (a.flicker > 0.0F) {
            float h = MenuMath.hash01(a.flickerSeed + (long) (time * 25.0F));
            flicker = 1.0F - (1.0F - (0.15F + 0.85F * h)) * mood.flashing;
        }
        a.alpha = a.maxAlpha * visibility * flicker;
    }

    private void jump(Apparition a) {
        a.x = MenuMath.clamp(a.x + MenuMath.range(random, -0.12F, 0.12F), 0.30F, 1.0F);
        a.y = MenuMath.clamp(a.y + MenuMath.range(random, -0.10F, 0.10F), 0.08F, 0.95F);
        a.tear = 1.0F;
        a.tearSeed = random.nextLong();
    }

    private int spriteCount(int kind) {
        return switch (kind) {
            case FACE -> art.faces.size();
            case EYES -> art.eyes.size();
            case MOUTH -> art.mouths.size();
            default -> art.silhouettes.size();
        };
    }

    private void spawnRegular(Apparition a) {
        int count = spriteCount(a.kind);
        if (count == 0) {
            a.wait = 1000.0F;
            return;
        }
        a.sprite = random.nextInt(count);
        a.mirror = random.nextBoolean();
        a.eventSpawned = false;
        a.opening = false;
        a.open = 1.0F;
        a.blink = -1.0F;
        a.nextBlink = MenuMath.range(random, 1.0F, 6.0F);
        a.tear = 0.0F;
        a.flicker = 0.0F;
        switch (a.kind) {
            case FACE -> {
                a.size = MenuMath.range(random, 0.38F, 0.70F) * (mood.stage >= 5 ? 1.15F : 1.0F);
                a.maxAlpha = mood.faceAlpha * MenuMath.range(random, 0.65F, 1.0F);
                a.glow = random.nextFloat() < 0.1F ? 0.0F : MenuMath.range(random, 0.8F, 1.2F);
                placeAvoiding(a, 0.40F, 1.02F, 0.15F, 0.85F);
            }
            case EYES -> {
                a.size = MenuMath.range(random, 0.022F, 0.055F);
                a.maxAlpha = mood.eyeAlpha * MenuMath.range(random, 0.6F, 1.0F);
                a.glow = 1.0F;
                placeAvoiding(a, 0.38F, 0.98F, 0.06F, 0.94F);
            }
            case MOUTH -> {
                a.size = MenuMath.range(random, 0.07F, 0.15F);
                a.maxAlpha = mood.mouthAlpha * MenuMath.range(random, 0.7F, 1.0F);
                a.glow = 0.0F;
                a.opening = true;
                a.open = 0.0F;
                placeAvoiding(a, 0.42F, 0.96F, 0.30F, 0.92F);
            }
            default -> {
                a.size = MenuMath.range(random, 0.55F, 0.95F);
                a.maxAlpha = mood.silhouetteAlpha * MenuMath.range(random, 0.7F, 1.0F);
                a.glow = mood.stage >= 4 && random.nextFloat() < 0.5F ? 0.9F : 0.0F;
                a.x = MenuMath.range(random, 0.42F, 0.96F);
                a.y = 1.0F - a.size * 0.5F + MenuMath.range(random, 0.0F, 0.08F);
            }
        }
        if (a.kind == SILHOUETTE) {
            a.vx = MenuMath.range(random, -0.002F, 0.002F);
            a.vy = 0.0F;
        } else {
            a.vx = MenuMath.range(random, -0.010F, 0.010F);
            a.vy = MenuMath.range(random, -0.006F, 0.006F);
        }
        // how it shows itself
        float r = random.nextFloat();
        boolean gentle = mood.flashing < 0.3F;
        if (r < 0.2F && a.kind != SILHOUETTE) {
            // only for a moment
            a.inTime = gentle ? 0.6F : MenuMath.range(random, 0.12F, 0.3F);
            a.holdTime = MenuMath.range(random, 0.25F, 0.8F);
            a.outTime = MenuMath.range(random, 1.2F, 2.5F);
            a.maxAlpha = Math.min(1.0F, a.maxAlpha * 1.25F);
        } else if (r < 0.3F) {
            // lingers
            a.inTime = MenuMath.range(random, 4.0F, 7.0F);
            a.holdTime = MenuMath.range(random, 10.0F, 18.0F);
            a.outTime = MenuMath.range(random, 4.0F, 6.0F);
        } else {
            a.inTime = MenuMath.range(random, 1.6F, 4.0F);
            a.holdTime = MenuMath.range(random, 3.0F, 9.0F);
            a.outTime = random.nextFloat() < 0.15F && !gentle ? 0.05F : MenuMath.range(random, 1.5F, 4.0F);
        }
        a.phase = IN;
        a.t = 0.0F;
        a.alpha = 0.0F;
    }

    /** Picks the candidate position farthest from everything else that is visible. */
    private void placeAvoiding(Apparition a, float minX, float maxX, float minY, float maxY) {
        float bestX = MenuMath.range(random, minX, maxX);
        float bestY = MenuMath.range(random, minY, maxY);
        float bestScore = -1.0F;
        for (int attempt = 0; attempt < 6; attempt++) {
            float x = MenuMath.range(random, minX, maxX);
            float y = MenuMath.range(random, minY, maxY);
            float score = Float.MAX_VALUE;
            for (Apparition[] group : regular) {
                for (Apparition other : group) {
                    if (other == a || other.phase == HIDDEN) {
                        continue;
                    }
                    float dx = (other.x - x) * aspect;
                    float dy = other.y - y;
                    score = Math.min(score, dx * dx + dy * dy);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
            }
        }
        a.x = bestX;
        a.y = bestY;
    }

    // ---------------------------------------------------------------------------------------
    // events

    private Apparition freeExtra() {
        for (Apparition a : extras) {
            if (a.phase == HIDDEN) {
                return a;
            }
        }
        return null;
    }

    private Apparition summon(int kind, float x, float y, float size, float alpha, float in, float hold, float out) {
        if (!art.hasApparitions() || mood == null) {
            return null;
        }
        int count = spriteCount(kind);
        Apparition a = freeExtra();
        if (a == null || count == 0) {
            return null;
        }
        boolean gentle = mood.flashing < 0.3F;
        a.kind = kind;
        a.sprite = random.nextInt(count);
        a.mirror = random.nextBoolean();
        a.x = x;
        a.y = y;
        a.size = size;
        a.vx = MenuMath.range(random, -0.004F, 0.004F);
        a.vy = 0.0F;
        a.maxAlpha = Math.min(1.0F, alpha);
        a.inTime = gentle ? Math.max(0.5F, in) : in;
        a.holdTime = hold;
        a.outTime = gentle ? Math.max(0.8F, out) : out;
        a.phase = IN;
        a.t = 0.0F;
        a.alpha = 0.0F;
        a.flicker = 0.0F;
        a.tear = 0.0F;
        a.open = 1.0F;
        a.blink = -1.0F;
        a.nextBlink = 10.0F;
        a.glow = kind == SILHOUETTE ? (mood.stage >= 3 ? 1.0F : 0.0F) : 1.1F;
        a.opening = false;
        a.eventSpawned = true;
        a.still = false;
        return a;
    }

    /** A face suddenly appears behind the title. Rect in screen fractions. */
    boolean faceBehind(float x0, float y0, float x1, float y1) {
        float x = MenuMath.range(random, x0 + (x1 - x0) * 0.2F, x0 + (x1 - x0) * 0.8F);
        float y = (y0 + y1) * 0.5F;
        float size = MenuMath.clamp((y1 - y0) * 1.6F, 0.30F, 0.70F);
        return summon(FACE, x, y, size, Math.min(0.5F, mood.faceAlpha * 1.6F), 0.25F, MenuMath.range(random, 0.5F, 1.2F), 1.4F) != null;
    }

    /** An eye opens in the dark for a moment. */
    boolean eyeOpens(float x, float y) {
        Apparition a = summon(EYES, x, y, MenuMath.range(random, 0.03F, 0.06F), Math.min(1.0F, mood.eyeAlpha * 1.2F),
                0.35F, MenuMath.range(random, 0.6F, 1.4F), 0.5F);
        if (a == null) {
            return false;
        }
        a.opening = true;
        a.open = 0.0F;
        return true;
    }

    boolean eyeOpensSomewhere() {
        return eyeOpens(MenuMath.range(random, 0.40F, 0.96F), MenuMath.range(random, 0.08F, 0.90F));
    }

    /** A mouth appears where there was nothing. */
    boolean mouthAppears() {
        Apparition a = summon(MOUTH, MenuMath.range(random, 0.45F, 0.94F), MenuMath.range(random, 0.25F, 0.9F),
                MenuMath.range(random, 0.09F, 0.17F), Math.min(0.6F, Math.max(0.2F, mood.mouthAlpha * 1.4F)), 0.9F,
                MenuMath.range(random, 0.8F, 2.0F), random.nextBoolean() ? 0.05F : 1.0F);
        if (a == null) {
            return false;
        }
        a.opening = true;
        a.open = 0.0F;
        return true;
    }

    /** A silhouette stands behind the buttons for a while. Rect in screen fractions. */
    boolean silhouetteBehind(float x0, float y0, float x1, float y1) {
        float size = MenuMath.clamp(Math.max(0.6F, (1.0F - y0) * 1.1F), 0.5F, 0.98F);
        float x = MenuMath.range(random, x0 + (x1 - x0) * 0.25F, x1);
        float y = 1.0F - size * 0.5F + 0.04F;
        return summon(SILHOUETTE, x, y, size, Math.max(0.35F, mood.silhouetteAlpha), 1.0F, MenuMath.range(random, 1.5F, 3.0F), 1.5F) != null;
    }

    /** A pair of eyes watching from around the waiting message. */
    void watchers(float centreY) {
        eyeOpens(MenuMath.range(random, 0.2F, 0.35F), centreY - MenuMath.range(random, 0.12F, 0.22F));
        eyeOpens(MenuMath.range(random, 0.65F, 0.8F), centreY + MenuMath.range(random, 0.10F, 0.2F));
    }

    /** Everything tears at once (static burst). */
    void tearAll() {
        for (Apparition[] group : regular) {
            for (Apparition a : group) {
                if (a.phase != HIDDEN) {
                    a.tear = 1.0F;
                    a.tearSeed = random.nextLong();
                }
            }
        }
    }
}
