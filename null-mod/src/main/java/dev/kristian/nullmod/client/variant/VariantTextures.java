package dev.kristian.nullmod.client.variant;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.variant.MobVariant;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Corrupted and infested copies of mob textures, made at run time from each mob's own texture
 * (read through the resource manager, or copied from a dynamic texture) by
 * {@link VariantPixels}, so NULL never ships anybody's art and modded mobs work too.
 * <p>
 * The pixel work runs on Minecraft's background executor; the render thread uploads at most one
 * finished texture per frame. Textures are cached per (texture, style) and shared by every mob
 * that looks the same, the cache is capped (least recently drawn first out), and everything is
 * released on resource reload and logout. A texture that cannot be read (atlas sprites, missing
 * files, huge images) gets a generic overlay instead. Texture locations are recycled, so neither
 * the texture manager nor vanilla's per-texture render type caches grow over a long session.
 */
final class VariantTextures {
    static final ResourceLocation TENTACLE = texture("tentacle");
    static final ResourceLocation EYE = texture("eye");
    static final ResourceLocation FALLBACK_CORRUPTED = texture("fallback_corrupted");
    static final ResourceLocation FALLBACK_INFESTED = texture("fallback_infested");

    static final int KIND_CORRUPTED = 0;
    static final int KIND_INFESTED = 1;
    private static final int MAX_ENTRIES = 96;
    private static final int MAX_SIZE = 512;
    private static final int MAX_RUNNING = 2;
    /** Dynamic textures larger than this are not copied on the render thread. */
    private static final int MAX_DYNAMIC_PIXELS = 256 * 256;

    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;
    private static final int RELEASED = 4;

    /** What a generated texture is made from. */
    record Key(ResourceLocation texture, int kind, int a, int b, long anchors) {
    }

    /** One generated texture (with its glow, for the eyes style), or the attempt to make it. */
    static final class Entry {
        final Key key;
        final int generation;
        @Nullable
        final float[] anchorUv;
        final int anchorCount;
        int state = QUEUED;
        long lastUsed;
        @Nullable
        ResourceLocation location;
        @Nullable
        ResourceLocation glowLocation;
        @Nullable
        private DynamicTexture texture;
        @Nullable
        private DynamicTexture glowTexture;
        // Filled by the worker; read on the render thread after it is handed over.
        @Nullable
        private int[] source;
        private int sourceWidth;
        private int sourceHeight;
        @Nullable
        private int[] pixels;
        @Nullable
        private int[] glow;
        private int width;
        private int height;

        Entry(Key key, int generation, @Nullable float[] anchorUv, int anchorCount) {
            this.key = key;
            this.generation = generation;
            this.anchorUv = anchorUv;
            this.anchorCount = anchorCount;
        }

        boolean usable() {
            return (state == READY || state == FAILED) && generation == VariantTextures.generation;
        }

        /** What to draw: the generated overlay, the generic one if it failed, or null while it is being made. */
        @Nullable
        ResourceLocation overlay() {
            if (generation != VariantTextures.generation) {
                return null;
            }
            if (state == READY) {
                return location;
            }
            if (state == FAILED) {
                return key.kind() == KIND_INFESTED ? FALLBACK_INFESTED : FALLBACK_CORRUPTED;
            }
            return null;
        }

        @Nullable
        ResourceLocation glow() {
            return state == READY && generation == VariantTextures.generation ? glowLocation : null;
        }
    }

    private static final Map<Key, Entry> ENTRIES = new HashMap<>();
    private static final ArrayDeque<Entry> WAITING = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Entry> DONE = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<ResourceLocation> FREE_LOCATIONS = new ArrayDeque<>();
    private static int running;
    private static int generation;
    private static long frame;
    private static int serial;

    private VariantTextures() {
    }

    private static ResourceLocation texture(String name) {
        return NullMod.id("textures/entity/variant/" + name + ".png");
    }

    static long frame() {
        return frame;
    }

