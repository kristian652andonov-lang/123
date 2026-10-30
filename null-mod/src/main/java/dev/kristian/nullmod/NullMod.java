package dev.kristian.nullmod;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(NullMod.MODID)
public final class NullMod {
    public static final String MODID = "null";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NullMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("NULL is loading.");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
