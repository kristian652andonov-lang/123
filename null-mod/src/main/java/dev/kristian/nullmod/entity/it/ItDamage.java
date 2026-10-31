package dev.kristian.nullmod.entity.it;

import dev.kristian.nullmod.NullMod;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The damage type of IT's kill ({@code data/null/damage_type/it.json}): it ignores armour,
 * shields, resistance, enchantments and the hurt cooldown and causes no knockback. A kill by IT
 * picks one of {@value #DEATH_MESSAGES} death messages ({@code death.attack.null.it.harvest.N});
 * any other use of the type (commands) gets the plain {@code death.attack.null.it[.player|.item]}.
 */
public final class ItDamage {
    public static final ResourceKey<DamageType> IT = ResourceKey.create(Registries.DAMAGE_TYPE, NullMod.id("it"));
    /** Number of death message variants in the language file. */
    public static final int DEATH_MESSAGES = 8;

    private ItDamage() {
    }

    /** A source for IT's kill. Falls back to generic damage when the datapack entry is missing. */
    public static DamageSource source(ServerLevel level, @Nullable Entity it) {
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(IT);
        if (type.isEmpty()) {
            return level.damageSources().generic();
        }
        return new Harvest(type.get(), it);
    }

    /** The death message variant for a death (stable for the tick it happens in). */
    static int variant(LivingEntity killed) {
        long mix = killed.getUUID().getLeastSignificantBits() * 31L + killed.level().getGameTime();
        mix ^= mix >>> 29;
        mix *= 0xBF58476D1CE4E5B9L;
        mix ^= mix >>> 32;
        return 1 + (int) Math.floorMod(mix, (long) DEATH_MESSAGES);
    }

    /** IT's own kills: the same damage type, one of several death messages. */
    private static final class Harvest extends DamageSource {
        Harvest(Holder<DamageType> type, @Nullable Entity it) {
            super(type, it, it);
        }

        @Override
        public Component getLocalizedDeathMessage(LivingEntity killed) {
            Entity it = this.getEntity();
            Component name = it != null ? it.getDisplayName() : Component.translatable("entity.null.it");
            return Component.translatable("death.attack.null.it.harvest." + variant(killed), killed.getDisplayName(), name);
        }
    }
}
