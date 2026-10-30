package dev.kristian.nullmod.client.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Every text desktop interference can show, sorted by window kind and by the stages it belongs
 * to. Messages only ever talk about NULL, the world and the player inside it: nothing pretends to
 * come from the operating system, a security product or an administrator, and nothing refers to
 * the player's real computer, files or location.
 * <p>
 * Placeholders: {@code {PLAYER}} becomes the player's Minecraft name, {@code %CODE%} a random
 * three digit error code and {@code %HEX%} a random address. Plain Java (no Minecraft classes).
 */
public final class DesktopMessages {
    static final String PLAYER = "{PLAYER}";
    static final String CODE = "%CODE%";
    static final String HEX = "%HEX%";

    /**
     * One message. {@code head} is the small first line (code, file name), {@code body} the
     * message itself and {@code extra} an optional second message line.
     */
    public record Message(int minStage, int maxStage, String head, String body, String extra) {
        boolean fits(int stage) {
            return stage >= minStage && stage <= maxStage;
        }

        String key() {
            return head + '|' + body;
        }
    }

    /** A "KEY: VALUE" report line with the values it may show in Stage 4 and in Stage 5. */
    record ReportField(String key, String[] stage4, String[] stage5) {
    }

    /** Report values printed in the warning colour. */
    private static final List<String> ALARMING_VALUES = List.of("DESTROYED", "NOT FOUND", "MISSING", "DELETED", "0%",
            "LOST", "REPLACED", "NULL", "NONE", "NEVER", "GONE", "TOO MANY", "STILL RUNNING", "CORRUPTED");

    // ----------------------------------------------------------------------------------------
    // A - small corner notifications

