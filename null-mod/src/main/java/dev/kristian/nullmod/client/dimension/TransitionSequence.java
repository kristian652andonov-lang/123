package dev.kristian.nullmod.client.dimension;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.client.audio.AudioCorruptionManager;
import dev.kristian.nullmod.client.visual.VisualDistortionManager;
import dev.kristian.nullmod.network.TransitionPayload;
import dev.kristian.nullmod.registry.NullDimensions;
import dev.kristian.nullmod.registry.NullSounds;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;

/**
 * The cinematic move into (and out of) NULL, driven by {@link TransitionPayload}s:
 * <ul>
 * <li><b>COLLAPSE</b>: everything stops. All sound is cut (only transition sounds may play), every
 * distortion is cleared, and the screen fades to black over three seconds while the collapse
 * sound plays.</li>
 * <li><b>ARRIVE</b> (the player has been moved): three seconds of darkness, then the distorted
 * arrival sound slowly begins and the picture comes back over five seconds; only then does
 * sound return.</li>
 * <li><b>RETURN</b>: the reverse; black and silent until the player is back in the world, then a
 * slow return.</li>
 * <li><b>CLEAR</b>: abort; the screen and the sound come back at once.</li>
 * </ul>
 * Every state has a timeout, so a lost packet can never leave the game black or silent, and a
 * dimension change during the collapse is treated as the arrival if its packet is late.
 */
final class TransitionSequence {
    private enum State { IDLE, COLLAPSING, ARRIVING, RETURNING, REVEALING }

    /** Darkness after arriving before anything happens. */
    private static final int ARRIVE_DARK = 60;
    /** Length of the fade back in after arriving. */
    private static final int ARRIVE_FADE = 100;
    /** Darkness after being returned before the fade back in. */
    private static final int RETURN_DARK = 20;
    private static final int RETURN_FADE = 60;
    /** Extra patience beyond the announced duration before a state gives up. */
    private static final int GRACE = 300;

    private static State state = State.IDLE;
    private static int ticks;
    private static int expected;
    @Nullable
    private static ClientLevel levelAtStart;

    private TransitionSequence() {
    }

    /** True while the screen is (being) darkened by a transition. */
    static boolean active() {
        return state != State.IDLE;
    }

    static void onPayload(TransitionPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        int duration = Math.max(1, payload.duration());
        switch (payload.phase()) {
            case TransitionPayload.PHASE_COLLAPSE -> collapse(mc, duration);
            case TransitionPayload.PHASE_ARRIVE -> {
                if (state != State.ARRIVING) {
                    arrive(duration);
                }
                // Otherwise the dimension change already started the arrival; let it run on.
            }
            case TransitionPayload.PHASE_RETURN -> leave(mc, duration);
            case TransitionPayload.PHASE_CLEAR -> clear();
            default -> NullMod.LOGGER.warn("Unknown NULL transition phase {}", payload.phase());
        }
    }

    private static void collapse(Minecraft mc, int duration) {
        enter(State.COLLAPSING, duration);
        levelAtStart = mc.level;
        silenceEverything();
        VisualDistortionManager.setTransitionFade(1.0F, 60);
        playTransitionSound(NullSounds.TRANSITION_COLLAPSE.get(), 1.0F);
    }

    private static void arrive(int duration) {
        enter(State.ARRIVING, duration);
        silenceEverything();
        if (VisualDistortionManager.transitionFadeAlpha() < 0.98F) {
            // Arriving without a collapse (respawned into NULL): go dark gently, never with a flash.
            VisualDistortionManager.setTransitionFade(1.0F, ClientLimits.flashing() < 0.5F ? 20 : 8);
        }
    }

    private static void leave(Minecraft mc, int duration) {
        enter(State.RETURNING, duration);
        levelAtStart = mc.level;
        silenceEverything();
        VisualDistortionManager.setTransitionFade(1.0F, Math.max(20, duration * 3 / 4));
        playTransitionSound(NullSounds.TRANSITION_COLLAPSE.get(), 0.75F);
    }

    /** Abort: the picture and the sound come back. */
    static void clear() {
        if (state != State.IDLE || VisualDistortionManager.transitionFadeAlpha() > 0.0F) {
            VisualDistortionManager.setTransitionFade(0.0F, 20);
        }
        AudioCorruptionManager.setTransitionSilence(false);
        state = State.IDLE;
        ticks = 0;
        levelAtStart = null;
    }

    static void tick(Minecraft mc) {
        if (state == State.IDLE || mc.isPaused()) {
            return;
        }
        ticks++;
        switch (state) {
            case COLLAPSING -> {
                if (mc.level != null && mc.level != levelAtStart && NullDimensions.isNull(mc.level)) {
                    // Moved into NULL before the arrival packet came: arrive now.
                    arrive(expected);
                } else if (ticks > expected + GRACE) {
                    NullMod.LOGGER.warn("NULL transition: no arrival {} ticks after the collapse; giving the screen back", ticks);
                    clear();
                }
            }
            case ARRIVING -> {
                if (ticks == ARRIVE_DARK) {
                    playTransitionSound(NullSounds.TRANSITION_ARRIVAL.get(), 1.0F);
                } else if (ticks == ARRIVE_DARK + 10) {
                    VisualDistortionManager.setTransitionFade(0.0F, ARRIVE_FADE);
                } else if (ticks >= ARRIVE_DARK + 10 + ARRIVE_FADE) {
                    AudioCorruptionManager.setTransitionSilence(false);
                    state = State.IDLE;
                    levelAtStart = null;
                }
            }
            case RETURNING -> {
                boolean moved = mc.level != null && mc.level != levelAtStart && !NullDimensions.isNull(mc.level);
                if (moved || ticks > expected + GRACE) {
                    if (!moved) {
                        NullMod.LOGGER.warn("NULL transition: still not returned {} ticks later; giving the screen back", ticks);
                    }
                    enter(State.REVEALING, RETURN_DARK + RETURN_FADE);
                }
            }
            case REVEALING -> {
                if (ticks == RETURN_DARK) {
                    VisualDistortionManager.setTransitionFade(0.0F, RETURN_FADE);
                } else if (ticks >= RETURN_DARK + RETURN_FADE + 5) {
                    AudioCorruptionManager.setTransitionSilence(false);
                    state = State.IDLE;
                    levelAtStart = null;
                }
            }
            default -> {
            }
        }
    }

    /** Logout: forget everything (the visual and audio systems reset themselves). */
    static void reset() {
        state = State.IDLE;
        ticks = 0;
        expected = 0;
        levelAtStart = null;
    }

    private static void enter(State next, int duration) {
        state = next;
        ticks = 0;
        expected = duration;
    }

    private static void silenceEverything() {
        AudioCorruptionManager.setTransitionSilence(true);
        VisualDistortionManager.clearAll();
        DimensionAtmosphere.reset();
    }

    /** Transition sounds play through the silence; the player's horror sound volume still applies. */
    private static void playTransitionSound(SoundEvent sound, float pitch) {
        float audio = ClientLimits.audio();
        if (audio <= 0.0F) {
            return;
        }
        AudioCorruptionManager.playUi(sound, 0.35F + 0.6F * audio, pitch);
    }
}
