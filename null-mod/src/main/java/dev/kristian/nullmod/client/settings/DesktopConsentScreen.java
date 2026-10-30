package dev.kristian.nullmod.client.settings;

import dev.kristian.nullmod.client.desktop.DesktopInterferenceManager;
import dev.kristian.nullmod.config.NullClientSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The plain, honest explanation shown before desktop interference can be switched on: what
 * appears, that every window is labelled as NULL's and can always be closed, that it can be
 * turned off at any time, and that nothing on the computer is changed, collected or sent.
 * Only "Enable desktop interference" sets the consent; Cancel and Esc leave everything off.
 */
public final class DesktopConsentScreen extends Screen {
    private static final Component TITLE = Component.translatable("null.desktop.consent.title");
    private static final Component ACCEPT = Component.translatable("null.desktop.consent.accept");
    private static final int POINT_COLOUR = 0xC8C8C8;

    private final Screen parent;
    private NullActionButton cancelButton;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public DesktopConsentScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    private static Component explanation() {
        MutableComponent text = Component.empty();
        text.append(Component.translatable("null.desktop.consent.intro"));
        text.append("\n\n");
        text.append(Component.translatable("null.desktop.consent.before"));
        String[] points = {"null.desktop.consent.point.when", "null.desktop.consent.point.label",
                "null.desktop.consent.point.close", "null.desktop.consent.point.input", "null.desktop.consent.point.nothing",
                "null.desktop.consent.point.off"};
        for (String point : points) {
            text.append("\n");
            Component line = point.endsWith(".label")
                    ? Component.translatable(point, Component.translatable("null.desktop.footer"))
                    : Component.translatable(point);
            text.append(Component.empty().append(line).withColor(POINT_COLOUR));
        }
        return text;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(200, Math.min(380, width - 40));
        panelX = (width - panelWidth) / 2;
        panelY = 32;
        panelHeight = Math.max(40, height - panelY - 44);
        ConsentText text = new ConsentText(panelX + 6, panelY + 6, panelWidth - 20, panelHeight - 12, explanation(), font);
        text.setColor(NullStyle.TEXT);
        addRenderableWidget(text);

        int buttonY = height - 30;
        int acceptWidth = Math.max(100, Math.min(190, panelWidth - 110));
        int cancelWidth = Math.max(70, Math.min(100, panelWidth - acceptWidth - 8));
        int left = (width - acceptWidth - cancelWidth - 8) / 2;
        NullActionButton accept = new NullActionButton(left, buttonY, acceptWidth, 20, ACCEPT, button -> accept());
        accept.active = DesktopInterferenceManager.osSupported();
        addRenderableWidget(accept);
        cancelButton = new NullActionButton(left + acceptWidth + 8, buttonY, cancelWidth, 20, CommonComponents.GUI_CANCEL,
                button -> onClose());
        addRenderableWidget(cancelButton);
    }

    @Override
    protected void setInitialFocus() {
        // Enter or Space right away cancels; agreeing always takes a deliberate choice
        if (cancelButton != null) {
            super.setInitialFocus(cancelButton);
        } else {
            super.setInitialFocus();
        }
    }

    private void accept() {
        // the consent belongs to the account that accepted it (see NullClientSettings.desktopAllowed)
        NullClientSettings.set(NullClientSettings.DESKTOP_CONSENT_ACCOUNT, NullClientSettings.accountCode());
        NullClientSettings.set(NullClientSettings.DESKTOP_CONSENT, true);
        NullClientSettings.set(NullClientSettings.DESKTOP_INTERFERENCE, true);
        DesktopInterferenceManager.requestAvailabilityCheck();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, minecraft != null && minecraft.level != null ? NullStyle.SCREEN_IN_WORLD : NullStyle.SCREEN);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, NullStyle.LIST);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, NullStyle.LINE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, (width - font.width(title)) / 2, 13, NullStyle.TEXT, false);
    }

    /** The explanation; scrolls when the window is too small, drawn on NULL's dark panel. */
    private static final class ConsentText extends FittingMultiLineTextWidget {
        ConsentText(int x, int y, int width, int height, Component message, Font font) {
            super(x, y, width, height, message, font);
        }

        @Override
        protected void renderBorder(GuiGraphics graphics, int x, int y, int width, int height) {
            // the screen already draws the panel; only show keyboard focus here
            if (isFocused()) {
                graphics.renderOutline(x, y, width, height, NullStyle.LINE);
            }
        }
    }
}
