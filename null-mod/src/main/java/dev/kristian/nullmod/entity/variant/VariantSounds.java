package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.network.NullNetworking;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/**
 * The wrong sounds. A mob's own sounds (ambient, hurt, death) are played at exactly its position,
 * so the server's sound hook can tell when a variant mob makes one: a corrupted mob now and then
 * makes another creature's sound instead, or its own pitched wrong; a parasite host's voice comes
 * out strained and low. Footsteps and splashes are left alone. NULL's own creature sounds (a
 * lunge, an infestation) go to each nearby player through their audio settings instead.
 */
final class VariantSounds {
    /** Voices a corrupted mob may borrow. */
    private static final List<SoundEvent> WRONG = List.of(
            SoundEvents.COW_AMBIENT, SoundEvents.PIG_AMBIENT, SoundEvents.SHEEP_AMBIENT, SoundEvents.CHICKEN_AMBIENT,
            SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT, SoundEvents.ENDERMAN_AMBIENT,
            SoundEvents.WOLF_GROWL, SoundEvents.CAT_HISS, SoundEvents.HORSE_AMBIENT, SoundEvents.DROWNED_AMBIENT,
            SoundEvents.HUSK_AMBIENT, SoundEvents.WITCH_AMBIENT, SoundEvents.VILLAGER_AMBIENT, SoundEvents.ZOMBIE_VILLAGER_AMBIENT,
            SoundEvents.PLAYER_BREATH, SoundEvents.PLAYER_HURT, SoundEvents.GOAT_SCREAMING_AMBIENT, SoundEvents.FOX_SCREECH,
            SoundEvents.PHANTOM_AMBIENT, SoundEvents.STRAY_AMBIENT, SoundEvents.WARDEN_HEARTBEAT, SoundEvents.PARROT_IMITATE_ZOMBIE);
    /** Chance that one of a variant's own sounds comes out wrong (when its quiet spell is over). */
    private static final float ODD_CHANCE = 0.3F;
    /** Least and most ticks between two wrong sounds of the same mob. */
    private static final int ODD_GAP_MIN = 20 * 8;
    private static final int ODD_GAP_SPREAD = 20 * 22;

    private static final RandomSource RANDOM = RandomSource.create();

    private VariantSounds() {
    }

    /** The server's sound hook: bends the sounds of variant mobs. */
    static void onPlaySound(PlayLevelSoundEvent.AtPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        if (sound == null) {
            return;
        }
        Mob mob = MobVariantManager.INSTANCE.variantAt(level, event.getPosition());
        if (mob == null) {
            return;
        }
        VariantData data = MobVariantManager.data(mob);
        if (data == null || data.variant() == MobVariant.NONE || !isVoice(sound.value().getLocation())) {
            return;
        }
        long now = level.getGameTime();
        if (now < data.nextOddSound || RANDOM.nextFloat() >= ODD_CHANCE) {
            return;
        }
        data.nextOddSound = now + ODD_GAP_MIN + RANDOM.nextInt(ODD_GAP_SPREAD);
        if (data.variant() == MobVariant.CORRUPTED) {
            if (RANDOM.nextFloat() < 0.55F) {
                SoundEvent wrong = wrongVoice(RANDOM, sound.value().getLocation());
                event.setSound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(wrong));
                event.setNewPitch(0.8F + RANDOM.nextFloat() * 0.25F);
            } else {
                // Its own voice, but wrong: far too deep or far too high.
                event.setNewPitch(RANDOM.nextBoolean() ? 0.42F + RANDOM.nextFloat() * 0.15F : 1.6F + RANDOM.nextFloat() * 0.35F);
            }
        } else {
            // Something else is using the host's throat.
            event.setNewPitch(Math.max(0.5F, event.getNewPitch() * (0.55F + 0.2F * RANDOM.nextFloat())));
            event.setNewVolume(event.getNewVolume() * 1.15F);
        }
    }

    /**
     * A mob's voice: an {@code entity.*} sound whose last part is a cry (ambient, hurt, death,
     * growl...), the naming vanilla and most mods use. Footsteps, swimming, eating, saddles and
     * the like are not voices.
     */
    static boolean isVoice(ResourceLocation id) {
        String path = id.getPath();
        if (!path.startsWith("entity.")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('.') + 1);
        for (String voice : VOICES) {
            if (last.contains(voice)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] VOICES = {"ambient", "hurt", "death", "growl", "howl", "hiss", "scream", "screech",
            "angry", "idle", "roar", "whine", "purr", "pant", "cry", "groan", "moan"};

    /** Another creature's voice, never the one it replaces. */
    static SoundEvent wrongVoice(RandomSource random, @Nullable ResourceLocation instead) {
        for (int attempt = 0; attempt < 4; attempt++) {
            SoundEvent pick = WRONG.get(random.nextInt(WRONG.size()));
            if (instead == null || !pick.getLocation().equals(instead)) {
                return pick;
            }
        }
        return SoundEvents.PLAYER_BREATH;
    }

    /**
     * Plays a sound at an entity for every player within {@code radius} through each player's own
     * audio corruption system, so their audio settings (and NULL's channel switches) apply.
     * Clients without NULL hear nothing.
     */
    static void playNear(Entity at, SoundEvent sound, float volume, float pitch, double radius) {
        if (!(at.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = at.blockPosition();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) <= radius * radius) {
                play(player, sound, pos, volume, pitch);
            }
        }
    }

    /** Plays a sound for one player through their audio corruption system (see {@link #playNear}). */
    static void play(ServerPlayer player, SoundEvent sound, BlockPos pos, float volume, float pitch) {
        NullNetworking.sendEffect(player, "audio.sound", player.getRandom().nextLong(), volume, 40,
                Optional.of(pos), Math.round(pitch * 100.0F), sound.getLocation().toString());
    }

    /** The exact spot a mob's own sounds are played at. */
    static boolean playedBy(Mob mob, Vec3 position) {
        return mob.getX() == position.x && mob.getY() == position.y && mob.getZ() == position.z;
    }
}
