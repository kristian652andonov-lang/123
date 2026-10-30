package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.PlacementJob;
import dev.kristian.nullmod.world.SafeBlockPlacer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Entry points for NULL's corrupted structures (Stage 2-5): things that look like the game
 * built them wrong, built them long ago, or built them where nothing should be. Every builder
 * plans a {@link PlacementJob} and hands it to {@link SafeBlockPlacer}, so a structure can never
 * overwrite a player's build; each entry point returns false when it found no valid place.
 * <p>
 * Structures that actually appeared are remembered in {@link WorldMemory} as
 * {@code "structure:<type>"} and kept in a short list ({@link #recent}) other systems can use.
 */
public final class CorruptedStructureGenerator {
    /** {@link CorruptionData#systemTag} key of the structure list. */
    public static final String RECORDS_KEY = "world_structures";
    private static final int MAX_RECORDS = 32;

    /** A structure NULL built. */
    public record StructureRecord(String type, ResourceKey<Level> dimension, BlockPos pos, long gameTime) {
    }

    private CorruptedStructureGenerator() {
    }

    // ------------------------------------------------------------------ entry points

    /** bedrock_sky: bedrock blocks and columns hanging in the sky. */
    public static boolean skyBedrock(GenerationRequest req) {
        return SkyBedrockBuilder.generate(req);
    }

    /** floating_house: an old house in the air, sometimes upside down. */
    public static boolean floatingHouse(GenerationRequest req) {
        return FloatingHouseBuilder.generate(req);
    }

    /** abandoned_village: an old village, abandoned, buried or broken. */
    public static boolean abandonedVillage(GenerationRequest req) {
        return OldVillageBuilder.generate(req);
    }

    /** wrong_building: a building assembled wrong, with wrong dimensions, or leading nowhere. */
    public static boolean wrongBuilding(GenerationRequest req) {
        return WrongBuildingBuilder.generate(req);
    }

    /** impossible_room: corridors and rooms carved into the stone that lead nowhere. */
    public static boolean impossibleRoom(GenerationRequest req) {
        return UndergroundBuilder.impossibleRoom(req);
    }

    /** endless_corridor: one very long, identical corridor under the ground. */
    public static boolean endlessCorridor(GenerationRequest req) {
        return UndergroundBuilder.endlessCorridor(req);
    }

    /** distant_structure: an outdated-looking build far away. */
    public static boolean distantStructure(GenerationRequest req) {
        return DistantStructureBuilder.generate(req);
    }

    /** strange_sign: a sign with unsettling text close to the player. */
    public static boolean strangeSign(GenerationRequest req) {
        return StrangeSignBuilder.generate(req);
    }

    // ------------------------------------------------------------------ submission

    /**
     * Queues a structure job. Once it has placed a meaningful part of itself the world remembers
     * it: {@code WorldMemory "structure:<type>"} with {@code detail}, and the structure list.
     */
    public static boolean submit(GenerationRequest req, PlacementJob job, String type, String detail, BlockPos anchor) {
        MinecraftServer server = req.server();
        UUID player = req.player().getUUID();
        ResourceKey<Level> dimension = req.level().dimension();
        BlockPos at = anchor.immutable();
        int meaningful = Math.max(1, Math.min(8, job.size() / 4));
        job.onComplete(result -> {
            if (!result.aborted() && result.placed() >= meaningful) {
                WorldMemory.remember(server, "structure:" + type, player, detail);
                record(server, type, dimension, at);
            }
        });
        return SafeBlockPlacer.INSTANCE.submit(job);
    }

    // ------------------------------------------------------------------ records

    /** Structures NULL built, newest first; only those in {@code dimension} when it is given. */
    public static List<StructureRecord> recent(MinecraftServer server, @Nullable ResourceKey<Level> dimension) {
        ListTag list = CorruptionManager.data(server).systemTag(RECORDS_KEY).getList("list", Tag.TAG_COMPOUND);
        List<StructureRecord> out = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("dim"));
            if (id == null) {
                continue;
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            if (dimension != null && !dimension.equals(key)) {
                continue;
            }
            out.add(new StructureRecord(entry.getString("type"), key, BlockPos.of(entry.getLong("pos")), entry.getLong("t")));
        }
        return out;
    }

    static void record(MinecraftServer server, String type, ResourceKey<Level> dimension, BlockPos pos) {
        CorruptionData data = CorruptionManager.data(server);
        CompoundTag tag = data.systemTag(RECORDS_KEY);
        ListTag list = tag.getList("list", Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        entry.putString("type", type);
        entry.putString("dim", dimension.location().toString());
        entry.putLong("pos", pos.asLong());
        entry.putLong("t", server.overworld().getGameTime());
        list.add(entry);
        while (list.size() > MAX_RECORDS) {
            list.remove(0);
        }
        tag.put("list", list);
        data.setDirty();
    }
}
