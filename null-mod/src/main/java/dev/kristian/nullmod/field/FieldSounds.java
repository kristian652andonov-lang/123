package dev.kristian.nullmod.field;

import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The field's own sounds, synthesised by {@code tools/gen_sounds_field.py} into
 * {@code assets/null/sounds/field/} and described in {@code assets-src/sounds/field.json}.
 */
public final class FieldSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, NullMod.MODID);

    // Loops
    /** Wind hissing through endless wheat. */
    public static final DeferredHolder<SoundEvent, SoundEvent> WIND = sound("field.wind");
    /** A low unease under the day, louder as the sun cracks. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE = sound("field.drone");
    /** The night: almost nothing, a pressure in the ears. */
    public static final DeferredHolder<SoundEvent, SoundEvent> NIGHT_AIR = sound("field.night_air");

    // The day
    public static final DeferredHolder<SoundEvent, SoundEvent> CROW = sound("field.crow");
    public static final DeferredHolder<SoundEvent, SoundEvent> CREAK = sound("field.creak");
    public static final DeferredHolder<SoundEvent, SoundEvent> BARN_DOOR = sound("field.barn_door");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHIMES = sound("field.chimes");
    /** The sun cracking, far above. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SUN_CRACK = sound("field.sun_crack");
    /** The sun breaking apart and the light dying. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SUN_SHATTER = sound("field.sun_shatter");
    /** The light coming back. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DAWN = sound("field.dawn");

    // The night
    /** A distant bell, once, when the countdown begins. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TOLL = sound("field.toll");
    /** Enormous footsteps somewhere far out in the dark. */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOOTSTEPS = sound("field.footsteps");
    /** Something heavy dragged through the wheat. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRAG = sound("field.drag");
    /** Slow, heavy breathing out in the field. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BREATH = sound("field.breath");
    /** One heartbeat; played faster and louder as the countdown runs out. */
    public static final DeferredHolder<SoundEvent, SoundEvent> HEARTBEAT = sound("field.heartbeat");

    private FieldSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(NullMod.id(name)));
    }
}
