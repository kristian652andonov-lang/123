package dev.kristian.combatlog.config;

import dev.kristian.combatlog.barrier.AnimationMode;
import dev.kristian.combatlog.cooldown.CooldownRule;
import dev.kristian.combatlog.history.EventType;
import dev.kristian.combatlog.text.TimeStyle;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Every option in {@code config.yml}, read once and exposed as plain fields.
 *
 * <p>The listeners and the tick loop touch these values thousands of times a
 * second, so nothing here does a string lookup at runtime.
 */
public final class Settings {

    /** Bump this whenever an option is added, so admins get told to look. */
    public static final int CURRENT_CONFIG_VERSION = 2;

    public final General general = new General();
    public final ActionBar actionBar = new ActionBar();
    public final BossBarOptions bossBar = new BossBarOptions();
    public final TitleOptions tagTitle = new TitleOptions();
    public final TitleOptions untagTitle = new TitleOptions();
    public final Sounds sounds = new Sounds();
    public final Punishment punishment = new Punishment();
    public final WorldGuardOptions worldGuard = new WorldGuardOptions();
    public final Barrier barrier = new Barrier();
    public final Restrictions restrictions = new Restrictions();
    public final Cooldowns cooldowns = new Cooldowns();
    public final Elytra elytra = new Elytra();
    public final History history = new History();
    public final Gui gui = new Gui();
    public final Messages messages = new Messages();

    public TimeStyle timeStyle = TimeStyle.TENTHS;
    public boolean smallCaps = true;
    public boolean convertUppercase = true;

    private final JavaPlugin plugin;
    private final YamlConfiguration bundled;
    private final Logger logger;
    private FileConfiguration config;

