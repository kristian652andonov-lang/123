package dev.kristian.nullmod.client.desktop;

import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.config.NullClientSettings;
import dev.kristian.nullmod.core.CorruptionStage;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * Decides what a desktop window looks like and says: which kind fits the stage, which message,
 * how long it stays, where it goes and how much it flickers and glitches. Flicker always follows
 * the player's flashing setting (none at all when flashing is off, with slow fades instead) and
 * the heaviest glitching is reserved for players who allow intense effects.
 * <p>
 * Render thread only. The result is plain data for {@link DesktopWindowManager}.
 */
final class DesktopNotificationManager {
    static final String FOOTER_KEY = "null.desktop.footer";
    static final String FOOTER_FALLBACK = "NULL · Minecraft mod · click to close";

    private static final Deque<String> RECENT = DesktopMessages.newRecentList();
    private static final DesktopWindowType[] TYPES = DesktopWindowType.values();
    /** Normal weights per stage (index = stage - 1): notification, error, distorted, report, final. */
    private static final int[][] NORMAL_WEIGHTS = {
            {100, 0, 0, 0, 0},
            {100, 0, 0, 0, 0},
            {60, 25, 15, 0, 0},
            {20, 25, 25, 30, 0},
            {10, 10, 15, 10, 55}};
    private static boolean classicReportShown;

    private DesktopNotificationManager() {
    }

    /** The player's switches allow this kind of window, and its chance is not zero. */
    static boolean allowed(DesktopWindowType type) {
        boolean switchedOn = type.errorWindow()
                ? NullClientSettings.get(NullClientSettings.DESKTOP_ERROR_WINDOWS)
                : NullClientSettings.get(NullClientSettings.DESKTOP_NOTIFICATIONS);
        return switchedOn && chance(type) > 0.0;
    }

    /** The player's chance multiplier for this kind of window (1 = normal, 0 = never). */
    static double chance(DesktopWindowType type) {
        return NullClientSettings.get(switch (type) {
            case NOTIFICATION -> NullClientSettings.DESKTOP_CHANCE_NOTIFICATION;
            case ERROR -> NullClientSettings.DESKTOP_CHANCE_ERROR;
            case DISTORTED -> NullClientSettings.DESKTOP_CHANCE_DISTORTED;
            case REPORT -> NullClientSettings.DESKTOP_CHANCE_REPORT;
            case FINAL -> NullClientSettings.DESKTOP_CHANCE_FINAL;
        });
    }

    /**
     * How the player's switches and chance settings change how often windows come in this stage:
     * the stage's weights scaled by each kind's chance, relative to all of them at 100%. 1 = the
     * normal pace, 0 = every kind the stage uses is off. So each chance scales how often its own
     * kind appears, and lowering one never makes the others more frequent.
     */
    static double paceFactor(CorruptionStage stage) {
        int[] weights = NORMAL_WEIGHTS[stage.tableIndex()];
        double total = 0.0;
        double allowedTotal = 0.0;
        for (int i = 0; i < weights.length; i++) {
            total += weights[i];
            if (weights[i] > 0 && allowed(TYPES[i])) {
                allowedTotal += weights[i] * chance(TYPES[i]);
            }
        }
        return total <= 0.0 ? 0.0 : allowedTotal / total;
    }

    /**
     * Picks the kind of window for the stage, or {@code null} when every fitting kind is switched
     * off. Order of the weights: notification, error, distorted, report, final; each is scaled by
     * the player's chance setting for that kind.
     */
    static DesktopWindowType chooseType(CorruptionStage stage, Random random, boolean stageChange) {
        int[] normal = NORMAL_WEIGHTS[stage.tableIndex()];
        if (stageChange) {
            int[] special = switch (stage) {
                case START, UNDER_MIDDLE -> normal;
                case MIDDLE -> new int[] {40, 60, 0, 0, 0};
                case ABOVE_MIDDLE -> new int[] {0, 20, 20, 60, 0};
                case NULL, HARVEST -> new int[] {0, 0, 10, 0, 90};
            };
            DesktopWindowType type = weighted(special, random);
            if (type != null) {
                return type;
            }
        }
        // with error windows off, Stage 5 still has its notifications and distorted messages
        return weighted(normal, random);
    }

