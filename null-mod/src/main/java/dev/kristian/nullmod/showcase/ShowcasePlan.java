package dev.kristian.nullmod.showcase;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The order of {@code /null showcase}: for each stage, the stage itself, then every random event
 * whose first stage it is, the ones that show the stage best first: what changes in the world,
 * block glitches, the mobs (Stages 2 and 3), what lives there, what happens on the screen, what is
 * heard, what is said, and last whatever happens in the sky (which the tour shows at night). Stage
 * 6 is a walk through the field's day and night; alone ({@code /null showcase stage 6}) it ends
 * with IT's hunt, watched from inside a house.
 * <p>
 * Pure logic over the event registry, so the plan can be tested without a world. Events other
 * systems register later appear in it by themselves.
 */
public final class ShowcasePlan {
    public static final String MOBS_CORRUPTED = "corrupted";
    public static final String MOBS_PARASITE = "parasite";

    public static final String FIELD_DAY = "day";
    public static final String FIELD_CRACKING = "cracking";
    public static final String FIELD_SHATTER = "shatter";
    public static final String FIELD_NIGHT = "night";
    public static final String FIELD_HUNT = "hunt";
    public static final String FIELD_DAWN = "dawn";

    /** Group of the corrupted/parasite mob step among the event groups (see {@link #group}). */
    private static final int MOBS_GROUP = 3;
    private static final int SKY_GROUP = 9;

    private ShowcasePlan() {
    }

    /** The whole tour: Stages 1 to 6 in order (no hunt in the field). */
    public static List<ShowcaseStep> fullTour(Collection<NullEventDefinition> events) {
        List<ShowcaseStep> steps = new ArrayList<>();
        for (CorruptionStage stage : CorruptionStage.values()) {
            steps.addAll(forStage(stage, events, false));
        }
        return steps;
    }

    /**
     * The steps of one stage.
     *
     * @param alone true for {@code /null showcase stage <n>}: Stage 6 then also shows IT's hunt
     */
    public static List<ShowcaseStep> forStage(CorruptionStage stage, Collection<NullEventDefinition> events, boolean alone) {
        List<ShowcaseStep> steps = new ArrayList<>();
        steps.add(new ShowcaseStep(ShowcaseStep.Kind.STAGE, stage, "", 0, 0));
        List<NullEventDefinition> first = new ArrayList<>();
        for (NullEventDefinition event : events) {
            if (event.minStage() == stage) {
                first.add(event);
            }
        }
        first.sort(Comparator.comparingInt(ShowcasePlan::group)
                .thenComparing(Comparator.comparingInt(NullEventDefinition::weight).reversed())
                .thenComparing(NullEventDefinition::id));
        String mobs = stage == CorruptionStage.UNDER_MIDDLE ? MOBS_CORRUPTED : stage == CorruptionStage.MIDDLE ? MOBS_PARASITE : null;
        if (stage == CorruptionStage.HARVEST) {
            steps.add(field(stage, FIELD_DAY));
            // Anything that begins in the field shows while the sun still stands (events rest once it breaks).
            for (NullEventDefinition event : first) {
                steps.add(new ShowcaseStep(ShowcaseStep.Kind.EVENT, stage, event.id(), 0, 0));
            }
            steps.add(field(stage, FIELD_CRACKING));
            steps.add(field(stage, FIELD_SHATTER));
            steps.add(field(stage, FIELD_NIGHT));
            if (alone) {
                steps.add(new ShowcaseStep(ShowcaseStep.Kind.HOUSE, stage, "", 0, 0));
                steps.add(field(stage, FIELD_HUNT));
                steps.add(field(stage, FIELD_DAWN));
            }
        } else {
            boolean mobsAdded = mobs == null;
            for (NullEventDefinition event : first) {
                if (!mobsAdded && group(event) > MOBS_GROUP - 1) {
                    steps.add(new ShowcaseStep(ShowcaseStep.Kind.MOBS, stage, mobs, 0, 0));
                    mobsAdded = true;
                }
                steps.add(new ShowcaseStep(ShowcaseStep.Kind.EVENT, stage, event.id(), 0, 0));
            }
            if (!mobsAdded) {
                steps.add(new ShowcaseStep(ShowcaseStep.Kind.MOBS, stage, mobs, 0, 0));
            }
        }
        List<ShowcaseStep> numbered = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            numbered.add(steps.get(i).numbered(i + 1, steps.size()));
        }
        return numbered;
    }

    private static ShowcaseStep field(CorruptionStage stage, String phase) {
        return new ShowcaseStep(ShowcaseStep.Kind.FIELD, stage, phase, 0, 0);
    }

    /** True for events that happen in the sky: the tour shows them at night and tells the player to look up. */
    public static boolean needsNight(NullEventDefinition event) {
        return event.has(EventTag.MOON) || event.has(EventTag.SKY);
    }

    /** True for events whose client effect swaps blocks in front of the player. */
    public static boolean blockGlitch(NullEventDefinition event) {
        String effect = event.clientEffect();
        return effect != null && effect.startsWith("block.");
    }

    /**
     * Where an event goes in its stage, lowest first: 0 the NULL dimension's own events, 1 changes
     * to the world, 2 block glitches, (3 the mobs), 3 things that live there, 4 screen effects,
     * 5 sounds, 6 the chat, 7 anything else, 9 the sky.
     */
    static int group(NullEventDefinition event) {
        if (needsNight(event)) {
            return SKY_GROUP;
        }
        if (event.has(EventTag.DIMENSION)) {
            return 0;
        }
        if (event.has(EventTag.WORLD) || event.has(EventTag.STRUCTURE)) {
            return 1;
        }
        if (blockGlitch(event)) {
            return 2;
        }
        if (event.has(EventTag.NULLROOT) || event.has(EventTag.VISITOR) || event.has(EventTag.FAKE_PLAYER)) {
            return MOBS_GROUP;
        }
        if (event.has(EventTag.VISUAL)) {
            return 4;
        }
        if (event.has(EventTag.AUDIO)) {
            return 5;
        }
        if (event.has(EventTag.CHAT)) {
            return 6;
        }
        return 7;
    }
}
