package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * NULL's music, and what becomes of the game's own.
 * <ul>
 * <li>NULL's own pieces (the distorted lullaby and piano, the NULL dimension's theme, the field's
 * piece and the overlays laid over music) play through this player on the master source: no NULL
 * setting and none of the game's category sliders switch them off, only the master volume makes
 * them quieter. The transition into NULL and the sudden silences may pause or cut them; this player
 * always starts the right music again afterwards.</li>
 * <li>In the NULL dimension NULL plays its theme itself (the game starts no music there). In the field
 * of Stage 6 a faint, broken pastoral piece plays by day and nothing at all once the sun shatters.</li>
 * <li>Everywhere else the game plays its own music. Stages 1-2 leave it alone, Stage 3 bends a track
 * now and then, barely. From Stage 4 on every track it starts is taken over: bent in real time on
 * the music source ({@link MusicWarp}: wow, flutter, the tape dragging, dropouts, stutters, and with
 * a detuned double running alongside, reversed fragments and notes left hanging laid over it), or
 * replaced by one of NULL's pieces, in their broken versions more and more often.</li>
 * </ul>
 * The game's music manager only sees its track end early and waits for the next one as usual.
 * Render thread only.
 */
public final class NullMusic {
    private enum Kind { PIECE, THEME, FIELD, GAME }

    // The plain pieces are registered sound events; the broken versions, the field's piece and the
    // overlays are only ever played by the client, by name (they exist in sounds.json only).
    private static final SoundEvent DISTORTED_1_BROKEN = clientOnly("music.distorted_1_broken");
    private static final SoundEvent DISTORTED_2_BROKEN = clientOnly("music.distorted_2_broken");
    private static final SoundEvent THEME_BROKEN = clientOnly("music.null_theme_broken");
    private static final SoundEvent FIELD_DAY = clientOnly("music.field_day");
    private static final SoundEvent REVERSED = clientOnly("music.fragment_reversed");
    private static final SoundEvent HANG = clientOnly("music.fragment_hang");

    private static final float PIECE_VOLUME = 0.9F;
    /** The field's piece is faint: an old record somewhere far across the wheat. */
    private static final float FIELD_VOLUME = 0.55F;
    private static final float FIELD_LEVEL = 0.8F;
    /** Without a report from the field's ambience for this long, it is not day (or we are not there). */
    private static final int FIELD_REPORT_TIMEOUT = 40;

    @Nullable
    private static MusicInstance track;
    @Nullable
    private static MusicInstance twin;
    @Nullable
    private static MusicInstance overlay;
    @Nullable
    private static Kind kind;
    private static int nextTheme = -1;
    private static int nextField = -1;
    private static boolean fieldDay;
    private static int fieldReportAge = FIELD_REPORT_TIMEOUT;
    private static int twinTimer;
    private static int overlayCooldown;

    private NullMusic() {
    }

    private static SoundEvent clientOnly(String name) {
        return SoundEvent.createVariableRangeEvent(NullMod.id(name));
    }

    // ------------------------------------------------------------------ public API

    /** The field's ambience reports every tick whether it is day there (the only time its piece plays). */
    public static void fieldDay(boolean day) {
        fieldDay = day;
        fieldReportAge = 0;
    }

    /** True while one of NULL's pieces, or a game track NULL took over, is playing. */
    public static boolean playing() {
        return isActive(track);
    }

    /**
     * How corrupted music is, 0..1 (see {@link MusicWarp}): nothing in Stages 1-2, a trace in Stage 3,
     * then more with every stage and as each stage goes on; the most in the field.
     */
    public static float levelFor(CorruptionStage stage, float stageProgress, boolean inNull, boolean inField) {
        float progress = Mth.clamp(stageProgress, 0.0F, 1.0F);
        float level = switch (stage) {
            case START, UNDER_MIDDLE -> 0.0F;
            case MIDDLE -> 0.1F;
            case ABOVE_MIDDLE -> 0.4F + 0.15F * progress;
            case NULL -> 0.7F + 0.1F * progress;
            case HARVEST -> 0.95F;
        };
        if (inNull) {
            level = Math.max(level, 0.75F);
        }
        return inField ? 1.0F : level;
    }

