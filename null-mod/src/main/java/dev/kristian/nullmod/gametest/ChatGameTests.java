package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.chat.ChatCorruptionManager;
import dev.kristian.nullmod.chat.ChatLine;
import dev.kristian.nullmod.chat.ChatLine.Category;
import dev.kristian.nullmod.chat.ChatMessages;
import dev.kristian.nullmod.chat.ChatPlaceholders;
import dev.kristian.nullmod.chat.ChatReactions;
import dev.kristian.nullmod.chat.ChatReactions.ReplyKind;
import dev.kristian.nullmod.chat.TextCorruptor;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The corrupted chat: the line database, the text corruptor, reply handling and the chat events. */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class ChatGameTests {
    private static final List<String> CHAT_EVENTS = List.of("corrupted_chat", "chat_burst", "name_whisper",
            "null_system_message", "fake_join_message", "echo_player", "fake_death_message");
    private static final float[] STRENGTHS = {0.0F, 0.1F, 0.3F, 0.55F, 0.8F, 1.0F};

    private ChatGameTests() {
    }

    @GameTest(template = "empty", batch = "chat")
    public static void databaseIsLargeUniqueAndResolvable(GameTestHelper helper) {
        List<ChatLine> lines = ChatMessages.all();
        helper.assertTrue(lines.size() >= 350, "only " + lines.size() + " chat lines");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            ChatLine line = lines.get(i);
            String text = line.text();
            helper.assertValueEqual(line.index(), i, "index of line " + i);
            helper.assertFalse(text.isBlank(), "line " + i + " is empty");
            helper.assertTrue(seen.add(text.trim().toLowerCase(Locale.ROOT)), "duplicate line '" + text + "'");
            helper.assertTrue(ChatPlaceholders.allKnown(text), "unresolvable placeholder in '" + text + "'");
            helper.assertTrue(line.minStage() >= 1 && line.minStage() <= line.maxStage() && line.maxStage() <= 5,
                    "bad stage range for '" + text + "'");
            helper.assertValueEqual(TextCorruptor.sanitize(text), text, "characters chat would strip in '" + text + "'");
            String worst = ChatPlaceholders.resolve(text, ChatGameTests::longestValue);
            helper.assertTrue(TextCorruptor.isValid(worst), "too long or invalid once resolved: '" + text + "'");
            boolean names = line.placeholders().stream().anyMatch(ChatPlaceholders.PERSONAL::contains);
            helper.assertTrue(!names || line.personal(), "'" + text + "' is about one player but is not marked personal");
        }
        for (String example : ChatMessages.SPEC_EXAMPLES) {
            helper.assertTrue(lines.stream().anyMatch(line -> line.text().equals(example)), "design example missing: '" + example + "'");
        }
        for (Category category : Category.values()) {
            helper.assertFalse(ChatMessages.of(category).isEmpty(), "no lines for " + category);
        }

        // Everything the chat asks for exists at every stage it asks for it.
        expect(helper, 2, 5, false, Category.QUESTION, Category.WHISPER, Category.WARNING, Category.CONNECTION, Category.MEMORY_SKY,
                Category.MEMORY_CHUNKS, Category.MEMORY_BLACKOUT, Category.MEMORY_SILHOUETTE, Category.MEMORY_SOUNDS,
                Category.MEMORY_STRUCTURE, Category.MEMORY_CORRUPTION, Category.JOIN_HELLO);
        expect(helper, 2, 5, true, Category.REPLY, Category.REPLY_QUESTION, Category.REPLY_GREETING, Category.REPLY_FEAR,
                Category.REPLY_LEAVE, Category.DEATH, Category.BREAK_CORRUPTION, Category.SLEEP, Category.WAKE, Category.DEEP,
                Category.STILL);
        expect(helper, 3, 5, false, Category.NULL_SYSTEM, Category.MEMORY_MOON, Category.MEMORY_NULLROOT);
        expect(helper, 3, 5, true, Category.ECHO, Category.REPLY_WHO, Category.REPLY_NULL);
        expect(helper, 4, 5, false, Category.BURST, Category.MEMORY_VISITOR, Category.MEMORY_MOON_GONE, Category.MEMORY_DISCONNECT,
                Category.ADVANCEMENT);
        expect(helper, 4, 5, true, Category.NAME);
        expect(helper, 5, 5, false, Category.STAGE5, Category.MEMORY_FAKE_PLAYER);
        expect(helper, 1, 5, true, Category.NULL_ARRIVAL, Category.NULL_RETURN);

        helper.assertTrue(ChatMessages.dialogues().size() >= 25, "only " + ChatMessages.dialogues().size() + " dialogues");
        for (ChatMessages.Dialogue dialogue : ChatMessages.dialogues()) {
            helper.assertTrue(dialogue.steps().size() >= 2, "dialogue " + dialogue.index() + " is too short");
            for (ChatMessages.Step step : dialogue.steps()) {
                helper.assertTrue("ABCUNLJQ".indexOf(step.role()) >= 0, "bad role in dialogue " + dialogue.index());
                helper.assertFalse(step.text().isBlank(), "empty step in dialogue " + dialogue.index());
                helper.assertTrue(ChatPlaceholders.allKnown(step.text()), "bad placeholder in dialogue " + dialogue.index());
                if (step.isJoinOrLeave()) {
                    helper.assertTrue("ABC".indexOf(step.text().charAt(0)) >= 0, "join/leave step without a role in dialogue " + dialogue.index());
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chat")
    public static void corruptorAlwaysProducesValidText(GameTestHelper helper) {
        RandomSource random = RandomSource.create(1234L);
        for (ChatLine line : ChatMessages.all()) {
            String text = ChatPlaceholders.resolve(line.text(), ChatGameTests::longestValue);
            for (float strength : STRENGTHS) {
                String plain = TextCorruptor.corruptText(text, strength, random);
                helper.assertTrue(TextCorruptor.isValid(plain), "invalid corruption of '" + text + "' at " + strength + ": '" + plain + "'");
                String styled = TextCorruptor.corrupt(text, strength, random).getString();
                helper.assertTrue(TextCorruptor.isValid(styled), "invalid styled corruption of '" + text + "' at " + strength + ": '" + styled + "'");
            }
            helper.assertValueEqual(TextCorruptor.corruptText(text, 0.0F, random), TextCorruptor.sanitize(text), "strength 0 changed '" + text + "'");
        }

        String[] hostile = {"line\nbreak", "§kformatting§r codes", "tab\tand\rreturn", "x".repeat(1000),
                "😀 emoji and a broken \uD83D", "\uDE00 lone low surrogate", "\u0000\u0007control\u007F", "   ",
                "private use", "a", "w o r d s"};
        for (String input : hostile) {
            helper.assertTrue(TextCorruptor.sanitize(input).length() <= TextCorruptor.MAX_LENGTH, "sanitize kept too much of a long input");
            for (float strength : STRENGTHS) {
                for (int i = 0; i < 20; i++) {
                    String plain = TextCorruptor.corruptText(input, strength, random);
                    helper.assertTrue(plain.isEmpty() || TextCorruptor.isValid(plain), "invalid output for hostile input: '" + plain + "'");
                    String styled = TextCorruptor.corrupt(input, strength, random).getString();
                    helper.assertTrue(styled.isEmpty() || TextCorruptor.isValid(styled), "invalid styled output for hostile input: '" + styled + "'");
                }
            }
        }
        helper.assertFalse(TextCorruptor.isValid(""), "empty text accepted");
        helper.assertFalse(TextCorruptor.isValid("a\nb"), "newline accepted");
        helper.assertFalse(TextCorruptor.isValid("§cred"), "section sign accepted");
        helper.assertFalse(TextCorruptor.isValid("x".repeat(TextCorruptor.MAX_LENGTH + 1)), "over-long text accepted");

        String sample = "are you still there";
        for (int i = 0; i < 200; i++) {
            for (String damaged : new String[] {TextCorruptor.cut(sample, random), TextCorruptor.stutter(sample, random),
                    TextCorruptor.repeat(sample, random), TextCorruptor.substitute(sample, 0.5F, random),
                    TextCorruptor.accents(sample, 0.5F, random), TextCorruptor.glyphs(sample, 0.3F, random),
                    TextCorruptor.dropLetters(sample, 0.5F, random), TextCorruptor.shout(sample),
                    TextCorruptor.obfuscate(sample, 2, random).getString()}) {
                helper.assertTrue(TextCorruptor.isValid(damaged), "invalid single damage: '" + damaged + "'");
            }
        }
        helper.assertValueEqual(TextCorruptor.corruptText("dont look behind you", 0.8F, RandomSource.create(99L)),
                TextCorruptor.corruptText("dont look behind you", 0.8F, RandomSource.create(99L)), "corruption differs for the same seed");

        int changedWeak = 0;
        int changedStrong = 0;
        for (int i = 0; i < 300; i++) {
            changedWeak += TextCorruptor.corruptText(sample, 0.1F, random).equals(sample) ? 0 : 1;
            changedStrong += TextCorruptor.corruptText(sample, 1.0F, random).equals(sample) ? 0 : 1;
        }
        helper.assertTrue(changedStrong > changedWeak, "corruption does not grow with strength (" + changedWeak + " vs " + changedStrong + ")");
        helper.assertTrue(changedStrong > 270, "full strength left the text intact too often (" + (300 - changedStrong) + " of 300)");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chat")
    public static void playerMessagesAreClassifiedAndQuotedSafely(GameTestHelper helper) {
        helper.assertValueEqual(ChatReactions.classify("hello"), ReplyKind.GREETING, "hello");
        helper.assertValueEqual(ChatReactions.classify("hey guys"), ReplyKind.GREETING, "hey guys");
        helper.assertValueEqual(ChatReactions.classify("where is the village?"), ReplyKind.QUESTION, "a question");
        helper.assertValueEqual(ChatReactions.classify("how do i get home"), ReplyKind.QUESTION, "a question without a question mark");
        helper.assertValueEqual(ChatReactions.classify("who is there"), ReplyKind.WHO, "who");
        helper.assertValueEqual(ChatReactions.classify("this is creepy"), ReplyKind.FEAR, "fear");
        helper.assertValueEqual(ChatReactions.classify("ok bye"), ReplyKind.LEAVE, "leaving");
        helper.assertValueEqual(ChatReactions.classify("what is NULL"), ReplyKind.NULL_NAME, "its name");
        helper.assertValueEqual(ChatReactions.classify("building a house"), ReplyKind.OTHER, "anything else");

        helper.assertValueEqual(ChatReactions.cleanSaid("/gamemode creative"), "", "a command was kept for quoting");
        helper.assertValueEqual(ChatReactions.cleanSaid("look at https://example.com"), "", "a link was kept for quoting");
        helper.assertValueEqual(ChatReactions.cleanSaid("  spaced \n out  "), "spaced out", "whitespace was not cleaned");
        helper.assertValueEqual(ChatReactions.cleanSaid("§cred"), "cred", "a section sign was kept");
        String cut = ChatReactions.cleanSaid("word ".repeat(60));
        helper.assertTrue(cut.length() <= 82 && cut.endsWith(".."), "a long message was not shortened: '" + cut + "'");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chat")
    public static void templatesResolveForAPlayerAndLinesDoNotRepeat(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            ChatCorruptionManager chat = ChatCorruptionManager.INSTANCE;
            for (ChatLine line : ChatMessages.all()) {
                String text = chat.resolve(line.text(), player, Map.of());
                helper.assertTrue(text.indexOf('{') < 0 && text.indexOf('}') < 0, "unresolved placeholder: '" + text + "'");
                helper.assertTrue(TextCorruptor.isValid(text), "invalid resolved line: '" + text + "'");
            }
            helper.assertValueEqual(chat.resolve("{player}", player, Map.of()), player.getScoreboardName(), "{player}");
            helper.assertValueEqual(chat.resolve("{x} {y} {z}", player, Map.of()),
                    player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ(), "{x} {y} {z}");
            helper.assertValueEqual(chat.resolve("hi {said}", player, Map.of("said", "{player}")), "hi {player}",
                    "a value was resolved a second time");

            int before = chat.deliveredCount(player.getUUID());
            for (int i = 0; i < 40; i++) {
                helper.assertTrue(chat.say(player, Category.WHISPER, CorruptionStage.ABOVE_MIDDLE, true), "no whisper left after " + i);
            }
            helper.assertTrue(chat.deliveredCount(player.getUUID()) >= before + 40, "not every line reached the player");
            List<Integer> history = chat.history(player.getUUID());
            helper.assertTrue(history.size() >= 40, "a line was repeated: only " + history.size() + " different lines in 40");
            for (String text : chat.recentlyDelivered(player.getUUID())) {
                helper.assertTrue(TextCorruptor.isValid(text), "invalid delivered line '" + text + "'");
            }
            helper.assertTrue(chat.markVisitedNull(player), "a new player had already been to NULL");
            helper.assertFalse(chat.markVisitedNull(player), "a second visit to NULL counted as the first");
        } finally {
            removeMockPlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chat_events")
    public static void forcedChatEventsDeliverToAMockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MinecraftServer server = helper.getLevel().getServer();
        try {
            ChatCorruptionManager chat = ChatCorruptionManager.INSTANCE;
            ChatReactions.recordSaid(player, "is anyone building near spawn");
            CorruptionContext ctx = CorruptionManager.context(server);
            for (String id : CHAT_EVENTS) {
                NullEventDefinition event = NullEventRegistry.get(id).orElse(null);
                helper.assertTrue(event != null, "event " + id + " is not registered");
                helper.assertTrue(event.has(EventTag.CHAT), id + " is not tagged CHAT");
                int before = chat.deliveredCount(player.getUUID());
                helper.assertTrue(RandomEventManager.INSTANCE.force(player, event, ctx), id + " could not run");
                helper.assertTrue(chat.deliveredCount(player.getUUID()) > before, id + " did not send a line right away");
            }
            for (String text : chat.recentlyDelivered(player.getUUID())) {
                helper.assertTrue(TextCorruptor.isValid(text), "invalid delivered line '" + text + "'");
            }
        } finally {
            removeMockPlayer(helper, player);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    /** Every stage in min..max has at least one line of each category (only non-personal ones unless {@code personal}). */
    private static void expect(GameTestHelper helper, int min, int max, boolean personal, Category... categories) {
        for (Category category : categories) {
            for (int number = min; number <= max; number++) {
                CorruptionStage stage = CorruptionStage.byNumber(number);
                boolean found = ChatMessages.of(category).stream()
                        .anyMatch(line -> line.allowedAt(stage) && (personal || !line.personal()) && line.topic().isEmpty());
                helper.assertTrue(found, "no " + (personal ? "" : "shared ") + category + " line for stage " + number);
            }
        }
    }

    /** The longest values the placeholders can take, to check line lengths in the worst case. */
    private static String longestValue(String key) {
        return switch (key) {
            case "player", "visitor", "name" -> "W".repeat(16);
            case "block", "biome", "dimension" -> "m".repeat(40);
            case "x", "z" -> "-30000000";
            case "y" -> "-2032";
            case "time", "clock" -> "23:59";
            case "deaths" -> "2147483647";
            case "day" -> "9223372036854775807";
            case "said" -> "s".repeat(80) + "..";
            case "online" -> "200";
            default -> "";
        };
    }

    private static void removeMockPlayer(GameTestHelper helper, ServerPlayer player) {
        try {
            helper.getLevel().getServer().getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after a chat test", e);
        }
    }
}
