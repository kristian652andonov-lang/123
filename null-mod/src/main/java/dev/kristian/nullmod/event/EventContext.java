package dev.kristian.nullmod.event;

import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.network.NullNetworking;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

/**
 * Everything an event needs while it runs. Effects are sent to {@link #player()} only, so one
 * player's visuals never leak onto another player's screen.
 */
public final class EventContext {
    /**
     * {@code data} of a {@code block.*} client effect sent by the showcase: the client picks the
     * blocks close in front of the player (block effects use no other data).
     */
    public static final int SHOWCASE_BLOCK_DATA = 1;
    private static final String BLOCK_EFFECT_PREFIX = "block.";

    private final MinecraftServer server;
    private final ServerPlayer player;
    private final CorruptionContext corruption;
    private final RandomSource random;
    private final float intensity;
    private final int duration;
    private final long seed;
    private final NullEventDefinition definition;
    private final boolean forced;
    private final boolean showcase;

    public EventContext(MinecraftServer server, ServerPlayer player, CorruptionContext corruption, RandomSource random,
                        float intensity, int duration, long seed, NullEventDefinition definition, boolean forced) {
        this(server, player, corruption, random, intensity, duration, seed, definition, forced, false);
    }

    public EventContext(MinecraftServer server, ServerPlayer player, CorruptionContext corruption, RandomSource random,
                        float intensity, int duration, long seed, NullEventDefinition definition, boolean forced,
                        boolean showcase) {
        this.server = server;
        this.player = player;
        this.corruption = corruption;
        this.random = random;
        this.intensity = intensity;
        this.duration = duration;
        this.seed = seed;
        this.definition = definition;
        this.forced = forced || showcase;
        this.showcase = showcase;
    }

    public MinecraftServer server() {
        return server;
    }

    public ServerPlayer player() {
        return player;
    }

    public ServerLevel level() {
        return player.serverLevel();
    }

    public CorruptionContext corruption() {
        return corruption;
    }

    public RandomSource random() {
        return random;
    }

    /** 0..1 strength for this run, already scaled by stage, region and the intensity setting. */
    public float intensity() {
        return intensity;
    }

    public int duration() {
        return duration;
    }

    public long seed() {
        return seed;
    }

    public NullEventDefinition definition() {
        return definition;
    }

    /** True when started by /null event (or the showcase); actions may relax their placement rules. */
    public boolean forced() {
        return forced;
    }

    /**
     * True when run by {@code /null showcase} for an operator who wants to see it: whatever the
     * event places or shows should end up in front of the player and in plain view (always forced
     * as well). Placement helpers that only see the player read the same hint from
     * {@link ShowcaseHint#active(ServerPlayer)}.
     */
    public boolean showcase() {
        return showcase;
    }

    /** Starts a client effect for this player. */
    public void sendEffect(String effect) {
        if (showcase && effect.startsWith(BLOCK_EFFECT_PREFIX)) {
            NullNetworking.sendEffect(player, effect, seed, intensity, duration, Optional.empty(), SHOWCASE_BLOCK_DATA, "");
            return;
        }
        NullNetworking.sendEffect(player, effect, seed, intensity, duration);
    }

    public void sendEffect(String effect, BlockPos pos) {
        if (showcase && effect.startsWith(BLOCK_EFFECT_PREFIX)) {
            NullNetworking.sendEffect(player, effect, seed, intensity, duration, Optional.of(pos), SHOWCASE_BLOCK_DATA, "");
            return;
        }
        NullNetworking.sendEffect(player, effect, seed, intensity, duration, pos);
    }

    public void sendEffect(String effect, Optional<BlockPos> pos, int data, String text) {
        NullNetworking.sendEffect(player, effect, seed, intensity, duration, pos, data, text);
    }

    /**
     * Plays one of NULL's sounds for this player through the client's audio corruption
     * system (so the player's own audio settings apply). With no position the client picks
     * an ambiguous spot around the player.
     */
    public void playSound(SoundEvent sound, Optional<BlockPos> pos, float volume, float pitch) {
        String id = sound.getLocation().toString();
        NullNetworking.sendEffect(player, "audio.sound", seed, volume, duration, pos, Math.round(pitch * 100.0F), id);
    }
}