    // ------------------------------------------------------------------ used by the audio package

    /** The level right now: 0 before the stage is known or while the corruption is paused. */
    static float level(Minecraft mc) {
        if (mc.level == null || !ClientCorruptionState.synced() || ClientCorruptionState.paused()) {
            return 0.0F;
        }
        return levelFor(ClientCorruptionState.stage(), ClientCorruptionState.stageProgress(),
                NullDimensions.isNull(mc.level), FieldDimension.isField(mc.level));
    }

    static void tick(Minecraft mc, RandomSource random) {
        if (track != null && !isActive(track)) {
            ended(random);
        }
        if (twin != null && (track == null || !isActive(twin))) {
            release(twin, 20);
            twin = null;
        }
        if (overlay != null && !isActive(overlay)) {
            overlay = null;
        }
        if (fieldReportAge < FIELD_REPORT_TIMEOUT) {
            fieldReportAge++;
        }
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        float level = level(mc);
        boolean quiet = AudioCorruptionManager.isSilenced();
        if (FieldDimension.isField(mc.level)) {
            tickField(random, quiet);
        } else if (NullDimensions.isNull(mc.level)) {
            tickNull(level, random, quiet);
        } else {
            tickWorld(mc);
        }
        if (track != null && kind != Kind.FIELD && track.warp() != null) {
            // The stage may move on while a piece plays.
            track.warp().setLevel(level);
        }
        tickLayers(level, random, quiet);
    }

    /**
     * The game is about to start one of its tracks (a {@link PlaySoundEvent} on the music source).
     * Stages 1-2 leave it alone; Stage 3 now and then bends it, barely; from Stage 4 it is always
     * taken over (bent, or replaced by one of NULL's pieces). In NULL and the field it never plays.
     */
    static void onGameMusic(PlaySoundEvent event, SoundInstance sound, Minecraft mc, RandomSource random) {
        if (mc.level == null) {
            return;
        }
        if (NullDimensions.isNull(mc.level) || FieldDimension.isField(mc.level)) {
            event.setSound(null);
            return;
        }
        if (isActive(track)) {
            Music situational = mc.getSituationalMusic();
            if (situational != null && situational.replaceCurrentMusic()) {
                // A boss fight, the End, the credits: their music begins (and is bent in its turn).
                stop(40);
            } else {
                event.setSound(null);
                return;
            }
        }
        float level = level(mc);
        if (level <= 0.0F) {
            return;
        }
        if (level < 0.3F) {
            if (random.nextFloat() < 0.25F) {
                event.setSound(null);
                bend(sound, level, random);
            }
            return;
        }
        event.setSound(null);
        if (random.nextFloat() < (level >= 0.7F ? 0.5F : 0.3F)) {
            startPiece(level, random);
        } else {
            bend(sound, level, random);
        }
    }

