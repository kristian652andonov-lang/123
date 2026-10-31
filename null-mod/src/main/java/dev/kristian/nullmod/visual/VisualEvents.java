package dev.kristian.nullmod.visual;

import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.EventContext;
import dev.kristian.nullmod.event.EventPlace;
import dev.kristian.nullmod.event.EventTag;
import dev.kristian.nullmod.event.NullEventDefinition;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

import static dev.kristian.nullmod.core.CorruptionStage.ABOVE_MIDDLE;
import static dev.kristian.nullmod.core.CorruptionStage.HARVEST;
import static dev.kristian.nullmod.core.CorruptionStage.MIDDLE;
import static dev.kristian.nullmod.core.CorruptionStage.NULL;
import static dev.kristian.nullmod.core.CorruptionStage.START;
import static dev.kristian.nullmod.core.CorruptionStage.UNDER_MIDDLE;
import static dev.kristian.nullmod.event.NullEventDefinition.COMMON;
import static dev.kristian.nullmod.event.NullEventDefinition.RARE;
import static dev.kristian.nullmod.event.NullEventDefinition.UNCOMMON;
import static dev.kristian.nullmod.event.NullEventDefinition.VERY_RARE;

/**
 * The visual random events. They change nothing on the server: each one sends a client effect
 * to the one player it happens to (block glitches are swaps in that client's copy of the world
 * only). Server-side actions are only checks: the sky effects need a sky in view, and the
 * effects that take the screen away entirely (freeze, full blackout, fake disconnect) wait for
 * a moment when it cannot get the player killed, unless an administrator forces them.
 * <p>
 * In Stage 6 (HARVEST, the wheat field) only three quiet ones remain, by day: the picture
 * warping, something moving at the edge of sight, and the colour draining away.
 */
public final class VisualEvents {
    /** Translation keys sent as glitch text; the client shows them translated. */
    public static final String TEXT_WORLD_NOT_FOUND = "null.visual.text.world_not_found";
    public static final String TEXT_CONNECTION_LOST = "null.visual.text.connection_lost";

    private VisualEvents() {
    }

    public static void register() {
        registerBlockGlitches();
        registerScreenEffects();
        registerFinalStage();
    }

    // ------------------------------------------------------------------ block glitches

    private static void registerBlockGlitches() {
        blockEvent("block_black_flash", START, MIDDLE, COMMON, Ticks.seconds(45), Ticks.seconds(3), "block.black")
                .stageWeights(130, 110, 80, 100, 100)
                .register();
        blockEvent("block_missing_texture", START, ABOVE_MIDDLE, COMMON, Ticks.seconds(60), Ticks.seconds(5), "block.missing")
                .stageWeights(110, 110, 90, 70, 100)
                .register();
        blockEvent("block_error_texture", START, NULL, UNCOMMON, Ticks.seconds(90), Ticks.seconds(6), "block.error")
                .stageWeights(60, 90, 110, 120, 120)
                .register();
        blockEvent("block_vanish", START, ABOVE_MIDDLE, UNCOMMON, Ticks.minutes(2), Ticks.seconds(3), "block.vanish")
                .register();
        blockEvent("block_wrong_texture", START, MIDDLE, UNCOMMON, Ticks.minutes(2), Ticks.seconds(8), "block.wrong")
                .register();
        blockEvent("tree_wrong_texture", START, ABOVE_MIDDLE, UNCOMMON, Ticks.minutes(3), Ticks.seconds(8), "block.tree")
                .register();
        blockEvent("terrain_gap", START, MIDDLE, RARE, Ticks.minutes(6), Ticks.seconds(5), "block.terrain_gap")
                .register();
        blockEvent("structure_flicker", START, ABOVE_MIDDLE, RARE, Ticks.minutes(6), Ticks.seconds(3.5), "block.structure_flicker")
                .register();
        blockEvent("block_displacement", UNDER_MIDDLE, NULL, UNCOMMON, Ticks.minutes(3), Ticks.seconds(6), "block.displace")
                .register();
        blockEvent("structure_vanish", ABOVE_MIDDLE, NULL, RARE, Ticks.minutes(8), Ticks.seconds(2.5), "block.structure_vanish")
                .register();
    }

