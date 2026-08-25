package dev.kristian.combatlog;

import org.bukkit.plugin.java.JavaPlugin;

public final class CombatLogPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("bootstrap");
    }
}
