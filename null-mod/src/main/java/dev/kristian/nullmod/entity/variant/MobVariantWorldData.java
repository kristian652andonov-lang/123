package dev.kristian.nullmod.entity.variant;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Two counters for the whole world ({@code data/null_mob_variants.dat} on the overworld): how often
 * the stage was moved back and how often NULL was reset. Every variant mob remembers the values
 * from when it was last checked, so a mob that was unloaded while the stage went back (or the
 * world was reset) is checked again when it loads. Kept outside NULL's own save data on purpose:
 * {@code /null reset} wipes that, and the reset counter has to survive it.
 */
public final class MobVariantWorldData extends SavedData {
    public static final String NAME = "null_mob_variants";

    private int resetEpoch;
    private int stageEpoch;

    public MobVariantWorldData() {
    }

    public static SavedData.Factory<MobVariantWorldData> factory() {
        return new SavedData.Factory<>(MobVariantWorldData::new, MobVariantWorldData::load, null);
    }

    public static MobVariantWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), NAME);
    }

    private static MobVariantWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        MobVariantWorldData data = new MobVariantWorldData();
        data.resetEpoch = tag.getInt("reset_epoch");
        data.stageEpoch = tag.getInt("stage_epoch");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("reset_epoch", resetEpoch);
        tag.putInt("stage_epoch", stageEpoch);
        return tag;
    }

    public int resetEpoch() {
        return resetEpoch;
    }

    public int stageEpoch() {
        return stageEpoch;
    }

    void stageMovedBack() {
        stageEpoch++;
        setDirty();
    }

    void reset() {
        resetEpoch++;
        stageEpoch++;
        setDirty();
    }
}
