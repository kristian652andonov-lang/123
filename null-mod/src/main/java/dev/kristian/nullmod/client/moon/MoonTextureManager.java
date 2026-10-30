package dev.kristian.nullmod.client.moon;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.moon.MoonGeometry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;

/**
 * Owns the damaged moon. Once the moon has started to break, the active resource pack's moon
 * phase sheet is read through the resource manager, the damage for the current progress is
 * drawn into a copy, and that copy is registered as a {@link DynamicTexture} under vanilla's
 * own moon location, so the sky renders it exactly like the real moon (with any resource pack
 * and most shader packs). Redrawn only when the damage visibly changes; the original texture
 * is put back when the progress returns to zero, outside the overworld and on logout, and
 * everything is rebuilt from the new pack after a resource reload.
 * <p>
 * Besides the moon itself it keeps three private textures (bound by id, never registered):
 * an untouched copy for the drifting fragments, a damaged copy for warp effects while the real
 * moon is hidden, and a glow overlay for crack flashes. Render thread only.
 */
public final class MoonTextureManager {
    /** Vanilla's moon phase sheet (LevelRenderer.MOON_LOCATION is private). */
    public static final ResourceLocation MOON = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    /** Larger moon sheets are scaled down to this width first; the moon covers few pixels on screen. */
    private static final int MAX_WIDTH = 1024;

    static final ResourceManagerReloadListener RELOAD_LISTENER = manager -> onResourcesReloaded();

    @Nullable
    private static MoonArt art;
    @Nullable
    private static DynamicTexture moon;
    @Nullable
    private static DynamicTexture copy;
    @Nullable
    private static DynamicTexture pristine;
    @Nullable
    private static DynamicTexture glow;
    private static int[] work = new int[0];
    private static int[] glowPixels = new int[0];
    private static int pristineWidth;
    private static int pristineHeight;
    private static boolean registered;
    private static boolean baseDirty = true;
    private static boolean loadFailed;
    private static long artSeed;
    private static long lastKey = Long.MIN_VALUE;
    private static boolean hiddenUploaded;

    private MoonTextureManager() {
    }

    // ------------------------------------------------------------------ state for the renderer

    /** True while NULL's moon is in place (the renderer may draw fragments and effects). */
    public static boolean active() {
        return registered && art != null;
    }

    @Nullable
    static MoonArt art() {
        return art;
    }

    /** Texture id of the untouched moon sheet, or -1. */
    static int pristineId() {
        return pristine == null ? -1 : pristine.getId();
    }

    /** Texture id of the damaged moon (same pixels as the real one, even while that is hidden), or -1. */
    static int copyId() {
        return copy == null ? -1 : copy.getId();
    }

    /** Texture id of the current crack-flash overlay (one phase cell), or -1. */
    static int glowId() {
        return glow == null ? -1 : glow.getId();
    }

    /** The moon as players should see it: gone once the server says so. */
    static boolean gone() {
        return ClientCorruptionState.moonGone();
    }

    /** True while the real moon texture is blacked out so an effect can draw the moon itself. */
    static boolean moonHidden() {
        return registered && hiddenUploaded;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called every client tick. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean wanted = mc.level != null
                && ClientCorruptionState.synced()
                && mc.level.dimension() == Level.OVERWORLD
                && (ClientCorruptionState.moonProgress() > 0.0F || ClientCorruptionState.moonGone() || MoonEffects.needsTextures());
        if (!wanted) {
            if (registered) {
                restore();
            }
            return;
        }
        long seed = ClientCorruptionState.seed();
        if (art == null || baseDirty || artSeed != seed) {
            if (loadFailed && !baseDirty && artSeed == seed) {
                return;
            }
            if (!rebuild(mc, seed)) {
                return;
            }
        }
        refresh();
    }

    /** Puts the pack's own moon back and frees everything. Safe to call at any time. */
    public static void restore() {
        if (registered) {
            try {
                // Registering a fresh SimpleTexture loads the pack's moon and closes ours.
                Minecraft.getInstance().getTextureManager().register(MOON, new SimpleTexture(MOON));
            } catch (RuntimeException e) {
                NullMod.LOGGER.warn("Could not restore the moon texture", e);
            }
            registered = false;
        }
        moon = null;
        closePrivate();
        art = null;
        lastKey = Long.MIN_VALUE;
        hiddenUploaded = false;
        loadFailed = false;
    }

    static void onResourcesReloaded() {
        baseDirty = true;
        loadFailed = false;
    }

