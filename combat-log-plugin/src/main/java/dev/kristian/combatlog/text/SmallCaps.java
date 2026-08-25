package dev.kristian.combatlog.text;

/**
 * Turns plain ASCII into the unicode small-caps glyphs that Minecraft's default
 * font can actually render.
 *
 * <p>Colour tags ({@code <accent>}, {@code <#5FD3FF>}) and placeholders
 * ({@code %time%}) are stepped over so a conversion can never corrupt them.
 */
public final class SmallCaps {

    /** Indexed by {@code letter - 'a'}. Every glyph here exists in vanilla's font. */
    private static final char[] GLYPHS = {
            'ᴀ', // a
            'ʙ', // b
            'ᴄ', // c
            'ᴅ', // d
            'ᴇ', // e
            'ғ', // f
            'ɢ', // g
            'ʜ', // h
            'ɪ', // i
            'ᴊ', // j
            'ᴋ', // k
            'ʟ', // l
            'ᴍ', // m
            'ɴ', // n
            'ᴏ', // o
            'ᴘ', // p
            'ǫ', // q
            'ʀ', // r
            's',      // s - vanilla has no small-caps s that renders cleanly
            'ᴛ', // t
            'ᴜ', // u
            'ᴠ', // v
            'ᴡ', // w
            'x',      // x
            'ʏ', // y
            'ᴢ'  // z
    };

    private SmallCaps() {
    }

    /**
     * @param input             the raw message
     * @param convertUppercase  also fold {@code A-Z} into small caps
     */
    public static String convert(String input, boolean convertUppercase) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        int length = input.length();
        StringBuilder out = new StringBuilder(length);

        int index = 0;
        while (index < length) {
            char current = input.charAt(index);

            // "\<" is MiniMessage's escape - copy both characters untouched.
            if (current == '\\' && index + 1 < length) {
                out.append(current).append(input.charAt(index + 1));
                index += 2;
                continue;
            }

            // A tag or a placeholder is copied verbatim, but only once we have
            // seen it close. A lone "<" or "%" in the text is just a character.
            if (current == '<') {
                int close = input.indexOf('>', index + 1);
                if (close > index) {
                    out.append(input, index, close + 1);
                    index = close + 1;
                    continue;
                }
            } else if (current == '%') {
                int close = placeholderEnd(input, index);
                if (close > index) {
                    out.append(input, index, close + 1);
                    index = close + 1;
                    continue;
                }
            }

            out.append(fold(current, convertUppercase));
            index++;
        }
        return out.toString();
    }

    /** Index of the closing {@code %} of a {@code %placeholder%}, or -1. */
    private static int placeholderEnd(String input, int start) {
        for (int i = start + 1; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '%') {
                return i > start + 1 ? i : -1;
            }
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '-') {
                return -1;
            }
        }
        return -1;
    }

    private static char fold(char character, boolean convertUppercase) {
        if (character >= 'a' && character <= 'z') {
            return GLYPHS[character - 'a'];
        }
        if (convertUppercase && character >= 'A' && character <= 'Z') {
            return GLYPHS[character - 'A'];
        }
        return character;
    }
}
