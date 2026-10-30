package dev.kristian.nullmod.client.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A plain vanilla {@link Button} drawn in NULL's style (dark body, thin white outline). */
final class NullActionButton extends Button {
    NullActionButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hot = active && isHoveredOrFocused();
        NullStyle.frame(graphics, getX(), getY(), getWidth(), getHeight(), active, hot);
        renderScrollingString(graphics, Minecraft.getInstance().font, 4, NullStyle.text(active, hot));
    }
}
