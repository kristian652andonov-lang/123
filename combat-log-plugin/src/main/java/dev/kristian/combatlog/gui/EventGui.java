package dev.kristian.combatlog.gui;

import dev.kristian.combatlog.history.CombatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/** One entry, blown up: both players, what happened, and where. */
public final class EventGui extends CombatGui {

    private static final int SLOT_ACTOR = 10;
    private static final int SLOT_DETAILS = 13;
    private static final int SLOT_SUBJECT = 16;
    private static final int SLOT_BACK = 19;
    private static final int SLOT_INVENTORY = 22;
    private static final int SLOT_TELEPORT = 25;

    private final CombatEvent event;
    private final CombatGui back;

    public EventGui(GuiContext context, CombatEvent event, CombatGui back) {
        super(context, 3, context.text().render("<gradient:#5FD3FF:#FF9D3D>entry</gradient> <dark_gray>#</dark_gray>%id%",
                "id", Long.toString(event.id())));
        this.event = event;
        this.back = back;
    }

    @Override
    protected void redraw() {
        clear();

        if (event.hasActor()) {
            set(SLOT_ACTOR, GuiItems.head(event.actorId(), context.settings().gui.usePlayerHeads, Material.IRON_SWORD,
                    render("<accent><bold>%player%</bold></accent>", "player", event.actorName()),
                    List.of(render("<muted>%role%</muted>", "role", actorRole()))));
        } else {
            set(SLOT_ACTOR, GuiItems.item(Material.GRAY_DYE,
                    render("<muted>no other player</muted>"),
                    List.of(render("<dark_gray>%cause%</dark_gray>", "cause", event.cause()))));
        }

        List<Component> details = new ArrayList<>();
        details.add(render("<muted>when</muted> <dark_gray>›</dark_gray> <primary>%when%</primary>",
                "when", GuiItems.ago(event.timestamp())));
        details.add(render("<muted>world</muted> <dark_gray>›</dark_gray> <primary>%world%</primary>",
                "world", event.worldName()));
        details.add(render("<muted>at</muted> <dark_gray>›</dark_gray> <primary>%coords%</primary>",
                "coords", event.coordinates()));
        if (!event.cause().isEmpty()) {
            details.add(render("<muted>cause</muted> <dark_gray>›</dark_gray> <primary>%cause%</primary>",
                    "cause", event.cause()));
        }
        if (!event.weapon().isEmpty()) {
            details.add(render("<muted>held</muted> <dark_gray>›</dark_gray> <primary>%weapon%</primary>",
                    "weapon", event.weapon()));
        }
        set(SLOT_DETAILS, GuiItems.item(context.settings().gui.icon(event.type()),
                render("<primary><bold>%type%</bold></primary>", "type", typeName()), details));

        set(SLOT_SUBJECT, GuiItems.head(event.subjectId(), context.settings().gui.usePlayerHeads, Material.PLAYER_HEAD,
                render("<primary><bold>%player%</bold></primary>", "player", event.subjectName()),
                List.of(render("<muted>%role%</muted>", "role", subjectRole()))));

        set(SLOT_BACK, GuiItems.item(Material.ARROW, render("<muted>back to the log</muted>"), List.of()));

        if (event.hasSnapshot()) {
            set(SLOT_INVENTORY, GuiItems.item(Material.CHEST,
                    render("<accent><bold>open their inventory</bold></accent>"),
                    List.of(
                            render("<muted>everything %player% was carrying</muted>", "player", event.subjectName()),
                            Component.empty(),
                            render("<muted>you can hand it back from there</muted>"))));
        } else {
            set(SLOT_INVENTORY, GuiItems.item(Material.BARRIER,
                    render("<dark_gray>no inventory kept</dark_gray>"),
                    List.of(render("<muted>this entry is older than</muted>"),
                            render("<muted>history.max-snapshots</muted>"))));
        }

        set(SLOT_TELEPORT, GuiItems.item(Material.ENDER_PEARL,
                render("<primary>teleport to the spot</primary>"),
                List.of(render("<muted>%world% %coords%</muted>",
                        "world", event.worldName(), "coords", event.coordinates()))));

        fillEmpty();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SLOT_BACK -> back.openLater(player);
            case SLOT_INVENTORY -> {
                if (event.hasSnapshot()) {
                    new SnapshotGui(context, event, this).openLater(player);
                }
            }
            case SLOT_TELEPORT -> teleport(player);
            default -> {
                // decoration
            }
        }
    }

    private void teleport(Player player) {
        if (!player.hasPermission("combatlog.admin")) {
            context.text().send(player, context.settings().messages.noPermission);
            return;
        }
        Location location = CombatEvent.location(event);
        if (location == null) {
            context.text().send(player, context.settings().messages.rollbackWorldMissing);
            return;
        }
        player.closeInventory();
        player.teleport(location);
        context.text().send(player, context.settings().messages.teleported,
                "world", event.worldName(), "coords", event.coordinates());
    }

    private String typeName() {
        return switch (event.type()) {
            case FIGHT -> "fight";
            case KILL -> "kill";
            case DEATH -> "death";
            case COMBAT_LOG -> "combat log";
            case ROLLBACK -> "rollback";
        };
    }

    private String actorRole() {
        return switch (event.type()) {
            case FIGHT -> "started the fight";
            case KILL -> "the killer";
            case COMBAT_LOG -> "was fighting them";
            case ROLLBACK -> "ran the rollback";
            case DEATH -> "";
        };
    }

    private String subjectRole() {
        return switch (event.type()) {
            case FIGHT -> "was hit";
            case KILL, DEATH -> "died here";
            case COMBAT_LOG -> "logged out in combat";
            case ROLLBACK -> "inventory was replaced";
        };
    }
}
