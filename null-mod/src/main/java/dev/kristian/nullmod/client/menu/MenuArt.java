package dev.kristian.nullmod.client.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kristian.nullmod.NullMod;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

/**
 * The menu's art: texture locations plus the sprite regions, eye anchors and glyph metrics that
 * {@code tools/gen_menu.py} writes to {@code textures/gui/menu/art.json}.
 * <p>
 * Every piece is optional. Whatever is missing or malformed is simply left out, so a broken
 * resource pack degrades the menu to a plain dark screen instead of crashing it.
 */
final class MenuArt {
    static final ResourceLocation METADATA = NullMod.id("textures/gui/menu/art.json");

    /** A sprite inside a texture, with UVs already normalised. */
    static final class Sprite {
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final int width;
        final int height;
        /** Eye anchors as fractions of the sprite size: x0, y0, x1, y1, ... (may be empty). */
        final float[] eyes;
        /** Glowing pupil radius as a fraction of the sprite width. */
        final float eyeSize;

        Sprite(float u0, float v0, float u1, float v1, int width, int height, float[] eyes, float eyeSize) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
            this.eyes = eyes;
            this.eyeSize = eyeSize;
        }

        /** Width divided by height. */
        float aspect() {
            return height == 0 ? 1.0F : (float) width / height;
        }
    }

    /** Metrics of the distressed glyph sheet (also used by the {@code null:horror} bitmap font). */
    static final class Glyphs {
        final MenuTexture texture;
        final int textureWidth;
        final int textureHeight;
        final int cellWidth;
        final int cellHeight;
        final int columns;
        final int baseline;
        final int capTop;
        final int space;
        final String chars;
        final int[] left;
        final int[] advance;

        Glyphs(MenuTexture texture, int textureWidth, int textureHeight, int cellWidth, int cellHeight, int columns,
               int baseline, int capTop, int space, String chars, int[] left, int[] advance) {
            this.texture = texture;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.columns = columns;
            this.baseline = baseline;
            this.capTop = capTop;
            this.space = space;
            this.chars = chars;
            this.left = left;
            this.advance = advance;
        }

        /** Index of a character in the sheet, or -1. */
        int index(char c) {
            return chars.indexOf(c);
        }

        int capHeight() {
            return baseline - capTop;
        }

        float u0(int index) {
            return (float) ((index % columns) * cellWidth) / textureWidth;
        }

        float v0(int index) {
            return (float) ((index / columns) * cellHeight) / textureHeight;
        }

        float u1(int index) {
            return (float) ((index % columns) * cellWidth + cellWidth) / textureWidth;
        }

        float v1(int index) {
            return (float) ((index / columns) * cellHeight + cellHeight) / textureHeight;
        }
    }

    static final MenuArt EMPTY = new MenuArt(null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null);

    private static MenuArt cached;

    /** Apparition atlas (faces, eyes, glints, mouths, silhouettes); null when unavailable. */
    final MenuTexture atlas;
    final MenuTexture grain;
    final MenuTexture fog;
    final MenuTexture vignette;
    final List<Sprite> faces;
    final List<Sprite> eyes;
    final List<Sprite> glints;
    final List<Sprite> mouths;
    final List<Sprite> silhouettes;
    /** Null when the glyph sheet is unavailable; the logo then falls back to the vanilla font. */
    final Glyphs glyphs;

    private MenuArt(MenuTexture atlas, MenuTexture grain, MenuTexture fog, MenuTexture vignette,
                    List<Sprite> faces, List<Sprite> eyes, List<Sprite> glints, List<Sprite> mouths, List<Sprite> silhouettes,
                    Glyphs glyphs) {
        this.atlas = atlas;
        this.grain = grain;
        this.fog = fog;
        this.vignette = vignette;
        this.faces = faces;
        this.eyes = eyes;
        this.glints = glints;
        this.mouths = mouths;
        this.silhouettes = silhouettes;
        this.glyphs = glyphs;
    }

    boolean hasApparitions() {
        return atlas != null && !faces.isEmpty();
    }

    /** The current art, loaded on first use after start-up or a resource reload. Never null. */
    static MenuArt get() {
        MenuArt art = cached;
        if (art == null) {
            art = load(Minecraft.getInstance().getResourceManager());
            cached = art;
        }
        return art;
    }

    /** Forgets the parsed metadata and frees its textures for good (resource reload). */
    static void invalidate() {
        MenuArt art = cached;
        cached = null;
        if (art != null) {
            art.forEachTexture(MenuTexture::close);
        }
    }

    /**
     * Frees the menu's textures from the GPU. Called when a world is joined; they load again
     * the next time the menu is drawn.
     */
    static void releaseTextures() {
        MenuArt art = cached;
        if (art != null) {
            art.forEachTexture(MenuTexture::release);
        }
    }

    private void forEachTexture(java.util.function.Consumer<MenuTexture> action) {
        if (atlas != null) {
            action.accept(atlas);
        }
        if (grain != null) {
            action.accept(grain);
        }
        if (fog != null) {
            action.accept(fog);
        }
        if (vignette != null) {
            action.accept(vignette);
        }
        if (glyphs != null) {
            action.accept(glyphs.texture);
        }
    }

    private static MenuArt load(ResourceManager resources) {
        Optional<Resource> metadata = resources.getResource(METADATA);
        if (metadata.isEmpty()) {
            NullMod.LOGGER.warn("NULL menu art metadata {} is missing; the menu will be plain", METADATA);
            return EMPTY;
        }
        JsonObject root;
        try (Reader reader = metadata.get().openAsReader()) {
            root = GsonHelper.parse(reader);
        } catch (IOException | RuntimeException e) {
            NullMod.LOGGER.warn("Could not read NULL menu art metadata {}", METADATA, e);
            return EMPTY;
        }

        MenuTexture atlas = null;
        List<Sprite> faces = List.of();
        List<Sprite> eyes = List.of();
        List<Sprite> glints = List.of();
        List<Sprite> mouths = List.of();
        List<Sprite> silhouettes = List.of();
        try {
            JsonObject atlasJson = GsonHelper.getAsJsonObject(root, "atlas");
            ResourceLocation location = texture(resources, GsonHelper.getAsString(atlasJson, "texture"));
            int width = GsonHelper.getAsInt(atlasJson, "width");
            int height = GsonHelper.getAsInt(atlasJson, "height");
            if (location != null && width > 0 && height > 0) {
                faces = sprites(root, "faces", width, height);
                eyes = sprites(root, "eyes", width, height);
                glints = sprites(root, "glints", width, height);
                mouths = sprites(root, "mouths", width, height);
                silhouettes = sprites(root, "silhouettes", width, height);
                atlas = new MenuTexture(location, true);
            }
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("NULL menu atlas metadata is malformed; the menu background will be plain", e);
            faces = eyes = glints = mouths = silhouettes = List.of();
            atlas = null;
        }

        MenuTexture grain = optionalTexture(resources, root, "grain", false);
        MenuTexture fog = optionalTexture(resources, root, "fog", true);
        MenuTexture vignette = optionalTexture(resources, root, "vignette", true);

        Glyphs glyphs = null;
        try {
            glyphs = glyphs(resources, GsonHelper.getAsJsonObject(root, "glyphs"));
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("NULL menu glyph metadata is malformed; the logo falls back to the default font", e);
        }
        return new MenuArt(atlas, grain, fog, vignette, faces, eyes, glints, mouths, silhouettes, glyphs);
    }

    private static MenuTexture optionalTexture(ResourceManager resources, JsonObject root, String key, boolean blur) {
        try {
            if (!root.has(key)) {
                return null;
            }
            ResourceLocation location = texture(resources, GsonHelper.getAsString(GsonHelper.getAsJsonObject(root, key), "texture"));
            return location == null ? null : new MenuTexture(location, blur);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("NULL menu art entry '{}' is malformed", key, e);
            return null;
        }
    }

    /** Parses a texture location and checks that the file exists. */
    private static ResourceLocation texture(ResourceManager resources, String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            NullMod.LOGGER.warn("NULL menu art: bad texture id '{}'", id);
            return null;
        }
        if (resources.getResource(location).isEmpty()) {
            NullMod.LOGGER.warn("NULL menu art: texture {} is missing", location);
            return null;
        }
        return location;
    }

    private static List<Sprite> sprites(JsonObject root, String key, int atlasWidth, int atlasHeight) {
        JsonArray array = GsonHelper.getAsJsonArray(root, key, new JsonArray());
        List<Sprite> list = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject json = GsonHelper.convertToJsonObject(element, key);
            int x = GsonHelper.getAsInt(json, "x");
            int y = GsonHelper.getAsInt(json, "y");
            int w = GsonHelper.getAsInt(json, "w");
            int h = GsonHelper.getAsInt(json, "h");
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > atlasWidth || y + h > atlasHeight) {
                throw new IllegalArgumentException("sprite outside the atlas in '" + key + "'");
            }
            float[] eyes = new float[0];
            float eyeSize = 0.0F;
            if (json.has("eyes")) {
                JsonArray anchors = GsonHelper.getAsJsonArray(json, "eyes");
                eyes = new float[anchors.size() * 2];
                for (int i = 0; i < anchors.size(); i++) {
                    JsonArray anchor = anchors.get(i).getAsJsonArray();
                    eyes[i * 2] = anchor.get(0).getAsFloat() / w;
                    eyes[i * 2 + 1] = anchor.get(1).getAsFloat() / h;
                }
                eyeSize = GsonHelper.getAsFloat(json, "eye_size") / w;
            }
            list.add(new Sprite((float) x / atlasWidth, (float) y / atlasHeight, (float) (x + w) / atlasWidth,
                    (float) (y + h) / atlasHeight, w, h, eyes, eyeSize));
        }
        return Collections.unmodifiableList(list);
    }

    private static Glyphs glyphs(ResourceManager resources, JsonObject json) {
        ResourceLocation texture = texture(resources, GsonHelper.getAsString(json, "texture"));
        if (texture == null) {
            return null;
        }
        String chars = GsonHelper.getAsString(json, "chars");
        JsonArray leftJson = GsonHelper.getAsJsonArray(json, "left");
        JsonArray advanceJson = GsonHelper.getAsJsonArray(json, "advance");
        if (chars.isEmpty() || leftJson.size() != chars.length() || advanceJson.size() != chars.length()) {
            throw new IllegalArgumentException("glyph tables do not match the character list");
        }
        int[] left = new int[chars.length()];
        int[] advance = new int[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            left[i] = leftJson.get(i).getAsInt();
            advance[i] = advanceJson.get(i).getAsInt();
        }
        int columns = GsonHelper.getAsInt(json, "columns");
        int cellWidth = GsonHelper.getAsInt(json, "cell_w");
        int cellHeight = GsonHelper.getAsInt(json, "cell_h");
        int width = GsonHelper.getAsInt(json, "width");
        int height = GsonHelper.getAsInt(json, "height");
        int rows = (chars.length() + Math.max(1, columns) - 1) / Math.max(1, columns);
        if (columns <= 0 || cellWidth <= 0 || cellHeight <= 0 || columns * cellWidth > width || rows * cellHeight > height) {
            throw new IllegalArgumentException("glyph grid does not fit the glyph texture");
        }
        int baseline = GsonHelper.getAsInt(json, "baseline");
        int capTop = GsonHelper.getAsInt(json, "cap_top");
        if (capTop < 0 || baseline <= capTop || baseline > cellHeight) {
            throw new IllegalArgumentException("bad glyph vertical metrics");
        }
        return new Glyphs(new MenuTexture(texture, true), width, height, cellWidth, cellHeight, columns, baseline, capTop,
                GsonHelper.getAsInt(json, "space"), chars, left, advance);
    }
}