    private static DesktopWindowType weighted(int[] weights, Random random) {
        double total = 0.0;
        double[] allowedWeights = new double[weights.length];
        int last = -1;
        for (int i = 0; i < weights.length; i++) {
            allowedWeights[i] = weights[i] > 0 && allowed(TYPES[i]) ? weights[i] * chance(TYPES[i]) : 0.0;
            total += allowedWeights[i];
            if (allowedWeights[i] > 0.0) {
                last = i;
            }
        }
        if (total <= 0.0 || last < 0) {
            return null;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < allowedWeights.length; i++) {
            roll -= allowedWeights[i];
            if (roll < 0.0 && allowedWeights[i] > 0.0) {
                return TYPES[i];
            }
        }
        // rounding left a sliver over: the last kind with any weight
        return TYPES[last];
    }

    /** The kind the test button shows on its {@code index}-th press: every allowed kind in turn. */
    static DesktopWindowType testType(int index) {
        List<DesktopWindowType> allowed = new ArrayList<>(TYPES.length);
        for (DesktopWindowType type : TYPES) {
            if (allowed(type)) {
                allowed.add(type);
            }
        }
        if (allowed.isEmpty()) {
            return DesktopWindowType.NOTIFICATION;
        }
        return allowed.get(Math.floorMod(index, allowed.size()));
    }

    // ------------------------------------------------------------------------------------------
    // building

    /**
     * A window of {@code type} for the stage. Kinds that normally start later in the game (a
     * report forced in Stage 2) use the messages of the nearest stage that has them.
     */
    static DesktopWindowSpec build(DesktopWindowType type, int stage, Random random) {
        Look look = Look.current(stage);
        String player = playerName();
        return switch (type) {
            case NOTIFICATION -> notification(DesktopMessages.pick(DesktopMessages.NOTIFICATIONS, stage, random, RECENT),
                    look, player, random, false);
            case ERROR -> error(DesktopMessages.pick(DesktopMessages.ERRORS, stage, random, RECENT), look, player, random, false);
            case DISTORTED -> distorted(DesktopMessages.pick(DesktopMessages.DISTORTED, stage, random, RECENT), look, player, random, false);
            case REPORT -> report(stage, look, player, random, false);
            case FINAL -> finalWindow(DesktopMessages.pick(DesktopMessages.FINAL, Math.max(5, stage), random, RECENT),
                    look, player, random);
        };
    }

    /** The test button's window of {@code type}: one of the specification's own examples. */
    static DesktopWindowSpec buildTest(DesktopWindowType type, Random random) {
        Look look = Look.current(4);
        String player = playerName();
        return switch (type) {
            case NOTIFICATION -> notification(new DesktopMessages.Message(1, 5, "ERROR 804", "INFORMATION : NULL.", ""),
                    look, player, random, true);
            case ERROR -> error(new DesktopMessages.Message(1, 5, "NULL.EXE", "CONNECTION LOST.", ""), look, player, random, true);
            case DISTORTED -> distorted(new DesktopMessages.Message(1, 5, "", "YOU ARE NOT ALONE.", ""), look, player, random, true);
            case REPORT -> classicReport(look, random, true);
            case FINAL -> {
                List<DesktopWindowSpec.Line> lines = List.of(
                        DesktopWindowSpec.Line.of("NULL IS STILL RUNNING."),
                        DesktopWindowSpec.Line.of("WE CAN SEE YOU.", DesktopWindowSpec.Tone.WARNING));
                yield new DesktopWindowSpec(DesktopWindowType.FINAL, DesktopWindowSpec.Layout.BIG_TEXT, "", lines, footer(),
                        1.2F, DesktopWindowSpec.Placement.CENTRE, look.lifetime(5200), false, look.flicker(0.8F),
                        look.distortion(0.9F), look.slowFades, true, true, random.nextLong());
            }
        };
    }

