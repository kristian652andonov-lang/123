package dev.kristian.nullmod.moon;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.network.NullNetworking;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The Shattering Moon, server side. The moon breaks from the start of Stage 2 to the end of
 * Stage 4: its progress follows the corruption clock (scaled by the moon speed setting) and
 * never goes back on its own. Milestones (first crack, spreading cracks, every piece that breaks
 * away, distortion, the shattering) are shown to the players who are outside at night; when the
 * moon is fully broken it disappears for good on the next night, or when Stage 5 begins.
 * <p>
 * Progress is stored in {@link CorruptionData} and synced to clients by the core; the shape of
 * the damage comes from {@link MoonGeometry}, which the clients compute from the same seed.
 */
public final class MoonCorruptionManager implements CorruptionSystem {
    public static final MoonCorruptionManager INSTANCE = new MoonCorruptionManager();
    public static final String NAME = "moon";

    public static final String EFFECT_CRACK = "moon.crack";
    public static final String EFFECT_PIECE = "moon.piece";
    public static final String EFFECT_DISTORT = "moon.distort";
    public static final String EFFECT_SHATTER = "moon.shatter";
    public static final String EFFECT_GONE = "moon.gone";

    /** The moon is complete this far into Stage 4, so the end still has a chance to happen at night. */
    private static final double END_WITHIN_STAGE_4 = 0.85;
    /** After this long at full progress the moon disappears on the next night even if it rains. */
    private static final long CLEAR_NIGHT_PATIENCE = 24000L;
    private static final long GONE_MESSAGE_DELAY = 70L;
    private static final String KEY_PENDING = "pending";
    private static final String KEY_FULL_SINCE = "full_since";

    /** Overworld game time at which the "[NULL]" line about the moon is sent, or -1. */
    private long goneMessageAt = -1L;
    @Nullable
    private List<Milestone> milestones;
    private long milestoneSeed;

    /** A moment the players should see, at a progress value. */
    record Milestone(float at, String effect, int detail, int duration, String memory) {
    }

    private MoonCorruptionManager() {
    }

    @Override
    public String name() {
        return NAME;
    }

    /** False when the server set the moon speed to zero: the moon then never changes. */
    public static boolean enabled() {
        return NullConfig.get(NullConfig.MOON_SPEED) > 0.0;
    }

    /**
     * The moon progress the corruption clock asks for: 0 until Stage 2 begins, rising to 1
     * late in Stage 4, multiplied by the moon speed setting.
     */
    public static float targetProgress(double corruptionTicks) {
        double start = CorruptionStageManager.threshold(CorruptionStage.UNDER_MIDDLE);
        double stage4 = CorruptionStageManager.threshold(CorruptionStage.ABOVE_MIDDLE);
        double stage5 = CorruptionStageManager.threshold(CorruptionStage.NULL);
        double end = stage4 + (stage5 - stage4) * END_WITHIN_STAGE_4;
        if (corruptionTicks <= start || end <= start) {
            return 0.0F;
        }
        double raw = (corruptionTicks - start) / (end - start) * NullConfig.get(NullConfig.MOON_SPEED);
        return (float) Math.max(0.0, Math.min(1.0, raw));
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        sendGoneMessage(server);
        CorruptionData data = ctx.data();
        if (!enabled() || data.moonGone()) {
            return;
        }
        float target = targetProgress(ctx.corruptionTicks());
        if (target > data.moonProgress()) {
            setProgress(data, target, true);
        }
        deliverPending(server, data);
        if (data.moonProgress() >= 1.0F) {
            checkFinalNight(server, data);
        }
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        CorruptionData data = context.data();
        if (to.number() < CorruptionStage.UNDER_MIDDLE.number()) {
            resetMoon(data);
            return;
        }
        if (to.number() < from.number()) {
            // Moved back by an administrator: the moon follows the clock back.
            if (enabled()) {
                data.setMoonProgress(Math.min(data.moonProgress(), targetProgress(context.corruptionTicks())));
            }
            data.setMoonGone(false);
            clearState(data);
            return;
        }
        if (to == CorruptionStage.NULL && from != CorruptionStage.NULL && enabled() && !data.moonGone()) {
            disappear(server, data);
        }
    }

