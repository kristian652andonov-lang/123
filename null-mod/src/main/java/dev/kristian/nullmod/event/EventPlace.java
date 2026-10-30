package dev.kristian.nullmod.event;

/** Where the targeted player has to be for an event to be eligible. */
public enum EventPlace {
    ANYWHERE,
    /** Any dimension except NULL (the original world). */
    OUTSIDE_NULL,
    /** Only inside the NULL dimension. */
    INSIDE_NULL,
    /** Only in the overworld (the moon, sky bedrock...). */
    OVERWORLD
}
