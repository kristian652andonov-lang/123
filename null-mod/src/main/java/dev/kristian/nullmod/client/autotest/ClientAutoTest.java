package dev.kristian.nullmod.client.autotest;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.client.ClientCorruptionState;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.field.FieldDimension;
import dev.kristian.nullmod.registry.NullDimensions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * A scripted client session used by CI to prove the client actually works: it waits for the
 * main menu, opens the settings, creates a world, walks it through the stages, forces every
 * random event, opens the inventory (blood graffiti), visits the NULL dimension, comes back,
 * leaves the world for the main menu (now at its most corrupted) and the world list, and quits —
 * taking screenshots along the way. Only active when the JVM is started with
 * {@code -Dnull.autotest=<anything>}; it does nothing in normal play.
 */
public final class ClientAutoTest {
    private static final String PROPERTY = "null.autotest";
    private static final String TAG = "NULL-AUTOTEST: ";
    private static final String WORLD = "null-autotest";

    private enum Phase { WAIT_MENU, RUN, WAIT_WORLD, WAIT_SERVER, WAIT_TITLE, DONE }

    private record Step(String name, int waitAfter, Runnable action) {
    }

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static Phase phase = Phase.WAIT_MENU;
    private static int countdown;
    private static int stableTicks;
    private static int failures;
    private static int totalTicks;
    private static int waitTicks;

    private ClientAutoTest() {
    }

    public static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    public static void init(IEventBus gameBus) {
        if (!enabled()) {
            return;
        }
        NullMod.LOGGER.info(TAG + "enabled ({})", System.getProperty(PROPERTY));
        if (multiplayer()) {
            phase = Phase.WAIT_SERVER;
        }
        gameBus.addListener(ClientAutoTest::onClientTick);
    }

