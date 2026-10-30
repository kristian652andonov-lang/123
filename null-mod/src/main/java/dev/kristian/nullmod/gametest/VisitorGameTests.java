package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.entity.SkinSet;
import dev.kristian.nullmod.entity.UsernameGenerator;
import dev.kristian.nullmod.entity.fakeplayer.FakePlayerManager;
import dev.kristian.nullmod.entity.visitor.VisitorEntity;
import dev.kristian.nullmod.entity.visitor.VisitorModule;
import dev.kristian.nullmod.entity.visitor.VisitorPersonality;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Usernames, skins, Visitors and the simulated player list. */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class VisitorGameTests {
    private static final List<String> DESIGN_EXAMPLES = List.of("JamesHou99", "Notch_2009", "Steve12345", "xX_DiamondKing_Xx",
            "Player_2012", "Herobrine_OLD", "MinecraftFan2008", "TheRealSteve", "OldAccount_2011");

    private VisitorGameTests() {
    }

    @GameTest(template = "empty", batch = "visitors")
    public static void usernamesAreValidDeterministicAndNeverFamous(GameTestHelper helper) {
        Set<String> distinct = new HashSet<>();
        for (long seed = 0; seed < 4000; seed++) {
            String name = UsernameGenerator.fromSeed(seed);
            helper.assertTrue(UsernameGenerator.isAcceptable(name), "unacceptable name '" + name + "' for seed " + seed);
            helper.assertTrue(UsernameGenerator.isValid(name) || UsernameGenerator.isDesignExample(name),
                    "name '" + name + "' is not 3-16 characters of [A-Za-z0-9_]");
            helper.assertFalse(UsernameGenerator.isBlocked(name), "blocked name '" + name + "' was generated");
            helper.assertValueEqual(UsernameGenerator.fromSeed(seed), name, "fromSeed(" + seed + ") is not deterministic");
            distinct.add(name);
        }
        helper.assertTrue(distinct.size() > 2500, "only " + distinct.size() + " distinct names in 4000 seeds");

        RandomSource a = RandomSource.create(1234L);
        RandomSource b = RandomSource.create(1234L);
        for (int i = 0; i < 64; i++) {
            helper.assertValueEqual(UsernameGenerator.random(b), UsernameGenerator.random(a), "random() differs for equal seeds");
        }

        for (String example : DESIGN_EXAMPLES) {
            helper.assertTrue(UsernameGenerator.curated().contains(example), "design example " + example + " can never be generated");
            helper.assertTrue(UsernameGenerator.isAcceptable(example), "design example " + example + " is rejected");
        }
        for (String famous : List.of("Notch", "notch", "jeb_", "Dinnerbone", "Grumm", "Technoblade", "Dream", "DreamWasTaken", "Herobrine")) {
            helper.assertTrue(UsernameGenerator.isBlocked(famous), famous + " is not on the blocklist");
            helper.assertFalse(UsernameGenerator.isAcceptable(famous), famous + " would be accepted");
        }
        helper.assertFalse(UsernameGenerator.isValid("ab"), "two characters accepted");
        helper.assertFalse(UsernameGenerator.isValid("seventeen_chars_x"), "seventeen characters accepted");
        helper.assertFalse(UsernameGenerator.isValid("has space"), "a space accepted");
        helper.assertFalse(UsernameGenerator.isValid("dash-name"), "a dash accepted");
        helper.assertTrue(UsernameGenerator.fragmentCount() >= 500, "only " + UsernameGenerator.fragmentCount() + " name fragments");

        Set<String> taken = new HashSet<>();
        RandomSource r = RandomSource.create(7L);
        for (int i = 0; i < 200; i++) {
            String name = UsernameGenerator.unique(r, taken);
            helper.assertTrue(taken.add(name.toLowerCase(java.util.Locale.ROOT)), "unique() repeated " + name);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "visitors")
    public static void skinTableIsConsistent(GameTestHelper helper) {
        helper.assertTrue(SkinSet.count() >= 32, "only " + SkinSet.count() + " skins");
        for (SkinSet.Category category : SkinSet.Category.values()) {
            int index = SkinSet.firstOf(category);
            helper.assertValueEqual(SkinSet.category(index), category, "firstOf(" + category + ")");
        }
        for (int i = 0; i < SkinSet.count(); i++) {
            helper.assertValueEqual(SkinSet.get(i).index(), i, "skin table order");
            helper.assertTrue(SkinSet.texture(i).getPath().equals("textures/entity/skins/skin_" + i + ".png"), "texture path of skin " + i);
            helper.assertTrue(SkinSet.get(i).eyes() == (SkinSet.eyesTexture(i) != null), "eyes mask of skin " + i);
        }
        helper.assertTrue(SkinSet.sanitize(-1) >= 0 && SkinSet.sanitize(-1) < SkinSet.count(), "negative skin index not sanitized");
        helper.assertTrue(SkinSet.sanitize(Integer.MAX_VALUE) < SkinSet.count(), "huge skin index not sanitized");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "visitors")
    public static void visitorVanishesWhenHit(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        VisitorEntity visitor = helper.spawn(VisitorModule.VISITOR.get(), new BlockPos(4, 1, 4));
        visitor.setIdentity("TestVisitor", SkinSet.firstOf(SkinSet.Category.OLD), 0);
        visitor.configure(VisitorPersonality.OBSERVER, null, 20 * 60, 3.0F, false);
        boolean hurt = visitor.hurt(helper.getLevel().damageSources().generic(), 4.0F);
        helper.assertFalse(hurt, "a Visitor took damage");
        helper.assertTrue(visitor.isRemoved(), "a Visitor that was hit did not vanish");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "visitors")
    public static void visitorsNeverTrampleOrGrief(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        VisitorEntity visitor = helper.spawn(VisitorModule.VISITOR.get(), new BlockPos(4, 1, 4));
        visitor.setIdentity("TestVisitor", SkinSet.firstOf(SkinSet.Category.OLD), 0);
        visitor.configure(VisitorPersonality.FOLLOWER, null, 20 * 60, 3.0F, false);
        // A fall of three blocks onto farmland always tramples it for a mob that may grief.
        helper.assertFalse(visitor.canTrample(Blocks.FARMLAND.defaultBlockState(), helper.absolutePos(new BlockPos(4, 0, 4)), 3.0F),
                "a Visitor can trample farmland");
        helper.assertFalse(EventHooks.canEntityGrief(helper.getLevel(), visitor), "a Visitor may grief (turtle eggs, other mods)");
        visitor.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "visitors", timeoutTicks = 200)
    public static void visitorVanishesWhenApproached(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(13, 0, 13), Blocks.STONE);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(13.5, 1.0, 13.5)));
        VisitorEntity visitor = helper.spawn(VisitorModule.VISITOR.get(), new BlockPos(2, 1, 2));
        visitor.setIdentity("TestVisitor", SkinSet.firstOf(SkinSet.Category.BLACK), 0);
        visitor.configure(VisitorPersonality.OBSERVER, player, 20 * 60, 5.0F, false);

        helper.runAfterDelay(20, () -> {
            helper.assertFalse(visitor.isRemoved(), "the Visitor vanished while its player was 15 blocks away");
            player.moveTo(helper.absoluteVec(new Vec3(3.5, 1.0, 2.5)));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 20, "waiting for the player to walk up");
            helper.assertTrue(visitor.isRemoved(), "the Visitor did not vanish when its player came close");
            removeMockPlayer(helper, player);
        });
    }

    @GameTest(template = "empty", batch = "visitors_stage")
    public static void fakePlayerListGrowsPersistsAndClears(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CorruptionStage original = CorruptionManager.stage(server);
        FakePlayerManager manager = FakePlayerManager.INSTANCE;
        try {
            helper.assertValueEqual(FakePlayerManager.targetCount(0.0F, 79), 3, "list size at the start of NULL");
            helper.assertValueEqual(FakePlayerManager.targetCount(1.0F, 79), 79, "list size at saturation");
            int middle = FakePlayerManager.targetCount(0.5F, 79);
            helper.assertTrue(middle > 3 && middle < 79, "list size half way: " + middle);
            helper.assertValueEqual(FakePlayerManager.targetCount(0.7F, 0), 0, "list size with the setting at 0");

            CorruptionManager.setStage(server, CorruptionStage.ABOVE_MIDDLE);
            helper.assertTrue(manager.names().isEmpty(), "simulated players exist before Stage 5");
            helper.assertTrue(manager.addSimulated(server, RandomSource.create(5L), false).isEmpty(),
                    "a simulated player was added before Stage 5");

            CorruptionManager.setStage(server, CorruptionStage.NULL);
            int before = manager.names().size();
            RandomSource random = RandomSource.create(99L);
            for (int i = 0; i < 6; i++) {
                helper.assertTrue(manager.addSimulated(server, random, false).isPresent(), "could not add simulated player " + i);
            }
            List<String> names = manager.names();
            helper.assertValueEqual(names.size(), before + 6, "list size after six joins");
            helper.assertValueEqual(FakePlayerManager.storedNames(server), names, "the saved list");
            manager.reloadFromData(server);
            helper.assertValueEqual(manager.names(), names, "the list after reloading it from the saved data");
            helper.assertTrue(manager.removeSimulated(server, random, false).isPresent(), "could not remove a simulated player");
            helper.assertValueEqual(manager.names().size(), before + 5, "list size after a leave");

            CorruptionManager.setStage(server, CorruptionStage.ABOVE_MIDDLE);
            helper.assertTrue(manager.names().isEmpty(), "the list was not cleared below Stage 5");
            helper.assertTrue(FakePlayerManager.storedNames(server).isEmpty(), "the saved list was not cleared below Stage 5");
        } finally {
            if (CorruptionManager.stage(server) != original) {
                CorruptionManager.setStage(server, original);
            }
        }
        helper.succeed();
    }

    private static void removeMockPlayer(GameTestHelper helper, ServerPlayer player) {
        try {
            helper.getLevel().getServer().getPlayerList().remove(player);
        } catch (RuntimeException e) {
            NullMod.LOGGER.warn("Could not remove the mock player after a Visitor test", e);
        }
    }
}