    static final List<Message> NOTIFICATIONS = List.of(
            // Stage 1: barely anything; easy to dismiss as a hiccup
            m(1, 1, "INFORMATION", "NULL."),
            m(1, 2, "NOTICE", "A CHUNK DID NOT FINISH LOADING."),
            m(1, 2, "INFORMATION", "SESSION RESUMED."),
            m(1, 1, "NOTICE", "SOMETHING CHANGED."),
            m(1, 2, "INFORMATION", "WORLD TIME DRIFT: 0.004 S."),
            m(1, 1, "STATUS", "EVERYTHING IS FINE."),
            m(1, 2, "NOTICE", "1 ENTITY NOT ACCOUNTED FOR."),
            m(1, 2, "INFORMATION", "THE MOON WAS COUNTED."),
            m(1, 2, "NOTICE", "A BLOCK UPDATE ARRIVED LATE."),
            // Stage 2: occasional small errors
            m(2, 4, "ERROR 804", "INFORMATION : NULL."),
            m(2, 4, "ERROR 001", "WORLD CONNECTION LOST."),
            m(2, 3, "WARNING", "TEXTURE NOT FOUND."),
            m(2, 3, "ERROR 212", "BLOCK DATA DOES NOT MATCH."),
            m(2, 3, "NOTICE", "YOU WERE AWAY FOR 0 SECONDS."),
            m(2, 3, "ERROR 033", "A SOUND PLAYED TWICE."),
            m(2, 4, "WARNING", "SKY COLOUR OUT OF RANGE."),
            m(2, 3, "NOTICE", "A BLOCK IS NOT WHERE YOU LEFT IT."),
            m(2, 3, "ERROR 517", "CHUNK 0, 0 REPORTS NOTHING."),
            m(2, 3, "INFORMATION", "NULL IS LOADING."),
            m(2, 3, "NOTICE", "THE SUN SET 4 SECONDS LATE."),
            m(2, 4, "ERROR 120", "LIGHT DATA MISSING."),
            m(2, 3, "INFORMATION", "A SECOND SET OF FOOTSTEPS WAS HEARD."),
            m(2, 3, "NOTICE", "THE WORLD IS 0.1 KB HEAVIER THAN YESTERDAY."),
            m(2, 3, "WARNING", "RENDER DISTANCE: UNKNOWN."),
            m(2, 4, "ERROR " + CODE, "CHUNK FAILED TO LOAD."),
            m(2, 4, "ERROR " + CODE, "TEXTURE DATA DAMAGED."),
            // Stage 3: unusual
            m(3, 4, "WARNING", "UNKNOWN PROCESS DETECTED."),
            m(3, 5, "ERROR 404", "REALITY NOT FOUND."),
            m(3, 5, "WARNING", "SOMETHING IS WATCHING."),
            m(3, 4, "ERROR 290", "THE MOON IS NOT RESPONDING."),
            m(3, 4, "NOTICE", "FOOTSTEPS LOGGED: 2 SETS."),
            m(3, 4, "ERROR 610", "TERRAIN GENERATED TWICE."),
            m(3, 4, "WARNING", "LIGHT LEVEL: WRONG."),
            m(3, 4, "ERROR 088", "MEMORY OF THIS WORLD IS INCOMPLETE."),
            m(3, 4, "NOTICE", "SOMEONE ELSE IS LOADING THIS WORLD."),
            m(3, 4, "ERROR 342", "NAME NOT FOUND."),
            m(3, 5, "WARNING", "DO NOT DIG DOWN."),
            m(3, 4, "INFORMATION", "YOU HAVE BEEN HERE BEFORE."),
            m(3, 4, "ERROR 451", "THE GROUND IS BREATHING."),
            m(3, 5, "ERROR " + CODE, "ENTITY HAS NO SHADOW."),
            // Stage 4: strange warnings
            m(4, 5, "ERROR 666", "PLAYER DATA CORRUPTED."),
            m(4, 5, "CONNECTION ESTABLISHED", "UNKNOWN ENTITY."),
            m(4, 5, "WARNING", "IT KNOWS YOUR NAME."),
            m(4, 5, "ERROR 000", "NOTHING REMAINS."),
            m(4, 5, "NOTICE", "YOU LEFT SOMETHING BEHIND."),
            m(4, 5, "WARNING", "DO NOT LOOK BEHIND YOU."),
            m(4, 5, "ERROR 913", "THE SKY HAS NO EDGE."),
            m(4, 5, "INFORMATION", PLAYER + " IS NOT ALONE."),
            m(4, 5, "WARNING", "IT IS IN THE WALLS."),
            m(4, 5, "ERROR 707", "THE WORLD IS SHRINKING."),
            m(4, 5, "NOTICE", "SOMEONE JOINED. NO ONE LEFT."),
            // Stage 5
            m(5, 5, "ERROR ???", "NULL."),
            m(5, 5, "NULL", "STILL HERE."),
            m(5, 5, "WARNING", "THERE IS NO WAY BACK."),
            m(5, 5, "ERROR 0", "0."),
            m(5, 5, "INFORMATION", "THE WORLD HAS BEEN REMOVED."),
            m(5, 5, "NOTICE", PLAYER + " WAS FOUND."));

    // ----------------------------------------------------------------------------------------
    // B - small error windows

    static final List<Message> ERRORS = List.of(
            m(2, 5, "NULL.EXE", "CONNECTION LOST."),
            m(3, 5, "NULL.EXE", "NULL.EXE HAS STOPPED RESPONDING."),
            m(4, 5, "NULL.EXE", "DO NOT CLOSE THIS WINDOW."),
            m(4, 5, "WORLD.NULL", "THE WORLD DOES NOT EXIST."),
            m(3, 4, "NULL.EXE", "FAILED TO UNLOAD."),
            m(3, 4, "REGION.NULL", "REGION -1, -1 IS EMPTY.", "IT WAS NOT EMPTY BEFORE."),
            m(3, 5, "NULL.EXE", "EXCEPTION AT " + HEX + "."),
            m(3, 4, "MOON.NULL", "THE MOON IS DAMAGED.", "PIECES ARE MISSING."),
            m(3, 4, "NULL.EXE", "UNEXPECTED PLAYER."),
            m(3, 4, "TERRAIN.NULL", "GENERATION FAILED AT Y = -64.", "SOMETHING IS BELOW."),
            m(3, 4, "NULL.EXE", "STACK OVERFLOW IN SKY."),
            m(3, 5, "ENTITY.NULL", "ENTITY HAS NO NAME.", "ENTITY HAS NO FACE."),
            m(3, 4, "NULL.EXE", "TOO MANY PLAYERS.", "THERE IS ONLY ONE PLAYER."),
            m(3, 4, "CLOCK.NULL", "DAY 0 HAS LASTED TOO LONG."),
            m(4, 5, "NULL.EXE", "IT IS NOT A BUG."),
            m(4, 5, "OBSERVER.NULL", "OBSERVER COUNT: 2.", "EXPECTED: 0."),
            m(4, 5, "NULL.EXE", "NULL.EXE IS STILL RUNNING."),
            m(4, 5, "MEMORY.NULL", "READ FAILED AT", "LAST NIGHT."),
            m(4, 5, "NULL.EXE", "WAITING FOR PLAYER RESPONSE..."),
            m(4, 5, "SPAWN.NULL", "SPAWN POINT MOVED.", "YOU DID NOT MOVE IT."),
            m(4, 5, "NULL.EXE", "A FATAL ERROR HAS OCCURRED IN THE WORLD."),
            m(4, 5, "NULL.EXE", "ERROR " + CODE + " - SIGNAL TOO STRONG."),
            m(5, 5, "NULL.EXE", "THERE IS NOTHING LEFT TO SAVE."),
            m(5, 5, "NULL.EXE", "RETURN TO WORLD?", "THERE IS NO WORLD."),
            m(5, 5, "NULL.EXE", "PLAYER NOT FOUND.", "PLAYER FOUND."),
            m(5, 5, "NULL.EXE", "NULL.EXE HAS STOPPED RESPONDING.", "NULL HAS NOT."));

