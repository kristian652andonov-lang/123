package dev.kristian.nullmod.client.audio;

import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.client.effect.ClientEffects;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.registry.NullSounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Client handlers for every {@code audio.*} effect the server can send (see AudioEvents). */
final class AudioEffects {
    private AudioEffects() {
    }

    static void register() {
        ClientEffects.register("audio.sound", AudioEffects::sound);
        ClientEffects.register("audio.static", AudioEffects::staticWhisper);
        ClientEffects.register("audio.footsteps_distant", AudioEffects::footstepsDistant);
        ClientEffects.register("audio.underground", AudioEffects::underground);
        ClientEffects.register("audio.interference", AudioEffects::interference);
        ClientEffects.register("audio.hum", AudioEffects::hum);
        ClientEffects.register("audio.reversed", AudioEffects::reversed);
        ClientEffects.register("audio.explosion", AudioEffects::explosion);
        ClientEffects.register("audio.silence", AudioEffects::silence);
        ClientEffects.register("audio.heartbeat", AudioEffects::heartbeat);
        ClientEffects.register("audio.corrupted_music", AudioEffects::corruptedMusic);
        ClientEffects.register("audio.whisper_behind", AudioEffects::whisperBehind);
        ClientEffects.register("audio.footsteps_behind", AudioEffects::footstepsBehind);
        ClientEffects.register("audio.voices", AudioEffects::voices);
        ClientEffects.register("audio.breath", AudioEffects::breath);
        ClientEffects.register("audio.knock", AudioEffects::knock);
        ClientEffects.register("audio.pitch_chaos", AudioEffects::pitchChaos);
    }

    /**
     * Generic: text = sound id, pos or an ambiguous spot, volume = intensity, pitch = data / 100.
     * The channel follows from the sound's name so the player's toggles still apply; a {@code music.*}
     * sound is NULL's music and goes to {@link NullMusic}, which no toggle switches off.
     */
    private static void sound(ClientEffectPayload effect) {
        ResourceLocation id = ResourceLocation.tryParse(effect.text());
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(id);
        if (sound == null) {
            sound = SoundEvent.createVariableRangeEvent(id);
        }
        float volume = Mth.clamp(effect.intensity(), 0.0F, 2.0F);
        float pitch = effect.data() > 0 ? Mth.clamp(effect.data() / 100.0F, 0.5F, 2.0F) : 1.0F;
        if (id.getPath().startsWith("music.")) {
            // Music is NullMusic's: no setting switches it off.
            NullMusic.playNamed(sound, volume, pitch, AudioCorruptionManager.random());
            return;
        }
        AudioCorruptionManager.Channel channel = channelFor(id);
        Vec3 pos = effect.pos().map(Vec3::atCenterOf).orElse(null);
        AudioCorruptionManager.play(sound, channel, pos, volume, pitch);
    }

    static AudioCorruptionManager.Channel channelFor(ResourceLocation id) {
        String path = id.getPath();
        if (path.contains("whisper") || path.contains("voices")) {
            return AudioCorruptionManager.Channel.WHISPER;
        }
        if (path.contains("static") || path.contains("interference") || path.contains("glitch")) {
            return AudioCorruptionManager.Channel.STATIC;
        }
        if (path.contains("footsteps")) {
            return AudioCorruptionManager.Channel.FOOTSTEPS;
        }
        if (path.startsWith("nullroot.") || path.startsWith("visitor.") || path.contains("breath")) {
            return AudioCorruptionManager.Channel.CREATURE;
        }
        return AudioCorruptionManager.Channel.EVENT;
    }

    /** Static, and from Stage 2 sometimes a whisper underneath it. */
    private static void staticWhisper(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        float i = effect.intensity();
        SoundEvent noise = i < 0.45F ? NullSounds.STATIC_FAINT.get() : NullSounds.STATIC_BURST.get();
        AudioCorruptionManager.play(noise, AudioCorruptionManager.Channel.STATIC, null, 0.3F + 0.5F * i, 0.9F + random.nextFloat() * 0.2F);
        if (ClientCorruptionState.stage().number() >= 2 && random.nextFloat() < 0.5F) {
            float pitch = 0.9F + random.nextFloat() * 0.15F;
            AudioCorruptionManager.schedule(10 + random.nextInt(20), () -> AudioCorruptionManager.play(NullSounds.WHISPER.get(),
                    AudioCorruptionManager.Channel.WHISPER, null, 0.2F + 0.2F * i, pitch));
        }
    }

