package dev.kristian.nullmod.chat;

import dev.kristian.nullmod.chat.ChatLine.Category;
import dev.kristian.nullmod.chat.ChatLine.Speaker;
import dev.kristian.nullmod.core.CorruptionStage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The curated database of corrupted chat. Every line is written to look like something a real
 * person (or the game itself) could have typed: mostly lowercase, little punctuation, no
 * explanations. Stage ranges keep Stage 2 subtle and leave the openly wrong lines for later.
 * <p>
 * Besides single lines there are short scripted {@link Dialogue dialogues} between two or three
 * usernames, which play out over a few seconds.
 */
public final class ChatMessages {
    /** The exact example lines from the design document; all of them are in the database. */
    public static final List<String> SPEC_EXAMPLES = List.of("are you still there", "dont look behind you",
            "why are you still playing", "connection lost", "I remember this place", "WORLD NOT FOUND", "help me",
            "YOU SHOULD NOT BE HERE", "its not supposed to look like this", "can you see them too?");

    private static final List<ChatLine> ALL;
    private static final Map<Category, List<ChatLine>> BY_CATEGORY;
    private static final List<Dialogue> DIALOGUES;

    /**
     * A short scripted exchange. Roles {@code A}, {@code B} and {@code C} are usernames picked
     * when it plays, {@code U} is Unknown, {@code N} is NULL, {@code L} a grey log line. A step
     * {@code J:A} shows "A joined the game", {@code Q:A} "A left the game".
     */
    public record Dialogue(int index, int minStage, int maxStage, List<Step> steps, boolean personal) {
        public Dialogue {
            steps = List.copyOf(steps);
        }

        public boolean allowedAt(CorruptionStage stage) {
            return stage.number() >= minStage && stage.number() <= maxStage;
        }
    }

    /** One step of a {@link Dialogue}: who speaks ({@code role}) and what. */
    public record Step(char role, String text) {
        public boolean isJoinOrLeave() {
            return role == 'J' || role == 'Q';
        }
    }

