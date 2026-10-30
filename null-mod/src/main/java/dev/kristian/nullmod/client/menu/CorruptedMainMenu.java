package dev.kristian.nullmod.client.menu;

import com.mojang.authlib.minecraft.BanDetails;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.settings.NullSettingsScreen;
import dev.kristian.nullmod.config.NullClientSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.gui.ModListScreen;
import net.neoforged.neoforge.internal.BrandingControl;

/**
 * NULL's replacement for the title screen: an almost black, grayscale, corrupted version of
 * Minecraft's main menu. It does exactly what the vanilla 1.21.1 title screen does
 * (Singleplayer, Multiplayer with the same safety screen / disabled-multiplayer handling,
 * Options, Quit Game, NeoForge's mod list, credits) and adds a link to NULL's settings.
 * <p>
 * How disturbing it looks follows the highest stage this computer has ever seen
 * ({@code ClientStageMemory}); the menu only reads that value and never advances anything.
 * Every effect follows the menu, animation and accessibility settings.
 */
public final class CorruptedMainMenu extends Screen {
    static final long PRESS_DELAY_MS = 150L;

    private static final Component TITLE = Component.translatable("narrator.screen.title");
    private static final Component COPYRIGHT = Component.translatable("title.credits");
    private static final Component MODS = Component.translatable("fml.menu.mods");
    private static final Component NULL_LINK = Component.literal("NULL");
    private static final Component NULL_LINK_TOOLTIP = Component.translatable("null.menu.settings");
    private static final int LINK_RGB = 0x707070;
    private static final int FOOTER_RGB = 0x5C5C5C;

    private static boolean firstOpenDone;

    private final Random random = new Random();
    private final SpriteBatch batch = new SpriteBatch();
    private final MainMenuAnimationManager animation = new MainMenuAnimationManager(random);
    private final MainMenuBackgroundRenderer background = new MainMenuBackgroundRenderer();
    private final CorruptedLogoRenderer logo = new CorruptedLogoRenderer();
    private final MenuAudio audio = new MenuAudio();
    private final MainMenuEventManager events = new MainMenuEventManager(this, random);
    private final List<NullMenuButton> mainButtons = new ArrayList<>(4);
    private final List<NullMenuButton> mainButtonsView = Collections.unmodifiableList(mainButtons);
    private final List<String> brandingLines = new ArrayList<>();
    private final List<String> statusLines = new ArrayList<>();

    private MenuArt art = MenuArt.EMPTY;
    private MenuMood mood;
    private boolean shown;
    private boolean fresh = true;
    private boolean broken;
    private boolean relayout;
    private long shownAtMs;
    private long lastFrameMs;
    private long fadeMs = 450L;
    private float time;
    private int footerLines;
    private Runnable pendingAction;
    private long pendingAtMs;
    private float buttonsLeft;
    private float buttonsTop;
    private float buttonsRight;
    private float buttonsBottom;

    public CorruptedMainMenu() {
        super(TITLE);
    }

    /** True when the corrupted menu should be shown instead of the vanilla title screen. */
    public static boolean enabled() {
        return NullClientSettings.get(NullClientSettings.CUSTOM_MAIN_MENU) && !Minecraft.getInstance().isDemo();
    }

    // ---------------------------------------------------------------------------------------
    // life cycle

    @Override
    protected void init() {
        if (!shown) {
            shown = true;
            onShown();
        }
        if (!broken) {
            art = MenuArt.get();
            animation.setArt(art);
        }
        animation.resize(width, height);
        buildLayout();
    }

    private void onShown() {
        MenuClientModule.cancelRelease();
        mood = MenuMood.read();
        art = MenuArt.get();
        broken = false;
        relayout = false;
        shownAtMs = Util.getMillis();
        lastFrameMs = 0L;
        fadeMs = firstOpenDone ? 450L : 2400L;
        firstOpenDone = true;
        pendingAction = null;
        readBranding();
        animation.reset(mood, art);
        logo.reset();
        audio.start(mood);
        events.reset(mood, fresh);
        fresh = false;
    }

    @Override
    public void removed() {
        shown = false;
        pendingAction = null;
        audio.stop();
        // free the art soon (at once if the menu was just switched off in NULL's settings)
        MenuClientModule.scheduleRelease(enabled() ? MenuClientModule.RELEASE_GRACE_MS : 0L);
    }