    /** Someone walking in the distance: step by step on the real ground, or a recorded walk. */
    private static void footstepsDistant(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        boolean late = ClientCorruptionState.stage().number() >= 4;
        if (random.nextFloat() < 0.65F) {
            PhantomWalkers.distant(random, effect.intensity(), late);
        } else {
            SoundEvent walk = late && random.nextBoolean() ? NullSounds.FOOTSTEPS_DISTORTED.get() : NullSounds.FOOTSTEPS_PLAYER.get();
            AudioCorruptionManager.playAt(walk, AudioCorruptionManager.Channel.FOOTSTEPS, AudioPositions.around(random, 14.0, 22.0),
                    0.45F + 0.35F * effect.intensity(), 0.92F + random.nextFloat() * 0.1F, 48.0F);
        }
    }

    private static void underground(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        Vec3 listener = AudioCorruptionManager.listener();
        if (listener == null) {
            return;
        }
        Vec3 pos = listener.add((random.nextDouble() - 0.5) * 8.0, -8.0 - random.nextDouble() * 8.0, (random.nextDouble() - 0.5) * 8.0);
        AudioCorruptionManager.playAt(NullSounds.UNDERGROUND.get(), AudioCorruptionManager.Channel.AMBIENT, pos,
                0.45F + 0.45F * effect.intensity(), 0.85F + random.nextFloat() * 0.15F, 40.0F);
    }

    /** Digital interference that stutters: the same burst repeats a few times. */
    private static void interference(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        float volume = 0.3F + 0.45F * effect.intensity();
        AudioCorruptionManager.playGlobal(NullSounds.DIGITAL_INTERFERENCE.get(), AudioCorruptionManager.Channel.STATIC, volume, 1.0F);
        int repeats = 1 + random.nextInt(3);
        int delay = 0;
        for (int k = 0; k < repeats; k++) {
            delay += 3 + random.nextInt(5);
            float pitch = 0.8F + random.nextFloat() * 0.5F;
            AudioCorruptionManager.schedule(delay, () -> AudioCorruptionManager.playGlobal(NullSounds.GLITCH.get(),
                    AudioCorruptionManager.Channel.STATIC, volume * 0.8F, pitch));
        }
    }

    /** A low hum that swells and fades over the effect's duration, from one fixed direction. */
    private static void hum(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        if (!AudioCorruptionManager.canStart(AudioCorruptionManager.Channel.AMBIENT)) {
            return;
        }
        int duration = Mth.clamp(effect.duration(), 100, 600);
        double angle = random.nextDouble() * Math.PI * 2.0;
        AudioCorruptionManager.start(new NullSoundInstance(NullSounds.LOW_HUM.get(), AudioCorruptionManager.Channel.AMBIENT,
                NullSoundInstance.Placement.FOLLOW, Math.cos(angle) * 6.0, -1.0, Math.sin(angle) * 6.0,
                0.25F + 0.35F * effect.intensity(), 0.8F + random.nextFloat() * 0.25F, true, false, random)
                .fades(40, 60).lifetime(duration - 60).wobble(0.01F, 0.02F));
    }

    private static void reversed(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        AudioCorruptionManager.play(NullSounds.REVERSED.get(), AudioCorruptionManager.Channel.AMBIENT, null,
                0.4F + 0.4F * effect.intensity(), 0.9F + random.nextFloat() * 0.15F);
    }

    /** An explosion somewhere in the distance, with nothing to show for it. */
    private static void explosion(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        Vec3 pos = AudioPositions.around(random, 24.0, 48.0).add(0.0, -6.0 + random.nextDouble() * 10.0, 0.0);
        float volume = 0.8F + 0.2F * effect.intensity();
        if (random.nextFloat() < 0.55F) {
            AudioCorruptionManager.playAt(SoundEvents.GENERIC_EXPLODE.value(), AudioCorruptionManager.Channel.EVENT, pos, volume,
                    0.55F + random.nextFloat() * 0.25F, 140.0F);
        } else {
            AudioCorruptionManager.playAt(NullSounds.EXPLOSION_DISTANT.get(), AudioCorruptionManager.Channel.EVENT, pos, volume,
                    0.9F + random.nextFloat() * 0.2F, 140.0F);
        }
    }

