package dev.kristian.nullmod.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;

/**
 * Makes chat text look damaged: look-alike substitutions (o/0, e/3...), accented and block
 * glyphs from Minecraft's own font page, missing letters, messages cut off mid-word, stuttering,
 * repetitions, shouting and obfuscated (constantly changing) spans.
 * <p>
 * Strength 0 leaves text untouched; it is meant to grow with the stage. Every output is
 * {@link #isValid valid}: at most {@value #MAX_LENGTH} characters (the vanilla chat limit), never
 * empty, and free of newlines, section signs, control characters and broken surrogate pairs.
 */
public final class TextCorruptor {
    /** Longest message vanilla lets a player type. */
    public static final int MAX_LENGTH = 256;

    /** Block, line and maths glyphs that are all on Minecraft's built-in ascii font page. */
    private static final String GLYPHS = "░▒▓█▄▀▌▐■ØøΣΩδ"
            + "∞∅≡±÷≈°√¿¡¬×ƒ║╬═┼";

    private TextCorruptor() {
    }

    // ------------------------------------------------------------------ combined

    /**
     * Corrupts plain text. The amount and kinds of damage grow with {@code strength} (0..1);
     * every step is random, so the same line comes out differently each time.
     */
    public static String corruptText(String text, float strength, RandomSource random) {
        String clean = sanitize(text);
        float s = Math.max(0.0F, Math.min(1.0F, strength));
        if (clean.isEmpty() || s <= 0.001F) {
            return clean;
        }
        String t = clean;
        if (random.nextFloat() < 0.2F + 0.5F * s) {
            t = accents(t, 0.01F + 0.05F * s, random);
        }
        if (random.nextFloat() < 0.1F + 0.7F * s) {
            t = substitute(t, 0.04F + 0.3F * s, random);
        }
        if (random.nextFloat() < 0.08F + 0.35F * s) {
            t = dropLetters(t, 0.02F + 0.1F * s, random);
        }
        if (random.nextFloat() < 0.04F + 0.3F * s) {
            t = stutter(t, random);
        }
        if (random.nextFloat() < 0.03F + 0.25F * s) {
            t = repeat(t, random);
        }
        if (s > 0.3F && random.nextFloat() < (s - 0.3F) * 1.3F) {
            t = glyphs(t, 0.02F + 0.08F * s, random);
        }
        if (random.nextFloat() < 0.05F + 0.3F * s) {
            t = cut(t, random);
        }
        if (s > 0.25F && random.nextFloat() < 0.04F + 0.4F * (s - 0.25F)) {
            t = shout(t);
        }
        String out = sanitize(t);
        return out.isEmpty() ? clean : out;
    }

    /** {@link #corruptText} as a chat component, sometimes with one or two obfuscated spans. */
    public static MutableComponent corrupt(String text, float strength, RandomSource random) {
        String t = corruptText(text, strength, random);
        float s = Math.max(0.0F, Math.min(1.0F, strength));
        if (t.isEmpty() || s <= 0.1F || random.nextFloat() >= 0.05F + 0.55F * s) {
            return Component.literal(t);
        }
        int spans = s > 0.6F && random.nextBoolean() ? 2 : 1;
        return obfuscate(t, spans, random);
    }

    // ------------------------------------------------------------------ single kinds of damage

