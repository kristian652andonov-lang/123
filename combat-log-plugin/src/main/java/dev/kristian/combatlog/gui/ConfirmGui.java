package dev.kristian.combatlog.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.function.Consumer;

/** A yes/no gate in front of anything destructive. */
public final class ConfirmGui extends CombatGui {

    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_QUESTION = 13;
    private static final int SLOT_CANCEL = 15;

    private final Component question;
    private final List<Component> explanation;
    private final Consumer<Player> onConfirm;
    private final CombatGui back;

    public ConfirmGui(GuiContext context, Component question, List<Component> explanation,
                      Consumer<Player> onConfirm, CombatGui back) {
        super(context, 3, question);
        this.question = question;
        this.explanation = explanation;
        this.onConfirm = onConfirm;
        this.back = back;
    }

    @Override
    protected void redraw() {
        clear();
        set(SLOT_CONFIRM, GuiItems.item(Material.LIME_CONCRETE,
                render("<success><bold>yes, do it</bold></success>"), List.of()));
        set(SLOT_QUESTION, GuiItems.item(Material.PAPER, question, explanation));
        set(SLOT_CANCEL, GuiItems.item(Material.RED_CONCRETE,
                render("<danger><bold>cancel</bold></danger>"), List.of()));
        fillEmpty();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == SLOT_CONFIRM) {
            onConfirm.accept(player);
            back.openLater(player);
        } else if (slot == SLOT_CANCEL) {
            back.openLater(player);
        }
    }
}
