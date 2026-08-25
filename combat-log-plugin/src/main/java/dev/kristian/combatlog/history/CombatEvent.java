package dev.kristian.combatlog.history;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One line of the combat log. */
public final class CombatEvent {

    private final long id;
    private final EventType type;
    private final long timestamp;

    /** The player the entry is about - who died, who was hit, whose inventory was restored. */
    private final UUID subjectId;
    private final String subjectName;

    /** The other party. Null for a death with nobody to blame. */
    private final UUID actorId;
    private final String actorName;

    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    private final String cause;
    private final String weapon;

    /** Base64 inventory. Dropped from old entries to keep history.yml small. */
    private String snapshot;

    public CombatEvent(long id, EventType type, long timestamp,
                       UUID subjectId, String subjectName,
                       UUID actorId, String actorName,
                       String worldName, int x, int y, int z,
                       String cause, String weapon, String snapshot) {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        this.subjectId = subjectId;
        this.subjectName = subjectName == null ? "?" : subjectName;
        this.actorId = actorId;
        this.actorName = actorName;
        this.worldName = worldName == null ? "?" : worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.cause = cause;
        this.weapon = weapon;
        this.snapshot = snapshot;
    }

    public long id() {
        return id;
    }

    public EventType type() {
        return type;
    }

    public long timestamp() {
        return timestamp;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public String subjectName() {
        return subjectName;
    }

    public UUID actorId() {
        return actorId;
    }

    public String actorName() {
        return actorName == null ? "" : actorName;
    }

    public boolean hasActor() {
        return actorId != null;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String cause() {
        return cause == null ? "" : cause;
    }

    public String weapon() {
        return weapon == null ? "" : weapon;
    }

    public String snapshot() {
        return snapshot;
    }

    public boolean hasSnapshot() {
        return snapshot != null && !snapshot.isBlank();
    }

    /** Old entries keep their metadata but give up the bulky item data. */
    void clearSnapshot() {
        this.snapshot = null;
    }

    public boolean involves(UUID playerId) {
        return playerId.equals(subjectId) || playerId.equals(actorId);
    }

    public String coordinates() {
        return x + ", " + y + ", " + z;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type.name());
        map.put("time", timestamp);
        map.put("subject", subjectId.toString());
        map.put("subject-name", subjectName);
        if (actorId != null) {
            map.put("actor", actorId.toString());
            map.put("actor-name", actorName);
        }
        map.put("world", worldName);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        if (cause != null) {
            map.put("cause", cause);
        }
        if (weapon != null) {
            map.put("weapon", weapon);
        }
        if (hasSnapshot()) {
            map.put("inventory", snapshot);
        }
        return map;
    }

    /** @return the parsed entry, or null when the row is unreadable */
    public static CombatEvent fromMap(Map<?, ?> raw) {
        try {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return new CombatEvent(
                    number(map.get("id")),
                    EventType.parse(string(map.get("type")), EventType.DEATH),
                    number(map.get("time")),
                    UUID.fromString(string(map.get("subject"))),
                    string(map.get("subject-name")),
                    map.get("actor") == null ? null : UUID.fromString(string(map.get("actor"))),
                    string(map.get("actor-name")),
                    string(map.get("world")),
                    (int) number(map.get("x")),
                    (int) number(map.get("y")),
                    (int) number(map.get("z")),
                    string(map.get("cause")),
                    string(map.get("weapon")),
                    string(map.get("inventory")));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static Location location(CombatEvent event) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(event.worldName());
        return world == null ? null : new Location(world, event.x() + 0.5D, event.y(), event.z() + 0.5D);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
