package dev.kristian.combatlog.gui;

import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.history.HistoryManager;
import dev.kristian.combatlog.history.RollbackService;
import dev.kristian.combatlog.text.Text;
import org.bukkit.plugin.Plugin;

/** The handful of services every screen needs, passed around as one value. */
public record GuiContext(Plugin plugin, Settings settings, Text text,
                         HistoryManager history, RollbackService rollback) {
}
