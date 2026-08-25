package dev.kristian.combatlog.region;

/** A region the plugin treats as protected ground for tagged players. */
public interface SafeZone {

    String name();

    boolean contains(int x, int y, int z);
}
