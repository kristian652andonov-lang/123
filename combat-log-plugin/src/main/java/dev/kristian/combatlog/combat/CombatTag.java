package dev.kristian.combatlog.combat;

import java.util.UUID;

/** One player's live combat timer. */
public final class CombatTag {

    private final UUID playerId;
    private final long taggedAt;

    private long expiresAt;
    private long duration;
    private UUID opponentId;
    private String opponentName = "";

    /** Rate limits the "you cannot enter a safe zone" spam. */
    private long lastBlockedMessageAt;

    CombatTag(UUID playerId, long duration, long now) {
        this.playerId = playerId;
        this.taggedAt = now;
        this.duration = duration;
        this.expiresAt = now + duration;
    }

    public UUID playerId() {
        return playerId;
    }

    public long taggedAt() {
        return taggedAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    /** How long this tag was set to run for - the denominator of the progress bar. */
    public long duration() {
        return duration;
    }

    public long remaining(long now) {
        return Math.max(0L, expiresAt - now);
    }

    /** How much of the timer is left, from 1.0 down to 0.0. */
    public float progress(long now) {
        if (duration <= 0L) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, remaining(now) / (float) duration));
    }

    public UUID opponentId() {
        return opponentId;
    }

    public String opponentName() {
        return opponentName;
    }

    public boolean tryBlockedMessage(long now, long cooldownMillis) {
        if (now - lastBlockedMessageAt < cooldownMillis) {
            return false;
        }
        lastBlockedMessageAt = now;
        return true;
    }

    void refresh(long duration, long now) {
        this.duration = duration;
        this.expiresAt = now + duration;
    }

    void extendTo(long expiresAt, long duration) {
        this.expiresAt = expiresAt;
        this.duration = duration;
    }

    void setOpponent(UUID opponentId, String opponentName) {
        this.opponentId = opponentId;
        this.opponentName = opponentName == null ? "" : opponentName;
    }
}
