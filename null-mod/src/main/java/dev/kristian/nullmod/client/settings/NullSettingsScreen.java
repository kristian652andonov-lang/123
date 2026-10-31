package dev.kristian.nullmod.client.settings;

import dev.kristian.nullmod.client.ClientStageMemory;
import dev.kristian.nullmod.client.desktop.DesktopInterferenceManager;
import dev.kristian.nullmod.client.menu.NullScreenLook;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.core.CorruptionStage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NULL's own settings screen (opened from the corrupted main menu and from the mod list's
 * config button). Dark and outlined like the rest of NULL, but plain and readable: every option
 * explains itself in a tooltip and is saved the moment it changes.
 * <p>
 * Sections: Visual, Audio, Main menu, Desktop. Desktop interference is on by default; a short
 * note under its heading says what the windows are, and switching it off closes every desktop
 * window at once. Outside a world the screen sits on NULL's dark backdrop like every other menu.
 * "All options..." opens NeoForge's configuration screen for the mod (including the world
 * settings when the player may edit them).
 */
public final class NullSettingsScreen extends Screen {
    private static final Component TITLE = Component.translatable("null.settings.screen.title");
    private static final Component ALL_OPTIONS = Component.translatable("null.settings.screen.all_options");
    private static final String PREFIX = "null.settings.";
    private static final long STATUS_MESSAGE_MS = 4_000L;
    /** NeoForge's generic screen offers every setting except NULL's internal bookkeeping value. */
    private static final ConfigurationScreen.ConfigurationSectionScreen.Filter HIDE_INTERNAL =
            (context, key, original) -> context.keylist().equals(List.of("desktop")) && "defaultsVersion".equals(key)
                    ? null : original;

    private final Screen parent;
    private final List<NullSliderButton> sliders = new ArrayList<>();
    private SettingsList list;
    private Component statusMessage;
    private int statusMessageColor;
    private long statusMessageUntilMs;
    private boolean rebuildPending;

    public NullSettingsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    // ------------------------------------------------------------------------------------------
    // layout

    @Override
    protected void init() {
        double scroll = list != null ? list.getScrollAmount() : 0.0;
        commitSliders();
        sliders.clear();

        int top = 34;
        int bottom = height - 34;
        int rowWidth = Math.max(200, Math.min(360, width - 36));
        list = new SettingsList(minecraft, this, width, Math.max(SettingsList.ROW_HEIGHT * 2, bottom - top), top, rowWidth);
        addVisual();
        addAudio();
        addMainMenu();
        addDesktop();
        list.refreshActive();
        addRenderableWidget(list);
        list.setClampedScrollAmount(scroll);

        int buttonWidth = Math.max(80, Math.min(150, (width - 40) / 2));
        int buttonY = height - 27;
        ModContainer container = SettingsClientModule.container();
        NullActionButton allOptions = new NullActionButton(width / 2 - buttonWidth - 4, buttonY, buttonWidth, 20, ALL_OPTIONS,
                button -> openAllOptions());
        allOptions.setTooltip(Tooltip.create(Component.translatable("null.settings.screen.all_options.tooltip")));
        allOptions.active = container != null;
        addRenderableWidget(allOptions);
        addRenderableWidget(new NullActionButton(width / 2 + 4, buttonY, buttonWidth, 20, CommonComponents.GUI_DONE,
                button -> onClose()));
    }

    private void addVisual() {
        list.addHeader(Component.translatable("null.settings.section.visual"));
        percent(NullClientSettings.SCREEN_SHAKE_INTENSITY, "screen_shake_intensity", always());
        toggle(NullClientSettings.REDUCE_SCREEN_SHAKE, "reduce_screen_shake", always());
        percent(NullClientSettings.FLASHING_INTENSITY, "flashing_intensity", always());
        toggle(NullClientSettings.DISABLE_INTENSE_EFFECTS, "disable_intense_effects", always());
        count(NullClientSettings.MAX_VISUAL_EFFECTS, "max_visual_effects", 0, 32, always());
        toggle(NullClientSettings.SHADER_EFFECTS, "shader_effects", always());
    }