    @Override
    public void onReset(MinecraftServer server) {
        goneMessageAt = -1L;
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        goneMessageAt = -1L;
        milestones = null;
    }

    // ------------------------------------------------------------------ progress and milestones

    /**
     * Sets the moon progress (never lower than it is). With {@code announce} the newest
     * milestone crossed is queued for the players watching the sky; without, crossing is silent
     * (used by events that show their own effect).
     */
    void setProgress(CorruptionData data, float value, boolean announce) {
        float before = data.moonProgress();
        if (value <= before) {
            return;
        }
        data.setMoonProgress(value);
        float after = data.moonProgress();
        Milestone crossed = null;
        for (Milestone milestone : milestones(data.seed())) {
            if (milestone.at() > before && milestone.at() <= after) {
                crossed = milestone;
            }
        }
        if (crossed != null && announce) {
            data.systemTag(NAME).putFloat(KEY_PENDING, crossed.at());
            data.setDirty();
        }
    }

    /** Raises the progress for an event and syncs it right away so the effect sees the new state. */
    void bump(MinecraftServer server, CorruptionData data, float value) {
        setProgress(data, Math.min(1.0F, value), false);
        CorruptionManager.syncAll(server);
    }

    private void deliverPending(MinecraftServer server, CorruptionData data) {
        CompoundTag tag = data.systemTag(NAME);
        if (!tag.contains(KEY_PENDING, Tag.TAG_FLOAT)) {
            return;
        }
        float at = tag.getFloat(KEY_PENDING);
        Milestone milestone = null;
        for (Milestone candidate : milestones(data.seed())) {
            if (candidate.at() == at) {
                milestone = candidate;
            }
        }
        if (milestone == null) {
            tag.remove(KEY_PENDING);
            data.setDirty();
            return;
        }
        List<ServerPlayer> watchers = watchers(server);
        if (watchers.isEmpty()) {
            return;
        }
        tag.remove(KEY_PENDING);
        data.setDirty();
        long seed = server.overworld().getRandom().nextLong();
        float intensity = 0.55F + 0.45F * data.moonProgress();
        for (ServerPlayer player : watchers) {
            NullNetworking.sendEffect(player, milestone.effect(), seed, intensity, milestone.duration(), Optional.empty(),
                    milestone.detail(), "");
        }
        WorldMemory.remember(server, milestone.memory(), null, "");
    }

    /** The moments worth showing, in order, for a world seed. */
    List<Milestone> milestones(long seed) {
        List<Milestone> list = milestones;
        if (list != null && milestoneSeed == seed) {
            return list;
        }
        MoonGeometry geometry = MoonGeometry.forSeed(seed);
        list = new ArrayList<>();
        list.add(new Milestone(MoonGeometry.FIRST_CRACK, EFFECT_CRACK, geometry.newestCrack(MoonGeometry.FIRST_CRACK), 50, "moon:cracked"));
        for (float at : new float[] {0.10F, 0.20F, 0.30F}) {
            list.add(new Milestone(at, EFFECT_CRACK, geometry.newestCrack(at), 50, "moon:cracks_spread"));
        }
        for (int order = 0; order < geometry.holeCount(); order++) {
            int shard = geometry.hole(order);
            float at = geometry.shard(shard).breakAt();
            if (at < MoonGeometry.SHATTER) {
                list.add(new Milestone(at, EFFECT_PIECE, shard, 140, "moon:piece"));
            }
        }
        list.add(new Milestone(MoonGeometry.DISTORTION, EFFECT_DISTORT, 0, 100, "moon:distorted"));
        list.add(new Milestone(0.775F, EFFECT_DISTORT, 0, 100, "moon:distorted"));
        list.add(new Milestone(MoonGeometry.SHATTER, EFFECT_SHATTER, -1, 160, "moon:shattered"));
        list.sort((a, b) -> Float.compare(a.at(), b.at()));
        milestones = List.copyOf(list);
        milestoneSeed = seed;
        return milestones;
    }

