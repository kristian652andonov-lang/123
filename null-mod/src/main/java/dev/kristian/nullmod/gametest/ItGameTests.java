package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.entity.it.ItDamage;
import dev.kristian.nullmod.entity.it.ItEntity;
import dev.kristian.nullmod.entity.it.ItEvents;
import dev.kristian.nullmod.entity.it.ItModule;
import dev.kristian.nullmod.entity.it.ItShelter;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * IT: a summoned IT hunts its target; the shelter test needs a roof, walls on every side, a closed
 * door and unbroken windows; a player in a closed box is never reached (IT prowls instead); an
 * unsheltered survival player is seized and dies of {@code null:it}; a player sprinting straight
 * away is run down; a player on a pillar just out of reach is leapt for; creative players are never
 * touched; IT leaves and is gone after its hunt; a saved IT never comes back; the damage type is in
 * place. IT hunts every unsheltered player in its world, so every test with a player runs in a batch
 * of its own (and the IT is told to hunt only that player).
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class ItGameTests {
    /** Feet of the test player and where IT is summoned: eight blocks apart, on a stone floor. */
    private static final Vec3 PLAYER_SPOT = new Vec3(4.5, 1.0, 8.5);
    private static final Vec3 IT_SPOT = new Vec3(12.5, 1.0, 8.5);
    /** Block updates that neither drop half a door nor reshape neighbours while a test builds. */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private ItGameTests() {
    }

    @GameTest(template = "empty", batch = "it")
    public static void damageTypeIsInPlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(ItDamage.IT);
        helper.assertTrue(type.isPresent(), "damage type null:it is not registered");
        helper.assertValueEqual(type.get().value().msgId(), "null.it", "death message id");
        helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_ARMOR), "null:it does not bypass armour");
        helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_SHIELD), "null:it does not bypass shields");
        helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_COOLDOWN), "null:it waits for the hurt cooldown");
        helper.assertTrue(type.get().is(DamageTypeTags.NO_KNOCKBACK), "null:it causes knockback");
        DamageSource source = ItDamage.source(level, null);
        helper.assertTrue(source.is(ItDamage.IT), "ItDamage.source does not use null:it");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "it")
    public static void shelterNeedsRoofWallsAndAClosedDoor(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        // A hut: stone walls x 2..6, z 2..6, two blocks high, a roof on top, a door in the north
        // wall and a window pane in the west wall.
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                boolean wall = x == 2 || x == 6 || z == 2 || z == 6;
                for (int y = 1; y <= 2; y++) {
                    if (wall) {
                        set(helper, new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
                set(helper, new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos doorLow = new BlockPos(4, 1, 2);
        door(helper, doorLow, false);
        BlockPos window = new BlockPos(2, 2, 4);
        set(helper, window, Blocks.GLASS_PANE.defaultBlockState());
        BlockPos inside = helper.absolutePos(new BlockPos(4, 1, 4));

        helper.assertTrue(ItShelter.isEnclosed(level, inside), "a closed hut is not a shelter");
        door(helper, doorLow, true);
        helper.assertFalse(ItShelter.isEnclosed(level, inside), "a hut with its door open is still a shelter");
        door(helper, doorLow, false);
        helper.assertTrue(ItShelter.isEnclosed(level, inside), "closing the door again does not make it a shelter");
        set(helper, window, Blocks.AIR.defaultBlockState());
        helper.assertFalse(ItShelter.isEnclosed(level, inside), "a hut with a broken window is still a shelter");
        set(helper, window, Blocks.GLASS_PANE.defaultBlockState());
        set(helper, new BlockPos(5, 3, 5), Blocks.AIR.defaultBlockState());
        helper.assertFalse(ItShelter.isEnclosed(level, inside), "a hut with a hole in the roof is still a shelter");
        set(helper, new BlockPos(5, 3, 5), Blocks.STONE.defaultBlockState());
        helper.assertTrue(ItShelter.isEnclosed(level, inside), "the mended hut is not a shelter");
        ItShelter.Room room = ItShelter.survey(level, inside, inside.above());
        helper.assertTrue(!room.windows().isEmpty(), "the survey missed the window");
        helper.assertTrue(room.windows().stream().anyMatch(o -> o.outward() == Direction.WEST), "the window does not face out of the hut");
        helper.assertFalse(ItShelter.isEnclosed(level, helper.absolutePos(new BlockPos(11, 1, 11))), "open ground counts as a shelter");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "it_hunt", timeoutTicks = 100)
    public static void summonedItHunts(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItEntity it = summon(helper, player, 400);
        helper.assertTrue(it.isHunting(), "a summoned IT is not hunting");
        helper.assertValueEqual(it.getType(), ItModule.IT.get(), "entity type");
        helper.assertValueEqual(it.quarryId(), player.getId(), "IT is not after its target");
        helper.assertFalse(ItModule.IT.get().canSerialize(), "IT would be saved with the world");
        helper.runAtTickTime(5, () -> {
            helper.assertTrue(it.isHunting() && !it.isRemoved(), "IT stopped hunting after five ticks");
            helper.assertTrue(it.phase() == ItEntity.PHASE_ARRIVING || it.phase() == ItEntity.PHASE_CHASING,
                    "IT is neither arriving nor chasing but in phase " + it.phase());
            cleanUp(helper, player, it);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "it_sheltered", timeoutTicks = 300)
    public static void shelteredPlayerIsNeverReached(GameTestHelper helper) {
        floor(helper);
        // A closed stone box in the middle of the floor (so IT's circles stay on it): one block of air, two high.
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 1; y <= 3; y++) {
                    boolean air = x == 7 && z == 7 && y <= 2;
                    if (!air) {
                        set(helper, new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new Vec3(7.5, 1.0, 7.5)));
        helper.assertTrue(ItEntity.isSheltered(player), "a player shut in a stone box is not sheltered");
        ItEntity it = summon(helper, player, 400, new Vec3(13.5, 1.0, 7.5));
        boolean[] prowled = {false};
        boolean[] held = {false};
        helper.onEachTick(() -> {
            prowled[0] |= it.phase() == ItEntity.PHASE_PROWLING;
            held[0] |= player.getVehicle() == it;
        });
        helper.runAtTickTime(240, () -> {
            helper.assertFalse(held[0], "IT reached a sheltered player");
            helper.assertTrue(player.isAlive(), "the sheltered player died");
            helper.assertTrue(prowled[0], "IT never prowled around the shelter");
            helper.assertTrue(it.isHunting(), "IT gave up on a sheltered player before its hunt was over");
            cleanUp(helper, player, it);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "it_kill", timeoutTicks = 400)
    public static void unshelteredPlayerIsCaughtAndKilled(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        helper.assertFalse(ItEntity.isSheltered(player), "a player in the open counts as sheltered");
        ItEntity it = summon(helper, player, 600);
        boolean[] seized = {false};
        helper.onEachTick(() -> seized[0] |= player.getVehicle() == it);
        helper.succeedWhen(() -> {
            helper.assertTrue(seized[0], "waiting for IT to seize the player");
            helper.assertTrue(player.isDeadOrDying(), "waiting for the kill");
            DamageSource last = player.getLastDamageSource();
            helper.assertTrue(last != null && last.is(ItDamage.IT), "the player died of " + last);
            helper.assertFalse(player.getVehicle() == it, "IT still holds the dead player");
            boolean remembered = WorldMemory.recentFor(helper.getLevel().getServer(), player.getUUID(), 20L * 60L).stream()
                    .anyMatch(entry -> entry.key().equals("it:harvested"));
            helper.assertTrue(remembered, "the death was not remembered as it:harvested");
            cleanUp(helper, player, it);
        });
    }

    @GameTest(template = "empty", batch = "it_sprint", timeoutTicks = 200)
    public static void sprintingPlayerIsRunDown(GameTestHelper helper) {
        floor(helper);
        // The player sprints straight away from it, a sprinting player's 0.28 blocks a tick, from
        // four blocks ahead: it has to catch them before they reach the far side of the floor.
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new Vec3(5.5, 1.0, 8.5)));
        ItEntity it = summon(helper, player, 600, new Vec3(1.5, 1.0, 8.5));
        Vec3 away = helper.absoluteVec(new Vec3(1.0, 0.0, 0.0)).subtract(helper.absoluteVec(Vec3.ZERO)).scale(0.28);
        Vec3 finish = helper.absoluteVec(new Vec3(15.0, 1.0, 8.5));
        boolean[] seized = {false};
        helper.onEachTick(() -> {
            seized[0] |= player.getVehicle() == it;
            if (seized[0]) {
                return;
            }
            Vec3 at = player.position().add(away);
            player.moveTo(at.x, at.y, at.z);
            if (at.subtract(finish).dot(away) >= 0.0) {
                helper.fail("IT never caught a player sprinting away from it");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seized[0], "waiting for IT to run the player down");
            cleanUp(helper, player, it);
        });
    }

    @GameTest(template = "empty", batch = "it_tower", timeoutTicks = 200)
    public static void playerOnATowerJustOutOfReachIsTaken(GameTestHelper helper) {
        floor(helper);
        // A one-block stone pillar seven blocks high; the player stands on top, half a block out of
        // its reach: it has to leap for them.
        for (int y = 1; y <= 7; y++) {
            set(helper, new BlockPos(8, y, 8), Blocks.STONE.defaultBlockState());
        }
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new Vec3(8.5, 8.0, 8.5)));
        helper.assertFalse(ItEntity.isSheltered(player), "a player on top of a pillar counts as sheltered");
        ItEntity it = summon(helper, player, 600, new Vec3(13.5, 1.0, 8.5));
        boolean[] seized = {false};
        helper.onEachTick(() -> seized[0] |= player.getVehicle() == it);
        helper.succeedWhen(() -> {
            helper.assertTrue(seized[0], "waiting for IT to leap for the player on the pillar");
            cleanUp(helper, player, it);
        });
    }

    @GameTest(template = "empty", batch = "it_creative", timeoutTicks = 200)
    public static void creativePlayersAreNeverTaken(GameTestHelper helper) {
        floor(helper);
        ServerPlayer player = player(helper, GameType.CREATIVE);
        ItEntity it = summon(helper, player, 400);
        boolean[] held = {false};
        helper.onEachTick(() -> held[0] |= player.getVehicle() == it);
        helper.runAtTickTime(140, () -> {
            helper.assertFalse(held[0], "IT seized a creative player");
            helper.assertTrue(player.isAlive(), "a creative player died");
            helper.assertFalse(it.isHunting(), "IT is still hunting with nobody it may take");
            cleanUp(helper, player, it);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "it_leave", timeoutTicks = 200)
    public static void itLeavesAndIsGoneAfterItsHunt(GameTestHelper helper) {
        floor(helper);
        // Somebody it may not take stands by, so it has to time out rather than see nobody around.
        ServerPlayer player = player(helper, GameType.CREATIVE);
        ItEntity it = summon(helper, player, 40);
        it.setLeaveTimeout(40);
        helper.runAtTickTime(20, () -> helper.assertTrue(it.isHunting(), "IT stopped hunting before its time"));
        helper.runAtTickTime(46, () -> helper.assertFalse(it.isHunting(), "IT is still hunting after its time"));
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 46, "waiting for the hunt to end");
            helper.assertTrue(it.isRemoved(), "waiting for IT to be gone");
            cleanUp(helper, player, it);
        });
    }

    @GameTest(template = "empty", batch = "it_saved", timeoutTicks = 60)
    public static void savedItNeverComesBack(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ItEntity original = ItModule.IT.get().create(level);
        ItEntity copy = ItModule.IT.get().create(level);
        helper.assertTrue(original != null && copy != null, "could not create IT");
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        copy.load(saved);
        copy.moveTo(helper.absoluteVec(IT_SPOT));
        helper.assertTrue(level.addFreshEntity(copy), "could not add IT");
        helper.runAtTickTime(3, () -> {
            helper.assertTrue(copy.isRemoved(), "an IT loaded from a save is still there");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static void set(GameTestHelper helper, BlockPos relative, BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(relative), state, QUIET);
    }

    private static void door(GameTestHelper helper, BlockPos lower, boolean open) {
        BlockState state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH).setValue(DoorBlock.OPEN, open);
        set(helper, lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(helper, lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        player.moveTo(helper.absoluteVec(PLAYER_SPOT));
        ItEvents.clearGrace(player);
        return player;
    }

    private static ItEntity summon(GameTestHelper helper, ServerPlayer target, int huntTicks) {
        return summon(helper, target, huntTicks, IT_SPOT);
    }

    private static ItEntity summon(GameTestHelper helper, ServerPlayer target, int huntTicks, Vec3 spot) {
        ItEntity it = ItEntity.summon(helper.getLevel(), helper.absoluteVec(spot), target, huntTicks);
        if (it == null) {
            helper.fail("could not summon IT");
            throw new IllegalStateException("unreachable");
        }
        // Only this test's player: IT hunts everybody in its world otherwise.
        it.setExclusive(true);
        return it;
    }

    private static void cleanUp(GameTestHelper helper, ServerPlayer player, ItEntity it) {
        if (!it.isRemoved()) {
            it.release(player);
            it.discard();
        }
        try {
            helper.getLevel().getServer().getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after an IT test", e);
        }
    }
}
