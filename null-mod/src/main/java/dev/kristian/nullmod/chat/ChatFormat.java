package dev.kristian.nullmod.chat;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EntityType;

/**
 * Builds chat lines that look exactly like the game's own: player chat, direct messages,
 * yellow join and leave lines, death and advancement messages, announcements. Everything uses
 * the vanilla translation keys (with an English fallback), so each client shows them in its own
 * language and format, and they are sent as system messages: nothing here is ever signed or
 * attributed to a real player's chat session.
 */
public final class ChatFormat {
    private ChatFormat() {
    }

    // ------------------------------------------------------------------ names

    /**
     * A username styled like a real player's name in chat: hovering shows the player tooltip
     * (offline-mode UUID), clicking suggests {@code /tell name}, shift-clicking inserts it.
     */
    public static MutableComponent playerName(String name) {
        UUID id = UUIDUtil.createOfflinePlayerUUID(name);
        return Component.literal(name).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tell " + name + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                        new HoverEvent.EntityTooltipInfo(EntityType.PLAYER, id, Component.literal(name))))
                .withInsertion(name));
    }

    /** The sender of lines whose author cannot be resolved; no tooltip, nothing to click. */
    public static MutableComponent unknownName() {
        return Component.translatableWithFallback("null.chat.sender.unknown", "Unknown");
    }

    // ------------------------------------------------------------------ chat

    /** {@code <name> message}, the vanilla player chat format. */
    public static MutableComponent chat(Component name, Component message) {
        return Component.translatableWithFallback("chat.type.text", "<%s> %s", name, message);
    }

    /** {@code name whispers to you: message}, the vanilla incoming direct message (grey italics). */
    public static MutableComponent whisper(Component name, Component message) {
        return Component.translatableWithFallback("commands.message.display.incoming", "%s whispers to you: %s", name, message)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    /** {@code [sender] message}, the format of /say. */
    public static MutableComponent announcement(Component sender, Component message) {
        return Component.translatableWithFallback("chat.type.announcement", "[%s] %s", sender, message);
    }

    /** NULL speaking: {@code [NULL] MESSAGE} in dark red. */
    public static MutableComponent nullLine(Component message) {
        return announcement(Component.literal("NULL"), message).withStyle(ChatFormatting.DARK_RED);
    }

    /** A grey server log line. */
    public static MutableComponent log(Component text) {
        return Component.empty().append(text).withStyle(ChatFormatting.GRAY);
    }

    /** A red error line. */
    public static MutableComponent error(Component text) {
        return Component.empty().append(text).withStyle(ChatFormatting.RED);
    }

    // ------------------------------------------------------------------ connections

    public static MutableComponent joined(Component name) {
        return Component.translatableWithFallback("multiplayer.player.joined", "%s joined the game", name).withStyle(ChatFormatting.YELLOW);
    }

    public static MutableComponent left(Component name) {
        return Component.translatableWithFallback("multiplayer.player.left", "%s left the game", name).withStyle(ChatFormatting.YELLOW);
    }

    /** "name (formerly known as formerly) joined the game". */
    public static MutableComponent joinedRenamed(Component name, Component formerly) {
        return Component.translatableWithFallback("multiplayer.player.joined.renamed", "%s (formerly known as %s) joined the game",
                name, formerly).withStyle(ChatFormatting.YELLOW);
    }

    // ------------------------------------------------------------------ deaths and advancements

    /** The ways a fake death message can read; each is a vanilla death message. */
    public enum Death {
        OUT_OF_WORLD("death.attack.outOfWorld", "%1$s fell out of the world"),
        GENERIC("death.attack.generic", "%1$s died"),
        FELL("death.fell.accident.generic", "%1$s fell from a high place"),
        IN_WALL("death.attack.inWall", "%1$s suffocated in a wall"),
        DROWNED("death.attack.drown", "%1$s drowned"),
        STARVED("death.attack.starve", "%1$s starved to death"),
        /** Needs a killer: "%1$s was slain by %2$s". */
        SLAIN("death.attack.player", "%1$s was slain by %2$s");

        private final String key;
        private final String fallback;

        Death(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }

        public boolean needsKiller() {
            return this == SLAIN;
        }
    }

    /** A death message exactly like the game's (no colour). {@code killer} is only used by {@link Death#SLAIN}. */
    public static MutableComponent death(Death kind, Component victim, Component killer) {
        if (kind.needsKiller()) {
            return Component.translatableWithFallback(kind.key, kind.fallback, victim, killer);
        }
        return Component.translatableWithFallback(kind.key, kind.fallback, victim);
    }

    /**
     * "name has made the advancement [title]" with the green bracketed title and its tooltip,
     * like a real task advancement. The advancement itself does not exist.
     */
    public static MutableComponent advancement(Component name, String title, String description) {
        Component tooltip = Component.literal(title).withStyle(ChatFormatting.GREEN)
                .append(Component.literal("\n"))
                .append(Component.literal(description).withStyle(ChatFormatting.WHITE));
        MutableComponent bracketed = ComponentUtils.wrapInSquareBrackets(Component.literal(title))
                .withStyle(ChatFormatting.GREEN)
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip)));
        return Component.translatableWithFallback("chat.type.advancement.task", "%s has made the advancement %s", name, bracketed);
    }
}
