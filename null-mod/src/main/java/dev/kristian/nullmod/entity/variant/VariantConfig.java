package dev.kristian.nullmod.entity.variant;

import dev.kristian.nullmod.config.NullConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * Reads the {@code mobs} section of {@link NullConfig}, falling back to the defaults before the
 * world's config has loaded. The denylist is parsed once per change of the list.
 */
final class VariantConfig {
    @Nullable
    private static List<? extends String> parsedFrom;
    private static Set<String> deniedIds = Set.of();
    private static Set<String> deniedNamespaces = Set.of();

    private VariantConfig() {
    }

    static boolean enabled() {
        return NullConfig.get(NullConfig.MOB_VARIANTS_ENABLED);
    }

    static double corruptedFrequency() {
        return NullConfig.get(NullConfig.MOB_CORRUPTED_FREQUENCY);
    }

    static double parasiteFrequency() {
        return NullConfig.get(NullConfig.MOB_PARASITE_FREQUENCY);
    }

    static boolean parasiteSpreading() {
        return NullConfig.get(NullConfig.MOB_PARASITE_SPREADING);
    }

    static int maxPerArea() {
        return NullConfig.get(NullConfig.MOB_MAX_PER_PLAYER_AREA);
    }

    static int maxPerWorld() {
        return NullConfig.get(NullConfig.MOB_MAX_PER_WORLD);
    }

    /** True if the denylist names this type, or its whole namespace ({@code "somemod:*"}). */
    static synchronized boolean denied(EntityType<?> type) {
        List<? extends String> list = NullConfig.loaded() ? NullConfig.MOB_DENYLIST.get() : List.of();
        if (list != parsedFrom) {
            parse(list);
        }
        if (deniedIds.isEmpty() && deniedNamespaces.isEmpty()) {
            return false;
        }
        ResourceLocation id = EntityType.getKey(type);
        return deniedIds.contains(id.toString()) || deniedNamespaces.contains(id.getNamespace());
    }

    private static void parse(List<? extends String> list) {
        Set<String> ids = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (String raw : list) {
            String entry = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (entry.endsWith(":*")) {
                String namespace = entry.substring(0, entry.length() - 2);
                if (!namespace.isEmpty()) {
                    namespaces.add(namespace);
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (id != null && !entry.isEmpty()) {
                ids.add(id.toString());
            }
        }
        deniedIds = Set.copyOf(ids);
        deniedNamespaces = Set.copyOf(namespaces);
        parsedFrom = list;
    }
}
