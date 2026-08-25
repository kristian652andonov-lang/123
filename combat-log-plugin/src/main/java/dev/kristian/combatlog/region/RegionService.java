package dev.kristian.combatlog.region;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

/**
 * The plugin's view of the region plugin. Everything that touches WorldGuard
 * classes lives behind this interface, so the plugin still loads and runs
 * normally on a server that has no WorldGuard installed.
 */
public interface RegionService {

    /** {@code false} when WorldGuard is absent or safe zones are switched off. */
    boolean isAvailable();

    /** Whether this exact position sits inside a safe zone. */
    boolean isSafe(Location location);

    /**
     * Safe zones whose bounds come within {@code radius} blocks of the given
     * position. Used to build the barrier without querying WorldGuard once per
     * candidate block.
     */
    List<SafeZone> safeZonesNear(World world, int x, int y, int z, int radius);

    /** Every region at a position, with a marker on the ones counted as safe. Used by /combatlog zones. */
    List<String> describeRegionsAt(Location location);

    /** Re-reads the configured flag names and drops any cached lookups. */
    void reload();

    RegionService NONE = new RegionService() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isSafe(Location location) {
            return false;
        }

        @Override
        public List<SafeZone> safeZonesNear(World world, int x, int y, int z, int radius) {
            return List.of();
        }

        @Override
        public List<String> describeRegionsAt(Location location) {
            return List.of();
        }

        @Override
        public void reload() {
            // nothing to re-read
        }
    };
}