    private static DesktopWindowSpec notification(DesktopMessages.Message message, Look look, String player, Random random,
            boolean test) {
        String head = DesktopMessages.fill(message.head(), player, random);
        List<DesktopWindowSpec.Line> lines = new ArrayList<>(2);
        lines.add(DesktopWindowSpec.Line.of(DesktopMessages.fill(message.body(), player, random)));
        if (!message.extra().isEmpty()) {
            lines.add(DesktopWindowSpec.Line.of(DesktopMessages.fill(message.extra(), player, random), DesktopWindowSpec.Tone.DIM));
        }
        boolean warning = head.startsWith("WARNING") && look.stage >= 3;
        int lifetime = look.stage <= 1 ? 5200 + random.nextInt(1800) : 6000 + random.nextInt(3000);
        float scale = 0.95F + random.nextFloat() * 0.15F;
        boolean typewriter = !test && random.nextFloat() < 0.2F;
        return new DesktopWindowSpec(DesktopWindowType.NOTIFICATION, DesktopWindowSpec.Layout.CORNER, head, lines, footer(),
                scale, DesktopWindowSpec.Placement.CORNER, look.lifetime(lifetime), typewriter,
                look.flicker(0.08F + 0.25F * look.depth), look.distortion(look.stage <= 1 ? 0.04F : 0.1F + 0.3F * look.depth),
                look.slowFades, warning, test, random.nextLong());
    }

    private static DesktopWindowSpec error(DesktopMessages.Message message, Look look, String player, Random random, boolean test) {
        String title = DesktopMessages.fill(message.head(), player, random);
        List<DesktopWindowSpec.Line> lines = new ArrayList<>(2);
        lines.add(DesktopWindowSpec.Line.of(DesktopMessages.fill(message.body(), player, random)));
        if (!message.extra().isEmpty()) {
            lines.add(DesktopWindowSpec.Line.of(DesktopMessages.fill(message.extra(), player, random),
                    look.stage >= 4 && random.nextFloat() < 0.5F ? DesktopWindowSpec.Tone.WARNING : DesktopWindowSpec.Tone.NORMAL));
        }
        float scale = 1.0F + random.nextFloat() * 0.15F;
        DesktopWindowSpec.Placement placement = random.nextFloat() < 0.25F ? DesktopWindowSpec.Placement.CORNER
                : DesktopWindowSpec.Placement.RANDOM;
        boolean typewriter = !test && random.nextFloat() < 0.5F;
        boolean red = look.stage >= 4 && random.nextFloat() < 0.4F;
        return new DesktopWindowSpec(DesktopWindowType.ERROR, DesktopWindowSpec.Layout.ERROR_BOX, title, lines, footer(), scale,
                placement, look.lifetime(8000 + random.nextInt(4000)), typewriter, look.flicker(0.15F + 0.3F * look.depth),
                look.distortion(0.2F + 0.4F * look.depth), look.slowFades, red, test, random.nextLong());
    }

    private static DesktopWindowSpec distorted(DesktopMessages.Message message, Look look, String player, Random random,
            boolean test) {
        List<DesktopWindowSpec.Line> lines = List.of(DesktopWindowSpec.Line.of(DesktopMessages.fill(message.body(), player, random)));
        float scale = 0.8F + random.nextFloat() * 0.45F;
        DesktopWindowSpec.Placement placement = random.nextBoolean() ? DesktopWindowSpec.Placement.CENTRE
                : DesktopWindowSpec.Placement.RANDOM;
        int lifetime = 2800 + random.nextInt(1700);
        boolean red = random.nextFloat() < 0.25F + 0.25F * look.depth;
        return new DesktopWindowSpec(DesktopWindowType.DISTORTED, DesktopWindowSpec.Layout.BIG_TEXT, "", lines, footer(), scale,
                placement, look.lifetime(lifetime), false, look.flicker(0.9F), look.distortion(0.85F), look.slowFades, red, test,
                random.nextLong());
    }

