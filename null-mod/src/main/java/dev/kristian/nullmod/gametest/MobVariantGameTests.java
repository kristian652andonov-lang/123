package dev.kristian.nullmod.gametest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.entity.it.ItModule;
import dev.kristian.nullmod.entity.nullroot.NullrootModule;
import dev.kristian.nullmod.entity.variant.MobVariant;
import dev.kristian.nullmod.entity.variant.MobVariantManager;
import dev.kristian.nullmod.entity.variant.MobVariantRules;
import dev.kristian.nullmod.entity.variant.VariantData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Mob variants: a variant is saved and loaded with its mob (and an ordinary mob saves nothing),
 * the behaviour goals follow the variant, excluded types never become variants (and NULL leaves
 * tamed and named mobs alone by itself), moving the stage back turns loaded variants back at once
 * and unloaded ones when they load, the background spread obeys the area and world caps, and
 * {@code /null mob} turns a mob. Tests that move the stage each run in a batch of their own.
 */
@GameTestHolder(NullMod.MODID)
@PrefixGameTestTemplate(false)
public final class MobVariantGameTests {
    private static final String ATTACHMENT = NullMod.id("mob_variant").toString();

    private MobVariantGameTests() {
    }

    @GameTest(template = "empty", batch = "mob_variant", timeoutTicks = 60)
    public static void variantSurvivesSaveAndLoad(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(4, 1, 4));
        helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.NONE, "variant of a fresh cow");
        helper.assertFalse(saved(cow), "an ordinary cow saves a variant attachment");

        helper.assertTrue(manager.set(cow, MobVariant.PARASITE, 0.35F, true), "the cow did not become a parasite host");
        VariantData data = MobVariantManager.data(cow);
        helper.assertTrue(data != null && data.variant() == MobVariant.PARASITE, "the cow carries no parasite data");
        CompoundTag tag = cow.saveWithoutId(new CompoundTag());
        helper.assertTrue(attachment(tag), "the variant is not saved with the cow");
        Cow copy = EntityType.COW.create(level);
        if (copy == null) {
            helper.fail("could not create a cow");
            return;
        }
        copy.load(tag);
        VariantData loaded = MobVariantManager.data(copy);
        helper.assertTrue(loaded != null, "the variant did not load with the cow");
        helper.assertValueEqual(loaded.variant(), MobVariant.PARASITE, "variant after loading");
        helper.assertValueEqual(loaded.seed(), data.seed(), "look seed after loading");
        helper.assertTrue(Math.abs(loaded.growth() - data.growth()) < 1.0E-6F, "growth after loading: " + loaded.growth());
        helper.assertTrue(loaded.forced(), "an operator's variant forgot that it is one");

        helper.assertTrue(manager.set(cow, MobVariant.NONE, 0.0F, false), "the cow did not turn back");
        helper.assertFalse(saved(cow), "a cow that turned back still saves a variant");
        helper.assertTrue(manager.set(cow, MobVariant.CORRUPTED, 1.0F, true), "the cow did not become corrupted");
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(MobVariantManager.behaviourInstalled(cow), "the corrupted cow did not get its behaviour");
            manager.set(cow, MobVariant.NONE, 0.0F, false);
        });
        helper.runAfterDelay(6, () -> {
            helper.assertFalse(MobVariantManager.behaviourInstalled(cow), "the cow kept its variant behaviour after turning back");
            cow.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "mob_variant")
    public static void excludedTypesNeverBecomeVariants(GameTestHelper helper) {
        floor(helper);
        helper.assertTrue(MobVariantRules.isExcludedType(EntityType.VILLAGER), "villagers must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(EntityType.WANDERING_TRADER), "wandering traders must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(EntityType.IRON_GOLEM), "iron golems must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(EntityType.ELDER_GUARDIAN), "the elder guardian must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(EntityType.WITHER), "bosses must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(NullrootModule.NULLROOT.get()), "NULLROOT must be excluded");
        helper.assertTrue(MobVariantRules.isExcludedType(ItModule.IT.get()), "IT must be excluded");
        helper.assertFalse(MobVariantRules.isExcludedType(EntityType.COW), "cows must be able to become variants");
        helper.assertFalse(MobVariantRules.isExcludedType(EntityType.ZOMBIE), "zombies must be able to become variants");

        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        helper.assertFalse(MobVariantRules.canBecomeVariant(villager, false), "a villager may become a variant");
        server.getCommands().performPrefixedCommand(source, "null mob corrupted " + villager.getStringUUID());
        helper.assertValueEqual(MobVariantManager.variantOf(villager), MobVariant.NONE, "variant of a villager after /null mob");

        // By itself NULL leaves alone the mobs players care about; an operator may still change them.
        Cow named = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(7, 1, 3));
        named.setCustomName(Component.literal("Bessie"));
        helper.assertFalse(MobVariantRules.canBecomeVariant(named, true), "NULL may take a name-tagged cow by itself");
        helper.assertTrue(MobVariantRules.canBecomeVariant(named, false), "an operator cannot change a name-tagged cow");
        Wolf wolf = helper.spawnWithNoFreeWill(EntityType.WOLF, new BlockPos(11, 1, 3));
        wolf.setOwnerUUID(UUID.randomUUID());
        helper.assertFalse(MobVariantRules.canBecomeVariant(wolf, true), "NULL may take someone's wolf by itself");

        if (NullConfig.loaded()) {
            List<? extends String> before = NullConfig.MOB_DENYLIST.get();
            try {
                NullConfig.MOB_DENYLIST.set(List.of("minecraft:pig", "somemod:*"));
                helper.assertTrue(MobVariantRules.isExcludedType(EntityType.PIG), "the denylist does not exclude pigs");
                helper.assertFalse(MobVariantRules.isExcludedType(EntityType.COW), "the denylist excludes cows too");
            } finally {
                NullConfig.MOB_DENYLIST.set(before);
            }
        }
        villager.discard();
        named.discard();
        wolf.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "mob_variant_stage", timeoutTicks = 60)
    public static void movingTheStageBackTurnsVariantsBack(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        CorruptionStage original = CorruptionManager.stage(server);
        List<Mob> mobs = new ArrayList<>();
        try {
            CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
            Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(4, 1, 4));
            Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(11, 1, 4));
            Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(8, 1, 11));
            mobs.add(cow);
            mobs.add(husk);
            helper.assertTrue(manager.set(cow, MobVariant.CORRUPTED, 1.0F, false), "the cow did not become corrupted");
            helper.assertTrue(manager.set(husk, MobVariant.PARASITE, 0.2F, false), "the husk did not become a host");
            helper.assertTrue(manager.set(pig, MobVariant.PARASITE, 0.2F, false), "the pig did not become a host");
            // The pig is "unloaded" (saved and taken away) while the stage moves back.
            CompoundTag pigSave = pig.saveWithoutId(new CompoundTag());
            pig.discard();

            CorruptionManager.setStage(server, CorruptionStage.UNDER_MIDDLE);
            helper.assertValueEqual(MobVariantManager.variantOf(husk), MobVariant.NONE, "variant of a host after moving back to Stage 2");
            helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.CORRUPTED, "variant of a corrupted cow in Stage 2");

            Pig reloaded = EntityType.PIG.create(level);
            if (reloaded == null) {
                helper.fail("could not create a pig");
                return;
            }
            reloaded.load(pigSave);
            reloaded.setUUID(UUID.randomUUID());
            reloaded.moveTo(helper.absoluteVec(new Vec3(8.5, 1.0, 11.5)));
            helper.assertTrue(level.addFreshEntity(reloaded), "could not put the pig back");
            mobs.add(reloaded);
            helper.assertValueEqual(MobVariantManager.variantOf(reloaded), MobVariant.NONE,
                    "variant of a host that was unloaded while the stage moved back");

            CorruptionManager.setStage(server, CorruptionStage.START);
            helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.NONE, "variant of a corrupted cow in Stage 1");
        } finally {
            mobs.forEach(Mob::discard);
            CorruptionManager.setStage(server, original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "mob_variant_caps", timeoutTicks = 60)
    public static void spreadObeysTheCaps(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        MobVariantManager manager = MobVariantManager.INSTANCE;
        CorruptionStage original = CorruptionManager.stage(server);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        List<Mob> mobs = new ArrayList<>();
        try {
            CorruptionManager.setStage(server, CorruptionStage.MIDDLE);
            player.moveTo(helper.absoluteVec(new Vec3(8.5, 1.0, 8.5)));
            for (int i = 0; i < 9; i++) {
                mobs.add(helper.spawnWithNoFreeWill(EntityType.SHEEP, new BlockPos(2 + (i % 3) * 5, 1, 2 + (i / 3) * 5)));
            }
            Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(14, 1, 8));
            mobs.add(villager);

            int cap = MobVariantManager.areaCap(CorruptionStage.MIDDLE);
            for (int i = 0; i < 30; i++) {
                manager.spreadNear(player, CorruptionStage.MIDDLE, false);
            }
            int near = manager.countNear(level, player.getX(), player.getZ());
            helper.assertTrue(near <= cap, "the spread made " + near + " variants around one player, the cap is " + cap);
            boolean on = NullConfig.get(NullConfig.MOB_VARIANTS_ENABLED) && NullConfig.get(NullConfig.MOB_MAX_PER_PLAYER_AREA) > 0
                    && NullConfig.get(NullConfig.MOB_CORRUPTED_FREQUENCY) + NullConfig.get(NullConfig.MOB_PARASITE_FREQUENCY) > 0.0
                    && NullConfig.get(NullConfig.MOB_MAX_PER_WORLD) > manager.loadedCount() - near;
            if (on) {
                helper.assertTrue(near >= 1, "the spread made no variant out of nine sheep");
            }
            helper.assertValueEqual(MobVariantManager.variantOf(villager), MobVariant.NONE, "variant of a villager after the spread");

            if (NullConfig.loaded()) {
                int maxArea = NullConfig.MOB_MAX_PER_PLAYER_AREA.get();
                int maxWorld = NullConfig.MOB_MAX_PER_WORLD.get();
                try {
                    // A full world takes no more, however much room the area has.
                    NullConfig.MOB_MAX_PER_PLAYER_AREA.set(64);
                    NullConfig.MOB_MAX_PER_WORLD.set(manager.loadedCount());
                    helper.assertValueEqual(manager.room(player, CorruptionStage.MIDDLE, false), 0, "room left in a full world");
                    helper.assertTrue(manager.spreadNear(player, CorruptionStage.MIDDLE, false) == null, "the spread went past the world cap");
                } finally {
                    NullConfig.MOB_MAX_PER_PLAYER_AREA.set(maxArea);
                    NullConfig.MOB_MAX_PER_WORLD.set(maxWorld);
                }
            }
        } finally {
            mobs.forEach(Mob::discard);
            CorruptionManager.setStage(server, original);
            // A mock player left on the server would have events and spreads rolled for it during later tests.
            try {
                server.getPlayerList().remove(player);
            } catch (RuntimeException e) {
                NullMod.LOGGER.warn("Could not remove the mock player after a mob variant test", e);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "mob_variant")
    public static void commandTurnsAMob(GameTestHelper helper) {
        floor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(8, 1, 8));
        String uuid = cow.getStringUUID();

        server.getCommands().performPrefixedCommand(source, "null mob corrupted " + uuid);
        helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.CORRUPTED, "variant after /null mob corrupted");
        VariantData data = MobVariantManager.data(cow);
        helper.assertTrue(data != null && data.forced(), "a variant made by /null mob is not marked as an operator's");
        long seed = data.seed();

        server.getCommands().performPrefixedCommand(source, "null mob parasite " + uuid);
        helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.PARASITE, "variant after /null mob parasite");
        VariantData host = MobVariantManager.data(cow);
        helper.assertTrue(host != null && host.growth() >= 0.39F, "a parasite made by /null mob shows no tentacles yet");
        helper.assertValueEqual(host.seed(), seed, "the cow's look seed after changing its variant");

        server.getCommands().performPrefixedCommand(source, "null mob clear " + uuid);
        helper.assertValueEqual(MobVariantManager.variantOf(cow), MobVariant.NONE, "variant after /null mob clear");
        cow.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static boolean saved(Mob mob) {
        return attachment(mob.saveWithoutId(new CompoundTag()));
    }

    private static boolean attachment(CompoundTag tag) {
        return tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).contains(ATTACHMENT);
    }
}