    // ------------------------------------------------------------------ the end

    private void checkFinalNight(MinecraftServer server, CorruptionData data) {
        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        CompoundTag tag = data.systemTag(NAME);
        if (!tag.contains(KEY_FULL_SINCE, Tag.TAG_LONG)) {
            tag.putLong(KEY_FULL_SINCE, now);
            data.setDirty();
        }
        if (!isNight(overworld)) {
            return;
        }
        boolean clear = overworld.getRainLevel(1.0F) < 0.5F;
        if (clear || now - tag.getLong(KEY_FULL_SINCE) >= CLEAR_NIGHT_PATIENCE) {
            disappear(server, data);
        }
    }

    /** The final disappearance: the moon is gone for every player, with a major event in the overworld. */
    void disappear(MinecraftServer server, CorruptionData data) {
        data.setMoonProgress(1.0F);
        data.setMoonGone(true);
        clearState(data);
        WorldMemory.remember(server, "moon:gone", null, "");
        NullMod.LOGGER.info("NULL: the moon is gone");
        CorruptionManager.syncAll(server);
        ServerLevel overworld = server.overworld();
        long seed = overworld.getRandom().nextLong();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == overworld) {
                NullNetworking.sendEffect(player, EFFECT_GONE, seed, 1.0F, 200, Optional.empty(), canSeeMoon(player) ? 1 : 0, "");
            }
        }
        goneMessageAt = overworld.getGameTime() + GONE_MESSAGE_DELAY;
    }

    private void sendGoneMessage(MinecraftServer server) {
        if (goneMessageAt < 0L || server.overworld().getGameTime() < goneMessageAt) {
            return;
        }
        goneMessageAt = -1L;
        Component line = Component.translatable("null.moon.gone_message").withStyle(ChatFormatting.DARK_RED);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(line);
        }
    }

    private void resetMoon(CorruptionData data) {
        data.setMoonProgress(0.0F);
        data.setMoonGone(false);
        clearState(data);
        goneMessageAt = -1L;
    }

    private static void clearState(CorruptionData data) {
        CompoundTag tag = data.systemTag(NAME);
        tag.remove(KEY_PENDING);
        tag.remove(KEY_FULL_SINCE);
        data.setDirty();
    }

    // ------------------------------------------------------------------ who can see the moon

    /** Night in the overworld: the moon is well above the horizon. */
    public static boolean isNight(Level level) {
        long time = Math.floorMod(level.getDayTime(), 24000L);
        return time >= 13000L && time <= 23000L;
    }

    /** The player is in the overworld, outside, at night, and it is not raining. */
    public static boolean canSeeMoon(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD || !isNight(level) || level.getRainLevel(1.0F) >= 0.5F) {
            return false;
        }
        return level.canSeeSky(BlockPos.containing(player.getX(), player.getEyeY(), player.getZ()));
    }

    static List<ServerPlayer> watchers(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (canSeeMoon(player)) {
                out.add(player);
            }
        }
        return out;
    }

    /** Sends a moon effect to everyone watching the sky, plus the player an event was rolled for. */
    static void broadcast(MinecraftServer server, ServerPlayer trigger, String effect, long seed, float intensity, int duration, int detail) {
        Set<ServerPlayer> targets = new LinkedHashSet<>(watchers(server));
        targets.add(trigger);
        for (ServerPlayer player : targets) {
            NullNetworking.sendEffect(player, effect, seed, intensity, duration, Optional.empty(), detail, "");
        }
    }
}
