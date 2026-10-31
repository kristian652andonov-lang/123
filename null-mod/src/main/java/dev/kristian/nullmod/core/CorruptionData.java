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
    /** 2: the clock counts in-game nights ({@link CorruptionStageManager}). 1: it counted minutes. */
    private static final int FORMAT = 2;
    private static final int FORMAT_MINUTES = 1;

    /** The clock still has to be placed at the world's time of day (a world NULL has just begun in). */
    private static final int CLOCK_NEW = 0;
    /** The clock is a minute clock from an older build and has to be moved to nights. */
    private static final int CLOCK_MINUTES = 1;
    private static final int CLOCK_NIGHTS = 2;

    private int stage = 1;
    private int highestStage = 1;
    /**
     * The corruption clock in clock ticks (24000 per in-game night, see {@link CorruptionStageManager});
     * this is what the stage thresholds are measured against.
     */
    private double corruptionTicks;
    private int clock = CLOCK_NEW;
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
        // Every save has carried a format; one without is older still, and also counted minutes.
        int format = tag.contains("format", Tag.TAG_INT) ? tag.getInt("format") : FORMAT_MINUTES;
        data.clock = format >= FORMAT ? CLOCK_NIGHTS : CLOCK_MINUTES;
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
        // A minute clock that has not been moved to nights yet stays marked as one.
        tag.putInt("format", clock == CLOCK_MINUTES ? FORMAT_MINUTES : FORMAT);
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

    /**
     * Moves the clock on by {@code scaledTicks} (never back); {@code realTick} also counts one tick of
     * time NULL has been running.
     */
    void advance(double scaledTicks, boolean realTick) {
        this.corruptionTicks += Math.max(0.0, scaledTicks);
        if (realTick) {
            this.activeTicks++;
        }
        setDirty();
    }

    /** True until the clock has been placed at the world's time of day, or moved from minutes to nights. */
    boolean clockNeedsSetup() {
        return clock != CLOCK_NIGHTS;
    }

    /** True for a clock saved by an older build, which counted minutes. */
    boolean minuteClock() {
        return clock == CLOCK_MINUTES;
    }

    /** The clock now counts nights, starting from {@code ticks}. */
    void setUpClock(double ticks) {
        this.corruptionTicks = Math.max(0.0, ticks);
        this.clock = CLOCK_NIGHTS;
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
        clock = CLOCK_NIGHTS;
        activeTicks = 0L;
        paused = false;
        speed = 1.0;
        moonProgress = 0.0F;
        moonGone = false;
        systems = new CompoundTag();
        setDirty();
    }
}