    static {
        Builder b = new Builder();
        questions(b);
        whispers(b);
        warnings(b);
        nullSystem(b);
        connection(b);
        names(b);
        stageFive(b);
        bursts(b);
        joinHello(b);
        advancements(b);
        memories(b);
        replies(b);
        reactions(b);
        ALL = Collections.unmodifiableList(b.lines);

        Map<Category, List<ChatLine>> byCategory = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            List<ChatLine> list = new ArrayList<>();
            for (ChatLine line : ALL) {
                if (line.in(category)) {
                    list.add(line);
                }
            }
            byCategory.put(category, Collections.unmodifiableList(list));
        }
        BY_CATEGORY = Collections.unmodifiableMap(byCategory);
        DIALOGUES = buildDialogues();
    }

    private ChatMessages() {
    }

    public static List<ChatLine> all() {
        return ALL;
    }

    public static int size() {
        return ALL.size();
    }

    public static ChatLine get(int index) {
        return ALL.get(index);
    }

    /** Every line usable for a category (its main category or one of its extra ones). */
    public static List<ChatLine> of(Category category) {
        return BY_CATEGORY.get(category);
    }

    public static List<Dialogue> dialogues() {
        return DIALOGUES;
    }

    // ================================================================== ambient chatter

    private static void questions(Builder b) {
        b.cat(Category.QUESTION).stages(2, 5);
        b.user("is anyone else on");
        b.user("hello?").also(Category.STILL, Category.JOIN_HELLO);
        b.user("is anyone there");
        b.user("did anyone else hear that");
        b.user("who just joined");
        b.user("is this server still up");
        b.user("why is it so quiet");
        b.user("anyone want to trade");
        b.user("where is spawn");
        b.user("whats the seed again");
        b.user("does anyone know what day it is");
        b.user("is it just me or is it darker");
        b.user("did someone move my chest");
        b.user("who keeps turning off the lights");
        b.user("hey");
        b.user("where did everyone go");
        b.user("how long have you been here");
        b.user("did the trees always look like that");
        b.user("why cant i hear the music anymore");
        b.user("has anyone been past the border");
        b.user("whos there");
        b.user("can anyone hear me");
        b.user("is my chat working");
        b.user("what was that");
        b.user("did the world just skip");
        b.user("did you see that");
        b.user("is there an admin online");
        b.user("is anyone else lagging");
        b.user("are we the only ones here");
        b.user("has anyone seen {name} today");
        b.user("what are you building {player}");
        b.stages(2, 4);
        b.user("who built the house by the river");
        b.user("has anyone seen my dog");
        b.user("who left the torch in the cave");
        b.stages(3, 5);
        b.user("are you real");
        b.user("why are you still playing");
        b.user("can you see them too?");
        b.user("do you remember when this was normal");
        b.user("what is that in the sky");
        b.user("is the ground supposed to make that sound");
        b.user("why did it get cold");
        b.user("why does my compass spin");
        b.user("is someone standing behind me");
        b.user("what happens if you go to sleep here");
        b.user("do you ever feel watched");
        b.user("why is nobody answering");
        b.user("who are you talking to");
        b.user("who is {name}");
        b.user("is it {time} for you too");
        b.user("is it really day {day}");
        b.user("do you want to see something");
        b.user("did you lock the door");
        b.user("what happened to the villagers");
        b.user("are you awake");
        b.unknown("who are you");
        b.voice("{player} are you there");
        b.stages(4, 5);
        b.user("when did the sun get so small");
        b.user("how do you get out of here");
        b.user("do you know what NULL is");
        b.user("who is that standing on the hill");
        b.user("why do you keep looking back");
        b.user("can you hear it breathing");
        b.user("what did you do");
        b.user("is this still minecraft");
        b.user("how many of us are left");
        b.user("can you come back");
        b.user("why are you still in the {biome}");
        b.user("its {clock}. why are you still playing");
        b.unknown("do you remember me");
        b.unknown("why did you come back");
    }

    private static void whispers(Builder b) {
        b.cat(Category.WHISPER).stages(2, 5);
        b.user("its so quiet");
        b.user("i dont like it here");
        b.user("something is wrong with the sky");
        b.user("i keep hearing footsteps");
        b.user("lag");
        b.user("brb");
        b.user("i think i saw someone");
        b.user("never mind");
        b.user("it was probably nothing");
        b.user("this chunk feels wrong");
        b.user("I remember this place");
        b.user("the water is too still");
        b.user("i cant find my base");
        b.user("the music sounds wrong");
        b.user("im so tired");
        b.user("dont mind me");
        b.user("i miss the old world");
        b.stages(2, 4);
        b.user("the trees were never this tall");
        b.user("the sheep are watching me");
        b.user("i hate this seed");
        b.stages(3, 5);
        b.user("its not supposed to look like this");
        b.user("help me").also(Category.BURST, Category.REPLY_FEAR);
        b.user("i think its following me");
        b.user("the clouds stopped moving");
        b.user("i can hear it under the floor");
        b.user("something keeps breaking my torches");
        b.user("my map is blank");
        b.user("my things are gone");
        b.user("everything is too far away");
        b.user("i dont think we are alone");
        b.user("im still here");
        b.user("the ground was warm");
        b.user("the blocks are humming");
        b.user("the sun came up wrong today");
        b.user("i stopped counting the days");
        b.user("nothing loads past the hill");
        b.user("i keep falling through the floor");
        b.user("i hear typing");
        b.user("just ignore it");
        b.user("i shouldnt have come back");
        b.user("i think the world is sick");
        b.user("the textures are bleeding");
        b.user("i dont remember building this");
        b.user("it feels like someone else is playing");
        b.user("everything i place disappears");
        b.user("i keep waking up in the same place");
        b.user("i can still hear the music from the old version");
        b.user("the night lasted too long");
        b.user("i dug down and there was a room");
        b.user("something answered when i typed");
        b.user("{name} hasnt moved in hours");
        b.user("it was {clock} the last time it happened too");
        b.unknown("i was here first");
        b.unknown("this was my world");
        b.stages(4, 5);
        b.user("it knows we are here");
        b.user("it was here before us");
        b.user("i left the game three days ago");
        b.user("this isnt my world");
        b.user("i was never here");
        b.user("someone is using my name");
        b.user("it copied my house");
        b.user("im not alone in my house");
        b.user("i see it every time i turn around");
        b.user("it stands where the light doesnt reach");
        b.user("the world is getting smaller");
        b.user("it types when we type");
        b.user("it said my name");
        b.user("its in the walls");
        b.user("its getting closer");
        b.user("it doesnt want us to leave");
        b.user("the world is forgetting itself");
        b.user("every chunk i load is emptier");
        b.user("i can see the void through the floor");
        b.user("i hear it when i close the game");
        b.user("it was here when i logged in");
        b.user("the world was bigger yesterday");
        b.user("my character moved on its own");
        b.user("something walks when you walk");
        b.user("{name} is not who you think");
        b.unknown("i can see you");
        b.unknown("you look tired");
    }

    private static void warnings(Builder b) {
        b.cat(Category.WARNING).stages(2, 5);
        b.user("dont dig down");
        b.stages(3, 5);
        b.user("dont look behind you");
        b.user("dont go into the caves tonight");
        b.user("dont sleep here");
        b.user("dont trust the sky");
        b.user("dont answer it");
        b.user("dont go outside");
        b.user("stay in the light");
        b.user("close the door");
        b.user("dont follow the footsteps");
        b.user("stay away from the black blocks");
        b.user("dont break the purple ones");
        b.user("dont go near the trees");
        b.user("dont listen to the music");
        b.user("keep your back to a wall");
        b.user("dont stand still");
        b.user("stay on the surface");
        b.user("if you hear knocking dont open");
        b.user("dont wake it up");
        b.user("stop digging");
        b.user("dont touch the roots");
        b.user("dont trust anyone named {name}");
        b.voice("dont go below {y}");
        b.voice("dont go to sleep {player}").also(Category.SLEEP);
        b.stages(3, 4);
        b.user("dont let the moon see you");
        b.stages(4, 5);
        b.user("dont look at it");
        b.user("dont stop moving");
        b.user("turn around");
        b.user("run").also(Category.BURST);
        b.user("leave while you still can");
        b.user("stop playing");
        b.user("log off");
        b.user("dont say its name");
        b.user("dont type anything");
        b.user("dont check your inventory");
        b.user("dont let it see you");
        b.user("dont go back to your base");
        b.user("its behind the trees");
        b.user("its under you");
        b.user("it can hear you");
        b.user("do not look up");
        b.user("dont trust the chat");
        b.user("dont trust me");
        b.user("do not go into the corridor");
        b.voice("{player} dont go home");
        b.voice("{player} move");
        b.voice("{player} its right behind you");
        b.voice("its watching you from the {biome}");
        b.voice("dont dig at {x} {z}");
        b.voice("something followed you into the {dimension}");
        b.unknown("you should leave");
    }

    private static void nullSystem(Builder b) {
        b.cat(Category.NULL_SYSTEM).stages(3, 5);
        b.nul("WORLD NOT FOUND");
        b.nul("YOU SHOULD NOT BE HERE").also(Category.NULL_ARRIVAL);
        b.nul("CHUNK DATA MISSING");
        b.nul("PLAYER NOT RESPONDING");
        b.nul("SAVE FILE CORRUPTED");
        b.nul("THIS WORLD IS NO LONGER SUPPORTED");
        b.nul("REALITY CHECK FAILED");
        b.nul("LEVEL.DAT NOT FOUND");
        b.nul("SKY TEXTURE UNAVAILABLE");
        b.nul("ENTITY LIMIT EXCEEDED");
        b.nul("UNKNOWN ENTITY IN RENDER DISTANCE");
        b.nul("CONNECTION UNSTABLE");
        b.nul("REGION FILE DAMAGED");
        b.nul("MEMORY LEAK DETECTED");
        b.nul("TIME IS NOT ADVANCING");
        b.nul("ERROR 0x00000000");
        b.nul("TEXTURES COULD NOT BE LOADED");
        b.nul("SEED NOT FOUND");
        b.nul("DIMENSION NOT FOUND");
        b.nul("INTEGRITY FAILING");
        b.stages(4, 5);
        b.nul("DO NOT DISCONNECT");
        b.nul("WE CAN SEE YOU");
        b.nul("RESTORING WORLD FROM BACKUP");
        b.nul("NULL");
        b.nul("PLEASE STAND STILL");
        b.nul("FILE IN USE BY ANOTHER PLAYER");
        b.nul("RENDER DISTANCE: 0");
        b.nul("SESSION RESUMED");
        b.nul("ATTEMPTING TO RECONNECT");
        b.nul("WAKE UP");
        b.nul("RETURN TO SPAWN");
        b.nul("OBSERVATION LOG UPDATED");
        b.nul("{player} IS NOT RESPONDING");
        b.nul("{player} HAS BEEN FOUND");
        b.nul("LOCATING {player}");
        b.nul("POSITION {x} {y} {z} CONFIRMED");
        b.nul("{player} DETECTED");
        b.nul("PLAYERDATA FOR {player} IS EMPTY");
        b.nul("{dimension} IS NOT RESPONDING");
        b.stages(5, 5);
        b.nul("THERE IS NO SERVER");
        b.nul("YOU ARE THE LAST ONE");
        b.nul("ALL PLAYERS HAVE BEEN SAVED");
        b.nul("DO NOT TRUST THE OTHER PLAYERS");
        b.nul("YOUR WORLD WAS DELETED");
        b.nul("GOODBYE");
        b.nul("YOU WERE NEVER HERE");
        b.nul("THERE ARE NO OTHER PLAYERS");
        b.nul("{online} PLAYERS ONLINE. 1 PLAYER REAL");
    }

    private static void connection(Builder b) {
        b.cat(Category.CONNECTION).stages(2, 5);
        b.user("connection lost");
        b.user("lagging so bad rn");
        b.user("did the server just restart");
        b.user("i keep timing out");
        b.user("rubberbanding everywhere");
        b.user("tps is dropping");
        b.log("Can't keep up! Is the server overloaded? Running 2047ms or 40 ticks behind");
        b.log("Saving the game (this may take a moment!)");
        b.log("Saved the game");
        b.log("Saving chunks for level 'ServerLevel[world]'/minecraft:overworld");
        b.stages(3, 5);
        b.user("my ping is 0");
        b.user("i got kicked for flying but i wasnt flying");
        b.log("Can't keep up! Is the server overloaded? Running 9183ms or 183 ticks behind");
        b.log("Lost connection to server");
        b.log("Reconnecting...");
        b.log("Internal Exception: java.io.IOException: Connection reset by peer");
        b.log("Disconnected");
        b.log("Timed out");
        b.log("Moved too quickly!");
        b.log("{player} moved wrongly!");
        b.log("Failed to load chunk at {x}, {z}");
        b.log("Region file is truncated");
        b.log("Chunk file at [0, 0] is missing level data, skipping");
        b.log("{name} lost connection: Disconnected");
        b.log("{name} lost connection: Timed out");
        b.log("Fetching addPacket for removed entity");
        b.log("Preparing level \"world\"");
        b.log("Loaded 0 advancements");
        b.log("Mismatch in destroy block pos");
        b.log("Attempted to load a chunk that does not exist");
        b.log("Saving players");
        b.error("An existing connection was forcibly closed by the remote host");
        b.error("Unknown or incomplete command, see below for error");
        b.stages(4, 5);
        b.user("why does it say i joined twice");
        b.log("UUID of player {name} is 00000000-0000-0000-0000-000000000000");
        b.log("Keeping entity minecraft:player that already exists with UUID 00000000-0000-0000-0000-000000000000");
        b.log("Stopping server");
        b.log("There are 0 of a max of 20 players online");
        b.log("Unknown entity type at {x} {y} {z}");
        b.log("Skipping Entity with id minecraft:player");
        b.log("Player {name} logged in with entity id -1 at (0.5, -64.0, 0.5)");
        b.error("Failed to verify username!");
        b.error("You logged in from another location");
        b.error("Invalid session (Try restarting your game)");
        b.error("No player was found");
        b.error("Failed to save world");
        b.error("Chunk at {x}, {z} could not be generated");
    }

    private static void names(Builder b) {
        b.cat(Category.NAME).stages(4, 5);
        b.voice("{player}");
        b.voice("{player}.");
        b.voice("{player}?").also(Category.STILL);
        b.voice("{player} {player} {player}");
        b.voice("i know your name {player}");
        b.voice("hello {player}");
        b.voice("{player} look up");
        b.voice("{player} can you hear me");
        b.voice("{player} why are you ignoring me");
        b.voice("we have been waiting for you {player}");
        b.voice("{player} come back");
        b.voice("{player} is that you");
        b.voice("its {player}");
        b.voice("{player} wake up");
        b.voice("you look different {player}");
        b.voice("i can see you {player}");
        b.voice("{player} stop");
        b.voice("goodnight {player}").also(Category.SLEEP);
        b.voice("{player} you left the door open");
        b.voice("{player} has been here before");
        b.voice("found you {player}");
        b.voice("{player} is not alone");
        b.voice("its your turn {player}");
        b.voice("dont be scared {player}");
        b.voice("{player} you dropped something");
        b.voice("{player} we saved you a spot");
        b.voice("say something {player}");
        b.voice("{player} answer me");
        b.voice("{player} {x} {y} {z}");
        b.voice("{player} why are you at {x} {z}");
    }

    private static void stageFive(Builder b) {
        b.cat(Category.STAGE5).stages(5, 5);
        b.user("there are so many of us now");
        b.user("who are all these people");
        b.user("everyone is here");
        b.user("its so loud");
        b.user("we are all still here");
        b.user("dont leave");
        b.user("theres no exit");
        b.user("there were only two of us yesterday");
        b.user("the list keeps growing");
        b.user("i cant see my own name");
        b.user("everyone is typing");
        b.user("all of us were real once");
        b.user("we live here now");
        b.user("it never ends");
        b.user("is anyone here a real player");
        b.user("nobody leaves");
        b.user("say your name so we know you are real");
        b.user("how many players are online");
        b.user("{online} players online");
        b.user("i joined to find my brother");
        b.user("the server is full");
        b.user("someone is wearing my skin");
        b.user("we all have the same face");
        b.user("it is better here");
        b.user("i dont want to go back");
        b.user("you will stay too");
        b.user("you are one of us now");
        b.user("i stopped trying to log off");
        b.user("its so crowded");
        b.user("everyone is staring at you");
        b.user("this is everyones world now");
        b.user("the chat never stops");
        b.user("can you hear all of us");
        b.user("which one of us is real");
        b.user("my name used to be different");
        b.unknown("we were waiting for you");
        b.unknown("there is room for one more");
    }

    private static void bursts(Builder b) {
        b.cat(Category.BURST).stages(4, 5);
        b.user("help");
        b.user("stop");
        b.user("its here");
        b.user("no").also(Category.REPLY_QUESTION);
        b.user("please");
        b.user("where");
        b.user("behind");
        b.user("look");
        b.user("dont");
        b.user("why");
        b.user("wait");
        b.user("it hurts");
        b.user("go");
        b.user("now");
        b.user("hide");
        b.user("i see it");
        b.user("its coming");
        b.user("too late");
        b.user("LISTEN");
        b.user("quiet");
        b.user("dont look");
        b.user("they know");
        b.user("its me");
        b.user("let me out");
        b.user("open the door");
        b.user("up");
        b.user("it sees us");
    }

    private static void joinHello(Builder b) {
        b.cat(Category.JOIN_HELLO).stages(2, 5);
        b.user("where is everyone");
        b.user("what happened to spawn");
        b.user("why is it so dark");
        b.user("is this the right server");
        b.user("i havent played in years");
        b.user("it looks different");
        b.user("finally");
        b.user("hey guys");
        b.user("who changed the sky");
        b.user("i was here before");
        b.user("what year is it");
        b.user("my house is gone");
        b.user("its been so long");
        b.user("i heard there was someone here");
    }

    private static void advancements(Builder b) {
        b.cat(Category.ADVANCEMENT).stages(4, 5).speaker(Speaker.ADVANCEMENT);
        b.line("Are You Still There?");
        b.line("Not Alone");
        b.line("It Remembers");
        b.line("No Way Back");
        b.line("Something Is Watching");
        b.line("Last One Here");
        b.line("Unreachable");
        b.line("Look Behind You");
        b.line("The World Forgot");
        b.line("Nobody Logged Off");
    }

    // ================================================================== memories

    private static void memories(Builder b) {
        b.cat(Category.MEMORY_MOON).stages(3, 5);
        b.user("did you see the moon tonight");
        b.user("the moon has a crack in it");
        b.user("the moon was never round");
        b.user("i heard the moon break");
        b.user("a piece of the moon fell into the ocean");
        b.user("the moon is watching less every night");
        b.user("dont look at the moon too long");
        b.user("the moon looks wrong");
        b.user("what happened to the moon");
        b.user("the moon is leaking");
        b.user("something is coming out of the moon");
        b.user("the moon flickered");
        b.user("the crack is getting bigger");
        b.user("was the moon always this close");
        b.user("i counted the pieces of the moon. one is missing");

        b.cat(Category.MEMORY_MOON_GONE).stages(4, 5);
        b.user("where did the moon go");
        b.user("the moon is gone");
        b.user("they took the moon");
        b.user("the nights are so dark now");
        b.user("nothing is holding up the night anymore");
        b.user("i miss the moon");
        b.user("the moon didnt fall. it left");
        b.user("did anyone see where the pieces went");
        b.user("how do the nights end without a moon");
        b.nul("THE MOON HAS BEEN REMOVED");

        b.cat(Category.MEMORY_VISITOR).stages(4, 5);
        b.user("did you see {visitor}");
        b.user("{visitor} was here");
        b.user("{visitor} is watching you");
        b.user("{visitor} says hello");
        b.user("why was {visitor} just standing there");
        b.user("{visitor} never says anything");
        b.user("{visitor} doesnt usually come out this far");
        b.user("dont look at {visitor}");
        b.user("{visitor} likes you");
        b.user("{visitor} has been following you for a while");
        b.user("{visitor} was there before you looked");
        b.user("{visitor} isnt a player");
        b.user("i saw {visitor} too");
        b.user("{visitor} left when you looked");
        b.user("who is {visitor}");
        b.user("{visitor} was in my base yesterday");
        b.user("i used to play with {visitor}");
        b.user("{visitor} is still standing there");
        b.user("{visitor} told me about you");
        b.user("you saw {visitor} didnt you");
        b.unknown("{visitor} is mine");

        b.cat(Category.MEMORY_FAKE_PLAYER).stages(5, 5);
        b.user("welcome {visitor}");
        b.user("{visitor} is back");
        b.user("{visitor} left again");
        b.user("{visitor} never really leaves");
        b.user("why did {visitor} join");
        b.user("{visitor} has no face");
        b.user("wb {visitor}");
        b.user("{visitor} doesnt talk");
        b.user("bye {visitor}");
        b.user("{visitor} logged in from your house");

        b.cat(Category.MEMORY_NULLROOT).stages(3, 5);
        b.user("it had you");
        b.user("you got away this time");
        b.user("it remembers you now");
        b.user("the roots are still moving");
        b.user("did it hurt");
        b.user("it pulled me under too");
        b.user("you shouldnt have touched the black ground");
        b.user("next time it wont let go");
        b.user("it grows where you walk");
        b.user("the ground is breathing");
        b.user("something is growing under your base");
        b.user("i heard it grab you");
        b.user("the roots know your name");
        b.user("it let you go on purpose");
        b.user("there is more of it every night");
        b.topic("killed");
        b.user("it finished what it started");
        b.user("it got you");
        b.user("you were too slow");

        b.cat(Category.MEMORY_STRUCTURE).stages(2, 5);
        b.user("who built that");
        b.user("that wasnt there yesterday");
        b.user("did you see the new building");
        b.user("someone is building at night");
        b.user("nobody built that");
        b.stages(4, 5);
        b.user("the structures are getting closer");
        b.user("i went inside. dont go inside");
        b.topic("floating_house");
        b.user("whose house is floating in the sky");
        b.user("the house up there has lights on");
        b.user("someone lives in the floating house");
        b.topic("village");
        b.user("that village was abandoned before you were born");
        b.user("the villagers in the old village dont move");
        b.user("i found a village nobody lives in");
        b.user("the bells in the old village still ring");
        b.topic("sky_bedrock");
        b.user("who put bedrock in the sky");
        b.user("the bedrock up there is getting lower");
        b.user("something is standing on the bedrock in the sky");
        b.topic("strange_sign").stages(3, 5);
        b.user("did you read the sign");
        b.user("who writes the signs");
        b.user("the sign changed when i looked away");
        b.topic("endless_corridor").stages(4, 5);
        b.user("the corridor doesnt end");
        b.user("i walked down the corridor for an hour");
        b.user("there is a door at the end of the corridor");
        b.topic("impossible_room");
        b.user("there is a room under the ground");
        b.user("who builds rooms under the ground");
        b.user("the room had no door");
        b.topic("wrong_building");
        b.user("that building is built wrong");
        b.user("the stairs go nowhere");
        b.user("the house has too many floors");
        b.topic("distant_structure").stages(2, 5);
        b.user("there is a tower that wasnt there");
        b.user("the old tower is back");
        b.user("something is standing in the distance");
        b.topic("old_terrain");
        b.user("the old land is coming back");
        b.user("the ground is from an old version");
        b.topic("floating_fragment");
        b.user("theres a piece of land in the air");
        b.user("the floating island wasnt there before");
        b.topic("duplicated_terrain");
        b.user("i saw the same hill twice");
        b.user("that mountain is a copy");
        b.topic("wrong_biome");
        b.user("the biomes are mixing");
        b.user("the grass is the wrong color here");
        b.voice("the {biome} is in the wrong place");

        b.cat(Category.MEMORY_SKY).stages(2, 5);
        b.user("did the sky just blink");
        b.user("the sky did it again");
        b.user("the sky changed color");
        b.user("the sky turned off for a second");
        b.user("did you see the sky flicker");
        b.user("the stars moved");
        b.stages(3, 5);
        b.user("the sky is a texture");
        b.user("the sky looked like a wall");

        b.cat(Category.MEMORY_CHUNKS).stages(2, 5);
        b.user("the chunks are flickering");
        b.user("the ground is missing again");
        b.user("did that block just turn black");
        b.user("a whole hill disappeared");
        b.user("i can see through the ground");
        b.user("the blocks keep disappearing");
        b.user("the world isnt loading right");
        b.user("purple and black everywhere");
        b.user("my house blinked");
        b.user("the textures are missing again");

        b.cat(Category.MEMORY_BLACKOUT).stages(2, 5);
        b.user("the lights went out");
        b.user("did your screen go black too");
        b.user("i couldnt see anything");
        b.user("it froze for me too");
        b.user("something moved in the dark");
        b.user("how long was it dark for you");

        b.cat(Category.MEMORY_DISCONNECT).stages(4, 5);
        b.user("you didnt really disconnect");
        b.user("welcome back");
        b.user("you never left");
        b.user("did you get kicked too");
        b.user("the server didnt go down");

        b.cat(Category.MEMORY_SILHOUETTE).stages(2, 5);
        b.user("someone was standing on that hill");
        b.user("did you see the shape by the trees");
        b.user("it was standing in the fog");
        b.user("something moves at the edge of my screen");
        b.user("i only see it when i dont look");

        b.cat(Category.MEMORY_SOUNDS).stages(2, 5);
        b.user("did you hear the knocking");
        b.user("those werent your footsteps");
        b.user("who was whispering");
        b.user("something breathed on my neck");
        b.user("i heard it too");
        b.user("it knocked three times");

        b.cat(Category.MEMORY_CORRUPTION).stages(2, 5);
        b.user("the ground is turning");
        b.user("the grass is wrong here");
        b.user("it spreads at night");
        b.user("the dirt sounds hollow");
        b.user("the stone is the wrong color");
        b.voice("the {block} under you is not real");
    }

    // ================================================================== replies to the player's chat

    private static void replies(Builder b) {
        b.cat(Category.REPLY).stages(2, 5);
        b.user("what");
        b.user("ok");
        b.user("?");
        b.user("who said that");
        b.user("huh");
        b.user("same");
        b.user("lol");
        b.user("...");
        b.user("say that again");
        b.user("i was going to say that");
        b.stages(3, 5);
        b.user("we heard you");
        b.user("no one is listening");
        b.user("dont talk so loud");
        b.user("why would you say that");
        b.unknown("who are you talking to {player}");
        b.stages(4, 5);
        b.user("it heard that");
        b.user("you shouldnt have said that");

        b.cat(Category.REPLY_QUESTION).stages(2, 5);
        b.user("yes");
        b.user("maybe");
        b.user("i dont remember");
        b.stages(3, 5);
        b.user("you already know");
        b.user("dont ask that");
        b.user("ask {name}");
        b.user("not anymore");
        b.user("we dont know either");
        b.user("why do you want to know");
        b.user("its better if you dont know");
        b.user("ask again tomorrow");
        b.user("it depends where you are standing");

        b.cat(Category.REPLY_GREETING).stages(2, 5);
        b.user("hi");
        b.user("hello again");
        b.user("hi. who are you");
        b.voice("oh. hello {player}");
        b.stages(3, 5);
        b.user("you came back").also(Category.NULL_RETURN);
        b.user("hello. we have been waiting");
        b.voice("welcome back {player}");

        b.cat(Category.REPLY_WHO).stages(3, 5);
        b.user("me");
        b.user("nobody");
        b.user("us");
        b.user("you know who");
        b.user("{name}");
        b.user("the one behind you");
        b.user("someone who knows you");
        b.user("dont worry about it");

        b.cat(Category.REPLY_FEAR).stages(2, 5);
        b.user("its ok");
        b.user("i know");
        b.stages(3, 5);
        b.user("nobody can help you");
        b.user("dont be scared");
        b.user("we are here");
        b.user("help is not coming");
        b.user("breathe");
        b.user("it wont hurt");
        b.stages(4, 5);
        b.user("its almost over");

        b.cat(Category.REPLY_LEAVE).stages(2, 5);
        b.user("bye");
        b.user("cya");
        b.stages(3, 5);
        b.user("dont go");
        b.user("you always come back");
        b.user("we will wait");
        b.voice("see you soon {player}");
        b.stages(4, 5);
        b.user("you cant leave");
        b.user("leaving wont help");

        b.cat(Category.REPLY_NULL).stages(3, 5);
        b.user("dont say that name");
        b.user("it heard you");
        b.user("how do you know that name");
        b.user("shh").also(Category.SLEEP, Category.BURST);
        b.user("never type that again");

        b.cat(Category.ECHO).stages(3, 5);
        b.user("{said}");
        b.user("{said}?");
        b.user("\"{said}\"");
        b.user("why did you say {said}");
        b.user("you said {said}");
        b.user("i heard you say {said}");
        b.user("remember when you said {said}");
        b.user("we heard {said}");
        b.unknown("{said}...");
        b.stages(4, 5);
        b.user("{said} {said} {said}");
        b.user("{player} said {said}");
        b.self("{said}..");
        b.self("i didnt say {said}");
    }

    // ================================================================== reactions to what the player does

    private static void reactions(Builder b) {
        b.cat(Category.DEATH).stages(2, 5);
        b.user("rip");
        b.user("f");
        b.user("again?");
        b.user("that looked like it hurt");
        b.user("welcome back again");
        b.user("how many times now");
        b.stages(3, 5);
        b.user("you died {deaths} times");
        b.user("{deaths}");
        b.user("it wasnt me");
        b.user("you came back. why");
        b.user("you were so close");
        b.user("your things are still there. for now");
        b.voice("{player} died again");
        b.stages(4, 5);
        b.user("stay down");
        b.user("it took your things");
        b.user("we saw that");
        b.user("the respawn point moved");

        b.cat(Category.BREAK_CORRUPTION).stages(2, 5);
        b.user("careful");
        b.user("dont touch that");
        b.stages(3, 5);
        b.user("you cant fix it");
        b.user("put it back");
        b.user("it grows back");
        b.user("that was holding something up");
        b.user("it felt that");
        b.user("stop breaking them");
        b.voice("why did you break the {block}");
        b.voice("the {block} was there for a reason");
        b.stages(4, 5);
        b.user("you are making it worse");
        b.user("it will remember that");

        b.cat(Category.SLEEP).stages(2, 5);
        b.user("sleep tight");
        b.user("dont dream");
        b.stages(3, 5);
        b.user("dont fall asleep");
        b.voice("close your eyes {player}");
        b.user("we will keep watch");
        b.user("its safer awake");
        b.user("we will be here when you wake up");
        b.user("dont sleep. not tonight");
        b.stages(4, 5);
        b.user("someone is standing next to your bed");

        b.cat(Category.WAKE).stages(2, 5);
        b.voice("good morning {player}");
        b.user("the night was longer than you think");
        b.stages(3, 5);
        b.user("you talk in your sleep");
        b.user("you were gone for a long time");
        b.user("did you dream of it too");
        b.user("you moved in your sleep");
        b.user("something changed while you slept");
        b.stages(4, 5);
        b.user("we watched you sleep");

        b.cat(Category.DEEP).stages(2, 5);
        b.user("how deep are you going");
        b.user("its warmer down here");
        b.stages(3, 5);
        b.user("they buried something down there");
        b.user("you are too deep");
        b.user("can you still see the sky");
        b.voice("it lives below {y}");
        b.user("nobody comes back from that deep");
        b.user("the stone is listening");
        b.user("do you hear the digging too");
        b.user("there are rooms down there");
        b.stages(4, 5);
        b.user("come deeper");
        b.topic("bedrock").stages(3, 5);
        b.user("theres something under the bedrock");
        b.user("the bedrock is thin here");
        b.user("it knocks from under the bedrock");

        b.cat(Category.STILL).stages(2, 5);
        b.user("are you still there");
        b.user("did you leave");
        b.user("are you afk");
        b.stages(3, 5);
        b.user("i can see you standing there");
        b.user("why arent you moving");
        b.voice("{player} are you ok");
        b.user("you havent moved in a while");
        b.user("are you asleep");
        b.stages(4, 5);
        b.user("we can wait too");
        b.user("we see you");

        b.cat(Category.NULL_ARRIVAL).stages(1, 5);
        b.nul("NEW PLAYER DETECTED");
        b.user("you found it");
        b.user("this is where the world goes");
        b.user("we have been waiting here");
        b.user("dont stay too long");
        b.user("everyone ends up here");
        b.user("you cant go back the way you came");
        b.user("how did you get in");
        b.voice("welcome {player}");
        b.voice("is that {player}? you came too?");

        b.cat(Category.NULL_RETURN).stages(1, 5);
        b.user("you again");
        b.user("welcome home");
        b.user("this place missed you");
        b.nul("RETURNING PLAYER");
    }

    // ================================================================== dialogues

    private static List<Dialogue> buildDialogues() {
        List<Dialogue> out = new ArrayList<>();
        dialogue(out, 2, 5, "A:is anyone else on", "B:yes", "A:where are you", "B:behind you");
        dialogue(out, 2, 5, "A:did the sky just flicker", "B:no", "A:ok");
        dialogue(out, 2, 5, "A:anyone want to team up", "B:sure", "B:where are you", "A:im already next to you");
        dialogue(out, 2, 5, "A:whats the seed", "B:why", "A:i want to see what it looked like before");
        dialogue(out, 3, 5, "A:who placed that sign", "B:what sign", "A:nevermind its gone");
        dialogue(out, 3, 5, "A:do you see that", "B:see what", "A:it moved");
        dialogue(out, 3, 5, "A:brb", "Q:A");
        dialogue(out, 3, 5, "A:how long have you been playing", "B:i dont remember starting");
        dialogue(out, 3, 5, "A:my inventory is empty", "B:so is mine", "C:so is the world");
        dialogue(out, 3, 4, "A:is the moon supposed to look like that", "B:like what", "A:cracked");
        dialogue(out, 3, 5, "A:did someone say my name", "B:no", "A:someone said my name");
        dialogue(out, 3, 5, "A:lol", "B:whats funny", "A:i didnt type that");
        dialogue(out, 3, 5, "A:i found a village", "B:is anyone there", "A:everyone", "A:theyre all standing still");
        dialogue(out, 3, 5, "A:why is my house different", "B:different how", "A:the door is on the wrong side");
        dialogue(out, 3, 5, "A:its getting dark", "B:its noon", "A:not here");
        dialogue(out, 3, 5, "A:did you hear that explosion", "B:what explosion", "A:right next to me", "B:theres nothing there");
        dialogue(out, 3, 5, "A:is it raining for you", "B:no", "A:its raining inside my house");
        dialogue(out, 3, 5, "A:what does the purple block do", "B:dont touch it", "A:why", "B:just dont");
        dialogue(out, 3, 5, "A:does anyone else hear footsteps", "B:those are mine", "A:youre not moving");
        dialogue(out, 4, 5, "A:whos {name}", "B:dont", "A:what");
        dialogue(out, 4, 5, "A:hello?", "U:hello", "A:who is that", "U:who is that");
        dialogue(out, 4, 5, "A:there is someone in my base", "B:who", "A:me");
        dialogue(out, 4, 5, "A:how do i leave", "B:you dont");
        dialogue(out, 4, 5, "A:stop following me", "B:im at spawn", "A:then who is this");
        dialogue(out, 4, 5, "A:i went to the edge of the world", "B:and", "A:it went back");
        dialogue(out, 4, 5, "A:can someone help me", "B:where are you", "A:i dont know", "A:its dark");
        dialogue(out, 4, 5, "A:i typed something and it typed back", "B:what did it say", "A:your name");
        dialogue(out, 4, 5, "N:PLAYER NOT FOUND", "A:what player", "B:me?");
        dialogue(out, 4, 5, "A:guys", "A:guys", "A:guys answer me");
        dialogue(out, 4, 5, "J:A", "A:why is everyone standing still", "Q:A");
        dialogue(out, 4, 5, "L:{name} lost connection: Disconnected", "A:where did {name} go", "B:{name} was never here");
        dialogue(out, 5, 5, "A:there are so many people online", "B:i only see you", "A:look at the list");
        dialogue(out, 5, 5, "A:who are all these people", "B:they were always here", "C:hi");
        dialogue(out, 5, 5, "A:does anyone remember how we got here", "B:we always lived here", "C:dont listen to him");
        dialogue(out, 5, 5, "A:is {player} real", "B:no", "C:yes", "B:no");
        return Collections.unmodifiableList(out);
    }

    private static void dialogue(List<Dialogue> out, int min, int max, String... steps) {
        List<Step> parsed = new ArrayList<>(steps.length);
        boolean personal = false;
        for (String step : steps) {
            if (step.length() < 3 || step.charAt(1) != ':') {
                throw new IllegalArgumentException("Bad dialogue step '" + step + "'");
            }
            Step s = new Step(step.charAt(0), step.substring(2));
            parsed.add(s);
            for (String name : ChatPlaceholders.find(s.text())) {
                personal |= ChatPlaceholders.PERSONAL.contains(name);
            }
        }
        out.add(new Dialogue(out.size(), min, max, parsed, personal));
    }

    // ================================================================== builder

    private static final class Builder {
        final List<ChatLine> lines = new ArrayList<>();
        Category category = Category.WHISPER;
        String topic = "";
        Speaker speaker = Speaker.USER;
        int min = 2;
        int max = 5;

        Builder cat(Category category) {
            this.category = category;
            this.topic = "";
            this.speaker = Speaker.USER;
            return this;
        }

        Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        Builder stages(int min, int max) {
            if (min < 1 || max > 5 || min > max) {
                throw new IllegalArgumentException("Bad stage range " + min + "-" + max);
            }
            this.min = min;
            this.max = max;
            return this;
        }

        Builder speaker(Speaker speaker) {
            this.speaker = speaker;
            return this;
        }

        Builder line(String text) {
            return add(speaker, text);
        }

        Builder user(String text) {
            return add(Speaker.USER, text);
        }

        Builder unknown(String text) {
            return add(Speaker.UNKNOWN, text);
        }

        Builder voice(String text) {
            return add(Speaker.VOICE, text);
        }

        Builder nul(String text) {
            return add(Speaker.NULL, text);
        }

        Builder log(String text) {
            return add(Speaker.LOG, text);
        }

        Builder error(String text) {
            return add(Speaker.ERROR, text);
        }

        Builder self(String text) {
            return add(Speaker.SELF, text);
        }

        /** Makes the line added last usable for more categories too. */
        Builder also(Category... more) {
            ChatLine last = lines.remove(lines.size() - 1);
            Set<Category> extra = EnumSet.noneOf(Category.class);
            extra.addAll(last.also());
            extra.addAll(List.of(more));
            extra.remove(last.category());
            lines.add(new ChatLine(last.index(), last.category(), extra, last.topic(), last.speaker(), last.minStage(),
                    last.maxStage(), last.text(), last.placeholders(), last.personal()));
            return this;
        }

        private Builder add(Speaker who, String text) {
            List<String> placeholders = ChatPlaceholders.find(text);
            boolean personal = who == Speaker.SELF || who == Speaker.WHISPER || category == Category.NAME;
            for (String name : placeholders) {
                personal |= ChatPlaceholders.PERSONAL.contains(name);
            }
            lines.add(new ChatLine(lines.size(), category, EnumSet.noneOf(Category.class), topic, who, min, max, text,
                    placeholders, personal));
            return this;
        }
    }
}
