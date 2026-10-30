package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.chat.ChatLine.Category;
import dev.kristian.nullmod.chat.ChatLine.Speaker;
import dev.kristian.nullmod.chat.ChatMessages.Dialogue;
import dev.kristian.nullmod.chat.ChatMessages.Step;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionSystem;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.entity.UsernameGenerator;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerManager;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The corrupted chat. Lines appear at random intervals (rare in Stage 2, constant and bursty at
 * the end of Stage 5), as reactions to what players do ({@link ChatReactions}) and from the chat
 * random events ({@link ChatEvents}). They come from generated usernames, from "Unknown", from
 * the simulated players of the final stage and now and then from NULL itself.
 * <p>
 * Every line is a system message built to look exactly like the vanilla format it imitates
 * ({@link ChatFormat}); nothing is ever signed chat and nothing leaves the server. Lines without
 * anything personal in them go to everyone in a dimension; lines that name, quote or locate a
 * player go to that player only. A per-player history (the last {@value #HISTORY_SIZE} lines,
 * saved with the world) and per-line cooldowns keep lines from repeating.
 * <p>
 * Everything runs on the server thread. Text is resolved and corrupted when a line is
 * delivered, so delayed lines use the player's state at that moment.
 */
public final class ChatCorruptionManager implements CorruptionSystem {
    public static final ChatCorruptionManager INSTANCE = new ChatCorruptionManager();
    /** Key of this system's {@link CorruptionData#systemTag} (histories, who has been to NULL). */
    public static final String TAG_KEY = "chat";
    /** How many recent lines per player are never picked again for that player. */
    public static final int HISTORY_SIZE = 60;

    private static final int DELIVERED_LOG = 40;
    private static final int SAID_MEMORY = 8;
    private static final int MAX_PENDING = 600;
    private static final int DELIVERIES_PER_TICK = 48;
    private static final int REGULARS = 6;
    private static final long MEMORY_WINDOW = 20L * 60L * 120L;
    private static final long SAID_MEMORY_GAP = 20L * 60L;
    private static final long LINE_COOLDOWN = 20L * 60L * 10L;
    private static final long SHORT_LINE_COOLDOWN = 20L * 30L;
    private static final long DIALOGUE_COOLDOWN = 20L * 60L * 20L;

    /** Average seconds between ambient lines for one player (personal) and for one dimension, by stage. */
    private static final float[] PERSONAL_SECONDS = {0.0F, 1800.0F, 720.0F, 300.0F, 150.0F};
    private static final float[] DIMENSION_SECONDS = {0.0F, 3000.0F, 1200.0F, 420.0F, 90.0F};
    /** Corruption strength at the start of each stage, and how much it grows through the stage. */
    private static final float[] STRENGTH = {0.0F, 0.1F, 0.22F, 0.38F, 0.55F};
    private static final float[] STRENGTH_GROWTH = {0.0F, 0.06F, 0.1F, 0.12F, 0.3F};
    /** Shortest gap between two reactions for one player, by stage (ticks). */
    private static final long[] REACTION_GAP = {20L * 600L, 20L * 120L, 20L * 60L, 20L * 40L, 20L * 20L};

    private static final String[] ADVANCEMENT_DESCRIPTIONS = {"???", "You were seen.", "It knows your name.",
            "There is no way back.", "Nobody else got this one."};

    /** What a piece of ambient chatter is; weights per stage in {@link #AMBIENT_WEIGHTS}. */
    private enum Ambient {
        QUESTION, WHISPER, WARNING, NULL_SYSTEM, CONNECTION, NAME, STAGE5, MEMORY, DIALOGUE
    }

    private static final int[][] AMBIENT_WEIGHTS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {34, 34, 6, 0, 14, 0, 0, 12, 0},
            {22, 24, 14, 6, 10, 0, 0, 20, 4},
            {14, 18, 14, 8, 7, 9, 0, 22, 8},
            {8, 9, 10, 10, 5, 8, 26, 12, 12}};

    private final RandomSource random = RandomSource.create();
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<ResourceKey<Level>, Long> dimensionSchedule = new HashMap<>();
    private final PriorityQueue<Delivery> queue = new PriorityQueue<>(
            Comparator.comparingLong(Delivery::at).thenComparingLong(Delivery::order));
    private final Map<Integer, Long> lineCooldowns = new HashMap<>();
    private final Map<Integer, Long> dialogueCooldowns = new HashMap<>();
    private final Map<String, Long> worldCooldowns = new HashMap<>();
    private long order;
    @Nullable
    private MinecraftServer loadedFor;
    private long regularsSeed;
    private List<String> regulars = List.of();

    private ChatCorruptionManager() {
    }

    // ------------------------------------------------------------------ types

    private static final class PlayerState {
        final UUID id;
        long nextAmbient = -1L;
        long nextReaction;
        /** Server tick of the last "chat:said" memory, or -1 for never. */
        long lastSaidMemory = -1L;
        int deliveredCount;
        final Map<String, Long> cooldowns = new HashMap<>();
        /** Newest first. */
        final ArrayDeque<Integer> history = new ArrayDeque<>();
        final ArrayDeque<String> delivered = new ArrayDeque<>();
        final ArrayDeque<String> said = new ArrayDeque<>();

        PlayerState(UUID id) {
            this.id = id;
        }
    }

    /** Who says a line: the speaker kind and, for usernames, the name. */
    private record Speech(Speaker speaker, String name) {
    }

    /** A finished line: the component to send, its plain message text and the database line it came from (or -1). */
    private record Rendered(Component line, String plain, boolean chatLike, int lineIndex) {
    }

    /** Produces the line for a recipient when it is delivered; may return null to drop it. */
    @FunctionalInterface
    private interface Payload {
        @Nullable
        Rendered render(ServerPlayer target);
    }

    /**
     * A line waiting to be sent: to one player ({@code player}) or to everyone in a dimension
     * ({@code dimension}, rendered for {@code focus}), once {@code condition} (if any) holds.
     */
    private record Delivery(long at, long order, @Nullable UUID player, @Nullable ResourceKey<Level> dimension,
                            @Nullable UUID focus, Payload payload, @Nullable Predicate<ServerPlayer> condition) {
    }

    // ------------------------------------------------------------------ CorruptionSystem

    @Override
    public String name() {
        return TAG_KEY;
    }

    @Override
    public void tick(MinecraftServer server, CorruptionContext ctx) {
        ensureLoaded(server);
        long now = server.getTickCount();
        deliverDue(server, now);
        ChatReactions.drainChat(server, ctx, now);
        if (now % 20L != 7L) {
            return;
        }
        ChatReactions.tick(server, ctx, now);
        if (ctx.paused() || ctx.stage() == CorruptionStage.START || !ambientEnabled()) {
            return;
        }
        double frequency = NullConfig.get(NullConfig.CHAT_FREQUENCY);
        tickPersonal(server, ctx, now, frequency);
        tickDimensions(server, ctx, now, frequency);
    }

    @Override
    public void onStageChanged(MinecraftServer server, CorruptionStage from, CorruptionStage to, CorruptionContext context) {
        ensureLoaded(server);
        for (PlayerState state : players.values()) {
            state.nextAmbient = -1L;
        }
        dimensionSchedule.clear();
        if (to == CorruptionStage.START) {
            queue.clear();
        }
    }

    @Override
    public void onPlayerJoin(ServerPlayer player, CorruptionContext context) {
        ensureLoaded(player.server);
        if (player instanceof FakePlayer) {
            return;
        }
        PlayerState state = state(player);
        state.nextAmbient = -1L;
        float chance = switch (context.stage()) {
            case MIDDLE -> 0.15F;
            case ABOVE_MIDDLE -> 0.3F;
            case NULL -> 0.4F;
            default -> 0.0F;
        };
        if (chance > 0.0F) {
            react(player, Category.REPLY_GREETING, null, chance, 20 * 6, 20 * 18, "join", 20 * 60 * 20, Map.of(), null, false);
        }
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        UUID id = player.getUUID();
        players.remove(id);
        queue.removeIf(delivery -> id.equals(delivery.player()));
        ChatReactions.forget(id);
    }

    @Override
    public void onReset(MinecraftServer server) {
        clearAll();
        loadedFor = server;
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        clearAll();
        loadedFor = null;
    }

    private void ensureLoaded(MinecraftServer server) {
        if (loadedFor != server) {
            clearAll();
            loadedFor = server;
        }
    }

    private void clearAll() {
        players.clear();
        dimensionSchedule.clear();
        queue.clear();
        lineCooldowns.clear();
        dialogueCooldowns.clear();
        worldCooldowns.clear();
        regulars = List.of();
        ChatReactions.clear();
    }

    // ------------------------------------------------------------------ ambient scheduling

    /** Ambient chatter runs while the chat frequency is above 0 and corrupted_chat is not disabled. */
    public static boolean ambientEnabled() {
        return NullConfig.get(NullConfig.CHAT_FREQUENCY) > 0.0 && !NullConfig.eventDisabled("corrupted_chat");
    }

    private void tickPersonal(MinecraftServer server, CorruptionContext ctx, long now, double frequency) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!eligible(player)) {
                continue;
            }
            PlayerState state = state(player);
            if (state.nextAmbient < 0L) {
                state.nextAmbient = now + delay(PERSONAL_SECONDS, ctx, frequency) / 2L + 20L * 30L;
                continue;
            }
            if (now < state.nextAmbient) {
                continue;
            }
            state.nextAmbient = now + delay(PERSONAL_SECONDS, ctx, frequency);
            ambient(player, ctx, ctx.stage(), random, true);
        }
    }

    private void tickDimensions(MinecraftServer server, CorruptionContext ctx, long now, double frequency) {
        Map<ResourceKey<Level>, List<ServerPlayer>> byDimension = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (eligible(player)) {
                byDimension.computeIfAbsent(player.level().dimension(), key -> new ArrayList<>()).add(player);
            }
        }
        dimensionSchedule.keySet().retainAll(byDimension.keySet());
        for (Map.Entry<ResourceKey<Level>, List<ServerPlayer>> entry : byDimension.entrySet()) {
            Long next = dimensionSchedule.get(entry.getKey());
            if (next == null) {
                dimensionSchedule.put(entry.getKey(), now + delay(DIMENSION_SECONDS, ctx, frequency) / 2L + 20L * 45L);
                continue;
            }
            if (now < next) {
                continue;
            }
            dimensionSchedule.put(entry.getKey(), now + delay(DIMENSION_SECONDS, ctx, frequency));
            List<ServerPlayer> list = entry.getValue();
            ambient(list.get(random.nextInt(list.size())), ctx, ctx.stage(), random, false);
        }
    }

    private long delay(float[] table, CorruptionContext ctx, double frequency) {
        float base = table[ctx.stage().number() - 1];
        if (ctx.stage() == CorruptionStage.NULL) {
            // Towards saturation the final stage gets more and more chaotic.
            base *= 1.0F - 0.6F * ctx.stageProgress();
        }
        float jitter = 0.5F + random.nextFloat();
        return Math.max(100L, (long) (base * jitter * 20.0F / Math.max(0.05, frequency)));
    }

    private static boolean eligible(ServerPlayer player) {
        return !(player instanceof FakePlayer) && !player.isSpectator() && !player.hasDisconnected();
    }

    // ------------------------------------------------------------------ public API

    /**
     * One piece of ambient chatter (a line, a short exchange, a memory, in Stage 5 sometimes a
     * burst) for {@code focus} alone ({@code personal}) or for everyone in its dimension.
     * The first line is sent immediately. Returns false if nothing fitted.
     */
    public boolean ambient(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, RandomSource r, boolean personal) {
        ensureLoaded(focus.server);
        if (stage == CorruptionStage.START) {
            return false;
        }
        if (!personal && stage == CorruptionStage.NULL && r.nextFloat() < 0.1F + 0.2F * progress(ctx, stage)
                && burst(focus, ctx, stage, r, false)) {
            return true;
        }
        int[] weights = AMBIENT_WEIGHTS[stage.number() - 1].clone();
        if (!personal) {
            weights[Ambient.NAME.ordinal()] = 0;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            Ambient kind = roll(weights, r);
            if (kind == null) {
                return false;
            }
            boolean sent = switch (kind) {
                case MEMORY -> memoryLine(focus, ctx, stage, r, personal);
                case DIALOGUE -> dialogue(focus, ctx, stage, r, personal);
                default -> single(focus, ctx, stage, categoryOf(kind), null, personal, 0L, r, Map.of(), 1.0F);
            };
            if (sent) {
                return true;
            }
            weights[kind.ordinal()] = 0;
        }
        return false;
    }

    /**
     * Sends one line of {@code category} now, to the player or their dimension. Other systems
     * can use this to make the chat comment on something. Returns false if no line fits.
     */
    public boolean say(ServerPlayer player, Category category, CorruptionStage stage, boolean personal) {
        ensureLoaded(player.server);
        return single(player, CorruptionManager.context(player.server), stage, category, null, personal, 0L, random, Map.of(), 1.0F);
    }

    /** A rapid burst of short lines from several names (chat_burst, and Stage 5 chaos). */
    public boolean burst(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, RandomSource r, boolean personal) {
        ensureLoaded(focus.server);
        long now = focus.server.getTickCount();
        boolean last = stage == CorruptionStage.NULL;
        int count = last ? 5 + r.nextInt(8) : 4 + r.nextInt(4);
        int voices = 2 + r.nextInt(last ? 4 : 2);
        List<String> names = new ArrayList<>(voices);
        for (int i = 0; i < voices; i++) {
            names.add(pickName(focus.server, stage, focus, r));
        }
        Collection<PlayerState> audience = audience(focus, personal);
        long at = now;
        int sent = 0;
        ChatLine previous = null;
        for (int i = 0; i < count; i++) {
            Speech speech = new Speech(r.nextFloat() < 0.1F ? Speaker.UNKNOWN : Speaker.USER, names.get(r.nextInt(names.size())));
            float strength = Math.min(1.0F, strength(ctx, stage, r, false) * 1.3F + 0.1F);
            ChatLine line;
            if (previous != null && r.nextFloat() < 0.22F) {
                // Someone else repeats what was just said.
                line = previous;
            } else {
                Category category = r.nextFloat() < 0.6F ? Category.BURST : (last ? Category.STAGE5 : Category.WARNING);
                line = pick(category, stage, audience, personal, null, r, now);
                if (line == null) {
                    line = pick(Category.BURST, stage, audience, personal, null, r, now);
                }
            }
            if (line == null || line.speaker() != Speaker.USER && line.speaker() != Speaker.UNKNOWN && line.speaker() != Speaker.VOICE) {
                continue;
            }
            if (schedule(focus, personal, at, linePayload(line, speech, strength, r.nextLong(), Map.of()), null)) {
                sent++;
                previous = line;
            }
            at += 3 + r.nextInt(Math.max(2, 14 - i));
        }
        if (sent > 0 && last && r.nextFloat() < 0.3F) {
            single(focus, ctx, stage, Category.NULL_SYSTEM, null, personal, at - now + 30 + r.nextInt(40), r, Map.of(), 1.0F);
        }
        return sent > 0;
    }

    /** name_whisper: the player's name, whispered by someone who should not know it. */
    public boolean nameWhisper(ServerPlayer player, CorruptionContext ctx, CorruptionStage stage, RandomSource r) {
        ensureLoaded(player.server);
        long now = player.server.getTickCount();
        ChatLine line = pick(Category.NAME, stage, List.of(state(player)), true, null, r, now);
        if (line == null) {
            return false;
        }
        float roll = r.nextFloat();
        Speaker speaker = roll < 0.6F ? Speaker.WHISPER : roll < 0.8F ? Speaker.UNKNOWN : Speaker.USER;
        Speech speech = new Speech(speaker, pickName(player.server, stage, player, r));
        float strength = strength(ctx, stage, r, false) * 0.8F;
        schedule(player, true, now, linePayload(line, speech, strength, r.nextLong(), Map.of()), null);
        if (r.nextFloat() < 0.3F) {
            // Again, and again, a little more broken each time.
            int repeats = 1 + r.nextInt(2);
            long at = now;
            for (int i = 0; i < repeats; i++) {
                at += 8 + r.nextInt(16);
                schedule(player, true, at, linePayload(line, speech, Math.min(1.0F, strength + 0.2F * (i + 1)), r.nextLong(), Map.of()), null);
            }
        }
        return true;
    }

    /** null_system_message: NULL speaks, to the player or to their whole dimension. */
    public boolean nullSystem(ServerPlayer player, CorruptionContext ctx, CorruptionStage stage, RandomSource r) {
        ensureLoaded(player.server);
        return single(player, ctx, stage, Category.NULL_SYSTEM, null, r.nextFloat() < 0.55F, 0L, r, Map.of(), 1.0F);
    }

    /**
     * fake_join_message: a yellow "joined the game" line for someone who is not there, maybe a
     * few words from them, and usually a "left the game" line some time later.
     */
    public boolean fakeJoin(ServerPlayer player, CorruptionContext ctx, CorruptionStage stage, RandomSource r) {
        ensureLoaded(player.server);
        MinecraftServer server = player.server;
        long now = server.getTickCount();
        boolean personal = r.nextFloat() < 0.7F;
        String name = null;
        if (r.nextFloat() < 0.4F) {
            name = seenVisitor(player).orElse(null);
        }
        if (name == null || isTaken(server, name)) {
            name = r.nextFloat() < 0.5F ? regular(server, r.nextInt(REGULARS)) : UsernameGenerator.random(r);
        }
        if (isTaken(server, name)) {
            name = UsernameGenerator.unique(r, takenNames(server));
        }
        String joiner = name;
        boolean renamed = personal && stage.atLeast(CorruptionStage.ABOVE_MIDDLE) && r.nextFloat() < 0.12F;
        schedule(player, personal, now, target -> {
            MutableComponent line = renamed
                    ? ChatFormat.joinedRenamed(ChatFormat.playerName(joiner), target.getDisplayName())
                    : ChatFormat.joined(ChatFormat.playerName(joiner));
            return new Rendered(line, line.getString(), false, -1);
        }, null);
        WorldMemory.remember(server, "chat:fake_join", personal ? player.getUUID() : null, joiner);
        long at = now + 40 + r.nextInt(100);
        if (r.nextFloat() < 0.65F) {
            ChatLine hello = pick(Category.JOIN_HELLO, stage, audience(player, personal), personal, null, r, now);
            if (hello != null) {
                schedule(player, personal, at, linePayload(hello, new Speech(Speaker.USER, joiner),
                        strength(ctx, stage, r, true), r.nextLong(), Map.of()), null);
            }
        }
        if (r.nextFloat() < 0.7F) {
            schedule(player, personal, at + 300 + r.nextInt(900), target -> {
                MutableComponent line = ChatFormat.left(ChatFormat.playerName(joiner));
                return new Rendered(line, line.getString(), false, -1);
            }, null);
        }
        return true;
    }

    /**
     * fake_death_message: a vanilla death message for someone who did not die (sometimes the
     * player themselves, sometimes "slain by" the player).
     */
    public boolean fakeDeath(ServerPlayer player, CorruptionContext ctx, CorruptionStage stage, RandomSource r) {
        ensureLoaded(player.server);
        long now = player.server.getTickCount();
        ChatFormat.Death[] simple = {ChatFormat.Death.OUT_OF_WORLD, ChatFormat.Death.GENERIC, ChatFormat.Death.FELL,
                ChatFormat.Death.IN_WALL, ChatFormat.Death.DROWNED, ChatFormat.Death.STARVED};
        ChatFormat.Death kind = simple[r.nextInt(simple.length)];
        boolean self = r.nextFloat() < 0.35F;
        String victim = self ? "" : pickName(player.server, stage, player, r);
        boolean slain = !self && r.nextFloat() < 0.25F;
        ChatFormat.Death finalKind = slain ? ChatFormat.Death.SLAIN : kind;
        return schedule(player, true, now, target -> {
            Component who = victim.isEmpty() ? target.getDisplayName() : ChatFormat.playerName(victim);
            MutableComponent line = ChatFormat.death(finalKind, who, target.getDisplayName());
            return new Rendered(line, line.getString(), false, -1);
        }, null);
    }

    /**
     * echo_player: repeats something the player said (WorldMemory "chat:said"), distorted.
     * Returns false if they have not said anything that can be quoted.
     */
    public boolean echo(ServerPlayer player, CorruptionContext ctx, CorruptionStage stage, RandomSource r) {
        ensureLoaded(player.server);
        List<String> options = saidOptions(player);
        if (options.isEmpty()) {
            return false;
        }
        String said = options.get(r.nextInt(Math.min(5, options.size())));
        return single(player, ctx, stage, Category.ECHO, null, true, 0L, r, Map.of("said", said), 1.0F);
    }

    /**
     * Schedules a reaction for one player if the stage, the chat settings, the dice and the
     * cooldowns allow it.
     *
     * @param chance       0..1, scaled by the chat frequency setting
     * @param cooldownKey  per-player cooldown for this kind of reaction (null for none)
     * @param condition    checked again at delivery (e.g. "still asleep"); null for none
     * @param priority     ignores the minimum gap between two reactions (still respects the cooldown key)
     */
    public boolean react(ServerPlayer player, Category category, @Nullable String topic, float chance, int minDelay, int maxDelay,
                         @Nullable String cooldownKey, int cooldownTicks, Map<String, String> overrides,
                         @Nullable Predicate<ServerPlayer> condition, boolean priority) {
        MinecraftServer server = player.server;
        ensureLoaded(server);
        if (!eligible(player)) {
            return false;
        }
        CorruptionContext ctx = CorruptionManager.context(server);
        double frequency = NullConfig.get(NullConfig.CHAT_FREQUENCY);
        if (ctx.paused() || frequency <= 0.0) {
            return false;
        }
        long now = server.getTickCount();
        PlayerState state = state(player);
        if (!priority && now < state.nextReaction) {
            return false;
        }
        if (cooldownKey != null && state.cooldowns.getOrDefault(cooldownKey, Long.MIN_VALUE) > now) {
            return false;
        }
        if (random.nextFloat() >= Math.min(1.0F, chance * (float) frequency)) {
            return false;
        }
        long delay = minDelay + (maxDelay > minDelay ? random.nextInt(maxDelay - minDelay + 1) : 0);
        if (!single(player, ctx, ctx.stage(), category, topic, true, delay, random, overrides, 1.0F, condition)) {
            return false;
        }
        state.nextReaction = now + REACTION_GAP[ctx.stage().number() - 1];
        if (cooldownKey != null) {
            state.cooldowns.put(cooldownKey, now + cooldownTicks);
        }
        return true;
    }

    /**
     * A world-wide reaction (the moon, simulated players coming and going): one line for
     * everyone in {@code focus}'s dimension, limited by a world-wide cooldown.
     */
    public boolean reactWorld(ServerPlayer focus, Category category, @Nullable String topic, float chance, int minDelay, int maxDelay,
                              String cooldownKey, int cooldownTicks, Map<String, String> overrides) {
        MinecraftServer server = focus.server;
        ensureLoaded(server);
        CorruptionContext ctx = CorruptionManager.context(server);
        double frequency = NullConfig.get(NullConfig.CHAT_FREQUENCY);
        long now = server.getTickCount();
        if (ctx.paused() || frequency <= 0.0 || worldCooldowns.getOrDefault(cooldownKey, Long.MIN_VALUE) > now) {
            return false;
        }
        if (random.nextFloat() >= Math.min(1.0F, chance * (float) frequency)) {
            return false;
        }
        long delay = minDelay + (maxDelay > minDelay ? random.nextInt(maxDelay - minDelay + 1) : 0);
        if (!single(focus, ctx, ctx.stage(), category, topic, false, delay, random, overrides, 1.0F)) {
            return false;
        }
        worldCooldowns.put(cooldownKey, now + cooldownTicks);
        return true;
    }

    /**
     * Remembers something a player typed, for echoes and {@code {said}}: always in the player's
     * own short list, and in {@link WorldMemory} as {@code "chat:said"} at most once a minute per
     * player (or always when {@code force}), so chat never floods the shared memory.
     */
    public void rememberSaid(ServerPlayer player, String clean, boolean force) {
        if (clean.isEmpty()) {
            return;
        }
        ensureLoaded(player.server);
        PlayerState state = state(player);
        state.said.addFirst(clean);
        while (state.said.size() > SAID_MEMORY) {
            state.said.removeLast();
        }
        long now = player.server.getTickCount();
        if (force || state.lastSaidMemory < 0L || now - state.lastSaidMemory >= SAID_MEMORY_GAP) {
            state.lastSaidMemory = now;
            WorldMemory.remember(player.server, "chat:said", player.getUUID(), clean);
        }
    }

    /** Marks that a player has been inside the NULL dimension; true if this was their first time. */
    public boolean markVisitedNull(ServerPlayer player) {
        CorruptionData data = CorruptionManager.data(player.server);
        CompoundTag root = data.systemTag(TAG_KEY);
        ListTag list = root.getList("visited_null", Tag.TAG_STRING);
        String id = player.getUUID().toString();
        for (int i = 0; i < list.size(); i++) {
            if (list.getString(i).equals(id)) {
                return false;
            }
        }
        list.add(StringTag.valueOf(id));
        root.put("visited_null", list);
        data.setDirty();
        return true;
    }

    /** Resolves a template's placeholders for a player (with {@code overrides} taking precedence). */
    public String resolve(String template, ServerPlayer target, Map<String, String> overrides) {
        return resolve(template, target, overrides, "");
    }

    /** How many lines have been sent to a player since they joined (for tests and debugging). */
    public int deliveredCount(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? 0 : state.deliveredCount;
    }

    /** The plain text of the last lines sent to a player, newest first. */
    public List<String> recentlyDelivered(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? List.of() : List.copyOf(state.delivered);
    }

    /** Database line ids a player saw most recently, newest first. */
    public List<Integer> history(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? List.of() : List.copyOf(state.history);
    }

    /** Lines waiting to be delivered. */
    public int pending() {
        return queue.size();
    }

    // ------------------------------------------------------------------ building blocks

    private boolean single(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, Category category, @Nullable String topic,
                           boolean personal, long delay, RandomSource r, Map<String, String> overrides, float strengthScale) {
        return single(focus, ctx, stage, category, topic, personal, delay, r, overrides, strengthScale, null);
    }

    private boolean single(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, Category category, @Nullable String topic,
                           boolean personal, long delay, RandomSource r, Map<String, String> overrides, float strengthScale,
                           @Nullable Predicate<ServerPlayer> condition) {
        long now = focus.server.getTickCount();
        ChatLine line = pick(category, stage, audience(focus, personal), personal, topic, r, now);
        if (line == null) {
            return false;
        }
        Speech speech = speechFor(line, focus, stage, r, personal);
        float strength = Math.min(1.0F, strength(ctx, stage, r, category == Category.ECHO) * strengthScale * categoryScale(category));
        if (!schedule(focus, personal, now + delay, linePayload(line, speech, strength, r.nextLong(), overrides), condition)) {
            return false;
        }
        // Rapid succession: the same line again, a little more broken each time.
        boolean typed = speech.speaker() == Speaker.USER || speech.speaker() == Speaker.UNKNOWN;
        float again = stage == CorruptionStage.NULL ? 0.1F + 0.1F * progress(ctx, stage)
                : stage == CorruptionStage.ABOVE_MIDDLE ? 0.05F : 0.0F;
        if (typed && condition == null && r.nextFloat() < again) {
            long at = now + delay;
            int repeats = 1 + r.nextInt(2);
            for (int i = 0; i < repeats; i++) {
                at += 4 + r.nextInt(9);
                schedule(focus, personal, at, linePayload(line, speech, Math.min(1.0F, strength + 0.15F * (i + 1)), r.nextLong(), overrides), null);
            }
        }
        return true;
    }

    private boolean memoryLine(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, RandomSource r, boolean personal) {
        MinecraftServer server = focus.server;
        List<WorldMemory.Entry> entries = personal
                ? WorldMemory.recentFor(server, focus.getUUID(), MEMORY_WINDOW)
                : WorldMemory.recent(server, MEMORY_WINDOW);
        if (entries.isEmpty()) {
            return false;
        }
        for (int attempt = 0; attempt < 6; attempt++) {
            WorldMemory.Entry entry = entries.get(r.nextInt(entries.size()));
            ChatReactions.MemoryRef ref = ChatReactions.memoryRef(entry.key());
            if (ref == null) {
                continue;
            }
            if (!personal && entry.player() != null) {
                ServerPlayer who = server.getPlayerList().getPlayer(entry.player());
                if (who == null || who.level() != focus.level()) {
                    continue;
                }
            }
            Map<String, String> overrides = ref.detailIsName() && !entry.detail().isEmpty()
                    ? Map.of("visitor", entry.detail()) : Map.of();
            if (single(focus, ctx, stage, ref.category(), ref.topic(), personal, 0L, r, overrides, 1.0F)) {
                return true;
            }
        }
        return false;
    }

    private boolean dialogue(ServerPlayer focus, CorruptionContext ctx, CorruptionStage stage, RandomSource r, boolean personal) {
        long now = focus.server.getTickCount();
        List<Dialogue> options = new ArrayList<>();
        for (Dialogue d : ChatMessages.dialogues()) {
            if (d.allowedAt(stage) && (personal || !d.personal()) && dialogueCooldowns.getOrDefault(d.index(), Long.MIN_VALUE) <= now) {
                options.add(d);
            }
        }
        if (options.isEmpty()) {
            return false;
        }
        Dialogue d = options.get(r.nextInt(options.size()));
        Map<Character, String> names = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (char role : new char[] {'A', 'B', 'C'}) {
            String name = pickName(focus.server, stage, focus, r);
            for (int tries = 0; tries < 4 && used.contains(name); tries++) {
                name = pickName(focus.server, stage, focus, r);
            }
            used.add(name);
            names.put(role, name);
        }
        float strength = strength(ctx, stage, r, false);
        long at = now;
        for (Step step : d.steps()) {
            schedule(focus, personal, at, stepPayload(step, names, strength, r.nextLong()), null);
            at += 30 + (long) (step.text().length() * 2.2F) + r.nextInt(40);
        }
        dialogueCooldowns.put(d.index(), now + DIALOGUE_COOLDOWN);
        return true;
    }

    private Payload stepPayload(Step step, Map<Character, String> names, float strength, long seed) {
        return target -> {
            RandomSource r = RandomSource.create(seed);
            if (step.isJoinOrLeave()) {
                String who = names.getOrDefault(step.text().isEmpty() ? 'A' : step.text().charAt(0), "Player");
                MutableComponent line = step.role() == 'J'
                        ? ChatFormat.joined(ChatFormat.playerName(who))
                        : ChatFormat.left(ChatFormat.playerName(who));
                return new Rendered(line, line.getString(), false, -1);
            }
            Speech speech = switch (step.role()) {
                case 'U' -> new Speech(Speaker.UNKNOWN, "");
                case 'N' -> new Speech(Speaker.NULL, "");
                case 'L' -> new Speech(Speaker.LOG, "");
                default -> new Speech(Speaker.USER, names.getOrDefault(step.role(), "Player"));
            };
            String text = resolve(step.text(), target, Map.of("name", names.getOrDefault('C', "")), speech.name());
            if (text.isBlank()) {
                return null;
            }
            return render(-1, speech, text, strength, target, r);
        };
    }

    private Payload linePayload(ChatLine line, Speech speech, float strength, long seed, Map<String, String> overrides) {
        return target -> {
            RandomSource r = RandomSource.create(seed);
            String text = resolve(line.text(), target, overrides, speech.name());
            if (text.isBlank()) {
                return null;
            }
            return render(line.index(), speech, text, strength, target, r);
        };
    }

    private Rendered render(int lineIndex, Speech speech, String text, float strength, ServerPlayer target, RandomSource r) {
        switch (speech.speaker()) {
            case NULL -> {
                MutableComponent body = nullBody(text, strength, r);
                return new Rendered(ChatFormat.nullLine(body), body.getString(), false, lineIndex);
            }
            case LOG -> {
                MutableComponent body = systemBody(text, strength, r);
                return new Rendered(ChatFormat.log(body), body.getString(), false, lineIndex);
            }
            case ERROR -> {
                MutableComponent body = systemBody(text, strength, r);
                return new Rendered(ChatFormat.error(body), body.getString(), false, lineIndex);
            }
            case ADVANCEMENT -> {
                String title = TextCorruptor.sanitize(text);
                Component who = speech.name().isEmpty() ? target.getDisplayName() : ChatFormat.playerName(speech.name());
                String description = ADVANCEMENT_DESCRIPTIONS[r.nextInt(ADVANCEMENT_DESCRIPTIONS.length)];
                return new Rendered(ChatFormat.advancement(who, title, description), title, false, lineIndex);
            }
            case UNKNOWN -> {
                MutableComponent body = TextCorruptor.corrupt(text, Math.min(1.0F, strength * 1.15F), r);
                return new Rendered(ChatFormat.chat(ChatFormat.unknownName(), body), body.getString(), true, lineIndex);
            }
            case WHISPER -> {
                MutableComponent body = TextCorruptor.corrupt(text, strength * 0.7F, r);
                return new Rendered(ChatFormat.whisper(ChatFormat.playerName(speech.name()), body), body.getString(), true, lineIndex);
            }
            case SELF -> {
                MutableComponent body = TextCorruptor.corrupt(text, strength, r);
                return new Rendered(ChatFormat.chat(target.getDisplayName(), body), body.getString(), true, lineIndex);
            }
            default -> {
                String name = speech.name().isEmpty() ? UsernameGenerator.random(r) : speech.name();
                MutableComponent body = TextCorruptor.corrupt(text, strength, r);
                return new Rendered(ChatFormat.chat(ChatFormat.playerName(name), body), body.getString(), true, lineIndex);
            }
        }
    }

    /** NULL's lines stay mostly clean; damage shows up as obfuscated spans and swapped letters. */
    private static MutableComponent nullBody(String text, float strength, RandomSource r) {
        String t = TextCorruptor.sanitize(text);
        if (strength > 0.3F && r.nextFloat() < strength * 0.4F) {
            t = TextCorruptor.substitute(t, 0.04F + 0.1F * strength, r);
        }
        if (strength > 0.25F && r.nextFloat() < strength * 0.6F) {
            return TextCorruptor.obfuscate(t, 1, r);
        }
        return Component.literal(t);
    }

    /** Log and error lines are the game talking: clean, except for the odd obfuscated piece late on. */
    private static MutableComponent systemBody(String text, float strength, RandomSource r) {
        String t = TextCorruptor.sanitize(text);
        if (strength > 0.45F && r.nextFloat() < (strength - 0.45F)) {
            return TextCorruptor.obfuscate(t, 1, r);
        }
        return Component.literal(t);
    }

    // ------------------------------------------------------------------ picking lines

    /**
     * Picks a line of {@code category} allowed at {@code stage} that nobody in {@code audience}
     * saw recently and that is not on its cooldown, relaxing those rules step by step if
     * nothing is left. Lines with a topic are only used for that topic (and preferred for it).
     */
    @Nullable
    private ChatLine pick(Category category, CorruptionStage stage, Collection<PlayerState> audience, boolean personal,
                          @Nullable String topic, RandomSource r, long now) {
        List<ChatLine> lines = ChatMessages.of(category);
        for (int relax = 0; relax < 4; relax++) {
            List<ChatLine> candidates = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            int total = 0;
            for (ChatLine line : lines) {
                if (!line.allowedAt(stage) || (!personal && line.personal())) {
                    continue;
                }
                boolean topical = !line.topic().isEmpty();
                if (topical && !line.topic().equals(topic)) {
                    continue;
                }
                if (relax < 1 && lineCooldowns.getOrDefault(line.index(), Long.MIN_VALUE) > now) {
                    continue;
                }
                if (relax < 3 && seenBy(audience, line.index(), relax < 2 ? HISTORY_SIZE : 10)) {
                    continue;
                }
                int weight = topical ? 3 : 1;
                candidates.add(line);
                weights.add(weight);
                total += weight;
            }
            if (total > 0) {
                int roll = r.nextInt(total);
                for (int i = 0; i < candidates.size(); i++) {
                    roll -= weights.get(i);
                    if (roll < 0) {
                        ChatLine picked = candidates.get(i);
                        lineCooldowns.put(picked.index(), now + cooldownFor(picked));
                        return picked;
                    }
                }
            }
        }
        return null;
    }

    private static boolean seenBy(Collection<PlayerState> audience, int index, int depth) {
        for (PlayerState state : audience) {
            int n = 0;
            for (Integer id : state.history) {
                if (n++ >= depth) {
                    break;
                }
                if (id == index) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long cooldownFor(ChatLine line) {
        return switch (line.category()) {
            case BURST, REPLY, REPLY_QUESTION, REPLY_GREETING, REPLY_WHO, REPLY_FEAR, REPLY_LEAVE, REPLY_NULL, ECHO -> SHORT_LINE_COOLDOWN;
            case NAME, STILL, DEATH -> SHORT_LINE_COOLDOWN * 4L;
            default -> LINE_COOLDOWN;
        };
    }

    private Speech speechFor(ChatLine line, ServerPlayer focus, CorruptionStage stage, RandomSource r, boolean personal) {
        Speaker speaker = line.speaker();
        if (speaker == Speaker.VOICE) {
            float roll = r.nextFloat();
            speaker = personal
                    ? (roll < 0.45F ? Speaker.WHISPER : roll < 0.85F ? Speaker.USER : Speaker.UNKNOWN)
                    : (roll < 0.8F ? Speaker.USER : Speaker.UNKNOWN);
        } else if (speaker == Speaker.WHISPER && !personal) {
            speaker = Speaker.USER;
        } else if (speaker == Speaker.SELF && !personal) {
            speaker = Speaker.USER;
        } else if (speaker == Speaker.USER && stage.atLeast(CorruptionStage.MIDDLE) && r.nextFloat() < 0.08F) {
            speaker = Speaker.UNKNOWN;
        }
        String name = switch (speaker) {
            case USER, WHISPER -> pickName(focus.server, stage, focus, r);
            case ADVANCEMENT -> personal && r.nextFloat() < 0.6F ? "" : pickName(focus.server, stage, focus, r);
            default -> "";
        };
        return new Speech(speaker, name);
    }

    private static Category categoryOf(Ambient kind) {
        return switch (kind) {
            case QUESTION -> Category.QUESTION;
            case WARNING -> Category.WARNING;
            case NULL_SYSTEM -> Category.NULL_SYSTEM;
            case CONNECTION -> Category.CONNECTION;
            case NAME -> Category.NAME;
            case STAGE5 -> Category.STAGE5;
            default -> Category.WHISPER;
        };
    }

    @Nullable
    private static Ambient roll(int[] weights, RandomSource r) {
        int total = 0;
        for (int w : weights) {
            total += Math.max(0, w);
        }
        if (total <= 0) {
            return null;
        }
        int pick = r.nextInt(total);
        Ambient[] kinds = Ambient.values();
        for (int i = 0; i < weights.length; i++) {
            pick -= Math.max(0, weights[i]);
            if (pick < 0) {
                return kinds[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ strength

    private static float progress(CorruptionContext ctx, CorruptionStage stage) {
        return ctx.stage() == stage ? ctx.stageProgress() : 0.5F;
    }

    /** How damaged a line's text is: grows with the stage and its progress, with some lines left clean early on. */
    private static float strength(CorruptionContext ctx, CorruptionStage stage, RandomSource r, boolean alwaysDamaged) {
        int i = stage.number() - 1;
        float base = STRENGTH[i] + STRENGTH_GROWTH[i] * progress(ctx, stage);
        if (alwaysDamaged) {
            base = Math.max(0.25F, base);
        } else if (stage.number() <= 3 && r.nextFloat() < 0.35F) {
            return 0.0F;
        }
        float jitter = 0.7F + 0.6F * r.nextFloat();
        float setting = (float) Mth.clamp(NullConfig.get(NullConfig.EVENT_INTENSITY), 0.0, 2.0);
        return Mth.clamp(base * jitter * Math.max(0.25F, setting), 0.0F, 1.0F);
    }

    private static float categoryScale(Category category) {
        return switch (category) {
            case NULL_SYSTEM, ADVANCEMENT -> 0.6F;
            case CONNECTION -> 0.4F;
            case NAME -> 0.8F;
            case ECHO -> 1.4F;
            case BURST -> 1.3F;
            case STAGE5 -> 1.1F;
            default -> 1.0F;
        };
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Queues a line ({@code personal}: for {@code focus} only; otherwise for everyone in its
     * dimension). Lines due now are delivered immediately. Returns false if the queue is full.
     */
    private boolean schedule(ServerPlayer focus, boolean personal, long at, Payload payload, @Nullable Predicate<ServerPlayer> condition) {
        MinecraftServer server = focus.server;
        Delivery delivery = new Delivery(at, order++, personal ? focus.getUUID() : null,
                personal ? null : focus.level().dimension(), focus.getUUID(), payload, condition);
        if (at <= server.getTickCount()) {
            deliver(server, delivery);
            return true;
        }
        if (queue.size() >= MAX_PENDING) {
            return false;
        }
        queue.add(delivery);
        return true;
    }

    private void deliverDue(MinecraftServer server, long now) {
        int budget = DELIVERIES_PER_TICK;
        while (budget-- > 0 && !queue.isEmpty() && queue.peek().at() <= now) {
            deliver(server, queue.poll());
        }
    }

    private void deliver(MinecraftServer server, Delivery delivery) {
        try {
            if (delivery.player() != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(delivery.player());
                if (player == null || (delivery.condition() != null && !delivery.condition().test(player))) {
                    return;
                }
                Rendered rendered = delivery.payload().render(player);
                if (rendered != null) {
                    send(player, rendered);
                }
                return;
            }
            ServerLevel level = delivery.dimension() == null ? null : server.getLevel(delivery.dimension());
            if (level == null) {
                return;
            }
            List<ServerPlayer> recipients = List.copyOf(level.players());
            if (recipients.isEmpty()) {
                return;
            }
            ServerPlayer focus = delivery.focus() == null ? null : server.getPlayerList().getPlayer(delivery.focus());
            if (focus == null || focus.level() != level) {
                focus = recipients.get(0);
            }
            Rendered rendered = delivery.payload().render(focus);
            if (rendered == null) {
                return;
            }
            for (ServerPlayer player : recipients) {
                if (delivery.condition() == null || delivery.condition().test(player)) {
                    send(player, rendered);
                }
            }
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL chat failed to deliver a line", e);
        }
    }

    private void send(ServerPlayer player, Rendered rendered) {
        if (player instanceof FakePlayer || player.hasDisconnected()) {
            return;
        }
        // A client that hides player chat would never show these; showing them anyway would give them away.
        ChatVisiblity visibility = player.getChatVisibility();
        if (visibility == ChatVisiblity.HIDDEN || (visibility == ChatVisiblity.SYSTEM && rendered.chatLike())) {
            return;
        }
        player.sendSystemMessage(rendered.line());
        PlayerState state = state(player);
        state.deliveredCount++;
        state.delivered.addFirst(rendered.plain());
        while (state.delivered.size() > DELIVERED_LOG) {
            state.delivered.removeLast();
        }
        if (rendered.lineIndex() >= 0) {
            addToHistory(player.server, state, rendered.lineIndex());
        }
    }

    // ------------------------------------------------------------------ per-player state

    private PlayerState state(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (state == null) {
            state = new PlayerState(player.getUUID());
            loadHistory(player.server, state);
            players.put(player.getUUID(), state);
        }
        return state;
    }

    private Collection<PlayerState> audience(ServerPlayer focus, boolean personal) {
        if (personal) {
            return List.of(state(focus));
        }
        List<PlayerState> out = new ArrayList<>();
        for (ServerPlayer player : focus.serverLevel().players()) {
            if (!(player instanceof FakePlayer)) {
                out.add(state(player));
            }
        }
        if (out.isEmpty()) {
            out.add(state(focus));
        }
        return out;
    }

    private static void loadHistory(MinecraftServer server, PlayerState state) {
        CompoundTag root = CorruptionManager.data(server).systemTag(TAG_KEY);
        if (!root.contains("history", Tag.TAG_COMPOUND)) {
            return;
        }
        int[] ids = root.getCompound("history").getIntArray(state.id.toString());
        for (int id : ids) {
            if (id >= 0 && id < ChatMessages.size() && state.history.size() < HISTORY_SIZE) {
                state.history.addLast(id);
            }
        }
    }

    private static void addToHistory(MinecraftServer server, PlayerState state, int index) {
        state.history.remove(index);
        state.history.addFirst(index);
        while (state.history.size() > HISTORY_SIZE) {
            state.history.removeLast();
        }
        CorruptionData data = CorruptionManager.data(server);
        CompoundTag root = data.systemTag(TAG_KEY);
        CompoundTag all = root.contains("history", Tag.TAG_COMPOUND) ? root.getCompound("history") : new CompoundTag();
        int[] ids = new int[state.history.size()];
        int i = 0;
        for (Integer id : state.history) {
            ids[i++] = id;
        }
        all.putIntArray(state.id.toString(), ids);
        root.put("history", all);
        data.setDirty();
    }

    // ------------------------------------------------------------------ names

    /**
     * A username for a line: in Stage 5 mostly one of the simulated players, otherwise one of
     * this world's recurring names, a Visitor the player saw, or a fresh one. Never the name of
     * a real player who is online.
     */
    private String pickName(MinecraftServer server, CorruptionStage stage, @Nullable ServerPlayer about, RandomSource r) {
        Set<String> taken = takenNames(server);
        for (int attempt = 0; attempt < 8; attempt++) {
            String name = null;
            float roll = r.nextFloat();
            if (stage == CorruptionStage.NULL && roll < 0.75F) {
                List<String> simulated = FakePlayerManager.INSTANCE.names();
                if (!simulated.isEmpty()) {
                    name = simulated.get(r.nextInt(simulated.size()));
                }
            } else if (roll < 0.4F) {
                name = regular(server, r.nextInt(REGULARS));
            } else if (roll < 0.55F && about != null && stage.atLeast(CorruptionStage.ABOVE_MIDDLE)) {
                name = seenVisitor(about).orElse(null);
            }
            if (name == null) {
                name = UsernameGenerator.random(r);
            }
            if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        return UsernameGenerator.unique(r, taken);
    }

    /** Lower-case names of the real players online. */
    private static Set<String> takenNames(MinecraftServer server) {
        Set<String> out = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            out.add(player.getScoreboardName().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isTaken(MinecraftServer server, String name) {
        return takenNames(server).contains(name.toLowerCase(Locale.ROOT));
    }

    /** One of a handful of names that keep coming back in this world (derived from the world's seed). */
    private String regular(MinecraftServer server, int index) {
        long seed = CorruptionManager.data(server).seed();
        if (regulars.isEmpty() || regularsSeed != seed) {
            List<String> names = new ArrayList<>(REGULARS);
            for (int i = 0; i < REGULARS; i++) {
                names.add(UsernameGenerator.fromSeed(seed * 31L + 0x5DEECE66DL * (i + 1)));
            }
            regulars = List.copyOf(names);
            regularsSeed = seed;
        }
        return regulars.get(Math.floorMod(index, regulars.size()));
    }

    private static Optional<String> seenVisitor(ServerPlayer player) {
        for (WorldMemory.Entry entry : WorldMemory.recentFor(player.server, player.getUUID(), MEMORY_WINDOW)) {
            if (entry.key().equals("visitor:seen") && !entry.detail().isEmpty() && entry.player() != null) {
                return Optional.of(entry.detail());
            }
        }
        return Optional.empty();
    }

    /** Things the player said recently, newest first (WorldMemory first, then this system's own list). */
    private List<String> saidOptions(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        for (WorldMemory.Entry entry : WorldMemory.recentFor(player.server, player.getUUID(), MEMORY_WINDOW)) {
            if (entry.key().equals("chat:said") && entry.player() != null && !entry.detail().isEmpty() && !out.contains(entry.detail())) {
                out.add(entry.detail());
            }
        }
        PlayerState state = players.get(player.getUUID());
        if (state != null) {
            for (String said : state.said) {
                if (!out.contains(said)) {
                    out.add(said);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ placeholders

    private String resolve(String template, ServerPlayer target, Map<String, String> overrides, String speaker) {
        return ChatPlaceholders.resolve(template, key -> {
            String value = overrides.get(key);
            return value != null ? value : value(key, target, speaker);
        });
    }

    private String value(String key, ServerPlayer target, String speaker) {
        MinecraftServer server = target.server;
        return switch (key) {
            case "player" -> target.getScoreboardName();
            case "visitor" -> seenVisitor(target).orElseGet(() -> regular(server, target.getUUID().hashCode()));
            case "name" -> otherName(server, speaker, target);
            case "block" -> blockUnder(target);
            case "x" -> Integer.toString(target.getBlockX());
            case "y" -> Integer.toString(target.getBlockY());
            case "z" -> Integer.toString(target.getBlockZ());
            case "time" -> gameClock(target.level().getDayTime());
            case "clock" -> String.format(Locale.ROOT, "%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute());
            case "biome" -> target.level().getBiome(target.blockPosition()).unwrapKey()
                    .map(k -> k.location().getPath().replace('_', ' ')).orElse("wilderness");
            case "dimension" -> target.level().dimension().location().getPath().replace('_', ' ');
            case "deaths" -> Integer.toString(target.getStats().getValue(Stats.CUSTOM, Stats.DEATHS));
            case "day" -> Long.toString(Math.max(0L, target.level().getDayTime()) / 24000L + 1L);
            case "said" -> {
                List<String> said = saidOptions(target);
                yield said.isEmpty() ? "..." : said.get(0);
            }
            case "online" -> Integer.toString(server.getPlayerCount()
                    + (CorruptionManager.stage(server) == CorruptionStage.NULL ? FakePlayerManager.INSTANCE.names().size() : 0));
            default -> "";
        };
    }

    private String otherName(MinecraftServer server, String speaker, ServerPlayer target) {
        for (int i = 0; i < REGULARS; i++) {
            String name = regular(server, random.nextInt(REGULARS));
            if (!name.equalsIgnoreCase(speaker) && !name.equalsIgnoreCase(target.getScoreboardName())) {
                return name;
            }
        }
        return UsernameGenerator.random(random);
    }

    /** "06:00" at sunrise: Minecraft's day starts at six in the morning. */
    static String gameClock(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        long hours = (t / 1000L + 6L) % 24L;
        long minutes = (t % 1000L) * 60L / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static String blockUnder(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        for (int down = 0; down < 4; down++) {
            BlockState state = player.level().getBlockState(feet.offset(0, -1 - down, 0));
            if (!state.isAir()) {
                return blockName(state.getBlock());
            }
        }
        return "ground";
    }

    /** "corrupted stone" for null:corrupted_stone, "grass block" for minecraft:grass_block. */
    public static String blockName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath().replace('_', ' ');
    }
}
