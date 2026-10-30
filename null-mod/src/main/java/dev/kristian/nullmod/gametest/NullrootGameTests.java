package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.entity.nullroot.NullrootCaptures;
import dev.kristian.nullmod.entity.nullroot.NullrootDamage;
import dev.kristian.nullmod.entity.nullroot.NullrootEntity;
import dev.kristian.nullmod.entity.nullroot.NullrootModule;
import dev.kristian.nullmod.entity.nullroot.NullrootState;
import dev.kristian.nullmod.entity.nullroot.NullrootVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NULLROOT: every variant grows and stays rooted, a hunter catches a survival player standing
 * in its reach within a few seconds, hurting it frees them and takes the modifiers away, a
 * lethal capture kills at the end of the sequence and a harmless one lets go, creative players
 * are never touched, and the damage type is in place. Every test that uses a player runs in a
 * batch of its own so no organism can see another test's player.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class NullrootGameTests {
    private static final BlockPos HUNTER_ROOT = new BlockPos(8, 0, 8);
    /** Feet of the test player: three blocks from the hunter, well inside its reach. */
    private static final Vec3 PLAYER_SPOT = new Vec3(8.5, 1.0, 11.5);

    private NullrootGameTests() {
    }

    // ------------------------------------------------------------------ growth

    @GameTest(template = "empty", batch = "nullroot", timeoutTicks = 120)
    public static void everyVariantGrowsAndStaysRooted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        record Placement(NullrootVariant variant, BlockPos root, Direction face) {
        }
        List<Placement> placements = List.of(
                new Placement(NullrootVariant.SMALL, new BlockPos(2, 0, 2), Direction.UP),
                new Placement(NullrootVariant.LARGE, new BlockPos(5, 0, 2), Direction.UP),
                new Placement(NullrootVariant.TENDRIL, new BlockPos(8, 0, 2), Direction.UP),
                new Placement(NullrootVariant.UNDERGROUND, new BlockPos(11, 0, 2), Direction.UP),
                new Placement(NullrootVariant.CENTRAL, new BlockPos(4, 0, 10), Direction.UP),
                new Placement(NullrootVariant.CEILING, new BlockPos(10, 5, 9), Direction.DOWN),
                new Placement(NullrootVariant.WALL, new BlockPos(13, 2, 13), Direction.NORTH));
        List<NullrootEntity> grown = new ArrayList<>();
        List<Vec3> positions = new ArrayList<>();
        for (Placement placement : placements) {
            helper.setBlock(placement.root(), Blocks.STONE);
            NullrootEntity organism = grow(helper, placement.variant(), placement.root(), placement.face());
            NullrootVariant variant = placement.variant();
            BlockPos root = helper.absolutePos(placement.root());
            helper.assertTrue(organism.isAlive(), variant.id() + " is not alive");
            helper.assertValueEqual(organism.variant(), variant, "variant");
            helper.assertValueEqual(organism.face(), placement.face(), variant.id() + " face");
            helper.assertValueEqual(organism.rootPos(), root, variant.id() + " root");
            helper.assertTrue(Math.abs(organism.getHealth() - variant.maxHealth()) < 1.0E-3F, variant.id() + " health " + organism.getHealth());
            helper.assertTrue(Math.abs(organism.getBbWidth() - variant.width()) < 1.0E-3F
                    && Math.abs(organism.getBbHeight() - variant.height()) < 1.0E-3F, variant.id() + " has the wrong size");
            AABB rootBox = new AABB(root.getX(), root.getY(), root.getZ(), root.getX() + 1.0, root.getY() + 1.0, root.getZ() + 1.0);
            helper.assertFalse(organism.getBoundingBox().deflate(0.01).intersects(rootBox), variant.id() + " grows into its own root block");
            helper.assertTrue(organism.getBoundingBox().inflate(0.01).intersects(rootBox), variant.id() + " does not touch its root block");

            CompoundTag saved = organism.saveWithoutId(new CompoundTag());
            NullrootEntity copy = NullrootModule.NULLROOT.get().create(level);
            helper.assertTrue(copy != null, "could not create a copy");
            copy.load(saved);
            helper.assertValueEqual(copy.variant(), variant, variant.id() + " variant after loading");
            helper.assertValueEqual(copy.face(), placement.face(), variant.id() + " face after loading");
            helper.assertValueEqual(copy.rootPos(), root, variant.id() + " root after loading");
            helper.assertTrue(copy.growth() >= 1.0F, variant.id() + " growth after loading");
            grown.add(organism);
            positions.add(organism.position());
        }
        helper.runAfterDelay(40, () -> {
            for (int i = 0; i < grown.size(); i++) {
                NullrootEntity organism = grown.get(i);
                helper.assertTrue(organism.isAlive() && !organism.isRemoved(), organism.variant().id() + " did not survive 40 ticks");
                helper.assertTrue(organism.position().distanceToSqr(positions.get(i)) < 1.0E-6,
                        organism.variant().id() + " moved from where it grew");
            }
            grown.forEach(NullrootEntity::discard);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "nullroot", timeoutTicks = 100)
    public static void witheringWhenRootIsDugOut(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 0, 12), Blocks.STONE);
        ServerLevel level = helper.getLevel();
        NullrootEntity organism = NullrootEntity.create(level, NullrootVariant.SMALL, helper.absolutePos(new BlockPos(3, 0, 12)), Direction.UP,
                null, null, true, true);
        helper.assertTrue(organism != null && level.addFreshEntity(organism), "could not grow the organism");
        helper.runAfterDelay(5, () -> helper.setBlock(new BlockPos(3, 0, 12), Blocks.AIR));
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 5, "waiting for the root to be dug out");
            helper.assertTrue(organism.isRemoved() || organism.isWithering(), "it lives on without its root block");
            if (!organism.isRemoved()) {
                organism.discard();
            }
        });
    }

    @GameTest(template = "empty", batch = "nullroot")
    public static void damageTypeIsInPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(NullrootDamage.NULLROOT);
        helper.assertTrue(type.isPresent(), "damage type null:nullroot is not registered");
        helper.assertValueEqual(type.get().value().msgId(), "null.nullroot", "death message id");
        helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_ARMOR), "null:nullroot does not bypass armour");
        helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_SHIELD), "null:nullroot does not bypass shields");
        helper.assertTrue(type.get().is(DamageTypeTags.NO_KNOCKBACK), "null:nullroot causes knockback");
        DamageSource source = NullrootDamage.source(level, null);
        helper.assertTrue(source.is(NullrootDamage.NULLROOT), "NullrootDamage.source does not use null:nullroot");
        helper.succeed();
    }

    // ------------------------------------------------------------------ captures

    @GameTest(template = "empty", batch = "nullroot_capture", timeoutTicks = 200)
    public static void hunterCapturesPlayerInReach(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            helper.assertTrue(NullrootCaptures.isCaptured(player), "the player standing in reach was not captured");
            NullrootCaptures.Capture capture = NullrootCaptures.captureOf(player);
            helper.assertTrue(capture != null && capture.grabber() == hunter, "captured by something else");
            helper.assertTrue(NullrootCaptures.hasModifiers(player), "a captured player can still move");
            helper.assertValueEqual(hunter.state(), NullrootState.GRABBING, "hunter state");
            helper.assertValueEqual(hunter.focusId(), player.getId(), "hunter focus");
            helper.assertTrue(capture.duration() >= 160 && capture.duration() <= 240, "capture lasts " + capture.duration() + " ticks");
            cleanUp(helper, player, hunter);
        });
    }

    @GameTest(template = "empty", batch = "nullroot_release", timeoutTicks = 220)
    public static void damageReleasesCapture(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ServerLevel level = helper.getLevel();
        helper.succeedWhen(() -> {
            helper.assertTrue(NullrootCaptures.isCaptured(player), "waiting for the capture");
            float damage = hunter.variant().releaseDamage() + 1.0F;
            helper.assertTrue(hunter.hurt(level.damageSources().playerAttack(player), damage), "the organism took no damage");
            helper.assertTrue(hunter.isAlive(), "one hit should not kill it");
            helper.assertFalse(NullrootCaptures.isCaptured(player), "damage did not release the player");
            helper.assertFalse(NullrootCaptures.hasModifiers(player), "the capture modifiers stayed on the released player");
            helper.assertValueEqual(hunter.state(), NullrootState.RECOIL, "the organism did not recoil");
            helper.assertTrue(NullrootCaptures.inGrace(player), "a freed player must not be grabbed again right away");
            cleanUp(helper, player, hunter);
        });
    }

    @GameTest(template = "empty", batch = "nullroot_lethal", timeoutTicks = 420)
    public static void lethalCaptureKillsAtTheEnd(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        boolean[] seen = {false};
        helper.onEachTick(() -> {
            NullrootCaptures.Capture capture = NullrootCaptures.captureOf(player);
            if (capture != null && !seen[0]) {
                seen[0] = true;
                // The world's config decides normally; this test checks the lethal path itself.
                capture.setLethal(Boolean.TRUE);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "waiting for the capture");
            helper.assertTrue(player.isDeadOrDying(), "waiting for the end of the capture");
            helper.assertFalse(NullrootCaptures.isCaptured(player), "a dead player is still held");
            helper.assertFalse(NullrootCaptures.hasModifiers(player), "the capture modifiers stayed on the dead player");
            boolean remembered = WorldMemory.recentFor(helper.getLevel().getServer(), player.getUUID(), 20L * 60L).stream()
                    .anyMatch(entry -> entry.key().equals("nullroot:killed"));
            helper.assertTrue(remembered, "the death was not remembered as nullroot:killed");
            cleanUp(helper, player, hunter);
        });
    }

    @GameTest(template = "empty", batch = "nullroot_safe", timeoutTicks = 420)
    public static void harmlessCaptureLetsGo(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        boolean[] seen = {false};
        helper.onEachTick(() -> {
            NullrootCaptures.Capture capture = NullrootCaptures.captureOf(player);
            if (capture != null && !seen[0]) {
                seen[0] = true;
                capture.setLethal(Boolean.FALSE);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "waiting for the capture");
            helper.assertFalse(NullrootCaptures.isCaptured(player), "waiting for the end of the capture");
            helper.assertTrue(player.isAlive(), "a harmless capture killed the player");
            helper.assertFalse(NullrootCaptures.hasModifiers(player), "the capture modifiers stayed on the released player");
            cleanUp(helper, player, hunter);
        });
    }

    @GameTest(template = "empty", batch = "nullroot_creative", timeoutTicks = 160)
    public static void creativePlayersAreNeverTaken(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.CREATIVE);
        boolean[] reached = {false};
        helper.onEachTick(() -> reached[0] |= hunter.state() == NullrootState.REACHING || NullrootCaptures.isCaptured(player));
        helper.runAtTickTime(120, () -> {
            helper.assertFalse(reached[0], "it reached for a creative player");
            helper.assertFalse(NullrootCaptures.hasModifiers(player), "a creative player got capture modifiers");
            cleanUp(helper, player, hunter);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "nullroot_removal", timeoutTicks = 200)
    public static void removingTheOrganismFreesThePlayer(GameTestHelper helper) {
        floor(helper);
        NullrootEntity hunter = grow(helper, NullrootVariant.TENDRIL, HUNTER_ROOT, Direction.UP);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        helper.succeedWhen(() -> {
            helper.assertTrue(NullrootCaptures.isCaptured(player), "waiting for the capture");
            hunter.discard();
            helper.assertFalse(NullrootCaptures.isCaptured(player), "the player is still held by a removed organism");
            helper.assertFalse(NullrootCaptures.hasModifiers(player), "the capture modifiers outlived the organism");
            cleanUp(helper, player, hunter);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A stone floor under the whole test area (relative y=0), so roots and the player have ground. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** A fully grown, unmanaged organism rooted in the (relative) block, growing out of its face. */
    private static NullrootEntity grow(GameTestHelper helper, NullrootVariant variant, BlockPos relativeRoot, Direction face) {
        ServerLevel level = helper.getLevel();
        NullrootEntity organism = NullrootEntity.create(level, variant, helper.absolutePos(relativeRoot), face, null, null, false, false);
        if (organism == null) {
            helper.fail("could not create a " + variant.id());
            throw new IllegalStateException("unreachable");
        }
        organism.setGrowth(1.0F);
        helper.assertTrue(level.addFreshEntity(organism), "could not add the " + variant.id());
        return organism;
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        player.moveTo(helper.absoluteVec(PLAYER_SPOT));
        return player;
    }

    private static void cleanUp(GameTestHelper helper, ServerPlayer player, NullrootEntity organism) {
        NullrootCaptures.release(player, NullrootCaptures.Reason.RESET);
        if (!organism.isRemoved()) {
            organism.discard();
        }
        try {
            helper.getLevel().getServer().getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after a NULLROOT test", e);
        }
    }
}