    // ----------------------------------------------------------------------------------------
    // C - distorted messages

    static final List<Message> DISTORTED = List.of(
            m(3, 5, "", "YOU ARE NOT ALONE."),
            m(3, 4, "", "LOOK AGAIN."),
            m(3, 4, "", "IT SEES YOU."),
            m(3, 4, "", "NOT HERE."),
            m(3, 5, "", "WHO ARE YOU?"),
            m(3, 5, "", "STAY."),
            m(3, 4, "", "DID YOU HEAR THAT?"),
            m(3, 4, "", "THAT WAS NOT YOU."),
            m(3, 5, "", "WAKE UP."),
            m(3, 4, "", "THE WORLD IS FORGETTING."),
            m(3, 4, "", "SOMEONE IS HERE."),
            m(3, 5, "", "HELLO?"),
            m(3, 4, "", "KEEP PLAYING."),
            m(4, 5, "", "TURN AROUND."),
            m(4, 5, "", "IT IS GETTING CLOSER."),
            m(4, 5, "", "NO ONE IS COMING."),
            m(4, 5, "", "THE MOON IS GONE."),
            m(4, 5, "", "WE REMEMBER YOU."),
            m(4, 5, "", "LET US IN."),
            m(4, 5, "", "YOU WERE NEVER HERE."),
            m(4, 5, "", "STOP DIGGING."),
            m(4, 5, "", "IT FOLLOWED YOU HOME."),
            m(4, 5, "", "DON'T SLEEP."),
            m(4, 5, "", PLAYER + "."),
            m(4, 5, "", "WE FOUND " + PLAYER + "."),
            m(4, 5, "", "THIS IS NOT A GAME."),
            m(4, 5, "", "CAN YOU SEE IT TOO?"),
            m(4, 5, "", "IT IS WEARING YOUR SKIN."),
            m(5, 5, "", "NOTHING IS REAL."),
            m(5, 5, "", "YOU CANNOT FIX THIS."));

    // ----------------------------------------------------------------------------------------
    // E - Stage 5 final interference