    /** Every sound stops at once (silence() honours the sudden-silence and horror-sound settings). */
    private static void silence(ClientEffectPayload effect) {
        AudioCorruptionManager.silence(Mth.clamp(effect.duration(), 30, 160));
    }

    private static void heartbeat(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        if (!AudioCorruptionManager.canStart(AudioCorruptionManager.Channel.AMBIENT)) {
            return;
        }
        int duration = Mth.clamp(effect.duration(), 80, 400);
        AudioCorruptionManager.start(new NullSoundInstance(NullSounds.HEARTBEAT.get(), AudioCorruptionManager.Channel.AMBIENT,
                NullSoundInstance.Placement.GLOBAL, 0.0, 0.0, 0.0, 0.35F + 0.4F * effect.intensity(),
                0.95F + 0.12F * effect.intensity() + random.nextFloat() * 0.03F, true, false, random)
                .fades(20, 40).lifetime(duration - 40));
    }

    private static void corruptedMusic(ClientEffectPayload effect) {
        NullMusic.playCorrupted(RandomSource.create(effect.seed()));
    }

    private static void whisperBehind(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        SoundEvent sound = ClientCorruptionState.stage().number() >= 5 && random.nextBoolean()
                ? NullSounds.FAKE_PLAYER_WHISPER.get() : NullSounds.WHISPER.get();
        AudioCorruptionManager.playBehind(sound, AudioCorruptionManager.Channel.WHISPER, 1.2F + random.nextFloat() * 0.8F,
                0.45F + 0.4F * effect.intensity(), 0.9F + random.nextFloat() * 0.15F);
    }

    private static void footstepsBehind(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        PhantomWalkers.behind(random, effect.intensity(), ClientCorruptionState.stage().number() >= 5);
    }

    private static void voices(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        AudioCorruptionManager.playAt(NullSounds.VOICES_DISTANT.get(), AudioCorruptionManager.Channel.WHISPER,
                AudioPositions.around(random, 12.0, 26.0), 0.5F + 0.4F * effect.intensity(), 0.85F + random.nextFloat() * 0.25F, 60.0F);
    }

    private static void breath(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        AudioCorruptionManager.playBehind(NullSounds.BREATH_BEHIND.get(), AudioCorruptionManager.Channel.CREATURE,
                0.6F + random.nextFloat() * 0.3F, 0.5F + 0.4F * effect.intensity(), 0.95F + random.nextFloat() * 0.1F);
    }

    /** Knocking: on a door near the player if the server found one, otherwise somewhere close. */
    private static void knock(ClientEffectPayload effect) {
        RandomSource random = RandomSource.create(effect.seed());
        Vec3 pos = effect.pos().map(Vec3::atCenterOf).orElseGet(() -> AudioPositions.around(random, 4.0, 9.0));
        float volume = 0.6F + 0.4F * effect.intensity();
        AudioCorruptionManager.playAt(NullSounds.KNOCK.get(), AudioCorruptionManager.Channel.EVENT, pos, volume, 1.0F, 32.0F);
        if (random.nextFloat() < 0.5F) {
            // ...and again, a little later. Slower.
            AudioCorruptionManager.schedule(40 + random.nextInt(40), () -> AudioCorruptionManager.playAt(NullSounds.KNOCK.get(),
                    AudioCorruptionManager.Channel.EVENT, pos, volume * 0.85F, 0.9F, 32.0F));
        }
    }

    private static void pitchChaos(ClientEffectPayload effect) {
        PitchCorruption.startChaos(Mth.clamp(effect.duration(), 60, 400), effect.intensity());
        AudioCorruptionManager.playGlobal(NullSounds.GLITCH.get(), AudioCorruptionManager.Channel.STATIC, 0.35F, 0.7F);
    }
}
