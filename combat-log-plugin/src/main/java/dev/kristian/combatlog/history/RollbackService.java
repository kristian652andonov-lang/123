package dev.kristian.combatlog.history;

import dev.kristian.combatlog.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Puts a recorded inventory back where it came from. */
public final class RollbackService {

    public enum Outcome {
        RESTORED,
        GIVEN,
        DROPPED,
        /** The entry is too old to still carry its item data. */
        NO_SNAPSHOT,
        /** The owner has to be online to have their inventory replaced. */
        TARGET_OFFLINE,
        /** The world the entry points at no longer exists. */
        WORLD_MISSING
    }

    private final Settings settings;
    private final HistoryManager history;

    public RollbackService(Settings settings, HistoryManager history) {
        this.settings = settings;
        this.history = history;
    }

    /**
     * Replaces the owner's inventory with the recorded one.
     *
     * <p>Their current inventory is snapshotted first, so the rollback itself
     * shows up in the log and can be undone the same way.
     */
    public Outcome restoreToOwner(CombatEvent event, String actorName) {
        InventorySnapshot snapshot = InventorySnapshot.decode(event.snapshot());
        if (snapshot == null) {
            return Outcome.NO_SNAPSHOT;
        }
        Player target = Bukkit.getPlayer(event.subjectId());
        if (target == null || !target.isOnline()) {
            return Outcome.TARGET_OFFLINE;
        }

        if (settings.gui.snapshotBeforeRollback) {
            history.recordRollback(target, actorName);
        }

        PlayerInventory inventory = target.getInventory();
        inventory.setStorageContents(
                InventorySnapshot.fit(snapshot.storage(), inventory.getStorageContents().length));
        inventory.setArmorContents(InventorySnapshot.fit(snapshot.armor(), 4));
        inventory.setItemInOffHand(snapshot.offHand());
        target.updateInventory();
        return Outcome.RESTORED;
    }

    /** Hands the recorded items to whoever asked, dropping whatever will not fit. */
    public Outcome giveTo(CombatEvent event, Player receiver) {
        InventorySnapshot snapshot = InventorySnapshot.decode(event.snapshot());
        if (snapshot == null) {
            return Outcome.NO_SNAPSHOT;
        }
        List<ItemStack> items = snapshot.allItems();
        Map<Integer, ItemStack> leftovers = new HashMap<>();
        for (ItemStack item : items) {
            leftovers.putAll(receiver.getInventory().addItem(item.clone()));
        }
        World world = receiver.getWorld();
        for (ItemStack leftover : leftovers.values()) {
            world.dropItemNaturally(receiver.getLocation(), leftover);
        }
        return Outcome.GIVEN;
    }

    /** Spills the recorded items back at the spot the entry was made. */
    public Outcome dropAtSite(CombatEvent event) {
        InventorySnapshot snapshot = InventorySnapshot.decode(event.snapshot());
        if (snapshot == null) {
            return Outcome.NO_SNAPSHOT;
        }
        Location location = CombatEvent.location(event);
        if (location == null) {
            return Outcome.WORLD_MISSING;
        }
        for (ItemStack item : snapshot.allItems()) {
            location.getWorld().dropItemNaturally(location, item.clone());
        }
        return Outcome.DROPPED;
    }
}