    /** The multiplayer scenario joins a dedicated server (started with --quickPlayMultiplayer). */
    private static boolean multiplayer() {
        return "multiplayer".equals(System.getProperty(PROPERTY));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        totalTicks++;
        if (totalTicks > 20 * 60 * 20 && phase != Phase.DONE) {
            fail("timed out in phase " + phase);
            finish(mc);
            return;
        }
        switch (phase) {
            case WAIT_MENU -> {
                if (mc.getOverlay() == null && mc.screen != null && isMainMenu(mc.screen)) {
                    if (++stableTicks >= 100) {
                        stableTicks = 0;
                        log("main menu is " + mc.screen.getClass().getName());
                        queueMenuSteps();
                        phase = Phase.RUN;
                    }
                } else {
                    stableTicks = 0;
                }
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.screen == null && mc.getSingleplayerServer() != null) {
                    if (++stableTicks >= 200) {
                        stableTicks = 0;
                        log("world loaded in " + mc.level.dimension().location());
                        phase = Phase.RUN;
                    }
                } else {
                    stableTicks = 0;
                }
            }
            case WAIT_SERVER -> {
                if (mc.level != null && mc.player != null && mc.screen == null && mc.getConnection() != null
                        && !mc.isLocalServer()) {
                    if (++stableTicks >= 200) {
                        stableTicks = 0;
                        log("joined the dedicated server in " + mc.level.dimension().location());
                        queueMultiplayerSteps();
                        phase = Phase.RUN;
                    }
                } else {
                    stableTicks = 0;
                }
            }
            case WAIT_TITLE -> {
                // back on the main menu after leaving the world; give its faces time to come
                if (mc.level == null && mc.getOverlay() == null && mc.screen != null && isMainMenu(mc.screen)) {
                    if (++stableTicks >= 120) {
                        stableTicks = 0;
                        log("back on the main menu: " + mc.screen.getClass().getName());
                        phase = Phase.RUN;
                    }
                } else {
                    stableTicks = 0;
                    if (++waitTicks > 20 * 90) {
                        fail("never got back to the main menu after leaving the world");
                        finish(mc);
                    }
                }
            }
            case RUN -> runSteps(mc);
            case DONE -> {
            }
        }
    }

    private static boolean isMainMenu(Screen screen) {
        return screen instanceof TitleScreen || screen.getClass().getSimpleName().equals("CorruptedMainMenu");
    }

    private static void runSteps(Minecraft mc) {
        if (countdown > 0) {
            countdown--;
            return;
        }
        Step step = STEPS.poll();
        if (step == null) {
            finish(mc);
            return;
        }
        log(step.name());
        try {
            step.action().run();
        } catch (Throwable t) {
            fail(step.name() + " threw " + t);
            NullMod.LOGGER.error(TAG + "step failed", t);
        }
        countdown = step.waitAfter();
    }

    // ------------------------------------------------------------------ script

    private static void queueMenuSteps() {
        Minecraft mc = Minecraft.getInstance();
        STEPS.add(new Step("screenshot main menu", 10, () -> screenshot("01_main_menu")));
        STEPS.add(new Step("open NULL settings", 40, () -> mc.setScreen(settingsScreen(mc.screen))));
        STEPS.add(new Step("screenshot settings", 10, () -> screenshot("02_settings")));
        STEPS.add(new Step("close settings", 40, () -> {
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        }));
        STEPS.add(new Step("create world", 0, ClientAutoTest::createWorld));
        STEPS.add(new Step("world steps", 0, ClientAutoTest::queueWorldSteps));
    }

    private static void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(0x4E554C4CL, true, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, WorldPresets::createNormalWorldDimensions, mc.screen);
        phase = Phase.WAIT_WORLD;
    }

    private static void queueWorldSteps() {
        STEPS.add(new Step("screenshot stage 1", 20, () -> screenshot("03_stage1_day")));
        STEPS.add(new Step("status", 20, () -> command("null status")));
        for (CorruptionStage stage : CorruptionStage.values()) {
            if (stage == CorruptionStage.NULL) {
                break;
            }
            int number = stage.number();
            STEPS.add(new Step("set stage " + number, 60, () -> command("null stage " + number)));
            if (number == 2) {
                queueInventorySteps();
            }
            STEPS.add(new Step("night", 40, () -> command("time set 18000")));
            for (NullEventDefinition event : eventsStartingAt(stage)) {
                STEPS.add(new Step("event " + event.id(), 30, () -> command("null event " + event.id())));
            }
            STEPS.add(new Step("look up at the moon", 30, () -> look(-89.0F)));
            STEPS.add(new Step("screenshot stage " + number + " moon", 10, () -> screenshot("04_stage" + number + "_moon")));
            STEPS.add(new Step("look ahead", 60, () -> look(10.0F)));
            STEPS.add(new Step("screenshot stage " + number, 10, () -> screenshot("05_stage" + number)));
        }
        // Stage 4: a corrupted cow and a parasite host in front of the player.
        STEPS.add(new Step("day for the mobs", 20, () -> {
            command("time set 6000");
            look(0.0F);
        }));
        STEPS.add(new Step("summon mobs", 20, () -> {
            command("summon minecraft:cow ^-2 ^ ^6 {NoAI:1b}");
            command("summon minecraft:husk ^2 ^ ^6 {NoAI:1b}");
        }));
        STEPS.add(new Step("make them variants", 100, () -> {
            command("null mob corrupted @e[type=minecraft:cow,sort=nearest,limit=1]");
            command("null mob parasite @e[type=minecraft:husk,sort=nearest,limit=1]");
        }));
        STEPS.add(new Step("screenshot variant mobs", 10, () -> screenshot("05c_variant_mobs")));
        STEPS.add(new Step("remove the mobs", 10, () -> {
            command("kill @e[type=minecraft:cow]");
            command("kill @e[type=minecraft:husk]");
        }));
        STEPS.add(new Step("set stage 5", 20 * 20, () -> command("null stage 5")));
        STEPS.add(new Step("check NULL dimension", 20, () -> {
            Minecraft mc = Minecraft.getInstance();
            boolean inNull = mc.level != null && mc.level.dimension() == NullDimensions.NULL_LEVEL;
            if (!inNull) {
                fail("player is not in the NULL dimension after Stage 5 (in "
                        + (mc.level == null ? "nothing" : mc.level.dimension().location()) + ")");
            } else {
                log("arrived in the NULL dimension");
            }
        }));
        STEPS.add(new Step("screenshot NULL dimension", 10, () -> screenshot("06_null_dimension")));
        for (NullEventDefinition event : eventsStartingAt(CorruptionStage.NULL)) {
            STEPS.add(new Step("event " + event.id(), 30, () -> command("null event " + event.id())));
        }
        STEPS.add(new Step("look around", 40, () -> look(-20.0F)));
        STEPS.add(new Step("screenshot NULL sky", 10, () -> screenshot("07_null_sky")));
        STEPS.add(new Step("fill the player list", 60, () -> {
            for (int i = 0; i < 6; i++) {
                command("null event fake_player_join");
            }
        }));
        STEPS.add(new Step("hold tab", 4, () -> Minecraft.getInstance().options.keyPlayerList.setDown(true)));
        STEPS.add(new Step("screenshot player list", 4, () -> screenshot("08_player_list")));
        STEPS.add(new Step("release tab", 20, () -> Minecraft.getInstance().options.keyPlayerList.setDown(false)));
        queueHarvestSteps();
        STEPS.add(new Step("back to stage 1", 20 * 15, () -> command("null stage 1")));
        STEPS.add(new Step("check return", 20, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || NullDimensions.isNull(mc.level)) {
                fail("player was not returned from the NULL dimension");
            } else {
                log("returned to " + mc.level.dimension().location());
            }
        }));
        STEPS.add(new Step("screenshot returned", 10, () -> screenshot("09_returned")));
        STEPS.add(new Step("reset", 10, () -> {
            command("null reset");
            command("null reset");
        }));
        STEPS.add(new Step("final status", 40, () -> command("null status")));
        queueMainMenuAfterSteps();
    }

    /** Stage 6: the field by day, the cracking sun, the night countdown and IT's hunt (logged, never failed). */
    private static void queueHarvestSteps() {
        STEPS.add(new Step("set stage 6", 20 * 20, () -> command("null stage 6")));
        STEPS.add(new Step("check field", 10, () -> {
            Minecraft mc = Minecraft.getInstance();
            log("stage 6: now in " + (mc.level == null ? "no level" : mc.level.dimension().location())
                    + (mc.level != null && FieldDimension.isField(mc.level) ? " (the field)" : " (NOT the field)"));
        }));
        STEPS.add(new Step("look at the sun", 20, () -> look(-8.0F)));
        STEPS.add(new Step("screenshot field", 10, () -> screenshot("13_field_day")));
        STEPS.add(new Step("sun cracking", 60, () -> command("null field cracking")));
        STEPS.add(new Step("screenshot sun cracking", 10, () -> screenshot("14_field_sun_cracking")));
        STEPS.add(new Step("night", 100, () -> command("null field night")));
        STEPS.add(new Step("look ahead in the dark", 10, () -> look(0.0F)));
        STEPS.add(new Step("screenshot countdown", 10, () -> screenshot("15_field_countdown")));
        STEPS.add(new Step("survival for IT", 10, () -> command("gamemode survival")));
        STEPS.add(new Step("hunt", 60, () -> command("null field hunt")));
        STEPS.add(new Step("screenshot IT coming", 60, () -> screenshot("16_it_coming")));
        STEPS.add(new Step("screenshot IT close", 60, () -> screenshot("17_it_close")));
        STEPS.add(new Step("screenshot IT kill", 120, () -> screenshot("18_it_kill")));
        STEPS.add(new Step("respawn", 60, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.isDeadOrDying()) {
                log("IT killed the player");
                mc.player.respawn();
                mc.setScreen(null);
            } else {
                log("the player survived the hunt");
            }
        }));
        STEPS.add(new Step("creative after the hunt", 40, () -> command("gamemode creative")));
        STEPS.add(new Step("screenshot field after", 10, () -> screenshot("19_field_after")));
    }

    /** The player's own inventory (survival, a few items) with the blood graffiti on its panel. */
    private static void queueInventorySteps() {
        STEPS.add(new Step("survival for the inventory", 20, () -> command("gamemode survival")));
        STEPS.add(new Step("a few items", 20, () -> {
            command("give @s minecraft:bread 12");
            command("give @s minecraft:torch 40");
            command("give @s minecraft:iron_pickaxe");
            command("give @s minecraft:oak_log 23");
        }));
        STEPS.add(new Step("open inventory", 30, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.setScreen(new InventoryScreen(mc.player));
            }
        }));
        STEPS.add(new Step("screenshot inventory", 10, () -> screenshot("05b_inventory")));
        STEPS.add(new Step("close inventory", 10, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.onClose();
            }
        }));
        STEPS.add(new Step("creative again", 20, () -> {
            command("clear @s");
            command("gamemode creative");
        }));
    }

    /** Back to the title screen (the menu now remembers Stage 5 or more), then the world list. */
    private static void queueMainMenuAfterSteps() {
        STEPS.add(new Step("leave the world", 0, ClientAutoTest::leaveWorld));
        STEPS.add(new Step("screenshot main menu after", 60, () -> screenshot("10_main_menu_after")));
        STEPS.add(new Step("screenshot main menu after 2", 10, () -> screenshot("11_main_menu_after_2")));
        STEPS.add(new Step("open select world", 60, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new SelectWorldScreen(mc.screen));
        }));
        STEPS.add(new Step("screenshot select world", 10, () -> screenshot("12_select_world")));
    }

    /**
     * Leaves the world the way the pause menu's "Save and Quit to Title" does. It runs as a task
     * of the client's main loop, outside the tick that runs the steps; the WAIT_TITLE phase then
     * waits until the main menu has been showing for about six seconds.
     */
    private static void leaveWorld() {
        Minecraft mc = Minecraft.getInstance();
        phase = Phase.WAIT_TITLE;
        stableTicks = 0;
        waitTicks = 0;
        mc.tell(() -> {
            if (mc.level != null) {
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local) {
                    mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                } else {
                    mc.disconnect();
                }
            }
            mc.setScreen(new TitleScreen());
        });
    }

    private static void queueMultiplayerSteps() {
        STEPS.add(new Step("screenshot joined", 20, () -> screenshot("mp_01_joined")));
        STEPS.add(new Step("check stage sync", 10, () -> {
            if (!ClientCorruptionState.synced()) {
                fail("the server never synced the corruption state to this client");
            } else {
                log("synced: stage " + ClientCorruptionState.stage().number());
            }
        }));
        for (CorruptionStage stage : CorruptionStage.values()) {
            if (stage == CorruptionStage.NULL) {
                break;
            }
            int number = stage.number();
            STEPS.add(new Step("set stage " + number, 80, () -> command("null stage " + number)));
            STEPS.add(new Step("check stage " + number + " arrived", 10, () -> {
                if (ClientCorruptionState.stage() != stage) {
                    fail("client shows stage " + ClientCorruptionState.stage().number() + " instead of " + number);
                }
            }));
            for (NullEventDefinition event : eventsStartingAt(stage)) {
                STEPS.add(new Step("event " + event.id(), 30, () -> command("null event " + event.id())));
            }
            STEPS.add(new Step("screenshot stage " + number, 10, () -> screenshot("mp_02_stage" + number)));
        }
        STEPS.add(new Step("set stage 5", 20 * 22, () -> command("null stage 5")));
        STEPS.add(new Step("check NULL dimension", 20, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.level.dimension() != NullDimensions.NULL_LEVEL) {
                fail("not moved into the NULL dimension on the dedicated server");
            } else {
                log("arrived in the NULL dimension on the dedicated server");
            }
        }));
        STEPS.add(new Step("screenshot NULL", 10, () -> screenshot("mp_03_null")));
        for (NullEventDefinition event : eventsStartingAt(CorruptionStage.NULL)) {
            STEPS.add(new Step("event " + event.id(), 30, () -> command("null event " + event.id())));
        }
        STEPS.add(new Step("hold tab", 4, () -> Minecraft.getInstance().options.keyPlayerList.setDown(true)));
        STEPS.add(new Step("screenshot player list", 4, () -> screenshot("mp_04_player_list")));
        STEPS.add(new Step("release tab", 20, () -> Minecraft.getInstance().options.keyPlayerList.setDown(false)));
        STEPS.add(new Step("back to stage 1", 20 * 15, () -> command("null stage 1")));
        STEPS.add(new Step("check return", 20, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || NullDimensions.isNull(mc.level)) {
                fail("not returned from the NULL dimension on the dedicated server");
            }
        }));
        STEPS.add(new Step("reset", 40, () -> {
            command("null reset");
            command("null reset");
        }));
        STEPS.add(new Step("stop the server", 20 * 10, () -> command("stop")));
    }

    /** NULL's settings screen, looked up reflectively so the harness never blocks a build. */
    private static Screen settingsScreen(Screen parent) {
        try {
            return (Screen) Class.forName("dev.kristian.nullmod.client.settings.NullSettingsScreen")
                    .getConstructor(Screen.class).newInstance(parent);
        } catch (ReflectiveOperationException e) {
            fail("NullSettingsScreen is missing: " + e);
            return parent;
        }
    }

    private static List<NullEventDefinition> eventsStartingAt(CorruptionStage stage) {
        List<NullEventDefinition> list = new ArrayList<>();
        for (NullEventDefinition event : NullEventRegistry.all()) {
            if (event.minStage() == stage) {
                list.add(event);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static void command(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            fail("no player for command '" + command + "'");
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            // Dedicated server: the test player was made an operator by ServerAutoTest.
            mc.player.connection.sendCommand(command);
            return;
        }
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            CommandSourceStack source = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
                    .withPermission(4);
            server.getCommands().performPrefixedCommand(source, command);
        });
    }

    private static void look(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
    }

    private static void screenshot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                message -> log("screenshot " + name + ": " + message.getString()));
    }

    private static void log(String message) {
        NullMod.LOGGER.info(TAG + message);
    }

    private static void fail(String message) {
        failures++;
        NullMod.LOGGER.error("NULL-AUTOTEST-FAIL: " + message);
    }

    private static void finish(Minecraft mc) {
        phase = Phase.DONE;
        NullMod.LOGGER.info(TAG + "DONE with {} failure(s)", failures);
        mc.stop();
    }
}
