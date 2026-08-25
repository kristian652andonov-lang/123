package dev.kristian.combatlog.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Builders for the icons the screens are made of. */
public final class GuiItems {

    private GuiItems() {
    }

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        apply(stack, name, lore);
        return stack;
    }

    /** A player head, falling back to a plain icon when heads are switched off. */
    public static ItemStack head(UUID owner, boolean useHeads, Material fallback,
                                 Component name, List<Component> lore) {
        if (!useHeads || owner == null) {
            return item(fallback, name, lore);
        }
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            stack.setItemMeta(skull);
        }
        apply(stack, name, lore);
        return stack;
    }

    public static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static void apply(ItemStack stack, Component name, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        // Item names and lore are italic by default, which looks wrong next to
        // the rest of the plugin's text.
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
    }

    /** "just now", "4m ago", "2h ago", "3d ago". */
    public static String ago(long timestamp) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - timestamp);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed);
        if (seconds < 10L) {
            return "just now";
        }
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h ago";
        }
        return hours / 24L + "d ago";
    }
}