    private static DesktopWindowSpec report(int stage, Look look, String player, Random random, boolean test) {
        if (!classicReportShown) {
            classicReportShown = true;
            return classicReport(look, random, test);
        }
        String title = DesktopMessages.fill(DesktopMessages.REPORT_TITLES.get(random.nextInt(DesktopMessages.REPORT_TITLES.size())),
                player, random);
        List<DesktopWindowSpec.Line> lines = new ArrayList<>();
        for (String[] pair : DesktopMessages.reportLines(Math.max(4, stage), random, 3 + random.nextInt(4))) {
            String value = DesktopMessages.fill(pair[1], player, random);
            lines.add(new DesktopWindowSpec.Line(pair[0], value,
                    DesktopMessages.alarming(pair[1]) ? DesktopWindowSpec.Tone.WARNING : DesktopWindowSpec.Tone.NORMAL));
        }
        return reportSpec(title, lines, look, random, test, stage >= 5 && random.nextFloat() < 0.5F);
    }

    private static DesktopWindowSpec classicReport(Look look, Random random, boolean test) {
        List<DesktopWindowSpec.Line> lines = new ArrayList<>(DesktopMessages.CLASSIC_REPORT.length);
        for (String[] pair : DesktopMessages.CLASSIC_REPORT) {
            lines.add(new DesktopWindowSpec.Line(pair[0], pair[1],
                    DesktopMessages.alarming(pair[1]) ? DesktopWindowSpec.Tone.WARNING : DesktopWindowSpec.Tone.NORMAL));
        }
        return reportSpec(DesktopMessages.CLASSIC_REPORT_TITLE, lines, look, random, test, false);
    }

    private static DesktopWindowSpec reportSpec(String title, List<DesktopWindowSpec.Line> lines, Look look, Random random,
            boolean test, boolean red) {
        int characters = 0;
        for (DesktopWindowSpec.Line line : lines) {
            characters += line.label().length() + line.text().length();
        }
        // reading time after the typewriter has finished
        int lifetime = 300 + characters * 1000 / 46 + 9000 + random.nextInt(4000);
        DesktopWindowSpec.Placement placement = random.nextFloat() < 0.4F ? DesktopWindowSpec.Placement.CORNER
                : DesktopWindowSpec.Placement.RANDOM;
        return new DesktopWindowSpec(DesktopWindowType.REPORT, DesktopWindowSpec.Layout.REPORT, title, lines, footer(), 1.0F,
                placement, look.lifetime(lifetime), true, look.flicker(0.2F), look.distortion(0.25F + 0.2F * look.depth),
                look.slowFades, red, test, random.nextLong());
    }

