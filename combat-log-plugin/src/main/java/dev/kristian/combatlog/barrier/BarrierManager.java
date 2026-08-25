package dev.kristian.combatlog.barrier;

import dev.kristian.combatlog.config.Settings;
import dev.kristian.combatlog.region.RegionService;
import dev.kristian.combatlog.region.SafeZone;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Draws the red glass wall that seals off safe zone entrances for a player in
 * combat.
 *
 * <p>The blocks are fake. They are sent straight to one player's client with
 * {@code sendBlockChange}, so the world is never touched, nobody else can see
 * them, and the wall costs nothing to take down. Because the client believes the
 * glass is really there it also refuses to walk through it, which makes the wall
 * feel solid on top of the server side check in the movement listener.
 *
 * <p>The wall is the shell of the safe zone: every block that is inside a safe
 * zone and touches a block that is not. That means it appears across doorways
 * and along borders instead of filling the region, and it follows the player as
 * they move along the edge.
 */
public final class BarrierManager {

    private final Settings settings;
    private final RegionService regions;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<Material, BlockData> blockDataCache = new EnumMap<>(Material.class);

    public BarrierManager(Settings settings, RegionService regions) {
        this.settings = settings;
        this.regions = regions;
    }

    /**
     * Recomputes and redraws one player's wall.
     *
     * @param tick the plugin's global tick counter, used to advance the animation
     */
    public void update(Player player, long tick) {
        Settings.Barrier options = settings.barrier;
        if (!options.enabled || !regions.isAvailable()) {
            remove(player);
            return;
        }

        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            remove(player);
            return;
        }

        // Somebody tagged while already standing in spawn must still be able to
        // walk out, so no wall is drawn around them.
        if (regions.isSafe(location)) {
            remove(player);
            return;
        }

        Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        if (session.needsRebuild(world.getUID(), blockX, blockY, blockZ)) {
            rebuild(session, world, blockX, blockY, blockZ);
        }

