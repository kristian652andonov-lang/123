package dev.kristian.nullmod.client.visual;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.network.ClientEffectPayload;
import dev.kristian.nullmod.world.BuildProtection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Purely client-side block glitches: block states in the client's own copy of the world are
 * swapped for a few seconds (black, missing-texture, error, wrong vanilla blocks, air, shifted
 * copies) and put back afterwards. Nothing is ever sent to the server, and the server's world
 * never changes.
 * <p>
 * Rules: only visible blocks (found by ray casts inside the view, never by scanning), only
 * non-air full cubes without block entities, fluids or light, never within 4 blocks of the
 * player or under any entity, at most {@value #MAX_SWAPS} at once. A swap is only undone if
 * the client still shows exactly what we put there, so a real change from the server always
 * wins. Everything is restored or forgotten on logout, dimension change and chunk unload, and a
 * swap near the player is undone before they could touch it.
 * <p>
 * Effects sent by {@code /null showcase} ({@code data} = {@link EventContext#SHOWCASE_BLOCK_DATA})
 * pick their blocks close in front of the player instead ({@value #SHOWCASE_MIN_DISTANCE}-{@value
 * #SHOWCASE_MAX_DISTANCE} blocks, near the middle of the view, more rays) and stay a little longer,
 * so an operator who was told where to look really sees them.
 */
public final class ClientBlockGlitchManager {
    static final int MAX_SWAPS = 64;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final double MIN_PLAYER_DISTANCE_SQ = 4.0 * 4.0;
    private static final double RELEASE_DISTANCE_SQ = 5.5 * 5.5;
    /** Showcase picks: just beyond the release distance... */
    static final double SHOWCASE_MIN_DISTANCE = 6.0;
    /** ...and no further than this. */
    static final double SHOWCASE_MAX_DISTANCE = 16.0;
    /** Showcase rays stay this much nearer the middle of the view. */
    private static final float SHOWCASE_CONE = 0.6F;
    /** Shortest time a showcase glitch stays (4 s). */
    private static final int SHOWCASE_MIN_DURATION = 80;

    private static final class Swap {
        final long pos;
        final BlockState original;
        final BlockState glitch;
        final long startTick;
        final long endTick;
        final int flickerPeriod;
        final long flickerSeed;
        final int group;
        BlockState expected;
        boolean applied;

        Swap(long pos, BlockState original, BlockState glitch, long startTick, long endTick, int flickerPeriod,
             long flickerSeed, int group) {
            this.pos = pos;
            this.original = original;
            this.glitch = glitch;
            this.startTick = startTick;
            this.endTick = endTick;
            this.flickerPeriod = flickerPeriod;
            this.flickerSeed = flickerSeed;
            this.group = group;
            this.expected = original;
        }
    }

    private static final List<Swap> SWAPS = new ArrayList<>();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static final int[] BROKEN_GROUPS = new int[MAX_SWAPS];
    private static ClientLevel level;
    private static long tick;
    private static int nextGroup;

    private ClientBlockGlitchManager() {
    }

    // ===================================================================== effects

    /** block.black: 1-4 natural blocks 12-40 blocks away turn pure black for a few seconds. */
    static boolean black(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        int count = 1 + Math.round(s.random.nextFloat() * 3.0F * Mth.clamp(0.4F + p.intensity(), 0.0F, 1.0F));
        List<BlockPos> hits = s.sample(count, 40, 12.0, 40.0, ClientBlockGlitchManager::natural);
        int group = nextGroup++;
        for (BlockPos pos : hits) {
            s.swap(pos, BlockGlitchPalette.black(), s.random.nextInt(8), s.duration(0.75F, 1.4F), 0, group);
        }
        return !hits.isEmpty();
    }

    /** block.missing: the magenta and black missing texture, here and there. */
    static boolean missing(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        int count = 1 + Math.round(s.random.nextFloat() * 5.0F * Mth.clamp(p.intensity(), 0.15F, 1.0F));
        List<BlockPos> hits = s.sample(count, 48, 8.0, 48.0, ClientBlockGlitchManager::natural);
        int group = nextGroup++;
        for (BlockPos pos : hits) {
            int flicker = s.strobe && s.random.nextFloat() < 0.3F ? 4 + s.random.nextInt(5) : 0;
            s.swap(pos, BlockGlitchPalette.missing(), s.random.nextInt(10), s.duration(0.7F, 1.3F), flicker, group);
        }
        return !hits.isEmpty();
    }

    /** block.error: little clusters of distorted error blocks. */
    static boolean error(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        int clusters = 1 + Math.round(s.random.nextFloat() * 2.0F * Mth.clamp(p.intensity(), 0.2F, 1.0F));
        List<BlockPos> centres = s.sample(clusters, 40, 8.0, 48.0, ClientBlockGlitchManager::exposedNatural);
        int placed = 0;
        for (BlockPos centre : centres) {
            int group = nextGroup++;
            List<BlockPos> cluster = s.surfaceCluster(centre, 1 + s.random.nextInt(4));
            for (BlockPos pos : cluster) {
                int flicker = s.strobe && s.random.nextFloat() < 0.4F ? 3 + s.random.nextInt(5) : 0;
                if (s.swap(pos, BlockGlitchPalette.error(s.random), s.random.nextInt(6), s.duration(0.8F, 1.3F), flicker, group)) {
                    placed++;
                }
            }
        }
        return placed > 0;
    }

    /** block.vanish: distant surface blocks disappear, and come back. */
    static boolean vanish(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        int count = 2 + Math.round(s.random.nextFloat() * 4.0F * Mth.clamp(p.intensity(), 0.2F, 1.0F));
        List<BlockPos> hits = s.sample(count, 48, 16.0, 48.0, ClientBlockGlitchManager::exposedNatural);
        int group = nextGroup++;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : hits) {
            int flicker = s.strobe ? 8 + s.random.nextInt(7) : 0;
            s.swap(pos, air, s.random.nextInt(12), s.duration(0.8F, 1.5F), flicker, group);
        }
        return !hits.isEmpty();
    }

    /** block.wrong: a few blocks show another vanilla block. */
    static boolean wrong(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        int clusters = 1 + s.random.nextInt(Math.max(1, Math.round(1.0F + 2.0F * p.intensity())));
        List<BlockPos> centres = s.sample(clusters, 36, 8.0, 40.0, ClientBlockGlitchManager::exposedNatural);
        int placed = 0;
        for (BlockPos centre : centres) {
            int group = nextGroup++;
            BlockState original = s.level.getBlockState(centre);
            BlockState replacement = BlockGlitchPalette.wrongFor(original, s.random);
            for (BlockPos pos : s.surfaceCluster(centre, 1 + s.random.nextInt(4))) {
                BlockState here = s.level.getBlockState(pos);
                BlockState shown = here.getBlock() == original.getBlock() ? replacement : BlockGlitchPalette.wrongFor(here, s.random);
                if (s.swap(pos, shown, 0, s.duration(0.9F, 1.2F), 0, group)) {
                    placed++;
                }
            }
        }
        return placed > 0;
    }

    /** block.tree: part of a tree shows the wrong textures. */
    static boolean tree(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        List<BlockPos> hits = s.sample(1, 48, 10.0, 40.0, ClientBlockGlitchManager::treePart);
        if (hits.isEmpty()) {
            return false;
        }
        int wanted = 6 + Math.round(s.random.nextFloat() * 14.0F * Mth.clamp(p.intensity() + 0.2F, 0.0F, 1.0F));
        List<BlockPos> part = s.flood(hits.get(0), wanted, 5, ClientBlockGlitchManager::treePart, 7.0);
        boolean error = s.random.nextFloat() < 0.3F;
        BlockState logAs = null;
        BlockState leavesAs = null;
        int group = nextGroup++;
        int placed = 0;
        for (BlockPos pos : part) {
            BlockState here = s.level.getBlockState(pos);
            BlockState shown;
            if (error) {
                shown = BlockGlitchPalette.error(s.random);
            } else if (here.is(BlockTags.LOGS)) {
                if (logAs == null) {
                    logAs = BlockGlitchPalette.wrongFor(here, s.random);
                }
                shown = BlockGlitchPalette.matchAxis(logAs, here);
            } else {
                if (leavesAs == null) {
                    leavesAs = BlockGlitchPalette.wrongFor(here, s.random);
                }
                shown = leavesAs;
            }
            if (s.swap(pos, shown, s.random.nextInt(4), s.duration(0.9F, 1.2F), 0, group)) {
                placed++;
            }
        }
        return placed > 0;
    }

    /** block.terrain_gap: a distant patch of terrain is simply not there. */
    static boolean terrainGap(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        List<BlockPos> hits = s.sample(1, 48, 20.0, 48.0, ClientBlockGlitchManager::exposedNatural);
        if (hits.isEmpty()) {
            return false;
        }
        BlockPos centre = hits.get(0);
        int radius = p.intensity() > 0.5F ? 2 : 1;
        int depth = 2 + s.random.nextInt(3);
        BlockState air = Blocks.AIR.defaultBlockState();
        int group = nextGroup++;
        int duration = s.duration(0.8F, 1.3F);
        int placed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                    continue;
                }
                int column = centre.getY();
                // Follow the surface of this column (it may be a block higher or lower).
                for (int up = 1; up >= -1; up--) {
                    m.set(centre.getX() + dx, centre.getY() + up, centre.getZ() + dz);
                    if (natural(m, s.level.getBlockState(m)) && s.level.getBlockState(m.above()).getCollisionShape(s.level, m.above()).isEmpty()) {
                        column = m.getY();
                        break;
                    }
                }
                BlockPos top = new BlockPos(centre.getX() + dx, column, centre.getZ() + dz);
                BlockState topState = s.level.getBlockState(top);
                if (!s.allowed(top, topState) || !natural(top, topState)) {
                    continue;
                }
                // Loose plants on top would float: they vanish with the ground.
                BlockPos abovePos = top.above();
                BlockState above = s.level.getBlockState(abovePos);
                if (!above.isAir() && above.canBeReplaced() && !above.hasBlockEntity() && above.getFluidState().isEmpty()) {
                    s.swapRaw(abovePos, above, air, 0, duration, group);
                }
                for (int d = 0; d < depth; d++) {
                    BlockPos pos = top.below(d);
                    BlockState state = s.level.getBlockState(pos);
                    if (s.allowed(pos, state) && natural(pos, state) && s.swap(pos, air, 0, duration, 0, group)) {
                        placed++;
                    }
                }
            }
        }
        return placed > 0;
    }

    /** block.structure_flicker: the blocks of a built structure flicker in and out. */
    static boolean structureFlicker(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        List<BlockPos> part = s.structure(10.0, 48.0, 48);
        if (part.isEmpty()) {
            return false;
        }
        BlockState shown = s.random.nextFloat() < 0.3F ? BlockGlitchPalette.missing() : Blocks.AIR.defaultBlockState();
        int period = s.strobe ? 2 + s.random.nextInt(3) : 10 + s.random.nextInt(7);
        int duration = s.duration(0.8F, 1.3F);
        long flickerSeed = s.random.nextLong();
        int group = nextGroup++;
        int placed = 0;
        for (BlockPos pos : part) {
            if (s.swapFlicker(pos, shown, duration, period, flickerSeed, group)) {
                placed++;
            }
        }
        return placed > 0;
    }

    /** block.structure_vanish: a nearby structure is briefly gone. */
    static boolean structureVanish(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        List<BlockPos> part = s.structure(10.0, 40.0, MAX_SWAPS);
        if (part.isEmpty()) {
            return false;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int duration = s.duration(0.8F, 1.2F);
        int group = nextGroup++;
        int placed = 0;
        for (BlockPos pos : part) {
            if (s.swap(pos, air, 0, duration, 0, group)) {
                placed++;
            }
        }
        return placed > 0;
    }

    /** block.displace: surface blocks appear shifted by one block. */
    static boolean displace(ClientEffectPayload p) {
        Scene s = scene(p);
        if (s == null) {
            return false;
        }
        List<BlockPos> centres = s.sample(1 + (p.intensity() > 0.6F ? 1 : 0), 40, 10.0, 40.0, ClientBlockGlitchManager::exposedNatural);
        BlockState air = Blocks.AIR.defaultBlockState();
        int placed = 0;
        for (BlockPos centre : centres) {
            Direction dir = s.random.nextFloat() < 0.3F ? Direction.UP : Direction.Plane.HORIZONTAL.getRandomDirection(s.random);
            int duration = s.duration(0.8F, 1.3F);
            for (BlockPos pos : s.surfaceCluster(centre, 2 + s.random.nextInt(4))) {
                BlockPos target = pos.relative(dir);
                BlockState state = s.level.getBlockState(pos);
                BlockState there = s.level.getBlockState(target);
                if (!there.isAir() || swappedAt(target.asLong()) || !s.clearOfEntities(target)) {
                    continue;
                }
                if (s.capacity() < 2) {
                    break;
                }
                // Negative groups are linked: both halves of a displacement live and die together.
                int group = -1 - (nextGroup++ & 0x3FFFFFFF);
                if (s.swap(pos, air, 0, duration, 0, group)) {
                    s.swapRaw(target, there, state, 0, duration, group);
                    placed++;
                }
            }
        }
        return placed > 0;
    }

    // ===================================================================== lifecycle

    static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        if (current != level) {
            // The world these swaps lived in is gone (dimension change or logout).
            SWAPS.clear();
            level = current;
        }
        if (current == null || SWAPS.isEmpty() || mc.isPaused()) {
            return;
        }
        tick++;
        LocalPlayer player = mc.player;
        int broken = 0;
        for (int i = SWAPS.size() - 1; i >= 0; i--) {
            Swap s = SWAPS.get(i);
            CURSOR.set(s.pos);
            if (!current.isLoaded(CURSOR)) {
                SWAPS.remove(i);
                continue;
            }
            BlockState now = current.getBlockState(CURSOR);
            boolean close = player != null && player.distanceToSqr(CURSOR.getX() + 0.5, CURSOR.getY() + 0.5, CURSOR.getZ() + 0.5) < RELEASE_DISTANCE_SQ;
            if (!s.applied) {
                if (tick < s.startTick) {
                    continue;
                }
                if (now != s.original || close) {
                    if (s.group < 0 && broken < BROKEN_GROUPS.length) {
                        BROKEN_GROUPS[broken++] = s.group;
                    }
                    SWAPS.remove(i);
                    continue;
                }
                write(current, s.glitch);
                s.expected = s.glitch;
                s.applied = true;
                continue;
            }
            if (now != s.expected) {
                // The server changed this block: it is real now, leave it alone.
                if (s.group < 0 && broken < BROKEN_GROUPS.length) {
                    BROKEN_GROUPS[broken++] = s.group;
                }
                SWAPS.remove(i);
                continue;
            }
            if (tick >= s.endTick || close) {
                if (now != s.original) {
                    write(current, s.original);
                }
                SWAPS.remove(i);
                continue;
            }
            if (s.flickerPeriod > 0) {
                long phase = (tick - s.startTick) / s.flickerPeriod;
                boolean on = phase == 0 || SmoothNoise.hash01(s.flickerSeed, phase) < 0.6F;
                BlockState want = on ? s.glitch : s.original;
                if (want != s.expected) {
                    write(current, want);
                    s.expected = want;
                }
            }
        }
        // A displaced block whose other half was overwritten makes no sense on its own.
        for (int b = 0; b < broken; b++) {
            restoreGroup(current, BROKEN_GROUPS[b]);
        }
    }

    /** Puts back everything that is still showing a glitch (if that world still exists). */
    public static void restoreAll() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != null && current == level) {
            for (Swap s : SWAPS) {
                if (!s.applied || s.expected == s.original) {
                    continue;
                }
                CURSOR.set(s.pos);
                if (current.isLoaded(CURSOR) && current.getBlockState(CURSOR) == s.expected) {
                    write(current, s.original);
                }
            }
        }
        SWAPS.clear();
    }

    /** Logout: the world is discarded anyway, so the records are simply dropped. */
    static void forgetAll() {
        SWAPS.clear();
        level = null;
    }

    /** Chunk unloaded on the client: its data is gone, so are our records for it. */
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide() || event.getLevel() != level || SWAPS.isEmpty()) {
            return;
        }
        ChunkPos chunk = event.getChunk().getPos();
        SWAPS.removeIf(s -> (BlockPos.getX(s.pos) >> 4) == chunk.x && (BlockPos.getZ(s.pos) >> 4) == chunk.z);
    }

    // ===================================================================== internals

    private static void restoreGroup(ClientLevel current, int group) {
        for (int i = SWAPS.size() - 1; i >= 0; i--) {
            Swap s = SWAPS.get(i);
            if (s.group != group) {
                continue;
            }
            CURSOR.set(s.pos);
            if (s.applied && current.isLoaded(CURSOR) && current.getBlockState(CURSOR) == s.expected && s.expected != s.original) {
                write(current, s.original);
            }
            SWAPS.remove(i);
        }
    }

    private static void write(ClientLevel target, BlockState state) {
        target.setBlock(CURSOR, state, FLAGS);
    }

    private static boolean swappedAt(long pos) {
        for (Swap s : SWAPS) {
            if (s.pos == pos) {
                return true;
            }
        }
        return false;
    }

    private static boolean natural(BlockPos pos, BlockState state) {
        return state.is(BuildProtection.CORRUPTIBLE) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
    }

    private static boolean exposedNatural(BlockPos pos, BlockState state) {
        if (!natural(pos, state)) {
            return false;
        }
        ClientLevel current = Minecraft.getInstance().level;
        return current != null && current.getBlockState(pos.above()).isAir();
    }

    private static boolean treePart(BlockPos pos, BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    /** A block only built things are made of (planks, cobblestone, bricks, glass, wool...). */
    private static boolean built(BlockPos pos, BlockState state) {
        return state.is(BuildProtection.BUILD_INDICATORS) && !state.is(BuildProtection.CORRUPTIBLE);
    }

    private static Scene scene(ClientEffectPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ClientLimits.maxVisualEffects() <= 0) {
            return null;
        }
        if (level != mc.level) {
            SWAPS.clear();
            level = mc.level;
        }
        if (SWAPS.size() >= MAX_SWAPS) {
            return null;
        }
        return new Scene(mc, mc.level, mc.player, p);
    }

    /** Everything one effect needs while it picks its blocks. */
    private static final class Scene {
        final Minecraft mc;
        final ClientLevel level;
        final LocalPlayer player;
        final ClientEffectPayload payload;
        final RandomSource random;
        final boolean strobe;
        final Vec3 eye;
        /** Sent by the showcase: pick blocks close in front of the player. */
        final boolean showcase;

        Scene(Minecraft mc, ClientLevel level, LocalPlayer player, ClientEffectPayload payload) {
            this.mc = mc;
            this.level = level;
            this.player = player;
            this.payload = payload;
            this.random = RandomSource.create(payload.seed());
            this.strobe = ClientLimits.intenseAllowed() && ClientLimits.flashing() >= 0.6F;
            this.eye = player.getEyePosition();
            this.showcase = (payload.data() & EventContext.SHOWCASE_BLOCK_DATA) != 0;
        }

        int capacity() {
            return MAX_SWAPS - SWAPS.size();
        }

        int duration(float min, float max) {
            float base = Math.max(20, payload.duration());
            int ticks = Math.max(10, Math.round(base * (min + random.nextFloat() * (max - min))));
            return showcase ? Math.max(SHOWCASE_MIN_DURATION, ticks) : ticks;
        }

        /** The common rules every swapped block obeys. */
        boolean allowed(BlockPos pos, BlockState state) {
            if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getLightEmission() > 0
                    || state.getRenderShape() != RenderShape.MODEL || !state.isCollisionShapeFullBlock(level, pos)) {
                return false;
            }
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < MIN_PLAYER_DISTANCE_SQ) {
                return false;
            }
            return !swappedAt(pos.asLong()) && clearOfEntities(pos);
        }

        /** Nothing stands on, in or right above this block. */
        boolean clearOfEntities(BlockPos pos) {
            AABB box = new AABB(pos.getX() - 0.35, pos.getY(), pos.getZ() - 0.35, pos.getX() + 1.35, pos.getY() + 2.6, pos.getZ() + 1.35);
            return level.getEntities((Entity) null, box).isEmpty();
        }

        /**
         * Distinct blocks hit by rays cast through the view, between the two distances (for the
         * showcase: close in front, see {@link #SHOWCASE_MIN_DISTANCE}).
         */
        List<BlockPos> sample(int wanted, int rays, double minDistance, double maxDistance, BiPredicate<BlockPos, BlockState> filter) {
            List<BlockPos> out = new ArrayList<>();
            if (wanted <= 0) {
                return out;
            }
            if (showcase) {
                minDistance = SHOWCASE_MIN_DISTANCE;
                maxDistance = Math.min(maxDistance, SHOWCASE_MAX_DISTANCE);
                rays *= 2;
            }
            payload.pos().ifPresent(hint -> {
                if (level.isLoaded(hint)) {
                    BlockState state = level.getBlockState(hint);
                    if (allowed(hint, state) && filter.test(hint, state)) {
                        out.add(hint.immutable());
                    }
                }
            });
            float fov = mc.options.fov().get().floatValue();
            float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
            float halfV = fov * 0.4F * (showcase ? SHOWCASE_CONE : 1.0F);
            float halfH = Math.min(70.0F, halfV * aspect);
            for (int i = 0; i < rays && out.size() < Math.min(wanted, capacity()); i++) {
                float yaw = player.getYRot() + (random.nextFloat() * 2.0F - 1.0F) * halfH;
                // Slightly favour the ground: that is where most of the visible world is.
                float pitch = Mth.clamp(player.getXRot() + (random.nextFloat() * 2.0F - 1.0F) * halfV + 5.0F, -89.0F, 89.0F);
                Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
                Vec3 end = eye.add(dir.scale(maxDistance));
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
                if (hit.getType() != HitResult.Type.BLOCK) {
                    continue;
                }
                BlockPos pos = hit.getBlockPos();
                double distSq = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distSq < minDistance * minDistance || distSq > (maxDistance + 1.0) * (maxDistance + 1.0)) {
                    continue;
                }
                if (out.contains(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (allowed(pos, state) && filter.test(pos, state)) {
                    out.add(pos.immutable());
                }
            }
            return out;
        }

        /** The block plus up to {@code extra} exposed natural neighbours on the same surface. */
        List<BlockPos> surfaceCluster(BlockPos centre, int extra) {
            List<BlockPos> out = new ArrayList<>();
            out.add(centre);
            for (int i = 0; i < 12 && out.size() <= extra; i++) {
                BlockPos from = out.get(random.nextInt(out.size()));
                BlockPos next = from.relative(Direction.Plane.HORIZONTAL.getRandomDirection(random), 1);
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos candidate = next.above(dy);
                    BlockState state = level.getBlockState(candidate);
                    if (!out.contains(candidate) && allowed(candidate, state) && exposedNatural(candidate, state)) {
                        out.add(candidate);
                        break;
                    }
                }
            }
            return out;
        }

        /** Connected blocks matching {@code filter} around {@code start}, breadth first. */
        List<BlockPos> flood(BlockPos start, int max, int radius, BiPredicate<BlockPos, BlockState> filter, double minDistance) {
            List<BlockPos> out = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            List<BlockPos> seen = new ArrayList<>();
            queue.add(start);
            seen.add(start);
            int limit = Math.min(max, capacity());
            while (!queue.isEmpty() && out.size() < limit && seen.size() < 400) {
                BlockPos pos = queue.poll();
                BlockState state = level.getBlockState(pos);
                if (!filter.test(pos, state)) {
                    continue;
                }
                if (allowed(pos, state)
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) >= minDistance * minDistance) {
                    out.add(pos);
                }
                for (Direction direction : Direction.values()) {
                    BlockPos next = pos.relative(direction);
                    if (Math.abs(next.getX() - start.getX()) > radius || Math.abs(next.getY() - start.getY()) > radius
                            || Math.abs(next.getZ() - start.getZ()) > radius || seen.contains(next) || !level.isLoaded(next)) {
                        continue;
                    }
                    seen.add(next);
                    queue.add(next);
                }
            }
            return out;
        }

        /** A cluster of built-looking blocks seen in the view, or an empty list. */
        List<BlockPos> structure(double minDistance, double maxDistance, int max) {
            List<BlockPos> hits = sample(3, 56, minDistance, maxDistance, ClientBlockGlitchManager::built);
            for (BlockPos hit : hits) {
                List<BlockPos> part = flood(hit, max, 6, ClientBlockGlitchManager::built, 7.0);
                if (part.size() >= 6) {
                    return part;
                }
            }
            return List.of();
        }

        boolean swap(BlockPos pos, BlockState glitch, int delay, int duration, int flickerPeriod, int group) {
            BlockState original = level.getBlockState(pos);
            if (!allowed(pos, original) || original == glitch) {
                return false;
            }
            return add(pos, original, glitch, delay, duration, flickerPeriod, random.nextLong(), group);
        }

        boolean swapFlicker(BlockPos pos, BlockState glitch, int duration, int period, long seed, int group) {
            BlockState original = level.getBlockState(pos);
            if (!allowed(pos, original) || original == glitch) {
                return false;
            }
            return add(pos, original, glitch, 0, duration, period, seed, group);
        }

        /** For the second half of a displacement or plants: the target was checked by the caller. */
        boolean swapRaw(BlockPos pos, BlockState original, BlockState glitch, int delay, int duration, int group) {
            if (swappedAt(pos.asLong()) || original == glitch) {
                return false;
            }
            return add(pos, original, glitch, delay, duration, 0, 0L, group);
        }

        private boolean add(BlockPos pos, BlockState original, BlockState glitch, int delay, int duration, int flickerPeriod,
                            long flickerSeed, int group) {
            if (SWAPS.size() >= MAX_SWAPS) {
                return false;
            }
            long start = tick + 1 + Math.max(0, delay);
            SWAPS.add(new Swap(pos.asLong(), original, glitch, start, start + Math.max(1, duration), flickerPeriod, flickerSeed, group));
            return true;
        }
    }
}