    /** Stage 5: the final messages in every style, more often red, more often glitching. */
    private static DesktopWindowSpec finalWindow(DesktopMessages.Message message, Look look, String player, Random random) {
        String text = DesktopMessages.fill(message.body(), player, random);
        float style = random.nextFloat();
        if (style < 0.45F) {
            List<DesktopWindowSpec.Line> lines = new ArrayList<>(2);
            lines.add(DesktopWindowSpec.Line.of(text));
            if (random.nextFloat() < 0.4F) {
                DesktopMessages.Message second = DesktopMessages.pick(DesktopMessages.FINAL, 5, random, RECENT);
                lines.add(DesktopWindowSpec.Line.of(DesktopMessages.fill(second.body(), player, random), DesktopWindowSpec.Tone.WARNING));
            }
            return new DesktopWindowSpec(DesktopWindowType.FINAL, DesktopWindowSpec.Layout.BIG_TEXT, "", lines, footer(),
                    1.05F + random.nextFloat() * 0.45F, random.nextBoolean() ? DesktopWindowSpec.Placement.CENTRE
                            : DesktopWindowSpec.Placement.RANDOM,
                    look.lifetime(3800 + random.nextInt(3200)), false, look.flicker(1.0F),
                    look.distortion(0.95F), look.slowFades, random.nextFloat() < 0.6F, false, random.nextLong());
        }
        if (style < 0.65F) {
            List<DesktopWindowSpec.Line> lines = List.of(DesktopWindowSpec.Line.of(text, DesktopWindowSpec.Tone.WARNING));
            return new DesktopWindowSpec(DesktopWindowType.FINAL, DesktopWindowSpec.Layout.ERROR_BOX, "NULL.EXE", lines, footer(),
                    1.1F, DesktopWindowSpec.Placement.RANDOM, look.lifetime(7000 + random.nextInt(4000)), random.nextBoolean(),
                    look.flicker(0.5F), look.distortion(0.7F), look.slowFades, true, false, random.nextLong());
        }
        if (style < 0.85F) {
            String head = random.nextBoolean() ? "ERROR " + DesktopMessages.errorCode(random) : "NULL";
            List<DesktopWindowSpec.Line> lines = List.of(DesktopWindowSpec.Line.of(text));
            return new DesktopWindowSpec(DesktopWindowType.FINAL, DesktopWindowSpec.Layout.CORNER, head, lines, footer(),
                    1.0F + random.nextFloat() * 0.2F, DesktopWindowSpec.Placement.CORNER, look.lifetime(6000 + random.nextInt(3000)),
                    false, look.flicker(0.4F), look.distortion(0.6F), look.slowFades, random.nextBoolean(), false, random.nextLong());
        }
        List<DesktopWindowSpec.Line> lines = new ArrayList<>();
        for (String[] pair : DesktopMessages.reportLines(5, random, 3 + random.nextInt(2))) {
            lines.add(new DesktopWindowSpec.Line(pair[0], DesktopMessages.fill(pair[1], player, random),
                    DesktopMessages.alarming(pair[1]) ? DesktopWindowSpec.Tone.WARNING : DesktopWindowSpec.Tone.NORMAL));
        }
        lines.add(DesktopWindowSpec.Line.of(text, DesktopWindowSpec.Tone.WARNING));
        return reportSpec("NULL // FINAL REPORT", lines, look, random, false, true);
    }

    // ------------------------------------------------------------------------------------------

    private static String footer() {
        try {
            if (I18n.exists(FOOTER_KEY)) {
                String text = I18n.get(FOOTER_KEY);
                if (!text.isBlank()) {
                    return text;
                }
            }
        } catch (RuntimeException ignored) {
            // language not ready: fall back to English
        }
        return FOOTER_FALLBACK;
    }

    private static String playerName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (RuntimeException e) {
            return "PLAYER";
        }
    }

    /** The player's accessibility limits and the stage's strength, read once per window. */
    private static final class Look {
        final int stage;
        final float depth;
        final float flashing;
        final boolean intense;
        final boolean slowFades;

        private Look(int stage, float flashing, boolean intense) {
            this.stage = Math.max(1, Math.min(5, stage));
            this.depth = (this.stage - 1) / 4.0F;
            this.flashing = Math.max(0.0F, Math.min(1.0F, flashing));
            this.intense = intense;
            this.slowFades = this.flashing < 0.05F;
        }

        static Look current(int stage) {
            return new Look(stage, ClientLimits.flashing(), ClientLimits.intenseAllowed());
        }

        /** Flicker strength: scaled by the flashing setting, halved without intense effects. */
        float flicker(float base) {
            float value = base * flashing * (intense ? 1.0F : 0.5F);
            return value < 0.02F ? 0.0F : value;
        }

        /** Character glitches are not flashes, but the heaviest ones need intense effects. */
        float distortion(float base) {
            return intense ? base : Math.min(base, 0.45F);
        }

        /** Slow fades need a little longer to be read. */
        int lifetime(int base) {
            return slowFades ? base + 1200 : base;
        }
    }
}
