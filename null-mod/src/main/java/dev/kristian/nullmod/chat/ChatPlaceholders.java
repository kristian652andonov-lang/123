package dev.kristian.nullmod.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The placeholders chat templates may use, and the code that fills them in. Templates are
 * resolved in a single pass, so a value (such as something a player typed) is never scanned for
 * placeholders itself.
 * <ul>
 *   <li>{@code {player}} the player the line is for</li>
 *   <li>{@code {visitor}} a Visitor the player has seen (or whoever the remembered event was about)</li>
 *   <li>{@code {name}} some other username</li>
 *   <li>{@code {block}} the block the player stands on (or the block involved)</li>
 *   <li>{@code {x} {y} {z}} the player's block position</li>
 *   <li>{@code {time}} the in-game time of day, {@code {clock}} the real time on the server</li>
 *   <li>{@code {biome}} the player's biome, {@code {dimension}} their dimension</li>
 *   <li>{@code {deaths}} how many times the player has died, {@code {day}} the in-game day</li>
 *   <li>{@code {said}} something the player said in chat</li>
 *   <li>{@code {online}} how many players the player list shows</li>
 * </ul>
 */
public final class ChatPlaceholders {
    public static final Set<String> KNOWN = Set.of("player", "visitor", "name", "block", "x", "y", "z", "time", "clock",
            "biome", "dimension", "deaths", "day", "said", "online");

    /** Placeholders whose value belongs to one player, so a line using them is never broadcast. */
    public static final Set<String> PERSONAL = Set.of("player", "block", "x", "y", "z", "biome", "deaths", "said");

    private ChatPlaceholders() {
    }

    /** Names of every {@code {placeholder}} in {@code text}, in order (unknown names included). */
    public static List<String> find(String text) {
        List<String> out = new ArrayList<>(2);
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = text.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String name = text.substring(open + 1, close);
            if (isToken(name)) {
                out.add(name);
                i = close + 1;
            } else {
                i = open + 1;
            }
        }
        return out;
    }

    /** True if every placeholder in {@code text} is a known one and no braces are left over. */
    public static boolean allKnown(String text) {
        for (String name : find(text)) {
            if (!KNOWN.contains(name)) {
                return false;
            }
        }
        String stripped = text;
        for (String name : KNOWN) {
            stripped = stripped.replace("{" + name + "}", "");
        }
        return stripped.indexOf('{') < 0 && stripped.indexOf('}') < 0;
    }

    /**
     * Replaces every known placeholder with {@code values.apply(name)} (null counts as empty).
     * Unknown placeholders are dropped. Doubled spaces left by empty values are collapsed.
     */
    public static String resolve(String text, Function<String, String> values) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 24);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i + 1);
                if (close > i) {
                    String name = text.substring(i + 1, close);
                    if (isToken(name)) {
                        if (KNOWN.contains(name)) {
                            String value = values.apply(name);
                            if (value != null) {
                                out.append(value);
                            }
                        }
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return collapseSpaces(out.toString());
    }

    private static boolean isToken(String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    private static String collapseSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                if (!space && out.length() > 0) {
                    out.append(c);
                }
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }
}
