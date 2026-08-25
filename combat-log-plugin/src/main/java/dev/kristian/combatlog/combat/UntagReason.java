package dev.kristian.combatlog.combat;

/** Why a player stopped being in combat. */
public enum UntagReason {

    /** The timer ran out normally. */
    EXPIRED,
    /** They died. */
    DEATH,
    /** They got a kill and the config frees the killer. */
    KILL,
    /** They disconnected - the tag is dropped after the punishment is applied. */
    QUIT,
    /** An operator ran /combatlog untag. */
    ADMIN,
    /** The plugin was reloaded or disabled. */
    SHUTDOWN
}
