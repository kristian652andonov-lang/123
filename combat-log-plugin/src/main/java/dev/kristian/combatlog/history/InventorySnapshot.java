package dev.kristian.combatlog.history;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * A frozen copy of what somebody was carrying.
 *
 * <p>Stored as base64 so a whole inventory sits on one line of history.yml and
 * survives a restart. {@link BukkitObjectOutputStream} is used rather than a
 * hand written format so enchantments, custom names, NBT and anything else a
 * server owner has on their items all come back intact.
 *
 * @param storage the 36 main inventory slots, hotbar first, nulls for gaps
 * @param armor   boots, leggings, chestplate, helmet - Bukkit's own order
 * @param offHand what was in the off hand, may be null
 */
public record InventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offHand) {

    /** @return the encoded snapshot, or null when it could not be written */
    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes);
            out.writeInt(storage.length);
            for (ItemStack item : storage) {
                out.writeObject(item);
            }
            out.writeInt(armor.length);
            for (ItemStack item : armor) {
                out.writeObject(item);
            }
            out.writeObject(offHand);
            out.close();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception exception) {
            return null;
        }
    }

    /** @return the decoded snapshot, or null when the data is missing or corrupt */
    public static InventorySnapshot decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw));

            ItemStack[] storage = new ItemStack[in.readInt()];
            for (int i = 0; i < storage.length; i++) {
                storage[i] = (ItemStack) in.readObject();
            }
            ItemStack[] armor = new ItemStack[in.readInt()];
            for (int i = 0; i < armor.length; i++) {
                armor[i] = (ItemStack) in.readObject();
            }
            ItemStack offHand = (ItemStack) in.readObject();

            in.close();
            return new InventorySnapshot(storage, armor, offHand);
        } catch (Exception exception) {
            return null;
        }
    }

    /** Pads or trims an array so it can be handed straight to a live inventory. */
    public static ItemStack[] fit(ItemStack[] source, int length) {
        ItemStack[] out = new ItemStack[length];
        System.arraycopy(source, 0, out, 0, Math.min(source.length, length));
        return out;
    }

    /** Every item in the snapshot, gaps removed. */
    public java.util.List<ItemStack> allItems() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        for (ItemStack item : storage) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        for (ItemStack item : armor) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        if (offHand != null && !offHand.getType().isAir()) {
            items.add(offHand);
        }
        return items;
    }
}
