package dev.kristian.nullmod;

import com.mojang.logging.LogUtils;
import dev.kristian.nullmod.client.NullClient;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.network.NullNetworking;
import dev.kristian.nullmod.registry.NullAttachments;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.registry.NullSounds;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * NULL - The World Is Failing.
 * <p>
 * Server-side systems are wired in {@link NullCommon} / {@link NullSystems}; everything that
 * draws or plays sound lives under {@code client} and is only loaded on the physical client.
 */
@Mod(NullMod.MODID)
public final class NullMod {
    public static final String MODID = "null";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NullMod(IEventBus modBus, ModContainer container, Dist dist) {
        NullBlocks.BLOCKS.register(modBus);
        NullSounds.SOUND_EVENTS.register(modBus);
        NullAttachments.ATTACHMENT_TYPES.register(modBus);
        modBus.addListener(NullNetworking::register);

        container.registerConfig(ModConfig.Type.SERVER, NullConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, NullClientSettings.SPEC);

        NullCommon.init(modBus);
        if (dist.isClient()) {
            NullClient.init(modBus, container);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