    static final List<Message> FINAL = List.of(
            m(5, 5, "", "WE CAN SEE YOU."),
            m(5, 5, "", "YOU SHOULD NOT BE HERE."),
            m(5, 5, "", "THERE IS NO WORLD."),
            m(5, 5, "", "NULL IS STILL RUNNING."),
            m(5, 5, "", "THIS WORLD IS GONE."),
            m(5, 5, "", "YOU CANNOT SAVE IT."),
            m(5, 5, "", "NOTHING IS LOADING."),
            m(5, 5, "", "WE ARE ALREADY HERE."),
            m(5, 5, "", "THE MOON IS NOT COMING BACK."),
            m(5, 5, "", "EVERYTHING IS NULL."),
            m(5, 5, "", PLAYER + " DOES NOT EXIST."),
            m(5, 5, "", "WE KNOW WHERE YOU BUILT YOUR HOUSE."),
            m(5, 5, "", "STOP LOOKING FOR THE EXIT."),
            m(5, 5, "", "IT IS TOO LATE."),
            m(5, 5, "", "YOU LET IT IN."),
            m(5, 5, "", "THE LAST CHUNK IS LOADING."),
            m(5, 5, "", "THERE WAS NEVER A WORLD."),
            m(5, 5, "", "WE ARE NOT FINISHED."),
            m(5, 5, "", "CLOSE YOUR EYES."),
            m(5, 5, "", "THE SIGNAL IS STRONGER NOW."),
            m(5, 5, "", "NULL HAS NO END."),
            m(5, 5, "", "WE ARE IN EVERY CHUNK."),
            m(5, 5, "", "YOU ARE THE LAST ONE."),
            m(5, 5, "", "STAY WITH US."),
            m(5, 5, "", "GOODBYE, " + PLAYER + "."));

    // ----------------------------------------------------------------------------------------
    // D - reports

    /** The report from the specification; the first report a player sees is always this one. */
    static final String CLASSIC_REPORT_TITLE = "NULL SYSTEM REPORT";
    static final String[][] CLASSIC_REPORT = {
            {"WORLD STATUS:", "DESTROYED"},
            {"PLAYER STATUS:", "OBSERVED"},
            {"CONNECTION STATUS:", "UNKNOWN"}};

    static final List<String> REPORT_TITLES = List.of(
            "NULL SYSTEM REPORT",
            "NULL STATUS REPORT",
            "WORLD DIAGNOSTIC",
            "OBSERVATION LOG",
            "NULL REPORT #" + CODE,
            "INTEGRITY CHECK",
            "SESSION REPORT",
            "NULL // LAST KNOWN STATE");

    static final List<ReportField> REPORT_FIELDS = List.of(
            field("WORLD STATUS:", a("FAILING", "UNSTABLE", "DEGRADED"), a("DESTROYED", "NOT FOUND", "NULL", "DELETED")),
            field("PLAYER STATUS:", a("OBSERVED", "FOUND", "FOLLOWED"), a("OBSERVED", "NOT ALONE", "MISSING", "REPLACED")),
            field("CONNECTION STATUS:", a("UNKNOWN", "UNSTABLE", "ONE-WAY"), a("UNKNOWN", "LOST", "ESTABLISHED")),
            field("PLAYER:", a(PLAYER), a(PLAYER, PLAYER + "?")),
            field("MOON:", a("CRACKED", "INCOMPLETE"), a("MISSING", "0%", "GONE")),
            field("ENTITIES NEARBY:", a("1", "2", "??"), a("3", "TOO MANY", "??")),
            field("OBSERVERS:", a("1", "UNKNOWN"), a("2", "ALL OF THEM")),
            field("CHUNKS LOADED:", a("0", "-1"), a("NULL", "NONE")),
            field("LAST SAVE:", a("UNKNOWN", "TOMORROW"), a("NEVER", "UNKNOWN")),
            field("TIME IN WORLD:", a("--:--", "TOO LONG"), a("FOREVER", "--:--")),
            field("REALITY:", a("PARTIAL", "4%"), a("NOT FOUND", "0%")),
            field("SKY:", a("WRONG COLOUR", "NO EDGE"), a("BEDROCK", "MISSING")),
            field("MEMORY:", a("INCOMPLETE", "OVERWRITTEN"), a("CORRUPTED", "NOT YOURS")),
            field("EXIT:", a("NOT FOUND", "CLOSED"), a("NONE", "NOT FOUND")),
            field("INTEGRITY:", a("31%", "12%"), a("3%", "0%")),
            field("NULL:", a("RUNNING", "AWAKE"), a("STILL RUNNING", "EVERYWHERE")),
            field("SIGNAL:", a("WEAK", "GROWING"), a("STRONG", "INSIDE")));

    private DesktopMessages() {
    }

    private static Message m(int min, int max, String head, String body) {
        return new Message(min, max, head, body, "");
    }

