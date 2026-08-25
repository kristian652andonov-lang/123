package dev.kristian.combatlog.gui;

import dev.kristian.combatlog.history.CombatEvent;
import dev.kristian.combatlog.history.InventorySnapshot;
import dev.kristian.combatlog.history.RollbackService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A recorded inventory, laid out the way the player saw it, with the rollback
 * buttons along the bottom.
 */
public final class SnapshotGui extends CombatGui {

    /** Bukkit stores armour boots-first; the screen shows it helmet-first. */
    private static final int SLOT_HELMET = 36;
    private static final int SLOT_OFF_HAND = 41;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_RESTORE = 47;
    private static final int SLOT_GIVE = 49;
    private static final int SLOT_DROP = 51;
    private static final int SLOT_CLOSE = 53;

    private final CombatEvent event;
    private final CombatGui back;
    private final InventorySnapshot snapshot;

    public SnapshotGui(GuiContext context, CombatEvent event, CombatGui back) {
        super(context, 6, context.text().render("<primary>%player%</primary> <dark_gray>·</dark_gray> <muted>%when%</muted>",
                "player", event.subjectName(), "when", GuiItems.ago(event.timestamp())));
        this.event = event;
        this.back = back;
        this.snapshot = InventorySnapshot.decode(event.snapshot());
    }

    @Override
    protected void redraw() {
        clear();

        if (snapshot != null) {
            ItemStack[] storage = snapshot.storage();
            // Main inventory first (storage 9-35), then the hotbar (storage 0-8).
            for (int i = 9; i < Math.min(36, storage.length); i++) {
                set(i - 9, storage[i]);
            }
            for (int i = 0; i < Math.min(9, storage.length); i++) {
                set(27 + i, storage[i]);
            }

            ItemStack[] armor = snapshot.armor();
            for (int i = 0; i < Math.min(4, armor.length); i++) {
                set(SLOT_HELMET + (3 - i), armor[i]);
            }
            set(SLOT_OFF_HAND, snapshot.offHand());
        }

        set(SLOT_BACK, GuiItems.item(Material.ARROW, render("<muted>back</muted>"), List.of()));

        set(SLOT_RESTORE, GuiItems.item(Material.CLOCK,
                render("<accent><bold>give it back to %player%</bold></accent>", "player", event.subjectName()),
                List.of(
                        render("<muted>replaces whatever they are</muted>"),
                        render("<muted>carrying right now</muted>"),
                        Component.empty(),
                        render("<danger>they must be online</danger>"),
                        render("<muted>their current inventory is saved first</muted>"))));

        set(SLOT_GIVE, GuiItems.item(Material.HOPPER,
                render("<primary>put it in my inventory</primary>"),
                List.of(render("<muted>anything that will not fit is dropped</muted>"))));

        set(SLOT_DROP, GuiItems.item(Material.DROPPER,
                render("<primary>drop it where it happened</primary>"),
                List.of(render("<muted>%world% %coords%</muted>",
                        "world", event.worldName(), "coords", event.coordinates()))));

        set(SLOT_CLOSE, GuiItems.item(Material.BARRIER, render("<danger>close</danger>"), List.of()));

        for (int slot = SLOT_HELMET; slot < 45; slot++) {
            if (getInventory().getItem(slot) == null) {
                set(slot, GuiItems.filler());
            }
        }
        for (int slot = 45; slot < 54; slot++) {
            if (getInventory().getItem(slot) == null) {
                set(slot, GuiItems.filler());
            }
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_BACK -> back.openLater(player);
            case SLOT_CLOSE -> player.closeInventory();
            case SLOT_RESTORE -> guard(player, () -> {
                if (context.settings().gui.confirmRollback) {
                    confirmRestore(player);
                } else {
                    report(player, context.rollback().restoreToOwner(event, player.getName()));
                }
            });
            case SLOT_GIVE -> guard(player, () -> report(player, context.rollback().giveTo(event, player)));
            case SLOT_DROP -> guard(player, () -> report(player, context.rollback().dropAtSite(event)));
            default -> {
                // the items themselves are display only
            }
        }
    }

    /** Handing somebody a whole inventory back is not something to misclick into. */
    private void confirmRestore(Player player) {
        new ConfirmGui(context,
                render("<danger>replace %player%'s inventory?</danger>", "player", event.subjectName()),
                List.of(
                        render("<muted>everything they are carrying now is</muted>"),
                        render("<muted>saved to the log first, so this can</muted>"),
                        render("<muted>be undone from there</muted>")),
                confirmer -> report(confirmer, context.rollback().restoreToOwner(event, confirmer.getName())),
                this)
                .openLater(player);
    }

    private void guard(Player player, Runnable action) {
        if (!player.hasPermission("combatlog.rollback")) {
            context.text().send(player, context.settings().messages.noPermission);
            return;
        }
        action.run();
    }

    private void report(Player player, RollbackService.Outcome outcome) {
        String message = switch (outcome) {
            case RESTORED -> context.settings().messages.rollbackRestored;
            case GIVEN -> context.settings().messages.rollbackGiven;
            case DROPPED -> context.settings().messages.rollbackDropped;
            case NO_SNAPSHOT -> context.settings().messages.rollbackNoSnapshot;
            case TARGET_OFFLINE -> context.settings().messages.rollbackTargetOffline;
            case WORLD_MISSING -> context.settings().messages.rollbackWorldMissing;
        };
        context.text().send(player, message,
                "player", event.subjectName(),
                "world", event.worldName(),
                "coords", event.coordinates());
    }
}
