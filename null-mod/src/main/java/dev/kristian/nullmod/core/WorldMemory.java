package dev.kristian.nullmod.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/**
 * A short memory of what has happened lately (events, captures, the moon...), so later
 * events and chat messages can refer back to it. Kept in memory only.
 */
public final class WorldMemory {
    private static final int CAPACITY = 128;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    /**
     * @param key      what happened, e.g. {@code "event:visitor_behind"} or {@code "moon:shattered"}
     * @param player   who it happened to, if anyone
     * @param detail   free text (a block name, a username...)
     * @param gameTime overworld game time when it happened
     */
    public record Entry(String key, @Nullable UUID player, String detail, long gameTime) {
    }

    private WorldMemory() {
    }

    public static synchronized void remember(MinecraftServer server, String key, @Nullable UUID player, String detail) {
        ENTRIES.addFirst(new Entry(key, player, detail == null ? "" : detail, server.overworld().getGameTime()));
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeLast();
        }
    }

    /** Newest first, at most {@code maxAgeTicks} old. */
    public static synchronized List<Entry> recent(MinecraftServer server, long maxAgeTicks) {
        long now = server.overworld().getGameTime();
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (now - entry.gameTime() <= maxAgeTicks) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Newest first entries for one player (or world-wide entries with no player). */
    public static synchronized List<Entry> recentFor(MinecraftServer server, UUID player, long maxAgeTicks) {
        long now = server.overworld().getGameTime();
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (now - entry.gameTime() <= maxAgeTicks && (entry.player() == null || entry.player().equals(player))) {
                out.add(entry);
            }
        }
        return out;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }
}
