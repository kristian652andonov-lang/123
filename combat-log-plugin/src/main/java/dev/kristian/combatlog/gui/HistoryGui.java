package dev.kristian.combatlog.gui;

import dev.kristian.combatlog.history.CombatEvent;
import dev.kristian.combatlog.history.EventType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The main log: every fight, kill, death and logout, newest first. */
public final class HistoryGui extends CombatGui {

    private static final int ENTRIES_PER_PAGE = 45;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_FILTER = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_SCOPE = 50;
    private static final int SLOT_NEXT = 53;

    /** null means "everything"; the rest cycle through in this order. */
    private static final EventType[] FILTERS = {
            null, EventType.FIGHT, EventType.KILL, EventType.DEATH,
            EventType.COMBAT_LOG, EventType.ROLLBACK
    };

    private final UUID playerFilter;
    private final String playerFilterName;

    private int filterIndex;
    private int page;
    private List<CombatEvent> visible = List.of();

    public HistoryGui(GuiContext context, UUID playerFilter, String playerFilterName) {
        super(context, 6, title(context));
        this.playerFilter = playerFilter;
        this.playerFilterName = playerFilterName;
    }

    private static Component title(GuiContext context) {
        Component rendered = context.text().render(context.settings().gui.title);
        return rendered == null ? Component.text("combat log") : rendered;
    }

    @Override
    protected void redraw() {
        clear();
        visible = context.history().filtered(FILTERS[filterIndex], playerFilter);

        int pages = Math.max(1, (visible.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * ENTRIES_PER_PAGE;
        for (int slot = 0; slot < ENTRIES_PER_PAGE && start + slot < visible.size(); slot++) {
            set(slot, entryIcon(visible.get(start + slot)));
        }

        if (page > 0) {
            set(SLOT_PREVIOUS, GuiItems.item(Material.ARROW,
                    render("<primary>previous page</primary>"),
                    List.of(render("<muted>page %page%</muted>", "page", Integer.toString(page)))));
        }
        if (page + 1 < pages) {
            set(SLOT_NEXT, GuiItems.item(Material.ARROW,
                    render("<primary>next page</primary>"),
                    List.of(render("<muted>page %page%</muted>", "page", Integer.toString(page + 2)))));
        }

        set(SLOT_FILTER, GuiItems.item(Material.HOPPER,
                render("<accent>showing</accent> <primary>%filter%</primary>", "filter", filterName()),
                List.of(
                        render("<muted>left click for the next filter</muted>"),
                        render("<muted>right click for the previous one</muted>"))));

        List<Component> info = new ArrayList<>();
        info.add(render("<muted>entries</muted> <dark_gray>›</dark_gray> <accent>%count%</accent>",
                "count", Integer.toString(visible.size())));
        info.add(render("<muted>page</muted> <dark_gray>›</dark_gray> <accent>%page%</accent><muted>/</muted><accent>%pages%</accent>",
                "page", Integer.toString(page + 1), "pages", Integer.toString(pages)));
        if (visible.isEmpty()) {
            info.add(Component.empty());
            info.add(render("<muted>nothing recorded yet</muted>"));
        }
        set(SLOT_INFO, GuiItems.item(Material.BOOK,
                render("<gradient:#5FD3FF:#FF9D3D><bold>combat log</bold></gradient>"), info));

        if (playerFilter != null) {
            set(SLOT_SCOPE, GuiItems.head(playerFilter, context.settings().gui.usePlayerHeads, Material.NAME_TAG,
                    render("<accent>only</accent> <primary>%player%</primary>", "player", playerFilterName),
                    List.of(render("<muted>click to show everyone</muted>"))));
        }

        for (int slot = ENTRIES_PER_PAGE; slot < 54; slot++) {
            if (getInventory().getItem(slot) == null) {
                set(slot, GuiItems.filler());
            }
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot < ENTRIES_PER_PAGE) {
            int index = page * ENTRIES_PER_PAGE + slot;
            if (index < visible.size()) {
                new EventGui(context, visible.get(index), this).openLater(player);
            }
            return;
        }

        switch (slot) {
            case SLOT_PREVIOUS -> {
                if (page > 0) {
                    page--;
                    redraw();
                }
            }
            case SLOT_NEXT -> {
                page++;
                redraw();
            }
            case SLOT_FILTER -> {
                filterIndex = Math.floorMod(filterIndex + (click.isRightClick() ? -1 : 1), FILTERS.length);
                page = 0;
                redraw();
            }
            case SLOT_SCOPE -> {
                if (playerFilter != null) {
                    new HistoryGui(context, null, null).openLater(player);
                }
            }
            default -> {
                // filler
            }
        }
    }

    private String filterName() {
        EventType type = FILTERS[filterIndex];
        if (type == null) {
            return "everything";
        }
        return switch (type) {
            case FIGHT -> "fights";
            case KILL -> "kills";
            case DEATH -> "deaths";
            case COMBAT_LOG -> "combat logs";
            case ROLLBACK -> "rollbacks";
        };
    }

    private ItemStack entryIcon(CombatEvent event) {
        String title = switch (event.type()) {
            case FIGHT -> "<accent>%actor%</accent> <muted>fought</muted> <primary>%subject%</primary>";
            case KILL -> "<accent>%actor%</accent> <danger>killed</danger> <primary>%subject%</primary>";
            case DEATH -> "<primary>%subject%</primary> <muted>died</muted>";
            case COMBAT_LOG -> "<primary>%subject%</primary> <danger>combat logged</danger>";
            case ROLLBACK -> "<primary>%subject%</primary> <muted>inventory replaced</muted>";
        };

        List<Component> lore = new ArrayList<>();
        lore.add(render("<muted>%when%</muted> <dark_gray>·</dark_gray> <muted>%world%</muted> <dark_gray>%coords%</dark_gray>",
                "when", GuiItems.ago(event.timestamp()),
                "world", event.worldName(),
                "coords", event.coordinates()));
        if (!event.cause().isEmpty()) {
            lore.add(render("<muted>cause</muted> <dark_gray>›</dark_gray> <primary>%cause%</primary>",
                    "cause", event.cause()));
        }
        if (!event.weapon().isEmpty()) {
            lore.add(render("<muted>held</muted> <dark_gray>›</dark_gray> <primary>%weapon%</primary>",
                    "weapon", event.weapon()));
        }
        lore.add(Component.empty());
        lore.add(event.hasSnapshot()
                ? render("<accent>›</accent> <muted>click to open their inventory</muted>")
                : render("<dark_gray>› too old to still have its items</dark_gray>"));

        return GuiItems.head(event.subjectId(), context.settings().gui.usePlayerHeads,
                context.settings().gui.icon(event.type()),
                render(title, "actor", event.actorName(), "subject", event.subjectName()),
                lore);
    }
}