    /** Re-uploads on the next tick, e.g. after an effect started or stopped hiding the moon. */
    static void invalidate() {
        lastKey = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------ drawing

    private static void refresh() {
        MoonArt current = art;
        DynamicTexture target = moon;
        if (current == null || target == null) {
            return;
        }
        float progress = ClientCorruptionState.moonProgress();
        boolean gone = gone();
        boolean hide = MoonEffects.hidesMoon();
        long key = stateKey(current.geometry(), progress, gone);
        if (key == lastKey && hide == hiddenUploaded) {
            return;
        }
        boolean changed = key != lastKey;
        lastKey = key;
        if (changed) {
            if (gone) {
                current.renderGone(work);
            } else {
                current.render(progress, work);
            }
            if (copy != null) {
                upload(copy, work, current.width(), current.height());
            }
        }
        if (hide) {
            if (!hiddenUploaded || changed) {
                fillBlack(target);
            }
        } else {
            upload(target, work, current.width(), current.height());
        }
        hiddenUploaded = hide;
    }

    /** Draws the glow overlay for a crack flash into the glow texture; returns false if unavailable. */
    static boolean prepareGlow(@Nullable int[] cracks, float progress, int phase) {
        MoonArt current = art;
        DynamicTexture texture = glow;
        if (current == null || texture == null) {
            return false;
        }
        current.renderGlow(cracks, progress, phase, glowPixels);
        upload(texture, glowPixels, current.cellWidth(), current.cellHeight());
        return true;
    }

    /**
     * A signature of everything visible at a progress value: redraws happen only when a crack
     * grows, a piece breaks, the distortion or shattering steps, or every 0.25 % of progress.
     */
    private static long stateKey(MoonGeometry geometry, float progress, boolean gone) {
        if (gone) {
            return -2L;
        }
        int appeared = 0;
        int grown = 0;
        for (int i = 0; i < geometry.crackCount(); i++) {
            MoonGeometry.Crack crack = geometry.crack(i);
            if (crack.appearAt() > progress) {
                break;
            }
            appeared++;
            if (crack.fullAt() <= progress) {
                grown++;
            }
        }
        int holes = 0;
        for (int k = 0; k < geometry.holeCount(); k++) {
            if (geometry.shard(geometry.hole(k)).breakAt() <= progress) {
                holes++;
            } else {
                break;
            }
        }
        long bucket = (long) (progress * 400.0F);
        return ((bucket * 1009L + appeared) * 1013L + grown) * 1019L + holes;
    }

    private static boolean rebuild(Minecraft mc, long seed) {
        baseDirty = false;
        artSeed = seed;
        int[] base;
        int width;
        int height;
        ResourceManager resources = mc.getResourceManager();
        Optional<Resource> resource = resources.getResource(MOON);
        if (resource.isEmpty()) {
            loadFailed = true;
            return false;
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth();
            int h = image.getHeight();
            if (w < 8 || h < 4) {
                loadFailed = true;
                return false;
            }
            int step = 1;
            while (w / step > MAX_WIDTH) {
                step++;
            }
            width = w / step;
            height = h / step;
            width -= width % MoonArt.COLUMNS;
            height -= height % MoonArt.ROWS;
            base = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    base[y * width + x] = image.getPixelRGBA(x * step, y * step);
                }
            }
        } catch (IOException | RuntimeException e) {
            NullMod.LOGGER.warn("NULL could not read the moon texture; the moon stays intact", e);
            loadFailed = true;
            return false;
        }

        MoonArt built = new MoonArt(base, width, height, MoonGeometry.forSeed(seed));
        TextureManager textures = mc.getTextureManager();
        closePrivate();
        DynamicTexture main = blank(built.width(), built.height());
        copy = blank(built.width(), built.height());
        glow = blank(built.cellWidth(), built.cellHeight());
        pristine = blank(width, height);
        upload(pristine, base, width, height);
        pristineWidth = width;
        pristineHeight = height;
        work = new int[built.width() * built.height()];
        glowPixels = new int[built.cellWidth() * built.cellHeight()];
        // Registering replaces (and closes) the texture that was there, including our previous one.
        textures.register(MOON, main);
        moon = main;
        registered = true;
        art = built;
        lastKey = Long.MIN_VALUE;
        hiddenUploaded = false;
        loadFailed = false;
        return true;
    }

    private static DynamicTexture blank(int width, int height) {
        return new DynamicTexture(new NativeImage(width, height, true));
    }

    private static void upload(DynamicTexture texture, int[] pixels, int width, int height) {
        NativeImage image = texture.getPixels();
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            return;
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, pixels[row + x]);
            }
        }
        texture.upload();
    }

    private static void fillBlack(DynamicTexture texture) {
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        image.fillRect(0, 0, image.getWidth(), image.getHeight(), 0xFF000000);
        texture.upload();
    }

    private static void closePrivate() {
        if (copy != null) {
            copy.close();
            copy = null;
        }
        if (pristine != null) {
            pristine.close();
            pristine = null;
        }
        if (glow != null) {
            glow.close();
            glow = null;
        }
        work = new int[0];
        glowPixels = new int[0];
    }

    static int pristineWidth() {
        return pristineWidth;
    }

    static int pristineHeight() {
        return pristineHeight;
    }
}