    /** Swaps letters for look-alike digits and symbols (o to 0, e to 3, a to 4, s to 5...). */
    public static String substitute(String text, float perChar, RandomSource random) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char swap = random.nextFloat() < perChar ? lookalike(c, random) : '\0';
            out.append(swap != '\0' ? swap : c);
        }
        return out.toString();
    }

    /** Replaces vowels and a few consonants with accented forms; subtle, it only looks slightly off. */
    public static String accents(String text, float perChar, RandomSource random) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String options = random.nextFloat() < perChar ? accentOptions(c) : null;
            out.append(options != null ? options.charAt(random.nextInt(options.length())) : c);
        }
        return out.toString();
    }

    /** Replaces some characters with block and line glyphs, and sometimes inserts one. */
    public static String glyphs(String text, float perChar, RandomSource random) {
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSurrogate(c)) {
                out.append(c);
                continue;
            }
            float roll = random.nextFloat();
            if (c != ' ' && roll < perChar) {
                out.append(randomGlyph(random));
            } else {
                out.append(c);
                if (roll > 1.0F - perChar * 0.3F) {
                    out.append(randomGlyph(random));
                }
            }
        }
        return out.toString();
    }

    /** Leaves out letters, as if some keys did not register. Never drops everything. */
    public static String dropLetters(String text, float perChar, RandomSource random) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean wordStart = i == 0 || text.charAt(i - 1) == ' ';
            if (Character.isLetter(c) && !wordStart && random.nextFloat() < perChar) {
                continue;
            }
            out.append(c);
        }
        return out.length() == 0 ? text : out.toString();
    }

    /** Cuts the message off, usually in the middle of a word, sometimes trailing off with "..". */
    public static String cut(String text, RandomSource random) {
        int length = text.length();
        if (length < 5) {
            return text;
        }
        int from = Math.max(2, (int) (length * 0.4F));
        int to = Math.max(from + 1, (int) (length * 0.85F));
        int at = from + random.nextInt(Math.max(1, to - from));
        // Prefer cutting inside a word: step forward off a space.
        while (at < length - 1 && text.charAt(at - 1) == ' ') {
            at++;
        }
        if (at > 0 && Character.isHighSurrogate(text.charAt(at - 1))) {
            at--;
        }
        String head = text.substring(0, Math.max(1, at)).stripTrailing();
        if (head.isEmpty()) {
            return text;
        }
        float roll = random.nextFloat();
        if (roll < 0.45F) {
            return head + "..";
        }
        if (roll < 0.55F) {
            return head + "...";
        }
        if (roll < 0.7F) {
            return head + "-";
        }
        return head;
    }

    /** "h-h-help", "d-dont": stutters the first letters of one word. */
    public static String stutter(String text, RandomSource random) {
        List<int[]> words = words(text);
        List<int[]> candidates = new ArrayList<>();
        for (int[] word : words) {
            if (Character.isLetter(text.charAt(word[0]))) {
                candidates.add(word);
            }
        }
        if (candidates.isEmpty()) {
            return text;
        }
        int[] word = random.nextFloat() < 0.5F ? candidates.get(0) : candidates.get(random.nextInt(candidates.size()));
        char first = text.charAt(word[0]);
        int times = 1 + random.nextInt(3);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < times; i++) {
            prefix.append(first).append('-');
        }
        return text.substring(0, word[0]) + prefix + text.substring(word[0]);
    }

    /** Repeats a word ("help help help me"), the whole message, or the last letter ("hellooooo"). */
    public static String repeat(String text, RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.45F) {
            List<int[]> words = words(text);
            if (!words.isEmpty()) {
                int[] word = words.get(random.nextInt(words.size()));
                String w = text.substring(word[0], word[1]);
                int times = 1 + random.nextInt(3);
                StringBuilder copies = new StringBuilder();
                for (int i = 0; i < times; i++) {
                    copies.append(' ').append(w);
                }
                return text.substring(0, word[1]) + copies + text.substring(word[1]);
            }
        } else if (roll < 0.75F && text.length() <= 60) {
            return text + " " + text;
        }
        char last = text.charAt(text.length() - 1);
        if (Character.isLetter(last)) {
            int extra = 2 + random.nextInt(5);
            return text + String.valueOf(last).repeat(extra);
        }
        return text;
    }

    /** ALL CAPS. */
    public static String shout(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    /**
     * Builds a component where {@code spans} parts of the text (whole words, or a few letters)
     * are obfuscated: they render as constantly changing random characters. A single span never
     * covers more than about 60% of the text, so the message stays partly readable.
     */
    public static MutableComponent obfuscate(String text, int spans, RandomSource random) {
        String t = sanitize(text);
        if (t.isEmpty()) {
            return Component.literal(t);
        }
        List<int[]> ranges = new ArrayList<>();
        List<int[]> words = words(t);
        int maxLength = Math.max(1, (int) Math.ceil(t.length() * 0.6F));
        for (int n = 0; n < Math.max(1, spans) && ranges.size() < spans; n++) {
            int[] range;
            if (!words.isEmpty() && random.nextFloat() < 0.6F) {
                int[] word = words.get(random.nextInt(words.size()));
                range = new int[] {word[0], word[1]};
            } else {
                int len = Math.min(t.length(), 2 + random.nextInt(7));
                int start = random.nextInt(Math.max(1, t.length() - len + 1));
                range = new int[] {start, start + len};
            }
            if (range[1] - range[0] > maxLength) {
                int start = range[0] + random.nextInt(range[1] - range[0] - maxLength + 1);
                range = new int[] {start, start + maxLength};
            }
            range[0] = alignStart(t, range[0]);
            range[1] = alignEnd(t, range[1]);
            if (range[1] > range[0] && !overlaps(ranges, range)) {
                ranges.add(range);
            }
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        MutableComponent root = Component.empty();
        int pos = 0;
        for (int[] range : ranges) {
            if (range[0] > pos) {
                root.append(Component.literal(t.substring(pos, range[0])));
            }
            root.append(Component.literal(t.substring(range[0], range[1])).withStyle(ChatFormatting.OBFUSCATED));
            pos = range[1];
        }
        if (pos < t.length()) {
            root.append(Component.literal(t.substring(pos)));
        }
        return root;
    }

    // ------------------------------------------------------------------ validity

    /**
     * Makes any text safe for chat: newlines and tabs become spaces, section signs, control
     * characters, private-use characters and broken surrogate pairs are removed, runs of spaces
     * collapse, and the result is trimmed to {@value #MAX_LENGTH} characters.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LENGTH + 8));
        boolean space = true;
        for (int i = 0; i < text.length() && out.length() < MAX_LENGTH; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!space) {
                    out.append(' ');
                    space = true;
                }
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    if (out.length() + 2 <= MAX_LENGTH) {
                        out.append(c).append(text.charAt(i + 1));
                        space = false;
                    }
                    i++;
                }
                continue;
            }
            if (!allowed(c)) {
                continue;
            }
            out.append(c);
            space = false;
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    /**
     * True if {@code text} can be sent as a chat message: not blank, at most
     * {@value #MAX_LENGTH} characters, and nothing {@link #sanitize} would remove.
     */
    public static boolean isValid(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
                continue;
            }
            if (c == '\n' || c == '\r' || (!allowed(c) && c != ' ')) {
                return false;
            }
        }
        return true;
    }

    private static boolean allowed(char c) {
        return c >= ' ' && c != '§' && c != 0x7F && !Character.isISOControl(c) && !Character.isSurrogate(c)
                && !(c >= '' && c <= '') && c < '￰';
    }

    // ------------------------------------------------------------------ helpers

    private static char lookalike(char c, RandomSource random) {
        return switch (Character.toLowerCase(c)) {
            case 'o' -> '0';
            case 'e' -> '3';
            case 'a' -> random.nextFloat() < 0.75F ? '4' : '@';
            case 'i' -> random.nextFloat() < 0.75F ? '1' : '!';
            case 'l' -> random.nextFloat() < 0.6F ? '1' : '|';
            case 's' -> random.nextFloat() < 0.75F ? '5' : '$';
            case 't' -> '7';
            case 'b' -> '8';
            case 'g' -> random.nextBoolean() ? '9' : '6';
            case 'z' -> '2';
            default -> '\0';
        };
    }

    private static String accentOptions(char c) {
        return switch (c) {
            case 'a' -> "àáâäå";
            case 'e' -> "èéêë";
            case 'i' -> "ìíîï";
            case 'o' -> "òóôö";
            case 'u' -> "ùúûü";
            case 'y' -> "ÿ";
            case 'n' -> "ñ";
            case 'c' -> "ç";
            case 'A' -> "ÄÅ";
            case 'E' -> "É";
            case 'O' -> "Ö";
            case 'U' -> "Ü";
            case 'N' -> "Ñ";
            case 'C' -> "Ç";
            default -> null;
        };
    }

    private static char randomGlyph(RandomSource random) {
        return GLYPHS.charAt(random.nextInt(GLYPHS.length()));
    }

    /** [start, end) of every run of non-space characters. */
    private static List<int[]> words(String text) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean boundary = i == text.length() || text.charAt(i) == ' ';
            if (boundary) {
                if (start >= 0) {
                    out.add(new int[] {start, i});
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        return out;
    }

    private static int alignStart(String text, int index) {
        int i = Math.max(0, Math.min(index, text.length()));
        if (i > 0 && i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int alignEnd(String text, int index) {
        int i = Math.max(0, Math.min(index, text.length()));
        if (i > 0 && i < text.length() && Character.isLowSurrogate(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean overlaps(List<int[]> ranges, int[] range) {
        for (int[] other : ranges) {
            if (range[0] < other[1] && other[0] < range[1]) {
                return true;
            }
        }
        return false;
    }
}
