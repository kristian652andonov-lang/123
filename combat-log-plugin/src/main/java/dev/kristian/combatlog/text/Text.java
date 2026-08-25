package dev.kristian.combatlog.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders every string the plugin shows.
 *
 * <p>A message goes through four stages: legacy {@code &} codes are rewritten as
 * MiniMessage tags, the text is folded into small caps, {@code %placeholders%}
 * are substituted, and finally MiniMessage parses the result with the server
 * owner's theme colours bound to {@code <primary>}, {@code <accent>} and friends.
 */
public final class Text {

    private static final Pattern LEGACY_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private static final String[] LEGACY_TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white"
    };

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private TagResolver theme = TagResolver.empty();
    private boolean smallCaps = true;
    private boolean convertUppercase = true;
    private String prefix = "";

    /** Rebuilds the theme tags from the {@code theme:} section of the config. */
    public void reload(ConfigurationSection themeSection, boolean smallCaps, boolean convertUppercase, String prefix) {
        this.smallCaps = smallCaps;
        this.convertUppercase = convertUppercase;
        this.prefix = prefix == null ? "" : prefix;

        List<TagResolver> resolvers = new ArrayList<>();
        if (themeSection != null) {
            for (String key : themeSection.getKeys(false)) {
                TextColor color = TextColor.fromHexString(String.valueOf(themeSection.getString(key, "")).trim());
                if (color == null) {
                    continue;
                }
                String name = key.toLowerCase(Locale.ROOT);
                resolvers.add(Placeholder.styling(name, color));
                // Accept "primary_dark" as well as "primary-dark".
                String underscored = name.replace('-', '_');
                if (!underscored.equals(name)) {
                    resolvers.add(Placeholder.styling(underscored, color));
                }
            }
        }
        this.theme = resolvers.isEmpty() ? TagResolver.empty() : TagResolver.resolver(resolvers);
    }

    /**
     * Builds a component from a raw config string.
     *
     * @param raw the configured message
     * @param kv  alternating key/value pairs. A key written as {@code "!bar"} has
     *            its value inserted verbatim so it may contain its own tags;
     *            every other value is escaped and cannot inject formatting.
     * @return the component, or {@code null} when the message is blank (disabled)
     */
    public Component render(String raw, String... kv) {
        String prepared = prepare(raw, kv);
        return prepared == null ? null : miniMessage.deserialize(prepared, theme);
    }

    public List<Component> renderAll(List<String> lines, String... kv) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            // An empty line in a help menu is a deliberate spacer, so keep it.
            String prepared = prepare(line, kv);
            out.add(prepared == null ? Component.empty() : miniMessage.deserialize(prepared, theme));
        }
        return out;
    }

    /** Strips all formatting - handy for console output. */
    public String plain(String raw, String... kv) {
        Component component = render(raw, kv);
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    public void send(CommandSender target, String raw, String... kv) {
        Component component = render(raw, kv);
        if (component != null) {
            target.sendMessage(component);
        }
    }

    public void sendActionBar(Player target, String raw, String... kv) {
        Component component = render(raw, kv);
        if (component != null) {
            target.sendActionBar(component);
        }
    }

    /** Makes a value safe to drop into a message - it can never open a tag. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    private String prepare(String raw, String... kv) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        String working = raw.contains("%prefix%") ? raw.replace("%prefix%", prefix) : raw;
        working = legacyToMiniMessage(working);
        if (smallCaps) {
            working = SmallCaps.convert(working, convertUppercase);
        }

        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = kv[i];
            String value = kv[i + 1];
            boolean verbatim = key.startsWith("!");
            if (verbatim) {
                key = key.substring(1);
            }
            working = working.replace("%" + key + "%", verbatim ? value : escape(value));
        }
        return working;
    }

    /** Rewrites {@code &c}, {@code &l} and {@code &#RRGGBB} as MiniMessage tags. */
    private static String legacyToMiniMessage(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) {
            return input;
        }

        Matcher hex = LEGACY_HEX.matcher(input);
        StringBuilder hexOut = new StringBuilder();
        while (hex.find()) {
            hex.appendReplacement(hexOut, "<#" + hex.group(1) + ">");
        }
        hex.appendTail(hexOut);

        Matcher code = LEGACY_CODE.matcher(hexOut.toString());
        StringBuilder out = new StringBuilder();
        while (code.find()) {
            code.appendReplacement(out, Matcher.quoteReplacement(tagFor(Character.toLowerCase(code.group(1).charAt(0)))));
        }
        code.appendTail(out);
        return out.toString();
    }

    private static String tagFor(char code) {
        int colour = Character.digit(code, 16);
        if (colour >= 0) {
            return "<" + LEGACY_TAGS[colour] + ">";
        }
        return switch (code) {
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }
}
