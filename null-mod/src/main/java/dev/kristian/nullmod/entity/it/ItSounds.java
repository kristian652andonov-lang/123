package dev.kristian.nullmod.entity.it;

import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * IT's sounds. The files are synthesised by {@code tools/gen_sounds_it.py} into
 * {@code assets/null/sounds/it/}; their events are described in {@code assets-src/sounds/it.json}
 * and merged into sounds.json by {@code tools/merge_sounds.py}. Registered by {@link ItModule}.
 */
public final class ItSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, NullMod.MODID);

    /** One enormous footfall: the ground takes the weight. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STEP = sound("it.step");
    /** Far away, as it arrives. */
    public static final DeferredHolder<SoundEvent, SoundEvent> HOWL = sound("it.howl");
    /** Wet breathing, close. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BREATH = sound("it.breath");
    /** Bones clicking and joints cracking as it moves. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CLICK = sound("it.click");
    /** At the window. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCREAM = sound("it.scream");
    /** Blades dragged over wood and glass. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SCRAPE = sound("it.scrape");
    /** It seizes someone. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GRAB = sound("it.grab");
    /** The hand through the chest: a crunch. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PIERCE = sound("it.pierce");
    /** A wet tear. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TEAR = sound("it.tear");
    /** A swallow. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SWALLOW = sound("it.swallow");
    /** It turns away and goes. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RETREAT = sound("it.retreat");

    private ItSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(NullMod.id(name)));
    }
}
