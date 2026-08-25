package dev.kristian.combatlog.config;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * A configured sound effect. The sound is addressed by its vanilla key so any
 * value that works in {@code /playsound} works here, with no enum to keep in
 * step across Minecraft versions.
 */
public record SoundSpec(boolean enabled, String key, float volume, float pitch) {

    public static SoundSpec from(ConfigurationSection section, String fallbackKey) {
        if (section == null) {
            return new SoundSpec(false, fallbackKey, 1.0F, 1.0F);
        }
        return new SoundSpec(
                section.getBoolean("enabled", true),
                section.getString("sound", fallbackKey),
                (float) section.getDouble("volume", 1.0D),
                (float) section.getDouble("pitch", 1.0D));
    }

    /** Plays the sound for one player only, at their own position. */
    public void play(Player player) {
        play(player, player.getLocation());
    }

    public void play(Player player, Location at) {
        if (!enabled || key == null || key.isBlank()) {
            return;
        }
        player.playSound(at, key, SoundCategory.MASTER, volume, pitch);
    }
}