    private static NullEventDefinition.Builder blockEvent(String id, CorruptionStage min, CorruptionStage max, int weight,
                                                          int cooldown, int duration, String effect) {
        return NullEventDefinition.builder(id)
                .stages(min, max)
                .weight(weight)
                .cooldown(cooldown)
                .duration(duration)
                .tags(EventTag.VISUAL)
                .place(EventPlace.OUTSIDE_NULL)
                .clientEffect(effect);
    }

    // ------------------------------------------------------------------ screen, sky, camera

    private static void registerScreenEffects() {
        NullEventDefinition.builder("chunk_flicker")
                .stages(START, NULL).weight(UNCOMMON).stageWeights(70, 100, 110, 120, 120)
                .cooldown(Ticks.minutes(3)).duration(16)
                .tags(EventTag.VISUAL)
                .clientEffect("visual.chunk_flicker")
                .register();
        NullEventDefinition.builder("sky_flicker")
                .stages(START, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(3)).duration(8)
                .tags(EventTag.VISUAL, EventTag.SKY).place(EventPlace.OUTSIDE_NULL)
                .action(VisualEvents::seesSky)
                .clientEffect("visual.sky_flicker")
                .register();
        NullEventDefinition.builder("distant_movement")
                // Not in NULL; back in the wheat field, where something moves between the stalks.
                .stages(START, HARVEST).weight(UNCOMMON).stageWeights(60, 100, 110, 120, 0, 120)
                .cooldown(Ticks.minutes(4)).duration(Ticks.seconds(3.5))
                .tags(EventTag.VISUAL).place(EventPlace.OUTSIDE_NULL)
                .clientEffect("visual.distant_movement")
                .register();
        NullEventDefinition.builder("texture_distortion")
                .stages(START, HARVEST).weight(COMMON).stageWeights(100, 100, 90, 80, 80, 60)
                .cooldown(Ticks.seconds(40)).duration(Ticks.seconds(3))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.distort")
                .register();
        NullEventDefinition.builder("camera_shake")
                .stages(UNDER_MIDDLE, NULL).weight(COMMON).stageWeights(100, 80, 100, 110, 120)
                .cooldown(Ticks.seconds(45)).duration(Ticks.seconds(2))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.shake")
                .register();
        NullEventDefinition.builder("camera_jerk")
                .stages(UNDER_MIDDLE, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(2)).duration(16)
                .tags(EventTag.VISUAL)
                .clientEffect("visual.camera_jerk")
                .register();
        NullEventDefinition.builder("screen_distortion")
                .stages(UNDER_MIDDLE, NULL).weight(UNCOMMON).stageWeights(100, 70, 100, 120, 130)
                .cooldown(Ticks.minutes(2)).duration(Ticks.seconds(2.5))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.screen_distortion")
                .register();
        NullEventDefinition.builder("visual_freeze")
                .stages(UNDER_MIDDLE, NULL).weight(RARE)
                .cooldown(Ticks.minutes(10)).duration(Ticks.seconds(2))
                .tags(EventTag.VISUAL, EventTag.INTENSE)
                .action(ctx -> safeToBlind(ctx, 12.0))
                .clientEffect("visual.freeze")
                .register();
        NullEventDefinition.builder("brief_blackout")
                .stages(UNDER_MIDDLE, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(4)).duration(25)
                .tags(EventTag.VISUAL, EventTag.INTENSE)
                .action(ctx -> safeToBlind(ctx, 0.0))
                .clientEffect("visual.blackout")
                .register();
        NullEventDefinition.builder("sky_color_shift")
                .stages(UNDER_MIDDLE, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(5)).duration(Ticks.seconds(20))
                .tags(EventTag.VISUAL, EventTag.SKY).place(EventPlace.OUTSIDE_NULL)
                .action(VisualEvents::seesSky)
                .clientEffect("visual.sky_color")
                .register();
        NullEventDefinition.builder("terrain_shift")
                .stages(UNDER_MIDDLE, NULL).weight(RARE)
                .cooldown(Ticks.minutes(6)).duration(Ticks.seconds(2))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.terrain_shift")
                .register();
        NullEventDefinition.builder("brightness_dip")
                .stages(UNDER_MIDDLE, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(2)).duration(Ticks.seconds(6))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.darken")
                .register();
        NullEventDefinition.builder("distant_silhouette")
                .stages(MIDDLE, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(5)).duration(Ticks.seconds(8))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.silhouette")
                .register();
        // The NULL stage's runner (stalkers): the figure in the distance sprints at the player and is
        // gone just before it reaches them. (A distant_silhouette in NULL is sometimes one as well.)
        NullEventDefinition.builder("silhouette_runner")
                .stages(NULL, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(5)).duration(Ticks.seconds(10))
                .tags(EventTag.VISUAL, EventTag.INTENSE)
                .clientEffect("visual.silhouette_runner")
                .register();
        NullEventDefinition.builder("color_drain")
                .stages(MIDDLE, HARVEST).weight(UNCOMMON).stageWeights(100, 100, 100, 100, 100, 70)
                .cooldown(Ticks.minutes(4)).duration(Ticks.seconds(15))
                .tags(EventTag.VISUAL)
                .clientEffect("visual.desaturate")
                .register();
        NullEventDefinition.builder("full_blackout")
                .stages(MIDDLE, NULL).weight(VERY_RARE)
                .cooldown(Ticks.minutes(15)).duration(Ticks.seconds(5))
                .tags(EventTag.VISUAL, EventTag.INTENSE)
                .action(ctx -> safeToBlind(ctx, 16.0))
                .clientEffect("visual.full_blackout")
                .register();
        NullEventDefinition.builder("fake_disconnect")
                .stages(ABOVE_MIDDLE, NULL).weight(VERY_RARE)
                .cooldown(Ticks.minutes(30)).duration(Ticks.seconds(3.5))
                .tags(EventTag.VISUAL)
                .action(ctx -> safeToBlind(ctx, 16.0))
                .clientEffect("visual.fake_disconnect")
                .register();
    }