        render(player, world, session, tick);
        spawnParticles(player, world, session);
    }

    /** Takes the wall down and forgets the player. */
    public void remove(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            restoreAll(player, session);
        }
    }

    /** Forgets a player who is no longer online - nothing can be sent to them. */
    public void forget(UUID playerId) {
        sessions.remove(playerId);
    }

    public void removeAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                remove(player);
            } else {
                sessions.remove(id);
            }
        }
    }

    // ------------------------------------------------------------- geometry

    private void rebuild(Session session, World world, int blockX, int blockY, int blockZ) {
        Settings.Barrier options = settings.barrier;

        // Blocks remembered for a different world can never be restored there,
        // and the client reloads its chunks on a world change anyway.
        if (!world.getUID().equals(session.worldId)) {
            session.shown.clear();
        }
        session.worldId = world.getUID();
        session.anchorX = blockX;
        session.anchorY = blockY;
        session.anchorZ = blockZ;
        session.wall.clear();

        int radius = options.radius;
        List<SafeZone> zones = regions.safeZonesNear(world, blockX, blockY, blockZ, radius + 2);
        if (zones.isEmpty()) {
            return;
        }

        int minY = Math.max(world.getMinHeight(), blockY - options.heightBelow);
        int maxY = Math.min(world.getMaxHeight() - 1, blockY + options.heightAbove);
        if (maxY < minY) {
            return;
        }

        // One extra ring on each horizontal side so the "is my neighbour outside
        // the zone" test never falls off the edge of the mask.
        int pad = radius + 1;
        int span = pad * 2 + 1;
        int height = maxY - minY + 1;
        boolean[] safe = new boolean[span * height * span];

        for (int dx = -pad; dx <= pad; dx++) {
            for (int dz = -pad; dz <= pad; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    if (inAnyZone(zones, blockX + dx, y, blockZ + dz)) {
                        safe[index(dx + pad, y - minY, dz + pad, span, height)] = true;
                    }
                }
            }
        }

        int radiusSquared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (!safe[index(dx + pad, y - minY, dz + pad, span, height)]) {
                        continue;
                    }
                    if (!touchesOutside(safe, dx + pad, y - minY, dz + pad, span, height, options.sealCorners)) {
                        continue;
                    }

                    int x = blockX + dx;
                    int z = blockZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    if (options.onlyReplacePassable && !world.getBlockAt(x, y, z).isPassable()) {
                        continue;
                    }

                    session.wall.add(new BlockKey(x, y, z));
                    if (session.wall.size() >= options.maxBlocksPerPlayer) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean inAnyZone(List<SafeZone> zones, int x, int y, int z) {
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i).contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** A wall block is one that is inside the zone but has a way out beside it. */
    private static boolean touchesOutside(boolean[] safe, int x, int y, int z, int span, int height, boolean corners) {
        if (isOutside(safe, x - 1, y, z, span, height)
                || isOutside(safe, x + 1, y, z, span, height)
                || isOutside(safe, x, y, z - 1, span, height)
                || isOutside(safe, x, y, z + 1, span, height)) {
            return true;
        }
        if (!corners) {
            return false;
        }
        return isOutside(safe, x - 1, y, z - 1, span, height)
                || isOutside(safe, x - 1, y, z + 1, span, height)
                || isOutside(safe, x + 1, y, z - 1, span, height)
                || isOutside(safe, x + 1, y, z + 1, span, height);
    }

    private static boolean isOutside(boolean[] safe, int x, int y, int z, int span, int height) {
        if (x < 0 || x >= span || z < 0 || z >= span || y < 0 || y >= height) {
            // Off the edge of what we sampled - treat it as open ground.
            return true;
        }
        return !safe[index(x, y, z, span, height)];
    }

    private static int index(int x, int y, int z, int span, int height) {
        return (x * height + y) * span + z;
    }

    // -------------------------------------------------------------- drawing

    private void render(Player player, World world, Session session, long tick) {
        Settings.Barrier options = settings.barrier;
        int frameCount = options.frames.size();
        long frame = tick / options.ticksPerFrame;

        for (BlockKey key : session.wall) {
            Material material = materialFor(options, key, session, frame, frameCount);
            if (session.shown.get(key) == material) {
                continue;
            }
            player.sendBlockChange(new Location(world, key.x(), key.y(), key.z()), blockData(material));
            session.shown.put(key, material);
        }

        if (session.shown.size() > session.wall.size()) {
            for (Iterator<Map.Entry<BlockKey, Material>> it = session.shown.entrySet().iterator(); it.hasNext(); ) {
                BlockKey key = it.next().getKey();
                if (!session.wall.contains(key)) {
                    restore(player, world, key);
                    it.remove();
                }
            }
        }
    }

    private Material materialFor(Settings.Barrier options, BlockKey key, Session session, long frame, int frameCount) {
        if (frameCount == 1 || options.animationMode == AnimationMode.STATIC) {
            return options.frames.get(0);
        }
        long step = switch (options.animationMode) {
            case PULSE -> frame;
            case WAVE -> frame + distanceFromAnchor(key, session);
            case STATIC -> 0L;
        };
        return options.frames.get((int) Math.floorMod(step, frameCount));
    }

    private static int distanceFromAnchor(BlockKey key, Session session) {
        return Math.abs(key.x() - session.anchorX)
                + Math.abs(key.y() - session.anchorY)
                + Math.abs(key.z() - session.anchorZ);
    }

    private void spawnParticles(Player player, World world, Session session) {
        Settings.Barrier options = settings.barrier;
        if (!options.particlesEnabled || options.particlesPerUpdate <= 0 || session.wall.isEmpty()) {
            return;
        }

        List<BlockKey> keys = new ArrayList<>(session.wall);
        int count = Math.min(options.particlesPerUpdate, keys.size());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean dust = options.particle.getDataType() == Particle.DustOptions.class;
        Particle.DustOptions dustOptions = dust
                ? new Particle.DustOptions(options.particleColor, options.particleSize)
                : null;

        for (int i = 0; i < count; i++) {
            BlockKey key = keys.get(random.nextInt(keys.size()));
            Location at = new Location(world, key.x() + 0.5D, key.y() + 0.5D, key.z() + 0.5D);
            if (dustOptions != null) {
                player.spawnParticle(options.particle, at, 1, 0.18D, 0.35D, 0.18D, 0.0D, dustOptions);
            } else {
                player.spawnParticle(options.particle, at, 1, 0.18D, 0.35D, 0.18D, 0.0D);
            }
        }
    }

    private void restoreAll(Player player, Session session) {
        World world = player.getWorld();
        if (session.worldId != null && session.worldId.equals(world.getUID())) {
            for (BlockKey key : session.shown.keySet()) {
                restore(player, world, key);
            }
        }
        session.shown.clear();
        session.wall.clear();
    }

    private void restore(Player player, World world, BlockKey key) {
        if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            return;
        }
        Location location = new Location(world, key.x(), key.y(), key.z());
        player.sendBlockChange(location, world.getBlockAt(key.x(), key.y(), key.z()).getBlockData());
    }

    private BlockData blockData(Material material) {
        return blockDataCache.computeIfAbsent(material, Material::createBlockData);
    }

    private record BlockKey(int x, int y, int z) {
    }

    private static final class Session {
        UUID worldId;
        int anchorX = Integer.MIN_VALUE;
        int anchorY = Integer.MIN_VALUE;
        int anchorZ = Integer.MIN_VALUE;

        final LinkedHashSet<BlockKey> wall = new LinkedHashSet<>();
        final Map<BlockKey, Material> shown = new HashMap<>();

        boolean needsRebuild(UUID world, int x, int y, int z) {
            return !world.equals(worldId) || x != anchorX || y != anchorY || z != anchorZ;
        }
    }
}