    @Override
    public void tick() {
        audio.tick();
        if (relayout) {
            relayout = false;
            rebuildWidgets();
        }
        if (pendingAction != null && Util.getMillis() >= pendingAtMs) {
            Runnable action = pendingAction;
            pendingAction = null;
            if (minecraft != null && minecraft.screen == this) {
                action.run();
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (events.skipWaiting()) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (events.skipWaiting()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------------------------------
    // layout

    private void readBranding() {
        brandingLines.clear();
        statusLines.clear();
        try {
            BrandingControl.forEachLine(true, true, (index, line) -> brandingLines.add(line));
            BrandingControl.forEachAboveCopyrightLine((index, line) -> statusLines.add(line));
        } catch (RuntimeException | LinkageError e) {
            brandingLines.clear();
            statusLines.clear();
        }
        if (brandingLines.isEmpty()) {
            brandingLines.add("Minecraft " + SharedConstants.getCurrentVersion().getName());
        }
    }

    private void buildLayout() {
        mainButtons.clear();
        int w = width;
        int h = height;
        int lineHeight = font.lineHeight + 1;
        int marginX = Mth.clamp(Math.round(w * 0.065F), 6, 64);
        int marginTop = Mth.clamp(Math.round(h * 0.085F), 4, 56);
        int buttonHeight = 20;
        int gap = Mth.clamp(Math.round(h * 0.012F), 2, 5);
        int logoGap = Mth.clamp(Math.round(h * 0.06F), 6, 34);

        footerLines = brandingLines.size();
        int footer = footerLines * lineHeight + 6;
        int stack = 4 * buttonHeight + 3 * gap;
        int available = h - marginTop - logoGap - stack - footer;
        if (available < 30 && footerLines > 1) {
            footerLines = 1;
            footer = lineHeight + 6;
            available = h - marginTop - logoGap - stack - footer;
        }
        if (available < 12) {
            // a tiny window: squeeze the column so every button stays reachable
            marginTop = 3;
            logoGap = 3;
            gap = 2;
            buttonHeight = Mth.clamp((h - marginTop - footer - 3 * gap - 14) / 4, 12, 20);
            stack = 4 * buttonHeight + 3 * gap;
            available = h - marginTop - logoGap - stack - footer;
        }

        // small links in the top-right corner
        int linkY = Mth.clamp(Math.round(h * 0.02F), 3, 10);
        int linkRight = w - Mth.clamp(Math.round(w * 0.015F), 4, 14);
        NullMenuLink nullLink = new NullMenuLink(0, linkY, NULL_LINK, font, LINK_RGB, this,
                () -> minecraft.setScreen(new NullSettingsScreen(this)));
        nullLink.setX(linkRight - nullLink.getWidth());
        nullLink.setTooltip(Tooltip.create(NULL_LINK_TOOLTIP));
        NullMenuLink modsLink = new NullMenuLink(0, linkY, MODS, font, LINK_RGB, this,
                () -> minecraft.setScreen(new ModListScreen(this)));
        modsLink.setX(nullLink.getX() - 10 - modsLink.getWidth());
        int linksWidth = linkRight - modsLink.getX();

        float logoMaxWidth = Math.min(w * 0.62F, w - marginX * 2.0F - linksWidth - 8.0F);
        float logoMaxHeight = Math.max(0.0F, Math.min(available, h * 0.40F));
        logo.layout(art.glyphs, font, marginX, marginTop, logoMaxWidth, logoMaxHeight, w, h);

        int buttonsY = logo.visible() ? Math.round(logo.bottom()) + logoGap : marginTop;
        int buttonWidth = Math.min(Mth.clamp(Math.round(w * 0.24F), 90, 200), Math.max(40, w - marginX * 2));
        int step = buttonHeight + gap;

        NullMenuButton singleplayer = new NullMenuButton(marginX, buttonsY, buttonWidth, buttonHeight,
                Component.translatable("menu.singleplayer"), Component.translatable("null.menu.wrong.singleplayer"), this,
                () -> minecraft.setScreen(new SelectWorldScreen(this)));
        NullMenuButton multiplayer = new NullMenuButton(marginX, buttonsY + step, buttonWidth, buttonHeight,
                Component.translatable("menu.multiplayer"), Component.translatable("null.menu.wrong.multiplayer"), this,
                () -> minecraft.setScreen(minecraft.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(this) : new SafetyScreen(this)));
        Component disabled = multiplayerDisabledReason();
        if (disabled != null) {
            multiplayer.active = false;
            multiplayer.setTooltip(Tooltip.create(disabled));
        }
        NullMenuButton options = new NullMenuButton(marginX, buttonsY + step * 2, buttonWidth, buttonHeight,
                Component.translatable("menu.options"), Component.translatable("null.menu.wrong.options"), this,
                () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options)));
        NullMenuButton quit = new NullMenuButton(marginX, buttonsY + step * 3, buttonWidth, buttonHeight,
                Component.translatable("menu.quit"), Component.translatable("null.menu.wrong.quit"), this,
                () -> minecraft.stop());
        mainButtons.add(singleplayer);
        mainButtons.add(multiplayer);
        mainButtons.add(options);
        mainButtons.add(quit);
        for (NullMenuButton button : mainButtons) {
            addRenderableWidget(button);
        }
        addRenderableWidget(modsLink);
        addRenderableWidget(nullLink);
        int copyrightWidth = font.width(COPYRIGHT);
        addRenderableWidget(new NullMenuLink(w - copyrightWidth - 3, h - 10, COPYRIGHT, font, FOOTER_RGB, this,
                () -> minecraft.setScreen(new CreditsAndAttributionScreen(this))));

        float fw = Math.max(1, w);
        float fh = Math.max(1, h);
        buttonsLeft = marginX / fw;
        buttonsTop = buttonsY / fh;
        buttonsRight = (marginX + buttonWidth) / fw;
        buttonsBottom = (buttonsY + step * 3 + buttonHeight) / fh;
    }

    /** The same reasons, in the same order, as the vanilla title screen. */
    private Component multiplayerDisabledReason() {
        if (minecraft.allowsMultiplayer()) {
            return null;
        }
        if (minecraft.isNameBanned()) {
            return Component.translatable("title.multiplayer.disabled.banned.name");
        }
        BanDetails ban = minecraft.multiplayerBan();
        if (ban != null) {
            return ban.expires() != null
                    ? Component.translatable("title.multiplayer.disabled.banned.temporary")
                    : Component.translatable("title.multiplayer.disabled.banned.permanent");
        }
        return Component.translatable("title.multiplayer.disabled");
    }

    // ---------------------------------------------------------------------------------------
    // drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0.0F : MenuMath.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;
        time += dt;
        if (mood != null) {
            animation.update(dt);
            events.update(dt);
            logo.update(dt, mood, random);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderOverlay(graphics);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (mood == null) {
            graphics.fill(0, 0, width, height, 0xFF050505);
            return;
        }
        if (!broken) {
            try {
                float reveal = 1.0F - MenuMath.smooth((Util.getMillis() - shownAtMs) / (fadeMs * 0.8F));
                float darken = 1.0F - (1.0F - events.darken()) * (1.0F - reveal);
                background.render(graphics, batch, art, animation, mood, width, height, time, darken);
                logo.render(graphics, batch, uiAlpha(), mood);
            } catch (RuntimeException e) {
                fail(e);
            }
        }
        if (broken) {
            graphics.fill(0, 0, width, height, 0xFF050505);
            if (!logo.usesGlyphs()) {
                logo.render(graphics, batch, uiAlpha(), mood);
            }
        }
        renderFooter(graphics);
    }

    private void renderOverlay(GuiGraphics graphics) {
        if (mood == null || broken) {
            return;
        }
        try {
            background.renderOverlay(graphics, batch, art, mood, events, width, height, time);
            float waiting = events.waitingTextAlpha();
            if (waiting > 0.0F) {
                logo.renderWaiting(graphics, batch, waiting, mood);
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void renderFooter(GuiGraphics graphics) {
        int color = MenuMath.argb(FOOTER_RGB, uiAlpha() * 0.9F);
        if (!MenuMath.visible(color)) {
            return;
        }
        int lineHeight = font.lineHeight + 1;
        int lines = Math.min(footerLines, brandingLines.size());
        // bottom line first; when space is short only the last lines ("Minecraft 1.21.1") remain
        int first = brandingLines.size() - lines;
        for (int i = 0; i < lines; i++) {
            graphics.drawString(font, brandingLines.get(first + i), 2, height - (10 + i * lineHeight), color, false);
        }
        for (int i = 0; i < statusLines.size(); i++) {
            String line = statusLines.get(i);
            graphics.drawString(font, line, width - font.width(line) - 2, height - (10 + (i + 1) * lineHeight), color, false);
        }
    }

    /** Something went wrong while drawing the art: fall back to a plain dark menu for good. */
    private void fail(RuntimeException e) {
        batch.abort();
        if (!broken) {
            NullMod.LOGGER.error("NULL's corrupted menu could not draw its background; falling back to a plain menu", e);
        }
        broken = true;
        art = MenuArt.EMPTY;
        relayout = true;
    }

    // ---------------------------------------------------------------------------------------
    // shared with the widgets and the event manager

    /** Visibility of the logo, buttons and footer (fade in, opening sequence). */
    float uiAlpha() {
        float fade = MenuMath.smooth((Util.getMillis() - shownAtMs) / (float) fadeMs);
        return Math.min(fade, events.waitingUiAlpha());
    }

    float flashing() {
        return mood == null ? 0.0F : mood.flashing;
    }

    /** Runs a button action after its click glitch has had a moment to show. */
    void requestAction(Runnable action, long delayMs) {
        if (pendingAction != null) {
            return;
        }
        pendingAction = action;
        pendingAtMs = Util.getMillis() + delayMs;
    }

    void onButtonHovered() {
        audio.hover();
    }

    MenuAudio audio() {
        return audio;
    }

    MainMenuAnimationManager animation() {
        return animation;
    }

    CorruptedLogoRenderer logo() {
        return logo;
    }

    List<NullMenuButton> mainButtons() {
        return mainButtonsView;
    }

    float logoLeft() {
        return logo.left() / Math.max(1, width);
    }

    float logoTop() {
        return logo.top() / Math.max(1, height);
    }

    float logoRight() {
        return logo.right() / Math.max(1, width);
    }

    float logoBottom() {
        return logo.bottom() / Math.max(1, height);
    }

    float buttonsLeft() {
        return buttonsLeft;
    }

    float buttonsTop() {
        return buttonsTop;
    }

    float buttonsRight() {
        return buttonsRight;
    }

    float buttonsBottom() {
        return buttonsBottom;
    }
}
