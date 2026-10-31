package dev.kristian.nullmod.field;

import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Keys of the field ({@code null:field}), the dimension of Stage 6: an endless wheat field under a
 * static sun. The dimension, its type and its biome are datapack JSON in {@code data/null/}; the
 * chunk generator type is registered by {@link FieldWorldgen}.
 */
public final class FieldDimension {
    public static final ResourceLocation FIELD_ID = NullMod.id("field");
    public static final ResourceKey<Level> FIELD_LEVEL = ResourceKey.create(Registries.DIMENSION, FIELD_ID);
    public static final ResourceKey<DimensionType> FIELD_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, FIELD_ID);
    /** Id of the client-side DimensionSpecialEffects of the field ({@code "effects"} in its dimension type). */
    public static final ResourceLocation FIELD_EFFECTS = NullMod.id("field");
    /** The only biome of the field ({@code data/null/worldgen/biome/the_field.json}). */
    public static final ResourceKey<Biome> THE_FIELD = ResourceKey.create(Registries.BIOME, NullMod.id("the_field"));

    /**
     * Where the sun hangs, as a player's yaw: due north. Everyone arrives facing it, and IT comes
     * out of it.
     */
    public static final float SUN_YAW = 180.0F;
    /** Height of the sun's centre above the horizon, in degrees. */
    public static final float SUN_ELEVATION = 13.0F;
    /** Horizontal direction of the sun (north). */
    public static final int SUN_DIR_X = 0;
    public static final int SUN_DIR_Z = -1;

    private FieldDimension() {
    }

    public static boolean isField(Level level) {
        return level.dimension() == FIELD_LEVEL;
    }
}
