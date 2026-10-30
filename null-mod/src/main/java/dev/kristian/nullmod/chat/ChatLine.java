package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.core.CorruptionStage;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One line of the corrupted chat database. A line belongs to one main {@link Category} (and
 * optionally a few more), can only appear between two stages, and is spoken by a kind of
 * {@link Speaker}. {@code text} is a template: placeholders such as {@code {player}} are
 * filled in by {@link ChatPlaceholders} right before the line is sent.
 *
 * @param index        position in {@link ChatMessages#all()}; used as the line's id in histories
 * @param topic        narrows memory lines to one kind of memory (e.g. a structure type); empty for any
 * @param placeholders the placeholder names used in {@code text}, in order
 * @param personal     true if the line only makes sense for one player (it names them, quotes them,
 *                     uses their position...), so it is never shown to a whole dimension
 */
public record ChatLine(
        int index,
        Category category,
        Set<Category> also,
        String topic,
        Speaker speaker,
        int minStage,
        int maxStage,
        String text,
        List<String> placeholders,
        boolean personal) {

    /** What a line is about, and so which part of the chat simulation uses it. */
    public enum Category {
        // ---- ambient chatter
        /** Questions into the void. */
        QUESTION,
        /** Quiet, unsettling statements. */
        WHISPER,
        /** Warnings: do not look, do not dig, do not sleep. */
        WARNING,
        /** NULL itself: "[NULL] WORLD NOT FOUND". */
        NULL_SYSTEM,
        /** Connection and server log lines, and players talking about lag and disconnects. */
        CONNECTION,
        /** Lines that only say or whisper the player's name. */
        NAME,
        /** Lines of the final stage, when the player list is full of people who are not there. */
        STAGE5,
        /** Short fragments for rapid bursts. */
        BURST,
        /** What someone who just (fake-)joined says. */
        JOIN_HELLO,
        /** Titles of advancements that do not exist. */
        ADVANCEMENT,

        // ---- lines that refer back to something that happened (WorldMemory)
        MEMORY_MOON,
        MEMORY_MOON_GONE,
        MEMORY_VISITOR,
        MEMORY_FAKE_PLAYER,
        MEMORY_NULLROOT,
        MEMORY_STRUCTURE,
        MEMORY_SKY,
        MEMORY_CHUNKS,
        MEMORY_BLACKOUT,
        MEMORY_DISCONNECT,
        MEMORY_SILHOUETTE,
        MEMORY_SOUNDS,
        MEMORY_CORRUPTION,

        // ---- reactions to what the player does
        REPLY,
        REPLY_QUESTION,
        REPLY_GREETING,
        REPLY_WHO,
        REPLY_FEAR,
        REPLY_LEAVE,
        REPLY_NULL,
        /** Quotes something the player said ({said}). */
        ECHO,
        DEATH,
        BREAK_CORRUPTION,
        SLEEP,
        WAKE,
        DEEP,
        STILL,
        NULL_ARRIVAL,
        NULL_RETURN
    }

    /** Who a line looks like it comes from, which decides how it is formatted. */
    public enum Speaker {
        /** A username, formatted exactly like vanilla player chat: {@code <Name> text}. */
        USER,
        /** {@code <Unknown> text}. */
        UNKNOWN,
        /** A direct message: {@code Name whispers to you: text} in grey italics. Personal only. */
        WHISPER,
        /** Picked when the line is sent: a username, Unknown, or a whisper. */
        VOICE,
        /** NULL itself, as a dark red announcement: {@code [NULL] TEXT}. */
        NULL,
        /** A grey server log line. */
        LOG,
        /** A red error line. */
        ERROR,
        /** The player's own name, as if they had typed it. Personal only. */
        SELF,
        /** "Name has made the advancement [text]". */
        ADVANCEMENT;

        /** True for lines that look like player chat (hidden when a client only shows system messages). */
        public boolean looksLikeChat() {
            return this == USER || this == UNKNOWN || this == WHISPER || this == VOICE || this == SELF;
        }
    }

    public ChatLine {
        also = Collections.unmodifiableSet(also.isEmpty() ? EnumSet.noneOf(Category.class) : EnumSet.copyOf(also));
        placeholders = List.copyOf(placeholders);
    }

    /** True if this line can be used for {@code wanted}. */
    public boolean in(Category wanted) {
        return category == wanted || also.contains(wanted);
    }

    public boolean allowedAt(CorruptionStage stage) {
        return stage.number() >= minStage && stage.number() <= maxStage;
    }

    public boolean uses(String placeholder) {
        return placeholders.contains(placeholder);
    }
}
