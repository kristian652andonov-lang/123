package dev.kristian.nullmod.client;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.network.FakePlayersPayload;
import dev.kristian.nullmod.network.StageSyncPayload;
import dev.kristian.nullmod.network.TransitionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The client's copy of the world's corruption state, as last synced by the server. Only
 * meaningful while connected to a world; reset on logout. Accessed on the render thread.
 */
public final class ClientCorruptionState {
    @FunctionalInterface
    public interface StageListener {
        void onStageChanged(CorruptionStage from, CorruptionStage to);
    }

    private static final List<StageListener> STAGE_LISTENERS = new ArrayList<>();
    private static final List<Consumer<TransitionPayload>> TRANSITION_LISTENERS = new ArrayList<>();
    private static final List<Consumer<List<FakePlayersPayload.Entry>>> FAKE_PLAYER_LISTENERS = new ArrayList<>();

    private static boolean synced;
    private static CorruptionStage stage = CorruptionStage.START;
    private static CorruptionStage highestStage = CorruptionStage.START;
    private static float stageProgress;
    private static float overallProgress;
    private static float moonProgress;
    private static boolean moonGone;
    private static boolean paused;
    private static long seed;
    private static List<FakePlayersPayload.Entry> fakePlayers = List.of();

    private ClientCorruptionState() {
    }

    public static void addStageListener(StageListener listener) {
        STAGE_LISTENERS.add(listener);
    }

    public static void addTransitionListener(Consumer<TransitionPayload> listener) {
        TRANSITION_LISTENERS.add(listener);
    }

    public static void addFakePlayerListener(Consumer<List<FakePlayersPayload.Entry>> listener) {
        FAKE_PLAYER_LISTENERS.add(listener);
    }

    static void apply(StageSyncPayload payload) {
        CorruptionStage previous = stage;
        boolean first = !synced;
        synced = true;
        stage = CorruptionStage.byNumber(payload.stage());
        highestStage = CorruptionStage.byNumber(payload.highestStage());
        stageProgress = payload.stageProgress();
        overallProgress = payload.overallProgress();
        moonProgress = payload.moonProgress();
        moonGone = payload.moonGone();
        paused = payload.paused();
        seed = payload.seed();
        ClientStageMemory.record(highestStage);
        if (first || previous != stage) {
            CorruptionStage from = first ? CorruptionStage.START : previous;
            for (StageListener listener : List.copyOf(STAGE_LISTENERS)) {
                listener.onStageChanged(from, stage);
            }
        }
    }

    static void applyTransition(TransitionPayload payload) {
        for (Consumer<TransitionPayload> listener : List.copyOf(TRANSITION_LISTENERS)) {
            listener.accept(payload);
        }
    }

    static void applyFakePlayers(FakePlayersPayload payload) {
        fakePlayers = List.copyOf(payload.entries());
        for (Consumer<List<FakePlayersPayload.Entry>> listener : List.copyOf(FAKE_PLAYER_LISTENERS)) {
            listener.accept(fakePlayers);
        }
    }

    static void reset() {
        CorruptionStage previous = stage;
        synced = false;
        stage = CorruptionStage.START;
        highestStage = CorruptionStage.START;
        stageProgress = 0.0F;
        overallProgress = 0.0F;
        moonProgress = 0.0F;
        moonGone = false;
        paused = false;
        seed = 0L;
        fakePlayers = List.of();
        if (previous != CorruptionStage.START) {
            for (StageListener listener : List.copyOf(STAGE_LISTENERS)) {
                listener.onStageChanged(previous, CorruptionStage.START);
            }
        }
        for (Consumer<List<FakePlayersPayload.Entry>> listener : List.copyOf(FAKE_PLAYER_LISTENERS)) {
            listener.accept(fakePlayers);
        }
    }

    /** True once the server has sent the state for the current world. */
    public static boolean synced() {
        return synced;
    }

    public static CorruptionStage stage() {
        return stage;
    }

    public static CorruptionStage highestStage() {
        return highestStage;
    }

    public static boolean atLeast(CorruptionStage other) {
        return synced && stage.atLeast(other);
    }

    public static float stageProgress() {
        return stageProgress;
    }

    public static float overallProgress() {
        return overallProgress;
    }

    /** Smooth 0..1 value across all five stages (Stage 5 saturates over time). */
    public static float continuousStage() {
        return Math.min(1.0F, ((stage.number() - 1) + stageProgress) / 5.0F);
    }

    public static float moonProgress() {
        return moonProgress;
    }

    public static boolean moonGone() {
        return moonGone;
    }

    public static boolean paused() {
        return paused;
    }

    public static long seed() {
        return seed;
    }

    public static List<FakePlayersPayload.Entry> fakePlayers() {
        return fakePlayers;
    }
}
