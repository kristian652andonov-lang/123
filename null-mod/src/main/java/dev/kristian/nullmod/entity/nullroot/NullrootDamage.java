package dev.kristian.nullmod.entity.nullroot;

import dev.kristian.nullmod.NullMod;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

/**
 * The damage type of a finished NULLROOT capture ({@code data/null/damage_type/nullroot.json}).
 * It ignores armour, shields, resistance and enchantments and causes no knockback; death
 * messages are {@code death.attack.null.nullroot[.player|.item]}.
 */
public final class NullrootDamage {
    public static final ResourceKey<DamageType> NULLROOT = ResourceKey.create(Registries.DAMAGE_TYPE, NullMod.id("nullroot"));

    private NullrootDamage() {
    }

    /** A source for the organism that finished the capture (or none). Falls back to generic damage without the datapack entry. */
    public static DamageSource source(ServerLevel level, @Nullable Entity organism) {
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(NULLROOT);
        if (type.isEmpty()) {
            return level.damageSources().generic();
        }
        return organism == null ? new DamageSource(type.get()) : new DamageSource(type.get(), organism);
    }
}