    /**
     * The overlay to draw on a variant mob whose own texture is {@code texture}: its corrupted
     * or infested copy, the generic one if the texture cannot be read, or null while it is
     * still being made (a mob keeps showing its previous overlay until the new one is ready).
     */
    @Nullable
    static Entry overlay(MobVisual visual, ResourceLocation texture) {
        boolean parasite = visual.variant == MobVariant.PARASITE;
        int stage = parasite ? visual.infestStage() : 0;
        long anchors = parasite ? visual.anchorsHash : 0L;
        Entry wanted = visual.overlay;
        if (wanted == null || wanted.state == RELEASED || wanted.generation != generation || visual.overlayStage != stage
                || visual.overlayAnchors != anchors || !texture.equals(visual.overlaySource)) {
            Key key = parasite ? new Key(texture, KIND_INFESTED, visual.layout, stage, anchors)
                    : new Key(texture, KIND_CORRUPTED, visual.primary, visual.secondary, 0L);
            Entry fresh = request(key, parasite ? visual.anchorUv : null, parasite ? visual.anchorCount : 0);
            visual.overlaySource = texture;
            visual.overlayStage = stage;
            visual.overlayAnchors = anchors;
            if (fresh.usable() || wanted == null || !wanted.usable()) {
                visual.overlay = fresh;
                wanted = fresh;
            } else {
                // Keep drawing the old one until the new one is ready.
                visual.overlayPending = fresh;
            }
        }
        Entry pending = visual.overlayPending;
        if (pending != null) {
            pending.lastUsed = frame;
            if (pending.state == RELEASED || pending.generation != generation) {
                visual.overlayPending = null;
            } else if (pending.usable()) {
                visual.overlay = pending;
                visual.overlayPending = null;
                wanted = pending;
            }
        }
        wanted.lastUsed = frame;
        return wanted;
    }

    private static Entry request(Key key, @Nullable float[] anchorUv, int anchorCount) {
        Entry entry = ENTRIES.get(key);
        if (entry != null) {
            return entry;
        }
        evictIfFull();
        float[] uv = anchorUv == null || anchorCount <= 0 ? null : Arrays.copyOf(anchorUv, anchorCount * 2);
        entry = new Entry(key, generation, uv, uv == null ? 0 : anchorCount);
        entry.lastUsed = frame;
        ENTRIES.put(key, entry);
        WAITING.add(entry);
        startJobs();
        return entry;
    }

    /** Called once per frame (render thread): hands one finished texture to the GPU, starts waiting work. */
    static void onRenderFrame() {
        frame++;
        Entry done = DONE.poll();
        if (done != null) {
            finish(done);
        }
        startJobs();
    }

    private static void startJobs() {
        while (running < MAX_RUNNING && !WAITING.isEmpty()) {
            Entry entry = WAITING.poll();
            if (entry.state != QUEUED || entry.generation != generation) {
                continue;
            }
            Minecraft mc = Minecraft.getInstance();
            copyDynamicSource(mc, entry);
            ResourceManager manager = mc.getResourceManager();
            entry.state = RUNNING;
            running++;
            try {
                Util.backgroundExecutor().execute(() -> work(entry, manager));
            } catch (RejectedExecutionException e) {
                work(entry, manager);
            }
        }
    }