    public Settings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.bundled = loadBundledDefaults(plugin);
        reload();
    }

    /**
     * Re-reads every option in place. The nested option groups are never
     * replaced, so listeners and managers that hold a reference to this object
     * pick the new values up immediately.
     */
    public void reload() {
        this.config = plugin.getConfig();
        load();
    }

    /** The {@code theme:} section, used to build the MiniMessage colour tags. */
    public ConfigurationSection themeSection() {
        return section("theme");
    }

    private void load() {
        int version = config.getInt("config-version", 0);
        if (version != CURRENT_CONFIG_VERSION) {
            logger.warning("config.yml is version " + version + " but this build expects "
                    + CURRENT_CONFIG_VERSION + ". Missing options fall back to their defaults - "
                    + "rename config.yml and restart to generate a fresh one.");
        }

        smallCaps = config.getBoolean("font.small-caps", true);
        convertUppercase = config.getBoolean("font.convert-uppercase", true);
        timeStyle = TimeStyle.parse(config.getString("time.style"), TimeStyle.TENTHS);

        loadGeneral();
        loadActionBar();
        loadBossBar();
        loadTitles();
        loadSounds();
        loadPunishment();
        loadWorldGuard();
        loadBarrier();
        loadRestrictions();
        loadCooldowns();
        loadElytra();
        loadHistory();
        loadGui();
        loadMessages();
    }

    private void loadGeneral() {
        general.durationMillis = Math.max(1L, (long) (config.getDouble("general.combat-duration", 15.0D) * 1000.0D));
        general.refreshOnHit = config.getBoolean("general.refresh-on-hit", true);
        general.disabledWorlds = lowerSet(config.getStringList("general.disabled-worlds"));
        general.tagOnPlayerDamage = config.getBoolean("general.tag-on-player-damage", true);
        general.tagOnMobDamage = config.getBoolean("general.tag-on-mob-damage", false);
        general.tagAttacker = config.getBoolean("general.tag-attacker", true);
        general.tagVictim = config.getBoolean("general.tag-victim", true);
        general.countProjectiles = config.getBoolean("general.count-projectiles", true);
        general.countPets = config.getBoolean("general.count-pets", false);
        general.ignoreSelfDamage = config.getBoolean("general.ignore-self-damage", true);
        general.noTagInsideSafeZone = config.getBoolean("general.no-tag-inside-safe-zone", true);
        general.respectBypassPermission = config.getBoolean("general.respect-bypass-permission", true);
        general.untagKillerOnKill = config.getBoolean("general.untag-killer-on-kill", true);
        general.untagOnDeath = config.getBoolean("general.untag-on-death", true);
        general.clearTagsOnReload = config.getBoolean("general.clear-tags-on-reload", true);
    }

    private void loadActionBar() {
        actionBar.enabled = config.getBoolean("actionbar.enabled", true);
        actionBar.updateTicks = Math.max(1, config.getInt("actionbar.update-ticks", 2));
        actionBar.format = config.getString("actionbar.format", "<accent>combat</accent> %bar% %time%s");
        actionBar.barEnabled = config.getBoolean("actionbar.progress-bar.enabled", true);
        actionBar.barLength = Math.max(1, Math.min(64, config.getInt("actionbar.progress-bar.length", 22)));
        actionBar.barSymbol = config.getString("actionbar.progress-bar.symbol", "▍");
        actionBar.barFilled = config.getString("actionbar.progress-bar.filled", "<primary>");
        actionBar.barEmpty = config.getString("actionbar.progress-bar.empty", "<dark_gray>");
        actionBar.barLow = config.getString("actionbar.progress-bar.low", "<accent>");
        actionBar.lowThresholdPercent = clampPercent(config.getInt("actionbar.progress-bar.low-threshold-percent", 30));
        actionBar.flashEnabled = config.getBoolean("actionbar.flash.enabled", true);
        actionBar.flashBelowMillis = (long) (config.getDouble("actionbar.flash.below-seconds", 3.0D) * 1000.0D);
        actionBar.flashIntervalTicks = Math.max(1, config.getInt("actionbar.flash.interval-ticks", 4));
        actionBar.flashColor = config.getString("actionbar.flash.color", "<danger>");
    }

    private void loadBossBar() {
        bossBar.enabled = config.getBoolean("bossbar.enabled", false);
        bossBar.title = config.getString("bossbar.title", "<primary>in combat</primary> <accent>%time%</accent>");
        bossBar.color = parseEnum(BossBar.Color.class, config.getString("bossbar.color"), BossBar.Color.BLUE);
        bossBar.overlay = parseEnum(BossBar.Overlay.class, config.getString("bossbar.overlay"), BossBar.Overlay.PROGRESS);
    }

    private void loadTitles() {
        tagTitle.load(config, "titles.on-tag");
        untagTitle.load(config, "titles.on-untag");
    }

    private void loadSounds() {
        sounds.tagged = SoundSpec.from(section("sounds.tagged"), "entity.experience_orb.pickup");
        sounds.untagged = SoundSpec.from(section("sounds.untagged"), "block.note_block.pling");
        sounds.barrierBlocked = SoundSpec.from(section("sounds.barrier-blocked"), "block.glass.hit");
        sounds.cooldownDenied = SoundSpec.from(section("sounds.cooldown-denied"), "block.note_block.bass");
        sounds.actionBlocked = SoundSpec.from(section("sounds.action-blocked"), "entity.villager.no");
        sounds.combatLogged = SoundSpec.from(section("sounds.combat-logged"), "entity.lightning_bolt.thunder");
    }

    private void loadPunishment() {
        punishment.killOnQuit = config.getBoolean("punishment.kill-on-quit", true);
        punishment.punishOnKick = config.getBoolean("punishment.punish-on-kick", false);
        punishment.dropInventory = config.getBoolean("punishment.drop-inventory", true);
        punishment.dropExperience = config.getBoolean("punishment.drop-experience", true);
        punishment.clearInventoryAfterDrop = config.getBoolean("punishment.clear-inventory-after-drop", true);
        punishment.lightningEffect = config.getBoolean("punishment.lightning-effect", true);
        punishment.broadcast = config.getBoolean("punishment.broadcast", true);
        punishment.broadcastSameWorldOnly = config.getBoolean("punishment.broadcast-same-world-only", false);
        punishment.commands = List.copyOf(config.getStringList("punishment.commands"));
    }

    private void loadWorldGuard() {
        worldGuard.enabled = config.getBoolean("worldguard.enabled", true);
        worldGuard.blockEntering = config.getBoolean("worldguard.block-entering-safe-zones", true);
        worldGuard.blockTeleport = config.getBoolean("worldguard.block-teleport-into-safe-zones", true);
        worldGuard.blockEnderPearl = config.getBoolean("worldguard.block-enderpearl-into-safe-zones", true);
        worldGuard.pushBack = config.getBoolean("worldguard.push-back.enabled", true);
        worldGuard.pushStrength = config.getDouble("worldguard.push-back.strength", 0.45D);
        worldGuard.pushUpward = config.getDouble("worldguard.push-back.upward", 0.16D);
        worldGuard.messageCooldownMillis = config.getLong("worldguard.message-cooldown-millis", 1200L);
        worldGuard.regionNames = lowerSet(config.getStringList("worldguard.safe-zone-detection.region-names"));
        worldGuard.matchPartialNames = config.getBoolean("worldguard.safe-zone-detection.match-partial-names", true);
        worldGuard.denyFlags = lowerList(config.getStringList("worldguard.safe-zone-detection.deny-flags"));
        worldGuard.allowFlags = lowerList(config.getStringList("worldguard.safe-zone-detection.allow-flags"));
        worldGuard.ignoredRegions = lowerSet(config.getStringList("worldguard.ignored-regions"));
        worldGuard.ignoreGlobalRegion = config.getBoolean("worldguard.ignore-global-region", true);
        worldGuard.regionCacheMillis = Math.max(0L, config.getLong("worldguard.region-cache-millis", 1000L));
    }

    private void loadBarrier() {
        barrier.enabled = config.getBoolean("barrier.enabled", true);
        barrier.radius = Math.max(1, Math.min(24, config.getInt("barrier.radius", 6)));
        barrier.heightAbove = Math.max(0, Math.min(24, config.getInt("barrier.height-above", 3)));
        barrier.heightBelow = Math.max(0, Math.min(24, config.getInt("barrier.height-below", 2)));
        barrier.updateTicks = Math.max(1, config.getInt("barrier.update-ticks", 3));
        barrier.onlyReplacePassable = config.getBoolean("barrier.only-replace-passable-blocks", true);
        barrier.sealCorners = config.getBoolean("barrier.seal-corners", true);
        barrier.maxBlocksPerPlayer = Math.max(16, config.getInt("barrier.max-blocks-per-player", 900));

        barrier.animationMode = AnimationMode.parse(config.getString("barrier.animation.mode"), AnimationMode.WAVE);
        barrier.ticksPerFrame = Math.max(1, config.getInt("barrier.animation.ticks-per-frame", 2));
        barrier.frames = materialList(config.getStringList("barrier.animation.frames"), Material.RED_STAINED_GLASS);

        barrier.particlesEnabled = config.getBoolean("barrier.particles.enabled", true);
        barrier.particle = parseEnum(Particle.class, config.getString("barrier.particles.particle"), Particle.REDSTONE);
        barrier.particlesPerUpdate = Math.max(0, Math.min(200, config.getInt("barrier.particles.per-update", 10)));
        barrier.particleColor = parseColor(config.getString("barrier.particles.color", "#FF5C5C"));
        barrier.particleSize = (float) config.getDouble("barrier.particles.size", 1.0D);
    }

    private void loadRestrictions() {
        restrictions.blockCommands = config.getBoolean("restrictions.block-commands.enabled", true);
        restrictions.commandWhitelistMode =
                "WHITELIST".equalsIgnoreCase(String.valueOf(config.getString("restrictions.block-commands.mode", "BLACKLIST")).trim());
        restrictions.commands = lowerSet(config.getStringList("restrictions.block-commands.commands"));
        restrictions.blockFlight = config.getBoolean("restrictions.block-flight", true);
        restrictions.blockElytra = config.getBoolean("restrictions.block-elytra", true);
        restrictions.blockRiptide = config.getBoolean("restrictions.block-riptide", false);
        restrictions.blockChorusFruit = config.getBoolean("restrictions.block-chorus-fruit", true);
    }

    private void loadCooldowns() {
        cooldowns.enabled = config.getBoolean("cooldowns.enabled", true);
        cooldowns.showItemAnimation = config.getBoolean("cooldowns.show-item-animation", true);
        cooldowns.messageCooldownMillis = config.getLong("cooldowns.message-cooldown-millis", 800L);

        Map<Material, CooldownRule> rules = new EnumMap<>(Material.class);
        ConfigurationSection items = section("cooldowns.items");
        for (String key : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(key);
            if (entry == null || !entry.getBoolean("enabled", true)) {
                continue;
            }
            Material material = Material.matchMaterial(key);
            if (material == null) {
                logger.warning("cooldowns.items." + key + " is not a valid 1.20.1 material - skipping it.");
                continue;
            }
            long millis = (long) (entry.getDouble("seconds", 0.0D) * 1000.0D);
            if (millis <= 0L) {
                continue;
            }
            rules.put(material, new CooldownRule(
                    material,
                    millis,
                    entry.getBoolean("only-in-combat", false),
                    entry.getBoolean("only-while-gliding", false)));
        }
        cooldowns.itemRules = Collections.unmodifiableMap(rules);

        cooldowns.foodEnabled = config.getBoolean("cooldowns.food.enabled", false);
        cooldowns.foodMillis = (long) (config.getDouble("cooldowns.food.seconds", 2.0D) * 1000.0D);
        cooldowns.foodOnlyInCombat = config.getBoolean("cooldowns.food.only-in-combat", true);
        Set<Material> ignored = new HashSet<>(cooldowns.itemRules.keySet());
        for (String name : config.getStringList("cooldowns.food.ignored")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                ignored.add(material);
            }
        }
        cooldowns.foodIgnored = Set.copyOf(ignored);
    }

    private void loadElytra() {
        elytra.disableInCombat = config.getBoolean("elytra.disable-in-combat", true);
        elytra.cooldownAfterCombatMillis = (long) (config.getDouble("elytra.cooldown-after-combat", 0.0D) * 1000.0D);
        elytra.glideCooldownMillis = (long) (config.getDouble("elytra.glide-cooldown", 0.0D) * 1000.0D);
        elytra.stopActiveGlide = config.getBoolean("elytra.stop-active-glide", true);
    }

    private void loadHistory() {
        history.enabled = config.getBoolean("history.enabled", true);
        history.maxEntries = Math.max(1, config.getInt("history.max-entries", 500));
        history.maxSnapshots = Math.max(0, config.getInt("history.max-snapshots", 200));
        history.recordFights = config.getBoolean("history.record-fights", true);
        history.recordKills = config.getBoolean("history.record-kills", true);
        history.recordDeathsWithoutKiller = config.getBoolean("history.record-deaths-without-killer", true);
        history.recordCombatLogs = config.getBoolean("history.record-combat-logs", true);
        history.keepInventorySnapshots = config.getBoolean("history.keep-inventory-snapshots", true);
        history.saveIntervalSeconds = Math.max(0, config.getInt("history.save-interval-seconds", 120));
    }

    private void loadGui() {
        gui.title = config.getString("gui.title", "combat log");
        gui.usePlayerHeads = config.getBoolean("gui.use-player-heads", true);
        gui.confirmRollback = config.getBoolean("gui.confirm-rollback", true);
        gui.snapshotBeforeRollback = config.getBoolean("gui.snapshot-before-rollback", true);

        gui.icons.clear();
        ConfigurationSection icons = section("gui.icons");
        for (String key : icons.getKeys(false)) {
            EventType type = EventType.parse(key, null);
            if (type == null) {
                logger.warning("gui.icons." + key + " is not a combat log entry type - skipping it.");
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(icons.getString(key, "")));
            if (material == null || material.isAir()) {
                logger.warning("gui.icons." + key + " is not a valid 1.20.1 item - skipping it.");
                continue;
            }
            gui.icons.put(type, material);
        }
    }

    private void loadMessages() {
        messages.prefix = config.getString("messages.prefix", "");
        messages.tagged = message("tagged");
        messages.taggedBy = message("tagged-by");
        messages.refreshed = message("refreshed");
        messages.untagged = message("untagged");
        messages.safezoneBlocked = message("safezone-blocked");
        messages.teleportBlocked = message("teleport-blocked");
        messages.enderpearlBlocked = message("enderpearl-blocked");
        messages.commandBlocked = message("command-blocked");
        messages.flightBlocked = message("flight-blocked");
        messages.elytraBlocked = message("elytra-blocked");
        messages.elytraCooldown = message("elytra-cooldown");
        messages.riptideBlocked = message("riptide-blocked");
        messages.chorusBlocked = message("chorus-blocked");
        messages.cooldownActive = message("cooldown-active");
        messages.combatLogBroadcast = message("combat-log-broadcast");
        messages.statusInCombat = message("status-in-combat");
        messages.statusSafe = message("status-safe");
        messages.checkInCombat = message("check-in-combat");
        messages.checkSafe = message("check-safe");
        messages.adminTagged = message("admin-tagged");
        messages.adminUntagged = message("admin-untagged");
        messages.reloaded = message("reloaded");
        messages.noPermission = message("no-permission");
        messages.playerNotFound = message("player-not-found");
        messages.playersOnly = message("players-only");
        messages.invalidNumber = message("invalid-number");
        messages.worldGuardMissing = message("worldguard-missing");
        messages.historyEmpty = message("history-empty");
        messages.historyCleared = message("history-cleared");
        messages.rollbackRestored = message("rollback-restored");
        messages.rollbackGiven = message("rollback-given");
        messages.rollbackDropped = message("rollback-dropped");
        messages.rollbackNoSnapshot = message("rollback-no-snapshot");
        messages.rollbackTargetOffline = message("rollback-target-offline");
        messages.rollbackWorldMissing = message("rollback-world-missing");
        messages.teleported = message("teleported");

        List<String> help = config.getStringList("messages.help");
        if (help.isEmpty()) {
            help = bundled.getStringList("messages.help");
        }
        messages.help = List.copyOf(help);
    }

    private String message(String key) {
        return config.getString("messages." + key, bundled.getString("messages." + key, ""));
    }

    /**
     * Returns a section from the live config, falling back to the copy bundled in
     * the jar when the admin's file predates the option. Never returns null.
     */
    private ConfigurationSection section(String path) {
        ConfigurationSection live = config.getConfigurationSection(path);
        if (live != null && !live.getKeys(false).isEmpty()) {
            return live;
        }
        ConfigurationSection fallback = bundled.getConfigurationSection(path);
        return fallback != null ? fallback : new YamlConfiguration();
    }

    private static YamlConfiguration loadBundledDefaults(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not read the bundled config.yml: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private List<Material> materialList(List<String> names, Material fallback) {
        List<Material> out = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                logger.warning(name + " is not a valid block for the barrier animation - skipping it.");
                continue;
            }
            out.add(material);
        }
        if (out.isEmpty()) {
            out.add(fallback);
        }
        return List.copyOf(out);
    }

    private static Color parseColor(String hex) {
        try {
            String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
            return Color.fromRGB(Integer.parseInt(cleaned, 16));
        } catch (RuntimeException ignored) {
            return Color.fromRGB(0xFF5C5C);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>(values.size() * 2);
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT).trim());
        }
        return Set.copyOf(out);
    }

    private static List<String> lowerList(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT).trim());
        }
        return List.copyOf(out);
    }

    // ----------------------------------------------------------------- groups

    public static final class General {
        public long durationMillis;
        public boolean refreshOnHit;
        public Set<String> disabledWorlds = Set.of();
        public boolean tagOnPlayerDamage;
        public boolean tagOnMobDamage;
        public boolean tagAttacker;
        public boolean tagVictim;
        public boolean countProjectiles;
        public boolean countPets;
        public boolean ignoreSelfDamage;
        public boolean noTagInsideSafeZone;
        public boolean respectBypassPermission;
        public boolean untagKillerOnKill;
        public boolean untagOnDeath;
        public boolean clearTagsOnReload;
    }

    public static final class ActionBar {
        public boolean enabled;
        public int updateTicks;
        public String format = "";
        public boolean barEnabled;
        public int barLength;
        public String barSymbol = "▍";
        public String barFilled = "";
        public String barEmpty = "";
        public String barLow = "";
        public int lowThresholdPercent;
        public boolean flashEnabled;
        public long flashBelowMillis;
        public int flashIntervalTicks;
        public String flashColor = "";
    }

    public static final class BossBarOptions {
        public boolean enabled;
        public String title = "";
        public BossBar.Color color = BossBar.Color.BLUE;
        public BossBar.Overlay overlay = BossBar.Overlay.PROGRESS;
    }

    public static final class TitleOptions {
        public boolean enabled;
        public String title = "";
        public String subtitle = "";
        public int fadeInTicks = 4;
        public int stayTicks = 24;
        public int fadeOutTicks = 8;

        void load(FileConfiguration config, String path) {
            enabled = config.getBoolean(path + ".enabled", false);
            title = config.getString(path + ".title", "");
            subtitle = config.getString(path + ".subtitle", "");
            fadeInTicks = Math.max(0, config.getInt(path + ".fade-in-ticks", 4));
            stayTicks = Math.max(1, config.getInt(path + ".stay-ticks", 24));
            fadeOutTicks = Math.max(0, config.getInt(path + ".fade-out-ticks", 8));
        }
    }

    public static final class Sounds {
        public SoundSpec tagged;
        public SoundSpec untagged;
        public SoundSpec barrierBlocked;
        public SoundSpec cooldownDenied;
        public SoundSpec actionBlocked;
        public SoundSpec combatLogged;
    }

    public static final class Punishment {
        public boolean killOnQuit;
        public boolean punishOnKick;
        public boolean dropInventory;
        public boolean dropExperience;
        public boolean clearInventoryAfterDrop;
        public boolean lightningEffect;
        public boolean broadcast;
        public boolean broadcastSameWorldOnly;
        public List<String> commands = List.of();
    }

    public static final class WorldGuardOptions {
        public boolean enabled;
        public boolean blockEntering;
        public boolean blockTeleport;
        public boolean blockEnderPearl;
        public boolean pushBack;
        public double pushStrength;
        public double pushUpward;
        public long messageCooldownMillis;
        public Set<String> regionNames = Set.of();
        public boolean matchPartialNames;
        public List<String> denyFlags = List.of();
        public List<String> allowFlags = List.of();
        public Set<String> ignoredRegions = Set.of();
        public boolean ignoreGlobalRegion;
        public long regionCacheMillis;
    }

    public static final class Barrier {
        public boolean enabled;
        public int radius;
        public int heightAbove;
        public int heightBelow;
        public int updateTicks;
        public boolean onlyReplacePassable;
        public boolean sealCorners;
        public int maxBlocksPerPlayer;
        public AnimationMode animationMode = AnimationMode.WAVE;
        public int ticksPerFrame;
        public List<Material> frames = List.of(Material.RED_STAINED_GLASS);
        public boolean particlesEnabled;
        public Particle particle = Particle.REDSTONE;
        public int particlesPerUpdate;
        public Color particleColor = Color.RED;
        public float particleSize = 1.0F;
    }

    public static final class Restrictions {
        public boolean blockCommands;
        public boolean commandWhitelistMode;
        public Set<String> commands = Set.of();
        public boolean blockFlight;
        public boolean blockElytra;
        public boolean blockRiptide;
        public boolean blockChorusFruit;
    }

    public static final class Cooldowns {
        public boolean enabled;
        public boolean showItemAnimation;
        public long messageCooldownMillis;
        public Map<Material, CooldownRule> itemRules = Map.of();
        public boolean foodEnabled;
        public long foodMillis;
        public boolean foodOnlyInCombat;
        public Set<Material> foodIgnored = Set.of();
    }

    public static final class Elytra {
        public boolean disableInCombat;
        public long cooldownAfterCombatMillis;
        public long glideCooldownMillis;
        public boolean stopActiveGlide;
    }

    public static final class History {
        public boolean enabled;
        public int maxEntries;
        public int maxSnapshots;
        public boolean recordFights;
        public boolean recordKills;
        public boolean recordDeathsWithoutKiller;
        public boolean recordCombatLogs;
        public boolean keepInventorySnapshots;
        public int saveIntervalSeconds;
    }

    public static final class Gui {
        public String title = "combat log";
        public boolean usePlayerHeads;
        public boolean confirmRollback;
        public boolean snapshotBeforeRollback;
        public final Map<EventType, Material> icons = new EnumMap<>(EventType.class);

        public Material icon(EventType type) {
            return icons.getOrDefault(type, Material.PAPER);
        }
    }

    public static final class Messages {
        public String prefix = "";
        public String tagged = "";
        public String taggedBy = "";
        public String refreshed = "";
        public String untagged = "";
        public String safezoneBlocked = "";
        public String teleportBlocked = "";
        public String enderpearlBlocked = "";
        public String commandBlocked = "";
        public String flightBlocked = "";
        public String elytraBlocked = "";
        public String elytraCooldown = "";
        public String riptideBlocked = "";
        public String chorusBlocked = "";
        public String cooldownActive = "";
        public String combatLogBroadcast = "";
        public String statusInCombat = "";
        public String statusSafe = "";
        public String checkInCombat = "";
        public String checkSafe = "";
        public String adminTagged = "";
        public String adminUntagged = "";
        public String reloaded = "";
        public String noPermission = "";
        public String playerNotFound = "";
        public String playersOnly = "";
        public String invalidNumber = "";
        public String worldGuardMissing = "";
        public String historyEmpty = "";
        public String historyCleared = "";
        public String rollbackRestored = "";
        public String rollbackGiven = "";
        public String rollbackDropped = "";
        public String rollbackNoSnapshot = "";
        public String rollbackTargetOffline = "";
        public String rollbackWorldMissing = "";
        public String teleported = "";
        public List<String> help = List.of();
    }
}
