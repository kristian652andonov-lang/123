package dev.kristian.combatlog.region;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import dev.kristian.combatlog.config.Settings;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads safe zones out of WorldGuard.
 *
 * <p>Region membership is answered from a short-lived per-world cache of the
 * regions that qualify as safe zones. A region's flags rarely change, but the
 * barrier asks about hundreds of block positions several times a second, so
 * going through WorldGuard's query API for every one of them would be wasteful.
 */
public final class WorldGuardRegionService implements RegionService {

    private static final String GLOBAL_REGION = "__global__";

    private final Settings.WorldGuardOptions options;
    private final Logger logger;

    private final List<StateFlag> denyFlags = new ArrayList<>();
    private final List<StateFlag> allowFlags = new ArrayList<>();
    private final Map<UUID, CachedZones> cache = new ConcurrentHashMap<>();

    public WorldGuardRegionService(Settings settings, Logger logger) {
        this.options = settings.worldGuard;
        this.logger = logger;
        resolveFlags();
    }

    @Override
    public boolean isAvailable() {
        return options.enabled;
    }

    @Override
    public boolean isSafe(Location location) {
        if (!options.enabled || location.getWorld() == null) {
            return false;
        }
        try {
            RegionManager manager = managerFor(location.getWorld());
            if (manager == null) {
                return false;
            }
            ApplicableRegionSet applicable = manager.getApplicableRegions(
                    BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            for (ProtectedRegion region : applicable) {
                if (isSafeRegion(region)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.WARNING, "WorldGuard lookup failed, treating the position as unprotected", error);
            return false;
        }
    }

    @Override
    public List<SafeZone> safeZonesNear(World world, int x, int y, int z, int radius) {
        if (!options.enabled) {
            return List.of();
        }
        List<Zone> zones = cachedZones(world);
        if (zones.isEmpty()) {
            return List.of();
        }

        List<SafeZone> out = new ArrayList<>(4);
        for (Zone zone : zones) {
            if (zone.intersects(x, y, z, radius)) {
                out.add(zone);
            }
        }
        return out;
    }

    @Override
    public List<String> describeRegionsAt(Location location) {
        if (location.getWorld() == null) {
            return List.of();
        }
        try {
            RegionManager manager = managerFor(location.getWorld());
            if (manager == null) {
                return List.of();
            }
            ApplicableRegionSet applicable = manager.getApplicableRegions(
                    BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            List<String> out = new ArrayList<>();
            for (ProtectedRegion region : applicable) {
                out.add(region.getId() + (isSafeRegion(region) ? " (safe zone)" : ""));
            }
            return out;
        } catch (RuntimeException | LinkageError error) {
            return List.of();
        }
    }

    @Override
    public void reload() {
        denyFlags.clear();
        allowFlags.clear();
        resolveFlags();
        cache.clear();
    }

    /** Turns the configured flag names into real WorldGuard state flags, once. */
    private void resolveFlags() {
        for (String name : options.denyFlags) {
            StateFlag flag = stateFlag(name);
            if (flag != null) {
                denyFlags.add(flag);
            }
        }
        for (String name : options.allowFlags) {
            StateFlag flag = stateFlag(name);
            if (flag != null) {
                allowFlags.add(flag);
            }
        }
    }

    private StateFlag stateFlag(String name) {
        try {
            Flag<?> flag = Flags.fuzzyMatchFlag(WorldGuard.getInstance().getFlagRegistry(), name);
            if (flag instanceof StateFlag stateFlag) {
                return stateFlag;
            }
            logger.warning("WorldGuard flag '" + name + "' is not an allow/deny flag - ignoring it.");
        } catch (RuntimeException | LinkageError error) {
            logger.warning("Could not resolve the WorldGuard flag '" + name + "'.");
        }
        return null;
    }

    private boolean isSafeRegion(ProtectedRegion region) {
        String id = region.getId().toLowerCase(Locale.ROOT);

        if (options.ignoreGlobalRegion && GLOBAL_REGION.equals(id)) {
            return false;
        }
        if (options.ignoredRegions.contains(id)) {
            return false;
        }

        if (options.regionNames.contains(id)) {
            return true;
        }
        if (options.matchPartialNames) {
            for (String candidate : options.regionNames) {
                if (!candidate.isEmpty() && id.contains(candidate)) {
                    return true;
                }
            }
        }

        for (StateFlag flag : denyFlags) {
            if (region.getFlag(flag) == StateFlag.State.DENY) {
                return true;
            }
        }
        for (StateFlag flag : allowFlags) {
            if (region.getFlag(flag) == StateFlag.State.ALLOW) {
                return true;
            }
        }
        return false;
    }

    private List<Zone> cachedZones(World world) {
        long now = System.currentTimeMillis();
        CachedZones cached = cache.get(world.getUID());
        if (cached != null && cached.expiresAt > now) {
            return cached.zones;
        }

        List<Zone> zones = new ArrayList<>();
        try {
            RegionManager manager = managerFor(world);
            if (manager != null) {
                for (ProtectedRegion region : manager.getRegions().values()) {
                    if (!isSafeRegion(region)) {
                        continue;
                    }
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();
                    zones.add(new Zone(region, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
                }
            }
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.WARNING, "Could not list WorldGuard regions for " + world.getName(), error);
        }

        cache.put(world.getUID(), new CachedZones(now + options.regionCacheMillis, List.copyOf(zones)));
        return zones;
    }

    private static RegionManager managerFor(World world) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    /** A safe zone plus its bounding box, so nearness tests need no region query. */
    private record Zone(ProtectedRegion region, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
            implements SafeZone {

        @Override
        public String name() {
            return region.getId();
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ
                    && region.contains(x, y, z);
        }

        boolean intersects(int x, int y, int z, int radius) {
            return maxX >= x - radius && minX <= x + radius
                    && maxY >= y - radius && minY <= y + radius
                    && maxZ >= z - radius && minZ <= z + radius;
        }
    }

    private record CachedZones(long expiresAt, List<Zone> zones) {
    }
}
