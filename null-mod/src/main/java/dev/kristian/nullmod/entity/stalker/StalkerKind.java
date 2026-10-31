package dev.kristian.nullmod.entity.stalker;

import dev.kristian.nullmod.core.CorruptionStage;

/**
 * The five things the figure does. One encounter is one of them; which one is chosen by the
 * player's situation (outdoors near trees, at home at night, underground). All of them belong to
 * Stages 1-4; the window breaker only starts in Stage 2.
 */
public enum StalkerKind {
    /** Half hidden behind a tree trunk 20-40 blocks away, peeking; slides back when looked at. */
    TREE_WATCHER("tree", CorruptionStage.START),
    /** At night, outside a window of the house the player is in, looking in. */
    WINDOW_WATCHER("window", CorruptionStage.START),
    /** At night, doors near the player creak open one after another; the figure in a doorway. */
    DOOR_OPENER("doors", CorruptionStage.START),
    /** A window pane near the player shatters (it is always put back exactly as it was). */
    WINDOW_BREAKER("window_break", CorruptionStage.UNDER_MIDDLE),
    /** Underground: a pickaxe in the rock, coming nearer; a short tunnel; the figure at its end. */
    MINER("miner", CorruptionStage.START);

    private final String id;
    private final CorruptionStage firstStage;

    StalkerKind(String id, CorruptionStage firstStage) {
        this.id = id;
        this.firstStage = firstStage;
    }

    /** Short stable id, used in event ids ({@code stalker_<id>}) and in {@code WorldMemory} keys. */
    public String id() {
        return id;
    }

    /** The first stage in which it happens on its own. */
    public CorruptionStage firstStage() {
        return firstStage;
    }

    /** True if it may happen on its own at this stage (Stages 1-4 only). */
    public boolean allowedAt(CorruptionStage stage) {
        return stage.isBetween(firstStage, CorruptionStage.ABOVE_MIDDLE);
    }
}