    private void addAudio() {
        list.addHeader(Component.translatable("null.settings.section.audio"));
        BooleanSupplier sounds = () -> NullClientSettings.get(NullClientSettings.HORROR_SOUNDS);
        toggle(NullClientSettings.HORROR_SOUNDS, "horror_sounds", always());
        percent(NullClientSettings.AUDIO_INTENSITY, "audio_intensity", sounds);
        toggle(NullClientSettings.STATIC_NOISE, "static_noise", sounds);
        toggle(NullClientSettings.WHISPERS, "whispers", sounds);
        toggle(NullClientSettings.PHANTOM_FOOTSTEPS, "phantom_footsteps", sounds);
        toggle(NullClientSettings.PITCH_DISTORTION, "pitch_distortion", sounds);
        toggle(NullClientSettings.SUDDEN_SILENCE, "sudden_silence", sounds);
    }

    private void addMainMenu() {
        list.addHeader(Component.translatable("null.settings.section.menu"));
        BooleanSupplier menu = () -> NullClientSettings.get(NullClientSettings.CUSTOM_MAIN_MENU);
        BooleanSupplier animated = () -> menu.getAsBoolean() && NullClientSettings.get(NullClientSettings.ANIMATED_BACKGROUND);
        toggle(NullClientSettings.CUSTOM_MAIN_MENU, "custom_main_menu", always());
        toggle(NullClientSettings.ANIMATED_BACKGROUND, "animated_background", menu);
        percent(NullClientSettings.BACKGROUND_ANIMATION_INTENSITY, "background_animation_intensity", animated);
        percent(NullClientSettings.MENU_GLITCH_INTENSITY, "menu_glitch_intensity", menu);
        toggle(NullClientSettings.MENU_SOUNDS, "menu_sounds", menu);

        Component forgetLabel = Component.translatable("null.settings.forget_stage");
        NullActionButton forget = new NullActionButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable("null.settings.forget_stage.button"), button -> {
                    ClientStageMemory.forget();
                    button.setMessage(Component.translatable("null.settings.forget_stage.done"));
                    // refresh the tooltip and state on the next tick, not in the middle of this click
                    rebuildPending = true;
                });
        Tooltip forgetTooltip = Tooltip.create(Component.translatable("null.settings.forget_stage.tooltip",
                ClientStageMemory.highestStage().number()));
        forget.setTooltip(forgetTooltip);
        list.addOption(forgetLabel, forget, forgetTooltip,
                () -> ClientStageMemory.highestStage().number() > CorruptionStage.START.number());
    }

    private void addDesktop() {
        list.addHeader(Component.translatable("null.settings.section.desktop"));
        // what the windows are, in two short lines (the switch's tooltip says the rest)
        list.addNote(Component.translatable("null.settings.desktop_note"), NullStyle.TEXT_DIM);
        BooleanSupplier allowed = NullClientSettings::desktopAllowed;

        NullToggleButton master = new NullToggleButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + "desktop_interference"), NullClientSettings.desktopAllowed(),
                (button, enable) -> {
                    if (enable) {
                        NullClientSettings.set(NullClientSettings.DESKTOP_INTERFERENCE, true);
                        DesktopInterferenceManager.requestAvailabilityCheck();
                        list.refreshActive();
                        return true;
                    }
                    NullClientSettings.set(NullClientSettings.DESKTOP_INTERFERENCE, false);
                    DesktopInterferenceManager.disableNow();
                    return true;
                });
        // it can only be switched on where it works, but it can always be switched off
        option("desktop_interference", master, () -> DesktopInterferenceManager.osSupported() || NullClientSettings.desktopAllowed());
        toggle(NullClientSettings.DESKTOP_NOTIFICATIONS, "desktop_notifications", allowed);
        toggle(NullClientSettings.DESKTOP_ERROR_WINDOWS, "desktop_error_windows", allowed);
        NullSliderButton frequency = new NullSliderButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + "desktop_frequency"), 0.0, 3.0, 0.1,
                NullClientSettings.get(NullClientSettings.DESKTOP_FREQUENCY),
                value -> value <= 0.0 ? Component.translatable("null.settings.value.never") : percentText(value),
                value -> NullClientSettings.set(NullClientSettings.DESKTOP_FREQUENCY, value));
        sliders.add(frequency);
        option("desktop_frequency", frequency, allowed);
        NullSliderButton cooldown = new NullSliderButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + "desktop_cooldown"), 15.0, 1800.0, 15.0,
                NullClientSettings.get(NullClientSettings.DESKTOP_COOLDOWN),
                value -> Component.translatable("null.settings.value.seconds", (int) Math.round(value)),
                value -> NullClientSettings.set(NullClientSettings.DESKTOP_COOLDOWN, (int) Math.round(value)));
        sliders.add(cooldown);
        option("desktop_cooldown", cooldown, allowed);
        BooleanSupplier notifications = () -> allowed.getAsBoolean()
                && NullClientSettings.get(NullClientSettings.DESKTOP_NOTIFICATIONS);
        BooleanSupplier errorWindows = () -> allowed.getAsBoolean()
                && NullClientSettings.get(NullClientSettings.DESKTOP_ERROR_WINDOWS);
        chance(NullClientSettings.DESKTOP_CHANCE_NOTIFICATION, "desktop_chance_notification", notifications);
        chance(NullClientSettings.DESKTOP_CHANCE_ERROR, "desktop_chance_error", errorWindows);
        chance(NullClientSettings.DESKTOP_CHANCE_DISTORTED, "desktop_chance_distorted", notifications);
        chance(NullClientSettings.DESKTOP_CHANCE_REPORT, "desktop_chance_report", errorWindows);
        chance(NullClientSettings.DESKTOP_CHANCE_FINAL, "desktop_chance_final", errorWindows);

        list.addText(this::desktopStatus, this::desktopStatusColor);
        NullActionButton test = new NullActionButton(0, 0, 160, SettingsList.CONTROL_HEIGHT,
                Component.translatable("null.settings.desktop_test"), button -> showTestMessage());
        test.setTooltip(Tooltip.create(Component.translatable("null.settings.desktop_test.tooltip")));
        list.addButton(test, DesktopInterferenceManager::canTest);
    }

    // ------------------------------------------------------------------------------------------
    // option helpers

    private static BooleanSupplier always() {
        return () -> true;
    }

    private void option(String name, AbstractWidget control, BooleanSupplier enabled) {
        Tooltip tooltip = Tooltip.create(Component.translatable(PREFIX + name + ".tooltip"));
        control.setTooltip(tooltip);
        list.addOption(Component.translatable(PREFIX + name), control, tooltip, enabled);
    }

    private void toggle(ModConfigSpec.BooleanValue value, String name, BooleanSupplier enabled) {
        NullToggleButton button = new NullToggleButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + name), NullClientSettings.get(value), (widget, next) -> {
                    NullClientSettings.set(value, next);
                    list.refreshActive();
                    return true;
                });
        option(name, button, enabled);
    }

    private void percent(ModConfigSpec.DoubleValue value, String name, BooleanSupplier enabled) {
        NullSliderButton slider = new NullSliderButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + name), 0.0, 1.0, 0.05, NullClientSettings.get(value),
                NullSettingsScreen::percentText, newValue -> NullClientSettings.set(value, newValue));
        sliders.add(slider);
        option(name, slider, enabled);
    }

    /** A 0-300 % chance slider where 0 reads "never". */
    private void chance(ModConfigSpec.DoubleValue value, String name, BooleanSupplier enabled) {
        NullSliderButton slider = new NullSliderButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + name), 0.0, 3.0, 0.1, NullClientSettings.get(value),
                newValue -> newValue <= 0.0 ? Component.translatable("null.settings.value.never") : percentText(newValue),
                newValue -> NullClientSettings.set(value, newValue));
        sliders.add(slider);
        option(name, slider, enabled);
    }

    private void count(ModConfigSpec.IntValue value, String name, int min, int max, BooleanSupplier enabled) {
        NullSliderButton slider = new NullSliderButton(0, 0, SettingsList.CONTROL_WIDTH, SettingsList.CONTROL_HEIGHT,
                Component.translatable(PREFIX + name), min, max, 1.0, NullClientSettings.get(value),
                newValue -> Component.literal(Integer.toString((int) Math.round(newValue))),
                newValue -> NullClientSettings.set(value, (int) Math.round(newValue)));
        sliders.add(slider);
        option(name, slider, enabled);
    }

    private static Component percentText(double value) {
        return Component.translatable("null.settings.value.percent", (int) Math.round(value * 100.0));
    }

    private void commitSliders() {
        for (NullSliderButton slider : sliders) {
            slider.commit();
        }
    }

    // ------------------------------------------------------------------------------------------
    // desktop status and test

    private Component desktopStatus() {
        if (statusMessage != null && Util.getMillis() < statusMessageUntilMs) {
            return statusMessage;
        }
        DesktopInterferenceManager.Availability availability = DesktopInterferenceManager.availability();
        boolean allowed = NullClientSettings.desktopAllowed();
        String key = switch (availability) {
            case UNSUPPORTED_OS -> "null.settings.desktop_status.unsupported_os";
            case HEADLESS -> "null.settings.desktop_status.headless";
            case FAILED -> "null.settings.desktop_status.failed";
            case CHECKING -> "null.settings.desktop_status.checking";
            case UNKNOWN -> allowed ? "null.settings.desktop_status.checking" : "null.settings.desktop_status.off";
            case AVAILABLE -> !allowed ? "null.settings.desktop_status.ready_off"
                    : minecraft != null && minecraft.getWindow().isFullscreen() ? "null.settings.desktop_status.fullscreen"
                    : "null.settings.desktop_status.on";
        };
        Component state = Component.translatable(key);
        int open = DesktopInterferenceManager.openWindows();
        if (open > 0) {
            state = Component.translatable("null.settings.desktop_status.open", state, open);
        }
        return Component.translatable("null.settings.desktop_status", state);
    }

    private int desktopStatusColor() {
        if (statusMessage != null && Util.getMillis() < statusMessageUntilMs) {
            return statusMessageColor;
        }
        return switch (DesktopInterferenceManager.availability()) {
            case UNSUPPORTED_OS, HEADLESS, FAILED -> NullStyle.TEXT_WARNING;
            case AVAILABLE -> NullClientSettings.desktopAllowed() ? NullStyle.TEXT_GOOD : NullStyle.TEXT_DIM;
            default -> NullStyle.TEXT_DIM;
        };
    }

    private void showTestMessage() {
        DesktopInterferenceManager.TestResult result = DesktopInterferenceManager.showTestMessage();
        String key = switch (result) {
            case SHOWN -> "null.settings.desktop_test.shown";
            case CHECKING -> "null.settings.desktop_test.checking";
            case NOT_ALLOWED -> "null.settings.desktop_test.not_allowed";
            case UNAVAILABLE -> "null.settings.desktop_test.unavailable";
            case FULLSCREEN -> "null.settings.desktop_test.fullscreen";
        };
        statusMessage = Component.translatable(key);
        statusMessageColor = result == DesktopInterferenceManager.TestResult.SHOWN || result == DesktopInterferenceManager.TestResult.CHECKING
                ? NullStyle.TEXT_GOOD : NullStyle.TEXT_WARNING;
        statusMessageUntilMs = Util.getMillis() + STATUS_MESSAGE_MS;
    }

    // ------------------------------------------------------------------------------------------
    // screen life cycle

    private void openAllOptions() {
        ModContainer container = SettingsClientModule.container();
        if (container != null) {
            commitSliders();
            minecraft.setScreen(new ConfigurationScreen(container, this, HIDE_INTERNAL));
        }
    }

    @Override
    public void tick() {
        if (rebuildPending) {
            rebuildPending = false;
            rebuildWidgets();
            return;
        }
        if (list != null) {
            list.refreshActive();
        }
        // a slider that was let go of outside its own bounds still gets saved
        if (minecraft != null && !minecraft.mouseHandler.isLeftPressed()) {
            commitSliders();
        }
    }

    @Override
    public void removed() {
        commitSliders();
    }

    @Override
    public void onClose() {
        commitSliders();
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean inWorld = minecraft != null && minecraft.level != null;
        // outside a world: the same darkness as every other menu (or plain black with the vanilla look)
        if (inWorld || !NullScreenLook.drawBackdrop(this, graphics)) {
            graphics.fill(0, 0, width, height, inWorld ? NullStyle.SCREEN_IN_WORLD : NullStyle.SCREEN);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        int titleY = 13;
        graphics.drawString(font, title, (width - titleWidth) / 2, titleY, NullStyle.TEXT, false);
        // thin rules either side of the title
        int ruleY = titleY + font.lineHeight / 2 - 1;
        int gap = 8;
        int span = Math.min(90, Math.max(0, (width - titleWidth) / 2 - gap - 10));
        if (span > 4) {
            graphics.hLine((width - titleWidth) / 2 - gap - span, (width - titleWidth) / 2 - gap, ruleY, NullStyle.LINE_FAINT);
            graphics.hLine((width + titleWidth) / 2 + gap, (width + titleWidth) / 2 + gap + span, ruleY, NullStyle.LINE_FAINT);
        }
    }
}
