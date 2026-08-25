package dev.kristian.combatlog;

import dev.kristian.combatlog.barrier.BarrierManager;
import dev.kristian.combatlog.combat.CombatManager;
import dev.kristian.combatlog.combat.CombatTag;
import dev.kristian.combatlog.combat.TagListener;
import dev.kristian.combatlog.combat.UntagReason;
import dev.kristian.combatlog.command.CombatLogCommand;
import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.cooldown.CooldownManager;
import dev.kristian.combatlog.display.DisplayManager;
import dev.kristian.combatlog.gui.GuiContext;
import dev.kristian.combatlog.gui.GuiListener;
import dev.kristian.combatlog.history.HistoryManager;
import dev.kristian.combatlog.history.RollbackService;
import dev.kristian.combatlog.hook.PlaceholderHook;
import dev.kristian.combatlog.listener.CombatListener;
import dev.kristian.combatlog.listener.CooldownListener;
import dev.kristian.combatlog.listener.MovementListener;
import dev.kristian.combatlog.listener.PunishmentListener;
import dev.kristian.combatlog.listener.RestrictionListener;
import dev.kristian.combatlog.region.RegionService;
import dev.kristian.combatlog.region.WorldGuardRegionService;
import dev.kristian.combatlog.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Combat tagging for box PvP servers.
 *
 * <p>The plugin runs a single one-tick loop. Everything the player sees - the
 * action bar countdown and the red glass wall across safe zone entrances - is
 * redrawn from there at whatever rate the config asks for, while the listeners
 * handle the events that start, refresh and enforce a tag.
 */
public final class CombatLogPlugin extends JavaPlugin {

    private Settings settings;
    private Text text;
    private CombatManager combat;
    private CooldownManager cooldowns;
    private RegionService regions;
    private BarrierManager barrier;
    private DisplayManager display;
    private HistoryManager history;
    private GuiContext guiContext;

    private BukkitTask ticker;
    private BukkitTask historySaver;
    private long tick;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new Settings(this);
        text = new Text();
        applyTextSettings();

        cooldowns = new CooldownManager();
        combat = new CombatManager(settings);
        regions = createRegionService();
        barrier = new BarrierManager(settings, regions);
        display = new DisplayManager(settings, text);

        history = new HistoryManager(this, settings);
        history.load();
        guiContext = new GuiContext(this, settings, text, history, new RollbackService(settings, history));

        combat.addListener(display);
        combat.addListener(new StateCleanup());

        registerListeners();
        registerCommand();
        hookPlaceholderApi();

        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        startHistorySaver();

