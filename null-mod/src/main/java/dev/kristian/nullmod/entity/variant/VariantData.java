package dev.kristian.nullmod.entity.variant;

import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * What NULL has done to one mob: the data attachment {@code null:mob_variant}, saved with the mob.
 * Only variant mobs carry it; a mob that turns back to normal loses it, and an attachment that
 * says {@link MobVariant#NONE} is never written to disk.
 * <p>
 * Saved: the variant, the look seed the clients draw it with, a parasite's growth, whether an
 * operator made it (it then survives at any stage), how long it has been a variant, when a
 * parasite may next spread, and the two epochs {@link MobVariantManager} uses to send mobs back
 * that were unloaded while the stage moved back or the world was reset.
 * <p>
 * The package-private fields at the end are the behaviour's working state (stares, approaches,
 * sync bookkeeping). They are never saved: a reloaded mob simply starts fresh.
 */
public final class VariantData {
    /** Writes nothing for an ordinary mob, so the attachment disappears with the next save. */
    public static final IAttachmentSerializer<CompoundTag, VariantData> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public VariantData read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            return VariantData.load(tag);
        }

        @Override
        @Nullable
        public CompoundTag write(VariantData data, HolderLookup.Provider provider) {
            return data.variant == MobVariant.NONE ? null : data.save();
        }
    };

    private static final int FORMAT = 1;

    private MobVariant variant = MobVariant.NONE;
    private long seed;
    private float growth;
    private boolean forced;
    private int age;
    private int nextSpread;
    private int resetEpoch;
    private int stageEpoch;

    // ------------------------------------------------------------------ working state (not saved)

    /** Game time the current stare ends; 0 when the mob is not staring. */
    long stareUntil;
    /** Network id of the player the mob stares at, or -1 for whoever is nearest. */
    int stareTarget = -1;
    /** An event's stare: only getting hurt (or a target) ends it early. */
    boolean stareHeld;
    /** A spontaneous "watch" stare: it turned to face a player who was not looking. */
    boolean watching;
    /** Game time until which a passive parasite host walks at {@link #approachTarget} (event). */
    long approachUntil;
    int approachTarget = -1;
    /** A drifting host is being watched: it stands dead still until the player looks away. */
    boolean driftHold;
    /** Growth the clients were last told, and when. */
    float syncedGrowth = -1.0F;
    long syncedAt;
    /** Earliest game time the sound hook may make this mob sound wrong again. */
    long nextOddSound;

    public VariantData() {
    }

    VariantData(MobVariant variant, long seed, float growth, boolean forced) {
        this.variant = variant;
        this.seed = seed;
        this.growth = Mth.clamp(growth, 0.0F, 1.0F);
        this.forced = forced;
    }

    public MobVariant variant() {
        return variant;
    }

    /** Per-mob seed for the look on the clients (texture corruption, tentacle layout) and its quirks. */
    public long seed() {
        return seed;
    }

    /** How far a parasite has grown, 0 to 1; always 1 for a corrupted mob. */
    public float growth() {
        return variant == MobVariant.PARASITE ? growth : 1.0F;
    }

    /** Made by an operator ({@code /null mob}): it stays whatever the stage is until the stage is moved back. */
    public boolean forced() {
        return forced;
    }

    void setForced(boolean forced) {
        this.forced = forced;
    }

    /** Ticks this mob has spent as a variant while its AI was running. */
    public int age() {
        return age;
    }

    int nextSpread() {
        return nextSpread;
    }

    void setNextSpread(int age) {
        this.nextSpread = age;
    }

    int resetEpoch() {
        return resetEpoch;
    }

    int stageEpoch() {
        return stageEpoch;
    }

    void setEpochs(int resetEpoch, int stageEpoch) {
        this.resetEpoch = resetEpoch;
        this.stageEpoch = stageEpoch;
    }

    void setGrowth(float growth) {
        this.growth = Mth.clamp(growth, 0.0F, 1.0F);
    }

    /** One AI tick older; a parasite grows by {@code growthPerTick}. */
    void age(float growthPerTick) {
        if (age < Integer.MAX_VALUE) {
            age++;
        }
        if (variant == MobVariant.PARASITE && growth < 1.0F) {
            growth = Math.min(1.0F, growth + growthPerTick);
        }
    }

    /** True while the mob holds still and stares (event, spontaneous freeze or watch). */
    boolean staring(long now) {
        return stareUntil > now;
    }

    void stare(long until, int targetId, boolean held, boolean watch) {
        this.stareUntil = until;
        this.stareTarget = targetId;
        this.stareHeld = held;
        this.watching = watch;
    }

    void endStare() {
        this.stareUntil = 0L;
        this.stareTarget = -1;
        this.stareHeld = false;
        this.watching = false;
    }

    // ------------------------------------------------------------------ persistence

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", FORMAT);
        tag.putInt("variant", variant.id());
        tag.putLong("seed", seed);
        tag.putFloat("growth", growth);
        tag.putBoolean("forced", forced);
        tag.putInt("age", age);
        tag.putInt("next_spread", nextSpread);
        tag.putInt("reset_epoch", resetEpoch);
        tag.putInt("stage_epoch", stageEpoch);
        return tag;
    }

    static VariantData load(CompoundTag tag) {
        VariantData data = new VariantData();
        data.variant = MobVariant.byId(tag.getInt("variant"));
        data.seed = tag.getLong("seed");
        data.growth = Mth.clamp(tag.contains("growth", Tag.TAG_FLOAT) ? tag.getFloat("growth") : 1.0F, 0.0F, 1.0F);
        data.forced = tag.getBoolean("forced");
        data.age = Math.max(0, tag.getInt("age"));
        data.nextSpread = Math.max(0, tag.getInt("next_spread"));
        data.resetEpoch = tag.getInt("reset_epoch");
        data.stageEpoch = tag.getInt("stage_epoch");
        return data;
    }

    @Override
    public String toString() {
        return "VariantData[" + variant + " seed=" + seed + " growth=" + growth + (forced ? " forced" : "") + " age=" + age + "]";
    }
}
