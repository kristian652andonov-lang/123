package dev.kristian.nullmod.registry;

import dev.kristian.nullmod.NullMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** Keys of the NULL dimension. The dimension itself is defined by datapack JSON in data/null/. */
public final class NullDimensions {
    public static final ResourceLocation NULL_ID = NullMod.id("null");
    public static final ResourceKey<Level> NULL_LEVEL = ResourceKey.create(Registries.DIMENSION, NULL_ID);
    public static final ResourceKey<DimensionType> NULL_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, NULL_ID);
    /** Id of the client-side DimensionSpecialEffects for the NULL dimension. */
    public static final ResourceLocation NULL_EFFECTS = NullMod.id("null");

    private NullDimensions() {
    }

    public static boolean isNull(Level level) {
        return level.dimension() == NULL_LEVEL;
    }
}
