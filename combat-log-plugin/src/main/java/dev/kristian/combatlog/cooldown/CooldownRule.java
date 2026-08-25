package dev.kristian.combatlog.cooldown;

import org.bukkit.Material;

/**
 * One entry from {@code cooldowns.items}.
 *
 * @param material         the item this rule governs
 * @param millis           how long the cooldown lasts
 * @param onlyInCombat     the rule is dormant while the player is not tagged
 * @param onlyWhileGliding only meaningful for rockets - ignores ground use
 */
public record CooldownRule(Material material, long millis, boolean onlyInCombat, boolean onlyWhileGliding) {
}
