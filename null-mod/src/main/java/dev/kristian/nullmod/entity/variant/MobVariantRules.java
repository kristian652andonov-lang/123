package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

/**
 * Which mobs may become variants, and when.
 * <p>
 * Never: players (they are not mobs), anything in NULL's own namespace (Visitors, the simulated
 * players' bodies, NULLROOT, IT, the stalker figures), bosses ({@code #c:bosses}), the types in
 * {@code #null:variant_excluded} (villagers, wandering traders and their llamas, iron and snow
 * golems, the elder guardian and the warden) and the types on the config denylist.
 * <p>
 * Corrupted mobs belong to Stage 2 (FRACTURE) on, parasite hosts to Stage 3 (INFESTATION) on, and
 * neither appears by itself in the NULL dimension or the field.
 */
public final class MobVariantRules {
    /** Entity types that never become variants (a datapack can add to it). */
    public static final TagKey<EntityType<?>> EXCLUDED = TagKey.create(Registries.ENTITY_TYPE, NullMod.id("variant_excluded"));

    private MobVariantRules() {
    }

    /** The first stage in which a variant appears on its own. */
    public static CorruptionStage firstStage(MobVariant variant) {
        return switch (variant) {
            case NONE -> CorruptionStage.START;
            case CORRUPTED -> CorruptionStage.UNDER_MIDDLE;
            case PARASITE -> CorruptionStage.MIDDLE;
        };
    }

    /** Whether a variant belongs to this stage (operators can still make one earlier with {@code /null mob}). */
    public static boolean allowedAt(MobVariant variant, CorruptionStage stage) {
        return stage.atLeast(firstStage(variant));
    }

    /** Types that never become variants, whoever asks. */
    public static boolean isExcludedType(EntityType<?> type) {
        if (NullMod.MODID.equals(EntityType.getKey(type).getNamespace())) {
            return true;
        }
        return type.is(Tags.EntityTypes.BOSSES) || type.is(EXCLUDED) || VariantConfig.denied(type);
    }

    /**
     * Whether this mob may become a variant now.
     *
     * @param automatic true for everything NULL does by itself (spawns, spreading, parasites
     *                  moving on, random events), false for {@code /null mob}. On its own NULL also
     *                  leaves alone the mobs a player cares about: tamed or owned animals,
     *                  name-tagged mobs, mobs whose AI was switched off, and anything a player rides.
     */
    public static boolean canBecomeVariant(Mob mob, boolean automatic) {
        if (mob.level().isClientSide() || mob.isRemoved() || !mob.isAlive() || isExcludedType(mob.getType())) {
            return false;
        }
        if (!automatic) {
            return true;
        }
        if (mob.hasCustomName() || mob.isNoAi()) {
            return false;
        }
        if (mob instanceof OwnableEntity owned && owned.getOwnerUUID() != null) {
            return false;
        }
        for (Entity passenger : mob.getPassengers()) {
            if (passenger instanceof Player) {
                return false;
            }
        }
        return true;
    }

    /** NULL's dimension and the field (Stage 6) never get variants on their own. */
    public static boolean levelAllows(Level level) {
        return !NullDimensions.isNull(level) && !FieldDimension.isField(level);
    }
}