        getLogger().info("CombatLog enabled - safe zone protection is "
                + (regions.isAvailable() ? "active via WorldGuard." : "OFF (WorldGuard not found)."));
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (historySaver != null) {
            historySaver.cancel();
            historySaver = null;
        }
        if (history != null) {
            history.saveNow();
        }
        if (barrier != null) {
            barrier.removeAll();
        }
        if (display != null) {
            display.clearEveryone();
        }
        if (combat != null) {
            combat.clearAll(UntagReason.SHUTDOWN);
        }
    }

    /** Backs {@code /combatlog reload}. */
    public void reloadEverything() {
        reloadConfig();
        settings.reload();
        applyTextSettings();
        regions.reload();

        if (settings.general.clearTagsOnReload) {
            combat.clearAll(UntagReason.SHUTDOWN);
        }
        barrier.removeAll();
        startHistorySaver();
    }

    /** Restarts the periodic history write with whatever interval the config now asks for. */
    private void startHistorySaver() {
        if (historySaver != null) {
            historySaver.cancel();
            historySaver = null;
        }
        long interval = settings.history.saveIntervalSeconds * 20L;
        if (!settings.history.enabled || interval <= 0L) {
            return;
        }
        historySaver = Bukkit.getScheduler().runTaskTimer(this, history::saveAsync, interval, interval);
    }

    // ------------------------------------------------------------- lifecycle

    private void applyTextSettings() {
        text.reload(settings.themeSection(), settings.smallCaps, settings.convertUppercase, settings.messages.prefix);
    }

    private RegionService createRegionService() {
        if (!settings.worldGuard.enabled) {
            getLogger().info("Safe zone protection is switched off in the config.");
            return RegionService.NONE;
        }
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            getLogger().warning("WorldGuard was not found. Tagged players will NOT be kept out of safe zones - "
                    + "install WorldGuard to turn that part of the plugin on.");
            return RegionService.NONE;
        }
        try {
            return new WorldGuardRegionService(settings, getLogger());
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("WorldGuard is installed but its API could not be reached (" + error + "). "
                    + "Safe zone protection is off.");
            return RegionService.NONE;
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                new CombatListener(settings, combat, regions, history), this);
        Bukkit.getPluginManager().registerEvents(
                new PunishmentListener(settings, combat, barrier, display, history, text, getLogger()), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(
                new MovementListener(settings, combat, regions, text), this);
        Bukkit.getPluginManager().registerEvents(
                new RestrictionListener(settings, combat, cooldowns, text), this);
        Bukkit.getPluginManager().registerEvents(
                new CooldownListener(settings, combat, cooldowns, text), this);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("combatlog");
        if (command == null) {
            getLogger().severe("The combatlog command is missing from plugin.yml - the jar is damaged.");
            return;
        }
        CombatLogCommand executor = new CombatLogCommand(this, settings, combat, regions, text, history, guiContext);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void hookPlaceholderApi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new PlaceholderHook(this, settings, combat).register();
            getLogger().info("Registered the %combatlog_...% placeholders.");
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("Could not register the PlaceholderAPI expansion: " + error);
        }
    }

    // ------------------------------------------------------------- tick loop

    private void tick() {
        tick++;
        combat.expireFinishedTags();
        if (combat.size() == 0) {
            return;
        }

        boolean drawDisplay = tick % settings.actionBar.updateTicks == 0L
                && (settings.actionBar.enabled || settings.bossBar.enabled);
        boolean drawBarrier = settings.barrier.enabled && tick % settings.barrier.updateTicks == 0L;

        for (CombatTag tag : combat.active()) {
            Player player = Bukkit.getPlayer(tag.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (drawDisplay) {
                display.update(player, tag, tick);
            }
            if (drawBarrier) {
                barrier.update(player, tick);
            }
            enforceMovementRestrictions(player);
        }
    }

    /**
     * A cancelled event is not always the last word - a client can keep asking to
     * glide, and a flight plugin may re-enable flying behind our back - so the
     * state is corrected every tick as well.
     */
    private void enforceMovementRestrictions(Player player) {
        if (settings.restrictions.blockElytra
                && player.isGliding()
                && !player.hasPermission("combatlog.bypass.elytra")) {
            player.setGliding(false);
        }
        if (settings.restrictions.blockFlight
                && player.isFlying()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR
                && !player.hasPermission("combatlog.bypass.flight")) {
            player.setFlying(false);
        }
    }

    /** Keeps the barrier, the elytra clock and player state in step with the tag. */
    private final class StateCleanup implements TagListener {

        @Override
        public void onTag(Player player, CombatTag tag, boolean fresh) {
            if (!fresh) {
                return;
            }
            if (settings.elytra.stopActiveGlide && player.isGliding()) {
                player.setGliding(false);
            }
            if (settings.restrictions.blockFlight
                    && player.isFlying()
                    && player.getGameMode() != GameMode.CREATIVE
                    && player.getGameMode() != GameMode.SPECTATOR
                    && !player.hasPermission("combatlog.bypass.flight")) {
                player.setFlying(false);
            }
        }

        @Override
        public void onUntag(UUID playerId, CombatTag tag, UntagReason reason) {
            if (settings.elytra.cooldownAfterCombatMillis > 0L && reason != UntagReason.SHUTDOWN) {
                cooldowns.set(playerId, CooldownManager.ELYTRA_KEY, settings.elytra.cooldownAfterCombatMillis);
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                barrier.remove(player);
            } else {
                barrier.forget(playerId);
            }
        }
    }
}