    // ------------------------------------------------------------------ Stage 5

    private static void registerFinalStage() {
        NullEventDefinition.builder("rgb_storm")
                .stages(NULL, NULL).weight(UNCOMMON)
                .cooldown(Ticks.minutes(4)).duration(Ticks.seconds(4))
                .tags(EventTag.VISUAL, EventTag.INTENSE)
                .clientEffect("visual.rgb_storm")
                .register();
        NullEventDefinition.builder("world_not_found")
                .stages(NULL, NULL).weight(RARE)
                .cooldown(Ticks.minutes(12)).duration(Ticks.seconds(3))
                .tags(EventTag.VISUAL)
                .action(ctx -> glitchText(ctx, TEXT_WORLD_NOT_FOUND))
                .register();
        NullEventDefinition.builder("connection_lost_text")
                .stages(NULL, NULL).weight(RARE)
                .cooldown(Ticks.minutes(12)).duration(Ticks.seconds(3))
                .tags(EventTag.VISUAL)
                .action(ctx -> glitchText(ctx, TEXT_CONNECTION_LOST))
                .register();
    }

    // ------------------------------------------------------------------ checks

    private static boolean glitchText(EventContext ctx, String text) {
        // data 1 = large text
        ctx.sendEffect("visual.glitch_text", Optional.empty(), 1, text);
        return true;
    }

    /** Sky effects are pointless underground or under a roof. */
    private static boolean seesSky(EventContext ctx) {
        if (ctx.forced()) {
            return true;
        }
        ServerLevel level = ctx.level();
        if (!level.dimensionType().hasSkyLight()) {
            return false;
        }
        BlockPos eye = BlockPos.containing(ctx.player().getEyePosition());
        return level.getBrightness(LightLayer.SKY, eye) >= 10;
    }

    /**
     * Whether the screen may be taken away for a moment: not while falling, burning, drowning,
     * in lava, badly hurt, or (when {@code enemyRadius > 0}) with a hostile mob close by.
     */
    private static boolean safeToBlind(EventContext ctx, double enemyRadius) {
        if (ctx.forced()) {
            return true;
        }
        ServerPlayer player = ctx.player();
        if (player.isCreative() || player.isSpectator()) {
            return true;
        }
        if (player.isInLava() || player.isOnFire() || player.isUnderWater() || player.isFallFlying()
                || player.fallDistance > 2.5F || player.getHealth() < player.getMaxHealth() * 0.5F) {
            return false;
        }
        if (enemyRadius <= 0.0) {
            return true;
        }
        AABB around = player.getBoundingBox().inflate(enemyRadius, 6.0, enemyRadius);
        return ctx.level().getEntitiesOfClass(Mob.class, around, mob -> mob instanceof Enemy && mob.isAlive()).isEmpty();
    }
}