    private static Message m(int min, int max, String head, String body, String extra) {
        return new Message(min, max, head, body, extra);
    }

    private static ReportField field(String key, String[] stage4, String[] stage5) {
        return new ReportField(key, stage4, stage5);
    }

    private static String[] a(String... values) {
        return values;
    }

    /** Number of distinct fixed messages (not counting generated report lines). */
    public static int catalogueSize() {
        return NOTIFICATIONS.size() + ERRORS.size() + DISTORTED.size() + FINAL.size() + REPORT_TITLES.size() + 1;
    }

    // ----------------------------------------------------------------------------------------
    // selection

    /**
     * A message from {@code pool} that belongs to {@code stage}, preferring ones that were not
     * shown recently. Falls back to the closest stage when nothing fits (forced or test windows).
     */
    static Message pick(List<Message> pool, int stage, Random random, Deque<String> recent) {
        List<Message> fitting = new ArrayList<>();
        for (Message message : pool) {
            if (message.fits(stage)) {
                fitting.add(message);
            }
        }
        if (fitting.isEmpty()) {
            int best = Integer.MAX_VALUE;
            for (Message message : pool) {
                int distance = stage < message.minStage() ? message.minStage() - stage : stage - message.maxStage();
                if (distance < best) {
                    best = distance;
                    fitting.clear();
                }
                if (distance == best) {
                    fitting.add(message);
                }
            }
        }
        List<Message> fresh = new ArrayList<>(fitting.size());
        for (Message message : fitting) {
            if (!recent.contains(message.key())) {
                fresh.add(message);
            }
        }
        List<Message> from = fresh.isEmpty() ? fitting : fresh;
        Message chosen = from.get(random.nextInt(from.size()));
        remember(recent, chosen.key());
        return chosen;
    }

    static void remember(Deque<String> recent, String key) {
        recent.remove(key);
        recent.addLast(key);
        while (recent.size() > 24) {
            recent.removeFirst();
        }
    }

    /** Report lines ("KEY:" / value) for a generated report. */
    static List<String[]> reportLines(int stage, Random random, int count) {
        List<ReportField> fields = new ArrayList<>(REPORT_FIELDS);
        List<String[]> lines = new ArrayList<>(count);
        // the three status lines lead most reports, the way the specification shows them
        int leading = random.nextFloat() < 0.75F ? 3 : 1;
        for (int i = 0; i < leading && i < count; i++) {
            lines.add(value(fields.get(i), stage, random));
        }
        List<ReportField> rest = new ArrayList<>(fields.subList(leading, fields.size()));
        while (lines.size() < count && !rest.isEmpty()) {
            lines.add(value(rest.remove(random.nextInt(rest.size())), stage, random));
        }
        return lines;
    }

    private static String[] value(ReportField field, int stage, Random random) {
        String[] values = stage >= 5 ? field.stage5() : field.stage4();
        return new String[] {field.key(), values[random.nextInt(values.length)]};
    }

    static boolean alarming(String value) {
        return ALARMING_VALUES.contains(value);
    }

    /** Replaces the placeholders; the player name is upper-cased like every other word. */
    static String fill(String text, String playerName, Random random) {
        String result = text;
        if (result.contains(PLAYER)) {
            String name = playerName == null || playerName.isBlank() ? "PLAYER" : playerName.toUpperCase(Locale.ROOT);
            result = result.replace(PLAYER, name);
        }
        while (result.contains(CODE)) {
            result = result.replaceFirst("%CODE%", errorCode(random));
        }
        while (result.contains(HEX)) {
            result = result.replaceFirst("%HEX%", hexAddress(random));
        }
        return result;
    }

    /** A three digit error code that is not one of the codes the fixed messages use. */
    static String errorCode(Random random) {
        while (true) {
            int code = 100 + random.nextInt(900);
            if (code != 404 && code != 666 && code != 804) {
                return Integer.toString(code);
            }
        }
    }

    static String hexAddress(Random random) {
        return random.nextFloat() < 0.3F ? "0x00000000" : String.format(Locale.ROOT, "0x%08X", random.nextInt() & 0x7FFFFFFF);
    }

    /** A fresh deque for {@link #pick}. */
    static Deque<String> newRecentList() {
        return new ArrayDeque<>();
    }
}
