package dev.kristian.nullmod.client.menu;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kristian.nullmod.NullMod;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * A menu texture owned by the menu itself rather than the global texture manager: the PNG is
 * decoded on first use (render thread) and its GPU memory is given back with {@link #release()}
 * as soon as a world is joined. It is loaded again the next time the menu needs it.
 */
final class MenuTexture {
    private final ResourceLocation location;
    private final boolean blur;
    private DynamicTexture texture;
    private boolean failed;
    private boolean closed;

    MenuTexture(ResourceLocation location, boolean blur) {
        this.location = location;
        this.blur = blur;
    }

    /** OpenGL id of the texture, loading it if necessary; -1 when it cannot be used. */
    int id() {
        if (texture != null) {
            return texture.getId();
        }
        if (failed || closed) {
            return -1;
        }
        NativeImage image = null;
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                failed = true;
                NullMod.LOGGER.warn("NULL menu texture {} is missing", location);
                return -1;
            }
            try (InputStream stream = resource.get().open()) {
                image = NativeImage.read(NativeImage.Format.RGBA, stream);
            }
            DynamicTexture loaded = new DynamicTexture(image);
            image = null;
            loaded.setFilter(blur, false);
            texture = loaded;
            return loaded.getId();
        } catch (IOException | RuntimeException e) {
            failed = true;
            if (image != null) {
                image.close();
            }
            NullMod.LOGGER.warn("NULL menu texture {} could not be loaded", location, e);
            return -1;
        }
    }

    /** Frees the GPU and native memory; the texture loads again on the next {@link #id()}. */
    void release() {
        DynamicTexture current = texture;
        texture = null;
        if (current != null) {
            current.close();
        }
    }

    /** Releases the texture for good (resource reload replaced this art). */
    void close() {
        release();
        closed = true;
    }
}
