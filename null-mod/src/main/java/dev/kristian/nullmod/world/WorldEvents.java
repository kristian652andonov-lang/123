package dev.kristian.nullmod.world;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventAction;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.world.structure.CorruptedStructureGenerator;
import java.util.function.Predicate;

/**
 * The random events of world corruption and corrupted structures. None of them has a client
 * effect: things simply are there when the player looks again. Every action returns false when
 * it found no valid place, so the scheduler tries another event; forced events (from
 * {@code /null event}) search harder and accept closer, visible spots.
 */
public final class WorldEvents {
    private WorldEvents() {
    }

    public static void register() {
        // ---- terrain (replaces natural blocks) ------------------------------------------------
        NullEventDefinition.builder("corruption_patch")
                .stages(CorruptionStage.START, CorruptionStage.NULL)
                .weight(NullEventDefinition.COMMON)
                // Stage 1 corruption is extremely rare; it becomes ordinary later.
                .stageWeights(15, 60, 100, 100, 70)
                .cooldown(Ticks.minutes(5))
                .globalCooldown(Ticks.minutes(1))
                .duration(20)
                .tags(EventTag.WORLD)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(req -> ChunkCorruptionManager.INSTANCE.corruptNear(req)))
                .register();
        NullEventDefinition.builder("infection_spread")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(4))
                .globalCooldown(Ticks.seconds(90))
                .duration(20)
                .tags(EventTag.WORLD)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(req -> ChunkCorruptionManager.INSTANCE.spreadNear(req)))
                .register();
        NullEventDefinition.builder("wrong_biome_patch")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(12))
                .globalCooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.WORLD)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(BiomeTransitionPatch::generate))
                .register();

        // ---- Stage 2 fragments (into air only) -------------------------------------------------
        NullEventDefinition.builder("old_terrain_fragment")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(12))
                .globalCooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(OldTerrainFragment::generate))
                .register();
        NullEventDefinition.builder("floating_fragment")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(10))
                .globalCooldown(Ticks.minutes(4))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(FloatingFragment::generate))
                .register();
        NullEventDefinition.builder("duplicated_terrain")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.ABOVE_MIDDLE)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(15))
                .globalCooldown(Ticks.minutes(6))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(DuplicatedTerrain::generate))
                .register();
        NullEventDefinition.builder("distant_structure")
                .stages(CorruptionStage.UNDER_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(12))
                .globalCooldown(Ticks.minutes(5))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::distantStructure))
                .register();

        // ---- Stage 3-5 structures --------------------------------------------------------------
        NullEventDefinition.builder("strange_sign")
                .stages(CorruptionStage.MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(10))
                .globalCooldown(Ticks.minutes(3))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::strangeSign))
                .register();
        NullEventDefinition.builder("bedrock_sky")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(10))
                .globalCooldown(Ticks.minutes(4))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OVERWORLD)
                .action(run(CorruptedStructureGenerator::skyBedrock))
                .register();
        NullEventDefinition.builder("floating_house")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.UNCOMMON)
                .cooldown(Ticks.minutes(15))
                .globalCooldown(Ticks.minutes(6))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::floatingHouse))
                .register();
        NullEventDefinition.builder("abandoned_village")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(30))
                .globalCooldown(Ticks.minutes(15))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::abandonedVillage))
                .register();
        NullEventDefinition.builder("wrong_building")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(20))
                .globalCooldown(Ticks.minutes(8))
                .duration(20)
                .tags(EventTag.STRUCTURE)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::wrongBuilding))
                .register();
        // Carved out of natural stone, so they need natural replacement as well as structures.
        NullEventDefinition.builder("impossible_room")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.RARE)
                .cooldown(Ticks.minutes(20))
                .globalCooldown(Ticks.minutes(8))
                .duration(20)
                .tags(EventTag.STRUCTURE, EventTag.WORLD)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::impossibleRoom))
                .register();
        NullEventDefinition.builder("endless_corridor")
                .stages(CorruptionStage.ABOVE_MIDDLE, CorruptionStage.NULL)
                .weight(NullEventDefinition.VERY_RARE)
                .cooldown(Ticks.minutes(40))
                .globalCooldown(Ticks.minutes(20))
                .duration(20)
                .tags(EventTag.STRUCTURE, EventTag.WORLD)
                .place(EventPlace.OUTSIDE_NULL)
                .action(run(CorruptedStructureGenerator::endlessCorridor))
                .register();
    }

    private static EventAction run(Predicate<GenerationRequest> generator) {
        return (EventContext ctx) -> generator.test(GenerationRequest.of(ctx));
    }
}
