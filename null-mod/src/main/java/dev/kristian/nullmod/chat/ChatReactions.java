package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.chat.ChatLine.Category;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.registry.NullBlocks;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * The chat noticing what players do: it answers (later, never instantly) when they type, when
 * they die, break corrupted blocks, go to sleep or wake up, dig deep, stand still for a long
 * time, see a Visitor, get caught by a NULLROOT or arrive in the NULL dimension for the first
 * time. A player's own message is only ever read, never cancelled or changed.
 * <p>
 * Listeners only take note; the chat itself is sent from the server tick through
 * {@link ChatCorruptionManager#react}, which applies the stage, the chat frequency setting and
 * per-player cooldowns.
 */
public final class ChatReactions {
    /** Seconds a player has to stand still before the chat notices, by stage. */
    private static final int[] STILL_SECONDS = {0, 240, 180, 120, 90};
    /** Chance the chat answers a player's own message, by stage. */
    private static final float[] REPLY_CHANCE = {0.0F, 0.08F, 0.2F, 0.35F, 0.5F};
    /** Base chance for most other reactions, by stage (multiplied with each reaction's own chance). */
    private static final float[] STAGE_CHANCE = {0.0F, 0.35F, 0.6F, 0.85F, 1.0F};
    private static final float[] DEATH_CHANCE = {0.0F, 0.25F, 0.4F, 0.55F, 0.6F};
    private static final float[] SLEEP_CHANCE = {0.0F, 0.15F, 0.35F, 0.5F, 0.5F};
    private static final float[] WAKE_CHANCE = {0.0F, 0.2F, 0.35F, 0.45F, 0.5F};
    private static final long MEMORY_SCAN_WINDOW = 20L * 20L;

    private static final ConcurrentLinkedQueue<Said> INCOMING = new ConcurrentLinkedQueue<>();
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    private static final Set<WorldMemory.Entry> HANDLED = Collections.newSetFromMap(new IdentityHashMap<>());
    @Nullable
    private static volatile Set<Block> corruptionBlocks;

    private ChatReactions() {
    }

    /** Something a player typed, waiting for the server tick. */
    private record Said(UUID player, String text) {
    }

    /** What the chat has noticed about one player's movements. */
    private static final class Watch {
        double x;
        double y;
        double z;
        long stillSince;
        int stillLines;
        boolean deep;
        boolean bedrock;
        boolean placed;
    }

    /** What kind of line a remembered event leads to, and how likely and how soon. */
    public record MemoryRef(Category category, @Nullable String topic, boolean detailIsName, boolean world,
                            float chance, int minDelay, int maxDelay, String cooldownKey, int cooldown) {
    }

    /** What a player's chat message looks like, for picking a fitting reply. */
    public enum ReplyKind {
        GREETING, QUESTION, WHO, FEAR, LEAVE, NULL_NAME, OTHER
    }

    // ------------------------------------------------------------------ registration

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.LOWEST, ChatReactions::onServerChat);
        gameBus.addListener(EventPriority.LOWEST, ChatReactions::onLivingDeath);
        gameBus.addListener(EventPriority.LOWEST, ChatReactions::onBlockBreak);
        gameBus.addListener(EventPriority.LOWEST, ChatReactions::onCanSleep);
        gameBus.addListener(ChatReactions::onWakeUp);
        gameBus.addListener(ChatReactions::onChangedDimension);
    }

    // ------------------------------------------------------------------ listeners

    private static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null || player instanceof FakePlayer) {
            return;
        }
        INCOMING.add(new Said(player.getUUID(), event.getRawText()));
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        CorruptionStage stage = stage(player);
        float chance = DEATH_CHANCE[stage.number() - 1];
        // The death count is read when the line is sent, a few seconds later, after the game has counted this one.
        ChatCorruptionManager.INSTANCE.react(player, Category.DEATH, null, chance, 20 * 3, 20 * 8, "death", 20 * 90,
                Map.of(), null, false);
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide() || !(event.getPlayer() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        Block block = event.getState().getBlock();
        if (!corruptionBlocks().contains(block)) {
            return;
        }
        CorruptionStage stage = stage(player);
        ChatCorruptionManager.INSTANCE.react(player, Category.BREAK_CORRUPTION, null, 0.12F * STAGE_CHANCE[stage.number() - 1],
                20 * 2, 20 * 7, "break", 20 * 60 * 3, Map.of("block", ChatCorruptionManager.blockName(block)), null, false);
    }

    private static void onCanSleep(CanPlayerSleepEvent event) {
        ServerPlayer player = event.getEntity();
        if (event.getProblem() != null || player instanceof FakePlayer) {
            return;
        }
        CorruptionStage stage = stage(player);
        float chance = SLEEP_CHANCE[stage.number() - 1];
        // Only if they are still lying there when the line is due.
        ChatCorruptionManager.INSTANCE.react(player, Category.SLEEP, null, chance, 30, 70, "sleep", 20 * 60 * 10,
                Map.of(), LivingEntity::isSleeping, false);
    }

    private static void onWakeUp(PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        // Fired before the game resets the sleep timer: only a full night counts.
        if (event.wakeImmediately() || !player.isSleepingLongEnough()) {
            return;
        }
        CorruptionStage stage = stage(player);
        float chance = WAKE_CHANCE[stage.number() - 1];
        ChatCorruptionManager.INSTANCE.react(player, Category.WAKE, null, chance, 40, 100, "wake", 20 * 60 * 15,
                Map.of(), null, false);
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || event.getTo() != NullDimensions.NULL_LEVEL) {
            return;
        }
        ChatCorruptionManager manager = ChatCorruptionManager.INSTANCE;
        if (manager.markVisitedNull(player)) {
            // After the arrival has settled: NULL first, then someone who was already here.
            manager.react(player, Category.NULL_ARRIVAL, null, 1.0F, 20 * 12, 20 * 16, "null_arrival", 20 * 60,
                    Map.of(), null, true);
            manager.react(player, Category.NULL_ARRIVAL, null, 0.8F, 20 * 22, 20 * 40, "null_arrival_2", 20 * 60,
                    Map.of(), null, true);
        } else {
            manager.react(player, Category.NULL_RETURN, null, 0.3F, 20 * 10, 20 * 30, "null_return", 20 * 60 * 10,
                    Map.of(), null, false);
        }
    }

    // ------------------------------------------------------------------ tick (server thread)

    /** Handles what players typed since the last tick. */
    static void drainChat(MinecraftServer server, CorruptionContext ctx, long now) {
        Said said;
        int budget = 64;
        while (budget-- > 0 && (said = INCOMING.poll()) != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(said.player());
            if (player == null) {
                continue;
            }
            String clean = cleanSaid(said.text());
            if (clean.isEmpty()) {
                continue;
            }
            ChatCorruptionManager.INSTANCE.rememberSaid(player, clean, false);
            reply(player, ctx, clean);
        }
    }

    /** Once a second: standing still, going deep, and new things in {@link WorldMemory}. */
    static void tick(MinecraftServer server, CorruptionContext ctx, long now) {
        scanMemory(server, ctx);
        if (ctx.paused() || ctx.stage() == CorruptionStage.START) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) {
                continue;
            }
            online.add(player.getUUID());
            Watch watch = WATCHES.computeIfAbsent(player.getUUID(), id -> new Watch());
            watchStillness(player, watch, ctx.stage(), now);
            if ((now / 20L) % 2L == 0L) {
                watchDepth(player, watch, ctx.stage());
            }
        }
        WATCHES.keySet().retainAll(online);
    }

    private static void reply(ServerPlayer player, CorruptionContext ctx, String clean) {
        CorruptionStage stage = ctx.stage();
        float chance = REPLY_CHANCE[stage.number() - 1];
        if (chance <= 0.0F) {
            return;
        }
        ReplyKind kind = classify(clean);
        Category category = switch (kind) {
            case GREETING -> Category.REPLY_GREETING;
            case QUESTION -> Category.REPLY_QUESTION;
            case WHO -> Category.REPLY_WHO;
            case FEAR -> Category.REPLY_FEAR;
            case LEAVE -> Category.REPLY_LEAVE;
            case NULL_NAME -> Category.REPLY_NULL;
            case OTHER -> Category.REPLY;
        };
        if (kind == ReplyKind.NULL_NAME) {
            // Saying its name does not go unnoticed.
            chance = Math.min(1.0F, chance * 2.0F + 0.2F);
        } else if (stage.atLeast(CorruptionStage.MIDDLE) && player.getRandom().nextFloat() < 0.25F) {
            category = Category.ECHO;
        }
        ChatCorruptionManager.INSTANCE.react(player, category, null, chance, 20 * 3, 20 * 25, "reply", 20 * 90,
                Map.of("said", clean), null, false);
    }

    private static void watchStillness(ServerPlayer player, Watch watch, CorruptionStage stage, long now) {
        double dx = player.getX() - watch.x;
        double dy = player.getY() - watch.y;
        double dz = player.getZ() - watch.z;
        boolean busy = player.isSleeping() || player.isPassenger() || player.isSpectator() || !player.isAlive();
        if (!watch.placed || busy || dx * dx + dy * dy + dz * dz > 1.44) {
            watch.x = player.getX();
            watch.y = player.getY();
            watch.z = player.getZ();
            watch.placed = true;
            watch.stillSince = now;
            watch.stillLines = 0;
            return;
        }
        long threshold = 20L * STILL_SECONDS[stage.number() - 1];
        if (threshold <= 0L || watch.stillLines >= 2 || now - watch.stillSince < threshold * (watch.stillLines + 1L)) {
            return;
        }
        watch.stillLines++;
        ChatCorruptionManager.INSTANCE.react(player, Category.STILL, null, 0.55F * STAGE_CHANCE[stage.number() - 1],
                0, 20 * 4, null, 0, Map.of(), null, false);
    }

    private static void watchDepth(ServerPlayer player, Watch watch, CorruptionStage stage) {
        ServerLevel level = player.serverLevel();
        if (NullDimensions.isNull(level)) {
            watch.deep = false;
            watch.bedrock = false;
            return;
        }
        BlockPos feet = player.blockPosition();
        boolean covered = level.hasChunkAt(feet) && !level.canSeeSky(feet.above());
        int surface = covered ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) : feet.getY();
        boolean deep = covered && (feet.getY() < surface - 30 || (level.dimension() == Level.OVERWORLD && feet.getY() < 0));
        boolean bedrock = covered && feet.getY() < level.getMinBuildHeight() + 6;
        float chance = STAGE_CHANCE[stage.number() - 1];
        if (deep && !watch.deep) {
            ChatCorruptionManager.INSTANCE.react(player, Category.DEEP, null, 0.4F * chance, 20 * 3, 20 * 12, "deep",
                    20 * 60 * 10, Map.of(), null, false);
        }
        if (bedrock && !watch.bedrock) {
            ChatCorruptionManager.INSTANCE.react(player, Category.DEEP, "bedrock", 0.5F * chance, 20 * 3, 20 * 10, "bedrock",
                    20 * 60 * 15, Map.of(), null, false);
        }
        watch.deep = deep;
        watch.bedrock = bedrock;
    }

    // ------------------------------------------------------------------ memories

    private static void scanMemory(MinecraftServer server, CorruptionContext ctx) {
        List<WorldMemory.Entry> recent = WorldMemory.recent(server, MEMORY_SCAN_WINDOW);
        if (recent.isEmpty()) {
            HANDLED.clear();
            return;
        }
        boolean active = !ctx.paused() && ctx.stage() != CorruptionStage.START;
        // Oldest first, so reactions come in the order things happened.
        for (int i = recent.size() - 1; i >= 0; i--) {
            WorldMemory.Entry entry = recent.get(i);
            if (!HANDLED.add(entry) || !active) {
                continue;
            }
            MemoryRef ref = memoryRef(entry.key());
            if (ref != null) {
                react(server, ctx, entry, ref);
            }
        }
        HANDLED.retainAll(new ArrayList<>(recent));
    }

    private static void react(MinecraftServer server, CorruptionContext ctx, WorldMemory.Entry entry, MemoryRef ref) {
        Map<String, String> overrides = ref.detailIsName() && !entry.detail().isEmpty() ? Map.of("visitor", entry.detail()) : Map.of();
        float chance = ref.chance() * STAGE_CHANCE[ctx.stage().number() - 1];
        if (entry.player() != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.player());
            if (player != null) {
                ChatCorruptionManager.INSTANCE.react(player, ref.category(), ref.topic(), chance, ref.minDelay(), ref.maxDelay(),
                        ref.cooldownKey(), ref.cooldown(), overrides, null, false);
            }
            return;
        }
        if (!ref.world()) {
            return;
        }
        // Something that happened to the whole world: whoever is around talks about it.
        List<ServerPlayer> candidates = new ArrayList<>();
        boolean moon = ref.category() == Category.MEMORY_MOON || ref.category() == Category.MEMORY_MOON_GONE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean fits = !moon || player.level().dimension() == Level.OVERWORLD;
            if (!(player instanceof FakePlayer) && !player.isSpectator() && fits) {
                candidates.add(player);
            }
        }
        if (!candidates.isEmpty()) {
            ServerPlayer focus = candidates.get(server.overworld().getRandom().nextInt(candidates.size()));
            ChatCorruptionManager.INSTANCE.reactWorld(focus, ref.category(), ref.topic(), chance, ref.minDelay(), ref.maxDelay(),
                    ref.cooldownKey(), ref.cooldown(), overrides);
        }
    }

    /**
     * Which lines a {@link WorldMemory} key leads to, or null if the chat ignores it. Used for
     * reactions as things happen and for ambient lines that bring old things up again.
     */
    @Nullable
    public static MemoryRef memoryRef(String key) {
        int colon = key.indexOf(':');
        String head = colon < 0 ? key : key.substring(0, colon);
        String tail = colon < 0 ? "" : key.substring(colon + 1);
        int minute = 20 * 60;
        return switch (head) {
            case "visitor" -> switch (tail) {
                case "seen" -> new MemoryRef(Category.MEMORY_VISITOR, null, true, false, 0.55F, 20 * 8, 20 * 30, "visitor", minute * 3);
                case "vanished" -> new MemoryRef(Category.MEMORY_VISITOR, null, true, false, 0.25F, 20 * 2, 20 * 10, "visitor", minute * 3);
                default -> null;
            };
            case "fakeplayer" -> switch (tail) {
                case "seen", "vanished" -> new MemoryRef(Category.MEMORY_VISITOR, null, true, false, 0.2F, 20 * 3, 20 * 12, "visitor", minute * 3);
                case "joined", "left" -> new MemoryRef(Category.MEMORY_FAKE_PLAYER, null, true, true, 0.25F, 20 * 2, 20 * 10, "fakeplayer", minute);
                default -> null;
            };
            case "nullroot" -> switch (tail) {
                case "captured" -> new MemoryRef(Category.MEMORY_NULLROOT, null, false, false, 0.6F, 20 * 3, 20 * 10, "nullroot", minute * 2);
                case "escaped" -> new MemoryRef(Category.MEMORY_NULLROOT, null, false, false, 0.7F, 20 * 2, 20 * 8, "nullroot", minute * 2);
                case "killed" -> new MemoryRef(Category.MEMORY_NULLROOT, "killed", false, false, 0.8F, 20 * 6, 20 * 15, "nullroot", minute * 2);
                default -> null;
            };
            case "structure" -> new MemoryRef(Category.MEMORY_STRUCTURE, tail, false, false, 0.35F, 20 * 30, 20 * 120,
                    "structure", minute * 5);
            case "world" -> tail.equals("corruption")
                    ? new MemoryRef(Category.MEMORY_CORRUPTION, null, false, false, 0.15F, 20 * 10, 20 * 60, "corruption", minute * 10)
                    : null;
            case "moon" -> switch (tail) {
                case "shattered", "gone" -> new MemoryRef(Category.MEMORY_MOON_GONE, null, false, true, 0.8F, 20 * 10, 20 * 60, "moon", minute * 5);
                default -> new MemoryRef(Category.MEMORY_MOON, null, false, true, 0.5F, 20 * 10, 20 * 60, "moon", minute * 5);
            };
            case "event" -> eventRef(tail);
            default -> null;
        };
    }

    @Nullable
    private static MemoryRef eventRef(String event) {
        int minute = 20 * 60;
        return switch (event) {
            case "sky_flicker", "sky_color_shift" ->
                    new MemoryRef(Category.MEMORY_SKY, null, false, false, 0.3F, 20 * 3, 20 * 15, "sky", minute * 4);
            case "chunk_flicker", "block_black_flash", "block_missing_texture", "block_error_texture", "block_vanish",
                 "block_wrong_texture", "tree_wrong_texture", "terrain_gap", "structure_flicker", "structure_vanish",
                 "block_displacement", "terrain_shift" ->
                    new MemoryRef(Category.MEMORY_CHUNKS, null, false, false, 0.2F, 20 * 3, 20 * 15, "chunks", minute * 4);
            case "brief_blackout", "full_blackout", "visual_freeze", "geometry_blackout" ->
                    new MemoryRef(Category.MEMORY_BLACKOUT, null, false, false, 0.35F, 20 * 3, 20 * 10, "blackout", minute * 4);
            case "fake_disconnect" ->
                    new MemoryRef(Category.MEMORY_DISCONNECT, null, false, false, 0.6F, 20 * 5, 20 * 15, "disconnect", minute * 10);
            case "distant_silhouette", "distant_movement" ->
                    new MemoryRef(Category.MEMORY_SILHOUETTE, null, false, false, 0.25F, 20 * 3, 20 * 15, "silhouette", minute * 4);
            case "door_knock", "footsteps_behind", "whisper_behind", "breath_behind", "distant_footsteps", "distant_voices" ->
                    new MemoryRef(Category.MEMORY_SOUNDS, null, false, false, 0.2F, 20 * 3, 20 * 15, "sounds", minute * 4);
            case "ground_pulse", "nullroot_glimpse" ->
                    new MemoryRef(Category.MEMORY_NULLROOT, null, false, false, 0.2F, 20 * 3, 20 * 15, "nullroot", minute * 4);
            case "moon_crack", "moon_piece", "moon_distortion" ->
                    new MemoryRef(Category.MEMORY_MOON, null, false, false, 0.3F, 20 * 5, 20 * 20, "moon", minute * 5);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ helpers (public for tests)

    /**
     * Makes a player's message safe to quote: sanitised, no commands or links, at most 80
     * characters (cut at a word, with ".."). Empty if it should not be quoted at all.
     */
    public static String cleanSaid(String raw) {
        String text = TextCorruptor.sanitize(raw);
        if (text.isEmpty() || text.startsWith("/")) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("http") || lower.contains("www.") || lower.contains("://")) {
            return "";
        }
        if (text.length() > 80) {
            int cut = text.lastIndexOf(' ', 78);
            text = text.substring(0, cut > 20 ? cut : 78).stripTrailing() + "..";
        }
        return text;
    }

    /** Sorts a player's message into a few kinds the chat has answers for. */
    public static ReplyKind classify(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9? ]", " ").replaceAll(" +", " ").trim() + " ";
        if (t.contains(" null ")) {
            return ReplyKind.NULL_NAME;
        }
        if (containsAny(t, " help ", " scared ", " afraid ", " creepy ", " scary ", " spooky ", " wtf ", " what the ", " freaky ")) {
            return ReplyKind.FEAR;
        }
        if (containsAny(t, " bye ", " gtg ", " cya ", " goodbye ", " logging off ", " log off ", " brb ", " gotta go ", " good night ", " gn ")) {
            return ReplyKind.LEAVE;
        }
        if (containsAny(t, " who ", " whos ", " who s ")) {
            return ReplyKind.WHO;
        }
        if (containsAny(t, " hi ", " hello ", " hey ", " yo ", " sup ", " hiya ", " howdy ", " hallo ", " heya ")) {
            return ReplyKind.GREETING;
        }
        String trimmed = t.trim();
        if (trimmed.contains("?") || startsWithAny(trimmed, "what ", "where ", "why ", "how ", "is ", "are ", "can ", "do ", "does ",
                "did ", "will ", "should ", "could ", "would ")) {
            return ReplyKind.QUESTION;
        }
        return ReplyKind.OTHER;
    }

    /** Records what a player said as if they had typed it (used by tests and by other systems). */
    public static void recordSaid(ServerPlayer player, String text) {
        String clean = cleanSaid(text);
        if (!clean.isEmpty()) {
            ChatCorruptionManager.INSTANCE.rememberSaid(player, clean, true);
        }
    }

    static void forget(UUID player) {
        WATCHES.remove(player);
    }

    static void clear() {
        WATCHES.clear();
        HANDLED.clear();
        INCOMING.clear();
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static CorruptionStage stage(ServerPlayer player) {
        return CorruptionManager.stage(player.server);
    }

    /** Every corruption block: the error blocks and the corrupted_* terrain. Built once the registries are filled. */
    private static Set<Block> corruptionBlocks() {
        Set<Block> blocks = corruptionBlocks;
        if (blocks == null) {
            Set<Block> set = new HashSet<>();
            for (DeferredBlock<Block> block : NullBlocks.errorBlocks()) {
                set.add(block.get());
            }
            set.add(NullBlocks.CORRUPTED_GRASS_BLOCK.get());
            set.add(NullBlocks.CORRUPTED_DIRT.get());
            set.add(NullBlocks.CORRUPTED_STONE.get());
            set.add(NullBlocks.CORRUPTED_SAND.get());
            set.add(NullBlocks.CORRUPTED_LOG.get());
            set.add(NullBlocks.CORRUPTED_LEAVES.get());
            blocks = Set.copyOf(set);
            corruptionBlocks = blocks;
        }
        return blocks;
    }
}
