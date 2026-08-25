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

        StringBuilder out = new StringBuilder(input.length());
        boolean inTag = false;
        boolean inPlaceholder = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // "\<" is MiniMessage's escape - copy both characters untouched.
            if (c == '\\' && i + 1 < input.length()) {
                out.append(c).append(input.charAt(++i));
                continue;
            }
            if (c == '<') {
                inTag = true;
                out.append(c);
                continue;
            }
            if (c == '>') {
                inTag = false;
                out.append(c);
                continue;
            }
            if (c == '%') {
                inPlaceholder = !inPlaceholder;
                out.append(c);
                continue;
            }
            if (inTag || inPlaceholder) {
                out.append(c);
                continue;
            }

            if (c >= 'a' && c <= 'z') {
                out.append(GLYPHS[c - 'a']);
            } else if (convertUppercase && c >= 'A' && c <= 'Z') {
                out.append(GLYPHS[c - 'A']);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
