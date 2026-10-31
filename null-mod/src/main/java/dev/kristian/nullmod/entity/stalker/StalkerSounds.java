package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The figure's sounds. The files are synthesised by {@code tools/gen_sounds_stalkers.py} into
 * {@code assets/null/sounds/stalkers/}; their events are described in
 * {@code assets-src/sounds/stalkers.json} and merged into sounds.json by
 * {@code tools/merge_sounds.py}. Registered by {@link StalkerModule}.
 * <p>
 * They are always played through the client's audio corruption system ({@code audio.sound} or
 * {@code AudioCorruptionManager}), which picks the player's channel switch from the name:
 * {@code *footsteps*} follow "phantom footsteps", {@code *breath*} the creature sounds, the rest the
 * event sounds; all of them follow the horror-sound switch and intensity.
 */
public final class StalkerSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, NullMod.MODID);

    /** A wooden door, trapdoor or gate opening slowly on old hinges. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DOOR_CREAK = sound("stalker.door_creak");
    /** One heavy, slow footfall. */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOOTSTEPS = sound("stalker.footsteps");
    /** A pickaxe striking stone somewhere in the rock: muffled, echoing. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DIG = sound("stalker.dig");
    /** Slow breathing against a window pane. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BREATH_GLASS = sound("stalker.breath_glass");
    /** A fingertip tapping on glass. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GLASS_TAP = sound("stalker.glass_tap");
    /** One footfall of something sprinting: hard, fast, heavy. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RUNNER_FOOTSTEPS = sound("stalker.runner_footsteps");

    private StalkerSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(NullMod.id(name)));
    }
}