    /** The audio.corrupted_music effect and the Stage 5 ambience: one of NULL's pieces begins now. */
    static void playCorrupted(RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || AudioCorruptionManager.isSilenced()) {
            return;
        }
        if (FieldDimension.isField(mc.level)) {
            // The field has its own piece, by day only: the night belongs to the field's own sounds.
            if (isFieldDay() && kind != Kind.FIELD) {
                start(Kind.FIELD, FIELD_DAY, FIELD_VOLUME, 1.0F, FIELD_LEVEL, random);
            }
            return;
        }
        mc.getMusicManager().stopPlaying();
        float level = level(mc);
        if (NullDimensions.isNull(mc.level)) {
            start(Kind.THEME, theme(level, random), PIECE_VOLUME, 1.0F, level, random);
        } else {
            startPiece(level, random);
        }
    }

    /** A {@code music.*} sound sent by the server (the generic audio.sound effect): played as NULL's music. */
    static void playNamed(SoundEvent sound, float volume, float pitch, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || AudioCorruptionManager.isSilenced()) {
            return;
        }
        mc.getMusicManager().stopPlaying();
        start(Kind.PIECE, sound, Mth.clamp(volume, 0.0F, 1.0F), pitch, level(mc), random);
    }

    /** Stops all of NULL's music (and any game track it took over), fading over {@code fade} ticks. */
    static void stop(int fade) {
        release(track, fade);
        release(twin, fade);
        release(overlay, fade);
        track = null;
        twin = null;
        overlay = null;
        kind = null;
    }

    /** A new world or dimension: nothing carries over, the schedules begin again there. */
    static void reset() {
        stop(0);
        nextTheme = -1;
        nextField = -1;
        fieldDay = false;
        fieldReportAge = FIELD_REPORT_TIMEOUT;
    }

    /** The game's own music never starts in NULL or in the field: NULL plays its own there. */
    static void onSelectMusic(SelectMusicEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && (NullDimensions.isNull(mc.level) || FieldDimension.isField(mc.level))) {
            event.setMusic(null);
        }
    }

    // ------------------------------------------------------------------ where the player is

    private static void tickWorld(Minecraft mc) {
        nextTheme = -1;
        nextField = -1;
        if (track == null) {
            return;
        }
        if (kind == Kind.THEME || kind == Kind.FIELD) {
            stop(40);
            return;
        }
        Music situational = mc.getSituationalMusic();
        if (situational != null && situational.replaceCurrentMusic()
                && !situational.getEvent().value().getLocation().equals(track.getLocation())) {
            stop(60);
        }
    }

    private static void tickNull(float level, RandomSource random, boolean quiet) {
        nextField = -1;
        if (track != null && kind != Kind.THEME && kind != Kind.PIECE) {
            stop(40);
        }
        if (track != null) {
            return;
        }
        if (nextTheme < 0) {
            // A little while after arriving (or after the last time it played), the theme begins.
            nextTheme = 400 + random.nextInt(600);
        }
        if (!quiet && --nextTheme <= 0) {
            nextTheme = -1;
            start(Kind.THEME, theme(level, random), PIECE_VOLUME, 1.0F, level, random);
        }
    }

    private static void tickField(RandomSource random, boolean quiet) {
        nextTheme = -1;
        if (track != null && kind != Kind.FIELD) {
            stop(30);
        }
        if (!isFieldDay()) {
            nextField = -1;
            if (track != null && !track.fading()) {
                // The music dies with the light: the record drags down and fades away.
                MusicWarp warp = track.warp();
                if (warp != null) {
                    warp.drag(0.22F, 30, 40, 1);
                }
                track.fadeOut(45);
            }
            return;
        }
        if (track != null) {
            return;
        }
        if (nextField < 0) {
            nextField = 160 + random.nextInt(240);
        }
        if (!quiet && --nextField <= 0) {
            nextField = -1;
            start(Kind.FIELD, FIELD_DAY, FIELD_VOLUME, 1.0F, FIELD_LEVEL, random);
        }
    }

    private static boolean isFieldDay() {
        return fieldDay && fieldReportAge < FIELD_REPORT_TIMEOUT;
    }

    /** The track ended (or the game cut it off): when the next one may come depends on where we are. */
    private static void ended(RandomSource random) {
        Kind was = kind;
        track = null;
        kind = null;
        if (overlay != null) {
            release(overlay, 30);
            overlay = null;
        }
        if (was == Kind.THEME) {
            // Minutes of the dimension's own sounds before the theme comes back.
            nextTheme = 2400 + random.nextInt(3600);
        } else if (was == Kind.FIELD) {
            nextField = 300 + random.nextInt(600);
        }
    }

    // ------------------------------------------------------------------ the layers over a piece

    private static void tickLayers(float level, RandomSource random, boolean quiet) {
        MusicInstance main = track;
        if (main == null || quiet || main.fading()) {
            return;
        }
        if (twin != null && --twinTimer <= 0) {
            // The detuned double comes in for a passage, then leaves again.
            boolean comingIn = twin.mixTarget() < 0.5F;
            twin.mixTo(comingIn ? 1.0F : 0.0F, 40 + random.nextInt(60));
            twinTimer = comingIn ? 100 + random.nextInt(240) : 240 + random.nextInt(480);
        }
        if (level < 0.4F || overlay != null) {
            return;
        }
        MusicWarp warp = main.warp();
        if (warp != null && warp.droppedOut() && level >= 0.6F && random.nextFloat() < 0.35F) {
            // The music drops out and one note is left hanging in the air.
            overlay = layer(HANG, main, 0.6F, random);
        } else if (--overlayCooldown <= 0) {
            overlayCooldown = Math.round((700 + random.nextInt(1300)) / level);
            if (random.nextFloat() < 0.6F) {
                // A fragment of music running backwards, swelling into nothing.
                overlay = layer(REVERSED, main, 0.45F, random);
            }
        }
    }

    @Nullable
    private static MusicInstance layer(SoundEvent sound, MusicInstance main, float share, RandomSource random) {
        return play(new MusicInstance(sound, main.getSource(), main.baseVolume() * share, 0.97F + random.nextFloat() * 0.06F,
                null, null, random));
    }

    private static void maybeDouble(MusicInstance main, float level, RandomSource random) {
        if (level < 0.4F || random.nextFloat() >= 0.2F + 0.45F * level) {
            return;
        }
        Sound chosen = main.getSound();
        if (chosen == null || chosen == SoundManager.EMPTY_SOUND || chosen == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
            return;
        }
        float detune = 1.0F + (random.nextBoolean() ? 1.0F : -1.0F) * (0.006F + random.nextFloat() * 0.008F);
        MusicInstance dbl = new MusicInstance(SoundEvent.createVariableRangeEvent(main.getLocation()), main.getSource(),
                main.baseVolume() * 0.45F, detune, null, main, random).fixed(chosen).mix(0.0F);
        twin = play(dbl);
        twinTimer = 200 + random.nextInt(400);
    }

    // ------------------------------------------------------------------ starting and stopping

    private static void startPiece(float level, RandomSource random) {
        // The plain pieces are already wrong; the broken versions belong to Stage 4 and later.
        boolean broken = level >= 0.6F ? random.nextFloat() < 0.75F : level >= 0.3F && random.nextFloat() < 0.35F;
        SoundEvent piece;
        if (level >= 0.6F && random.nextFloat() < 0.3F) {
            piece = theme(level, random);
        } else if (random.nextBoolean()) {
            piece = broken ? DISTORTED_1_BROKEN : NullSounds.MUSIC_DISTORTED_1.get();
        } else {
            piece = broken ? DISTORTED_2_BROKEN : NullSounds.MUSIC_DISTORTED_2.get();
        }
        start(Kind.PIECE, piece, PIECE_VOLUME, 1.0F, level, random);
    }

    private static SoundEvent theme(float level, RandomSource random) {
        return level >= 0.6F && random.nextFloat() < 0.6F ? THEME_BROKEN : NullSounds.MUSIC_NULL.get();
    }

    private static void start(Kind what, SoundEvent sound, float volume, float pitch, float level, RandomSource random) {
        stop(30);
        MusicWarp warp = level > 0.0F ? new MusicWarp(random, level) : null;
        MusicInstance next = play(new MusicInstance(sound, SoundSource.MASTER, volume, pitch, warp, null, random)
                .fadeIn(what == Kind.FIELD ? 60 : 30));
        if (next == null) {
            return;
        }
        track = next;
        kind = what;
        overlayCooldown = 300 + random.nextInt(600);
        maybeDouble(next, level, random);
    }

    /** A game track, bent: the same music on the music source, warped from tick to tick. */
    private static void bend(SoundInstance original, float level, RandomSource random) {
        stop(30);
        MusicInstance bent = play(new MusicInstance(SoundEvent.createVariableRangeEvent(original.getLocation()), SoundSource.MUSIC,
                1.0F, 1.0F, new MusicWarp(random, level), null, random));
        if (bent == null) {
            return;
        }
        track = bent;
        kind = Kind.GAME;
        overlayCooldown = 300 + random.nextInt(600);
        maybeDouble(bent, level, random);
    }

    @Nullable
    private static MusicInstance play(MusicInstance sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        mc.getSoundManager().play(sound);
        return sound;
    }

    private static void release(@Nullable MusicInstance sound, int fade) {
        if (sound == null) {
            return;
        }
        if (fade <= 0) {
            sound.stopNow();
            Minecraft.getInstance().getSoundManager().stop(sound);
        } else {
            sound.fadeOut(fade);
        }
    }

    private static boolean isActive(@Nullable MusicInstance sound) {
        return sound != null && !sound.isStopped() && Minecraft.getInstance().getSoundManager().isActive(sound);
    }
}
