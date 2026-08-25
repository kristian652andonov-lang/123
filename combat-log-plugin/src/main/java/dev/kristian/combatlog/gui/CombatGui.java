package dev.kristian.combatlog.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Base for every screen in the plugin.
 *
 * <p>Each screen <em>is</em> the inventory's holder, so a click can be routed
 * back to it straight from the event with no open-menu bookkeeping to leak.
 */
public abstract class CombatGui implements InventoryHolder {

    protected final GuiContext context;
    private final Inventory inventory;

    protected CombatGui(GuiContext context, int rows, Component title) {
        this.context = context;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        redraw();
        player.openInventory(inventory);
    }

    /**
     * Opens on the next tick. Bukkit does not like an inventory being swapped
     * out from inside its own click event, so navigation always goes through here.
     */
    public void openLater(Player player) {
        Bukkit.getScheduler().runTask(context.plugin(), () -> open(player));
    }

    protected void set(int slot, org.bukkit.inventory.ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    protected void fillEmpty() {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, GuiItems.filler());
            }
        }
    }

    protected void clear() {
        inventory.clear();
    }

    protected Component render(String raw, String... placeholders) {
        Component component = context.text().render(raw, placeholders);
        return component == null ? Component.empty() : component;
    }

    protected abstract void redraw();

    public abstract void onClick(Player player, int slot, ClickType click);
}