    /** A mob whose texture is generated in game (not a file) is read from the texture itself. */
    private static void copyDynamicSource(Minecraft mc, Entry entry) {
        AbstractTexture existing = mc.getTextureManager().getTexture(entry.key.texture(), null);
        if (!(existing instanceof DynamicTexture dynamic)) {
            return;
        }
        NativeImage image = dynamic.getPixels();
        if (image == null || image.format() != NativeImage.Format.RGBA) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 4 || h < 4 || w * h > MAX_DYNAMIC_PIXELS) {
            return;
        }
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = abgrToArgb(image.getPixelRGBA(x, y));
            }
        }
        entry.source = argb;
        entry.sourceWidth = w;
        entry.sourceHeight = h;
    }

    /** Worker thread: reads the original and builds the overlay. Always hands the entry back. */
    private static void work(Entry entry, ResourceManager manager) {
        try {
            int[] src = entry.source;
            int w = entry.sourceWidth;
            int h = entry.sourceHeight;
            if (src == null) {
                Optional<Resource> resource = manager.getResource(entry.key.texture());
                if (resource.isEmpty()) {
                    return;
                }
                try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in)) {
                    w = image.getWidth();
                    h = image.getHeight();
                    if (w < 4 || h < 4 || w > MAX_SIZE || h > MAX_SIZE) {
                        return;
                    }
                    src = new int[w * h];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            src[y * w + x] = abgrToArgb(image.getPixelRGBA(x, y));
                        }
                    }
                }
            }
            Key key = entry.key;
            long seed = seedFor(key);
            if (key.kind() == KIND_CORRUPTED) {
                VariantPixels.Corrupted corrupted = VariantPixels.corrupt(src, w, h, key.a(), key.b(), seed);
                entry.glow = corrupted.glow();
                entry.pixels = corrupted.overlay();
            } else {
                int n = entry.anchorCount;
                int[] anchors = new int[n * 2];
                float[] uv = entry.anchorUv;
                for (int i = 0; i < n && uv != null; i++) {
                    anchors[i * 2] = Math.floorMod((int) Math.floor(uv[i * 2] * w), w);
                    anchors[i * 2 + 1] = Math.floorMod((int) Math.floor(uv[i * 2 + 1] * h), h);
                }
                entry.pixels = VariantPixels.infest(src, w, h, anchors, n, key.b(), seed);
            }
            entry.width = w;
            entry.height = h;
        } catch (Exception e) {
            NullMod.LOGGER.debug("NULL could not corrupt the texture {}; it gets a generic look", entry.key.texture(), e);
            entry.pixels = null;
        } finally {
            entry.source = null;
            DONE.add(entry);
        }
    }

    /** Render thread: uploads a finished overlay (or marks it failed). */
    private static void finish(Entry entry) {
        if (entry.generation != generation) {
            // Made for textures that have been released since: nothing to do.
            return;
        }
        running = Math.max(0, running - 1);
        if (entry.state != RUNNING) {
            entry.pixels = null;
            entry.glow = null;
            return;
        }
        int[] pixels = entry.pixels;
        int[] glow = entry.glow;
        entry.pixels = null;
        entry.glow = null;
        if (pixels == null) {
            entry.state = FAILED;
            return;
        }
        try {
            entry.texture = upload(pixels, entry.width, entry.height);
            entry.location = takeLocation(entry.texture);
            if (glow != null) {
                entry.glowTexture = upload(glow, entry.width, entry.height);
                entry.glowLocation = takeLocation(entry.glowTexture);
            }
            entry.state = READY;
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("NULL could not upload a corrupted mob texture", e);
            release(entry);
            entry.state = FAILED;
        }
    }

    private static DynamicTexture upload(int[] argb, int w, int h) {
        NativeImage image = new NativeImage(w, h, true);
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    image.setPixelRGBA(x, y, argbToAbgr(argb[y * w + x]));
                }
            }
            return new DynamicTexture(image);
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
    }

    /** Registers a texture under a recycled location (replacing the closed texture that was there). */
    private static ResourceLocation takeLocation(DynamicTexture texture) {
        ResourceLocation location = FREE_LOCATIONS.poll();
        if (location == null) {
            location = NullMod.id("variant/overlay_" + serial++);
        }
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }

    /** Frees an entry's GPU and native memory and gives its locations back. */
    private static void release(Entry entry) {
        if (entry.texture != null) {
            entry.texture.close();
            entry.texture = null;
        }
        if (entry.glowTexture != null) {
            entry.glowTexture.close();
            entry.glowTexture = null;
        }
        if (entry.location != null) {
            FREE_LOCATIONS.add(entry.location);
            entry.location = null;
        }
        if (entry.glowLocation != null) {
            FREE_LOCATIONS.add(entry.glowLocation);
            entry.glowLocation = null;
        }
        entry.state = RELEASED;
    }

    /** Over the cap: the least recently drawn finished texture goes. */
    private static void evictIfFull() {
        if (ENTRIES.size() < MAX_ENTRIES) {
            return;
        }
        Entry oldest = null;
        for (Entry entry : ENTRIES.values()) {
            if ((entry.state == READY || entry.state == FAILED) && entry.lastUsed < frame
                    && (oldest == null || entry.lastUsed < oldest.lastUsed)) {
                oldest = entry;
            }
        }
        if (oldest != null) {
            ENTRIES.remove(oldest.key);
            release(oldest);
        }
    }

    /** Releases every generated texture (resource reload, logout); mobs make new ones as they are drawn. */
    static void releaseAll() {
        generation++;
        for (Iterator<Entry> it = ENTRIES.values().iterator(); it.hasNext(); ) {
            release(it.next());
            it.remove();
        }
        WAITING.clear();
        DONE.clear();
        running = 0;
    }

    /** The same texture and style always break the same way, in every world and every session. */
    private static long seedFor(Key key) {
        long h = key.texture().hashCode() * 0x9E3779B97F4A7C15L;
        h ^= key.kind() * 0xC2B2AE3D27D4EB4FL;
        h ^= (key.a() + 1L) * 0x165667B19E3779F9L;
        h ^= (key.b() + 1L) * 0x27D4EB2F165667C5L;
        h ^= key.anchors();
        return Tentacles.mix(h);
    }

    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >>> 16) & 0xFF);
    }

    private static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
    }
}
