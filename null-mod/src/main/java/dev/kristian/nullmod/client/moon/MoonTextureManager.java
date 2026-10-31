package dev.kristian.nullmod.client.moon;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.moon.MoonArt;
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
 * phase sheet is read through the resource manager, {@link MoonArt} finds the moon's body in every
 * phase cell and draws the damage for the current progress onto it, and that copy is registered as
 * a {@link DynamicTexture} under vanilla's own moon location, so the sky renders it exactly like
 * the real moon (same quad, same phase UVs, same additive blending; with any resource pack and most
 * shader packs). Everything outside the moon's body keeps the pack's own pixels, so nothing can
 * appear beside the moon. Redrawn only when the damage visibly changes; the original texture is
 * put back when the progress returns to zero, outside the overworld and on logout, and everything
 * is rebuilt from the new pack after a resource reload.
 * <p>
 * Besides the moon itself it keeps three private textures (bound by id, never registered): the
 * moon's body alone (the pieces that break off are cut from it), a damaged copy for warp effects
 * while the real moon is hidden, and a glow overlay for crack flashes. Render thread only.
 */
public final class MoonTextureManager {
    /** Vanilla's moon phase sheet (LevelRenderer.MOON_LOCATION is private). */
    public static final ResourceLocation MOON = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

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
    private static int[] glowPixels = new int[0];
    private static boolean registered;
    private static boolean baseDirty = true;
    private static boolean loadFailed;
    private static long artSeed;
    private static long lastKey = Long.MIN_VALUE;
    private static boolean hiddenUploaded;
    /** True while a texture holds something else than the canvas outside the bodies (blank or hidden). */
    private static boolean moonStale = true;
    private static boolean copyStale = true;

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

    /** Texture id of the moon's body alone (the sheet with everything else transparent), or -1. */
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

    /** True while the real moon texture is blanked out so an effect can draw the moon itself. */
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
        boolean full = false;
        if (changed) {
            if (gone) {
                current.renderGone();
                full = true;
            } else {
                full = current.render(progress);
            }
            if (copy != null) {
                upload(copy, current, full || copyStale);
                copyStale = false;
            }
        }
        if (hide) {
            if (!hiddenUploaded || changed) {
                clear(target);
                moonStale = true;
            }
        } else if (changed || hiddenUploaded) {
            upload(target, current, full || moonStale);
            moonStale = false;
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
        MoonArt.Sheet sheet;
        ResourceManager resources = mc.getResourceManager();
        Optional<Resource> resource = resources.getResource(MOON);
        if (resource.isEmpty()) {
            return fail();
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = image.getPixelRGBA(x, y);
                }
            }
            sheet = MoonArt.fit(pixels, w, h, MoonArt.MAX_SHEET_WIDTH);
        } catch (IOException | RuntimeException e) {
            NullMod.LOGGER.warn("NULL could not read the moon texture; the moon stays intact", e);
            return fail();
        }
        if (sheet == null) {
            return fail();
        }
        MoonArt built = new MoonArt(sheet.pixels(), sheet.width(), sheet.height(), MoonGeometry.forSeed(seed));
        if (!built.found()) {
            // The pack draws no moon to break: leave its sky alone.
            if (registered) {
                restore();
            }
            return fail();
        }
        TextureManager textures = mc.getTextureManager();
        closePrivate();
        DynamicTexture main = blank(built.width(), built.height());
        copy = blank(built.width(), built.height());
        glow = blank(built.cellWidth(), built.cellHeight());
        pristine = blank(built.sheetWidth(), built.sheetHeight());
        upload(pristine, built.bodyOnly(), built.sheetWidth(), built.sheetHeight());
        glowPixels = new int[built.cellWidth() * built.cellHeight()];
        // Registering replaces (and closes) the texture that was there, including our previous one.
        textures.register(MOON, main);
        moon = main;
        registered = true;
        art = built;
        lastKey = Long.MIN_VALUE;
        hiddenUploaded = false;
        moonStale = true;
        copyStale = true;
        loadFailed = false;
        return true;
    }

    private static boolean fail() {
        loadFailed = true;
        return false;
    }

    private static DynamicTexture blank(int width, int height) {
        return new DynamicTexture(new NativeImage(width, height, true));
    }

    /** Uploads the art's canvas: all of it, or only the bodies' boxes (nothing else ever changes). */
    private static void upload(DynamicTexture texture, MoonArt source, boolean full) {
        NativeImage image = texture.getPixels();
        int width = source.width();
        if (image == null || image.getWidth() != width || image.getHeight() != source.height()) {
            return;
        }
        int[] canvas = source.canvas();
        if (full) {
            copyRect(image, canvas, width, 0, 0, width, source.height());
        } else {
            for (int phase = 0; phase < MoonArt.CELLS; phase++) {
                copyRect(image, canvas, width, source.boxX(phase), source.boxY(phase), source.boxWidth(), source.boxHeight());
            }
        }
        texture.upload();
    }

    private static void upload(DynamicTexture texture, int[] pixels, int width, int height) {
        NativeImage image = texture.getPixels();
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            return;
        }
        copyRect(image, pixels, width, 0, 0, width, height);
        texture.upload();
    }

    private static void copyRect(NativeImage image, int[] pixels, int stride, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            int row = y * stride;
            for (int x = x0; x < x0 + w; x++) {
                image.setPixelRGBA(x, y, pixels[row + x]);
            }
        }
    }

    /** Transparent everywhere: the sky draws no moon at all (additive or not). */
    private static void clear(DynamicTexture texture) {
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        image.fillRect(0, 0, image.getWidth(), image.getHeight(), 0);
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
        glowPixels = new int[0];
    }
}
