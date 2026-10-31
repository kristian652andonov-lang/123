package dev.kristian.nullmod.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Everything NULL remembers about a world. Stored once per world on the overworld in
 * {@code data/null_corruption.dat}, so it survives restarts and is shared by every player.
 */
public final class CorruptionData extends SavedData {
    public static final String NAME = "null_corruption";
    private static final int FORMAT = 1;

    private int stage = 1;
    private int highestStage = 1;
    /** Scaled corruption clock in ticks; this is what the stage thresholds are measured against. */
    private double corruptionTicks;
    /** Real ticks that passed while NULL was counting. */
    private long activeTicks;
    private boolean paused;
    /** Administrator multiplier from /null speed. */
    private double speed = 1.0;
    private float moonProgress;
    private boolean moonGone;
    private long seed;
    private boolean seeded;
    /** Free-form storage for the individual systems, keyed by system name. */
    private CompoundTag systems = new CompoundTag();

    public CorruptionData() {
    }

    public static SavedData.Factory<CorruptionData> factory() {
        return new SavedData.Factory<>(CorruptionData::new, CorruptionData::load, null);
    }

    public static CorruptionData load(CompoundTag tag, HolderLookup.Provider registries) {
        CorruptionData data = new CorruptionData();
        int last = CorruptionStage.HARVEST.number();
        data.stage = Math.max(1, Math.min(last, tag.getInt("stage")));
        data.highestStage = Math.max(data.stage, Math.min(last, tag.getInt("highest_stage")));
        data.corruptionTicks = Math.max(0.0, tag.getDouble("corruption_ticks"));
        data.activeTicks = Math.max(0L, tag.getLong("active_ticks"));
        data.paused = tag.getBoolean("paused");
        data.speed = tag.contains("speed", Tag.TAG_DOUBLE) ? tag.getDouble("speed") : 1.0;
        data.moonProgress = Math.max(0.0F, Math.min(1.0F, tag.getFloat("moon_progress")));
        data.moonGone = tag.getBoolean("moon_gone");
        data.seeded = tag.contains("seed", Tag.TAG_LONG);
        data.seed = tag.getLong("seed");
        data.systems = tag.getCompound("systems").copy();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putInt("stage", stage);
        tag.putInt("highest_stage", highestStage);
        tag.putDouble("corruption_ticks", corruptionTicks);
        tag.putLong("active_ticks", activeTicks);
        tag.putBoolean("paused", paused);
        tag.putDouble("speed", speed);
        tag.putFloat("moon_progress", moonProgress);
        tag.putBoolean("moon_gone", moonGone);
        if (seeded) {
            tag.putLong("seed", seed);
        }
        tag.put("systems", systems.copy());
        return tag;
    }

    public CorruptionStage stage() {
        return CorruptionStage.byNumber(stage);
    }

    void setStage(CorruptionStage newStage) {
        this.stage = newStage.number();
        this.highestStage = Math.max(highestStage, stage);
        setDirty();
    }

    public CorruptionStage highestStage() {
        return CorruptionStage.byNumber(highestStage);
    }

    public double corruptionTicks() {
        return corruptionTicks;
    }

    void setCorruptionTicks(double ticks) {
        this.corruptionTicks = Math.max(0.0, ticks);
        setDirty();
    }

    void advance(double scaledTicks) {
        this.corruptionTicks += Math.max(0.0, scaledTicks);
        this.activeTicks++;
        setDirty();
    }

    public long activeTicks() {
        return activeTicks;
    }

    public boolean paused() {
        return paused;
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        setDirty();
    }

    public double speed() {
        return speed;
    }

    void setSpeed(double speed) {
        this.speed = Math.max(0.0, speed);
        setDirty();
    }

    public float moonProgress() {
        return moonProgress;
    }

    /** Moon progress only ever grows unless the world is reset or the stage is moved back. */
    public void setMoonProgress(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        if (clamped != moonProgress) {
            moonProgress = clamped;
            setDirty();
        }
    }

    public boolean moonGone() {
        return moonGone;
    }

    public void setMoonGone(boolean gone) {
        if (gone != moonGone) {
            moonGone = gone;
            setDirty();
        }
    }

    public long seed() {
        return seed;
    }

    boolean seeded() {
        return seeded;
    }

    void setSeed(long seed) {
        this.seed = seed;
        this.seeded = true;
        setDirty();
    }

    /**
     * Persistent storage for one system. The returned tag is live: mutate it and then call
     * {@link #setDirty()} so the change is written to disk.
     */
    public CompoundTag systemTag(String key) {
        if (!systems.contains(key, Tag.TAG_COMPOUND)) {
            systems.put(key, new CompoundTag());
        }
        return systems.getCompound(key);
    }

    void clearSystems() {
        systems = new CompoundTag();
        setDirty();
    }

    void resetAll() {
        stage = 1;
        highestStage = 1;
        corruptionTicks = 0.0;
        activeTicks = 0L;
        paused = false;
        speed = 1.0;
        moonProgress = 0.0F;
        moonGone = false;
        systems = new CompoundTag();
        setDirty();
    }
}
