package dev.kristian.nullmod.entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.RandomSource;

/**
 * Believable Minecraft usernames for Visitors, simulated players and corrupted chat. The
 * names look like real accounts from the early 2010s (first names with numbers, gamer tags,
 * {@code xX_..._Xx}, {@code Player_2012}, old account names), are always 3-16 characters of
 * {@code [A-Za-z0-9_]}, and never exactly match a well-known real account or person.
 * <p>
 * Everything here is common code and depends only on the {@link RandomSource} it is given,
 * so {@link #fromSeed(long)} always returns the same name for the same seed.
 */
public final class UsernameGenerator {
    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;
    private static final int ATTEMPTS = 24;

    // ------------------------------------------------------------------ fragments

    private static final String[] FIRST_NAMES = {
            "James", "John", "Robert", "Michael", "Will", "David", "Richard", "Joseph", "Thomas", "Chris",
            "Daniel", "Matt", "Anthony", "Mark", "Steven", "Paul", "Andrew", "Josh", "Kevin", "Brian",
            "George", "Tim", "Ron", "Jason", "Jeff", "Ryan", "Jacob", "Gary", "Nick", "Eric",
            "Jon", "Stephen", "Larry", "Justin", "Scott", "Brandon", "Ben", "Sam", "Greg", "Frank",
            "Ray", "Patrick", "Jack", "Dennis", "Jerry", "Tyler", "Aaron", "Jose", "Adam", "Henry",
            "Nathan", "Doug", "Zach", "Peter", "Kyle", "Walter", "Ethan", "Jeremy", "Keith", "Roger",
            "Noah", "Carl", "Terry", "Sean", "Austin", "Arthur", "Jesse", "Dylan", "Bryan", "Joe",
            "Jordan", "Billy", "Bruce", "Albert", "Gabe", "Logan", "Alan", "Juan", "Wayne", "Roy",
            "Ralph", "Randy", "Vincent", "Russell", "Elijah", "Louis", "Bobby", "Phil", "Johnny", "Steve",
            "Mary", "Patricia", "Jennifer", "Linda", "Liz", "Barbara", "Susan", "Jessica", "Sarah", "Karen",
            "Nancy", "Lisa", "Betty", "Sandra", "Ashley", "Kim", "Emily", "Donna", "Michelle", "Carol",
            "Amanda", "Melissa", "Debbie", "Steph", "Rebecca", "Sharon", "Laura", "Cindy", "Amy", "Kathy",
            "Angela", "Anna", "Brenda", "Pam", "Emma", "Nicole", "Helen", "Samantha", "Katie", "Christine",
            "Rachel", "Janet", "Maria", "Heather", "Diane", "Julie", "Joyce", "Victoria", "Kelly", "Tina",
            "Lauren", "Joan", "Evelyn", "Olivia", "Judy", "Megan", "Cheryl", "Martha", "Andrea", "Hannah",
            "Jacky", "Ann", "Jean", "Alice", "Kate", "Sophie", "Grace", "Chloe", "Ella", "Lily",
            "Mia", "Zoe", "Max", "Leo", "Oscar", "Finn", "Lucas", "Toby", "Jake", "Luke",
            "Connor", "Callum", "Kieran", "Jamie", "Liam", "Owen", "Rhys", "Ollie", "Harry", "Charlie",
            "Alfie", "Freddie", "Seth", "Caleb", "Isaac", "Marcus", "Felix", "Hugo", "Emil", "Jonas",
            "Lukas", "Niklas", "Jan", "Pieter", "Lars", "Sven", "Erik", "Mikael", "Pavel", "Ivan",
            "Dmitri", "Marco", "Luca", "Mateo", "Diego", "Pablo", "Kenji", "Yuki", "Hiro", "Min",
            "Wei", "Jin", "Arjun", "Ravi", "Omar", "Ali", "Yusuf", "Tomas", "Andrei", "Stefan",
            "Viktor", "Bogdan", "Milan", "Jakub", "Mateusz", "Piotr", "Rafael", "Bruno", "Thiago", "Tiago",
            "Cody", "Corey", "Derek", "Travis", "Trevor", "Shane", "Brett", "Chad", "Dustin", "Jared",
            "Spencer", "Mason", "Hunter", "Garrett", "Wesley", "Colin", "Devin", "Blake", "Cole", "Chase",
            "Riley", "Bailey", "Morgan", "Taylor", "Casey", "Quinn", "Reese", "Avery", "Dakota", "Skyler",
    };

    /** Short surname starts for names like {@code JamesHou99}. */
    private static final String[] SURNAME_BITS = {
            "Hou", "Smi", "Joh", "Wil", "Bro", "Jon", "Mil", "Dav", "Gar", "Rod",
            "Mar", "Her", "Lop", "Gon", "And", "Tho", "Tay", "Moo", "Jac", "Mart",
            "Lee", "Per", "Whi", "Har", "San", "Cla", "Ram", "Lew", "Rob", "Wal",
            "You", "All", "Kin", "Wri", "Sco", "Tor", "Ngu", "Hil", "Flo", "Gre",
            "Ada", "Nel", "Bak", "Hal", "Riv", "Cam", "Mit", "Car", "Kel", "Cha",
            "Ward", "Fox", "Ross", "Kim", "Lam", "Wu", "Chen", "Park", "Shaw", "Hunt",
            "Dunn", "Webb", "Cole", "West", "Ford", "Kane", "Hart", "Lane", "Rice", "Moss",
            "Vos", "Berg", "Holt", "Kerr", "Nash", "Pike", "Reed", "Tate", "Hay", "Bell",
            "Cook", "Gray", "Hall", "King", "Long", "Price", "Stone", "Wood", "Young", "Mo",
    };

    private static final String[] NOUNS = {
            "Diamond", "Creeper", "Pixel", "Block", "Craft", "Miner", "Builder", "Gamer", "Ninja", "Dragon",
            "Wolf", "Fox", "Tiger", "Shadow", "Storm", "Fire", "Ice", "Thunder", "Blade", "Sword",
            "Arrow", "Knight", "King", "Queen", "Lord", "Master", "Hunter", "Warrior", "Wizard", "Mage",
            "Ghost", "Phantom", "Zombie", "Skeleton", "Spider", "Slime", "Golem", "Pig", "Cow", "Chicken",
            "Sheep", "Cat", "Dog", "Bear", "Eagle", "Hawk", "Raven", "Crow", "Owl", "Snake",
            "Viper", "Cobra", "Shark", "Whale", "Dolphin", "Turtle", "Panda", "Lion", "Monkey", "Rabbit",
            "Bunny", "Duck", "Penguin", "Potato", "Carrot", "Cookie", "Pie", "Cake", "Bacon", "Taco",
            "Pizza", "Noodle", "Muffin", "Pickle", "Waffle", "Nugget", "Bean", "Toast", "Cheese", "Melon",
            "Apple", "Cactus", "Stone", "Cobble", "Dirt", "Grass", "Sand", "Gravel", "Iron", "Gold",
            "Emerald", "Redstone", "Lapis", "Obsidian", "Bedrock", "Lava", "Water", "Torch", "Pickaxe", "Shovel",
            "Axe", "Bow", "Sapling", "Oak", "Birch", "Spruce", "Leaf", "Moss", "Cave", "Mine",
            "Dungeon", "Portal", "Nether", "Biome", "Chunk", "Server", "Byte", "Bit", "Code", "Glitch",
            "Error", "Echo", "Static", "Signal", "Rocket", "Laser", "Robot", "Cyborg", "Alien", "Comet",
            "Star", "Moon", "Sun", "Sky", "Cloud", "Rain", "Snow", "Frost", "Blaze", "Flame",
            "Ember", "Ash", "Smoke", "Dust", "Rock", "Boulder", "River", "Lake", "Ocean", "Wave",
            "Tornado", "Volcano", "Mountain", "Hill", "Valley", "Forest", "Jungle", "Desert", "Tundra", "Swamp",
            "Island", "Castle", "Tower", "Bridge", "House", "Hut", "Farm", "Village", "Kid", "Boy",
            "Dude", "Guy", "Man", "Bro", "Pro", "Noob", "Legend", "Hero", "Champ", "Boss",
            "Chief", "Captain", "Pilot", "Agent", "Spy", "Doc", "Pirate", "Viking", "Samurai", "Monk",
            "Cowboy", "Bandit", "Ranger", "Scout", "Sniper", "Tank", "Gunner", "Racer", "Skater", "Surfer",
            "Gamer", "Player", "Crafter", "Digger", "Farmer", "Fisher", "Smith", "Brewer", "Trader", "Nomad",
            "Block", "Cube", "Brick", "Plank", "Wool", "Glass", "Bucket", "Minecart", "Rail", "Lever",
    };

    private static final String[] ADJECTIVES = {
            "Dark", "Silent", "Epic", "Crazy", "Mad", "Super", "Mega", "Ultra", "Hyper", "Little",
            "Big", "Tiny", "Giant", "Lazy", "Happy", "Sad", "Angry", "Cool", "Hot", "Cold",
            "Red", "Blue", "Green", "Black", "White", "Golden", "Silver", "Iron", "Night", "Lost",
            "Fallen", "Broken", "Hidden", "Secret", "Mystic", "Magic", "Wild", "Brave", "Swift", "Quick",
            "Fast", "Slow", "Sneaky", "Lucky", "Clumsy", "Funny", "Derpy", "Fluffy", "Fuzzy", "Sleepy",
            "Hungry", "Grumpy", "Jolly", "Noble", "Royal", "Ancient", "Old", "New", "Real", "True",
            "Pure", "Toxic", "Atomic", "Cosmic", "Electric", "Frozen", "Burning", "Flying", "Running", "Mining",
            "Crafty", "Retro", "Classic", "Original", "Infinite", "Eternal", "Last", "First", "Only", "Wise",
            "Bold", "Calm", "Grim", "Odd", "Pale", "Quiet", "Rusty", "Shiny", "Spooky", "Stormy",
            "Sunny", "Wicked", "Rapid", "Savage", "Smooth", "Sticky", "Stone", "Tiny", "Wooden", "Neon",
    };

    private static final String[] PREFIXES = {
            "Mr", "Mrs", "Ms", "Dr", "Sir", "Lord", "Captain", "Pro", "iAm", "Im",
            "Its", "ItsJust", "Real", "Official", "Not", "Just", "Le", "El", "Da", "Sgt",
            "Lil", "Big", "Young", "Agent", "King", "Prince", "Count", "Master", "Uncle", "Cousin",
    };

    private static final String[] SUFFIXES = {
            "MC", "_MC", "Craft", "PvP", "HD", "TV", "_TV", "YT", "Gaming", "Plays",
            "Games", "Gamer", "Builds", "Mines", "x", "X", "_", "xD", "XD", "123",
            "Pro", "God", "Boy", "Girl", "Kid", "Man", "Dude", "Bro", "Live", "Zz",
            "_xx", "_YT", "Official", "Rules", "Rocks", "4Life", "4Ever", "Fan", "Jr", "LP",
    };

    /** For names like {@code KingOfDirt}. */
    private static final String[] RULERS = {"King", "Lord", "Master", "Prince", "Queen", "Duke", "Keeper", "Eater"};
    private static final String[] REALMS = {
            "Dirt", "Pigs", "Cows", "Creepers", "Diamonds", "Blocks", "Cobble", "Lava", "Caves", "Bacon",
            "Cake", "Cookies", "Pixels", "Noobs", "Sheep", "Chickens", "Swords", "Mines", "Snow", "Sand",
            "Cactus", "Squids", "Torches", "Gravel", "Wolves", "Spiders", "Bones", "Carrots", "Potatoes", "Melons",
    };
    /** For names like {@code JakeTheMiner}. */
    private static final String[] TITLES = {
            "Miner", "Builder", "Crafter", "Digger", "Farmer", "Hunter", "Knight", "Wizard", "Pirate", "Ninja",
            "Great", "Brave", "Red", "Lost", "Gamer", "Noob", "Pro", "Legend", "Destroyer", "Explorer",
            "Archer", "Warrior", "Quiet", "First", "Last", "Kid", "Slayer", "Bold", "Strong", "Fisher",
    };

    /** Whole names, including every example from the design document. */
    private static final String[] CURATED = {
            "JamesHou99", "Notch_2009", "Steve12345", "xX_DiamondKing_Xx", "Player_2012", "Herobrine_OLD",
            "MinecraftFan2008", "TheRealSteve", "OldAccount_2011", "CreeperKid2010", "DiamondMiner99",
            "xXCraftKingXx", "BuilderBob2011", "CobbleMan", "minerguy11", "pigboy2010", "Pixel_Pete",
            "skyblock_kid", "NoobSlayer2012", "MrBlocky", "TheCubeGuy", "craftyjohn", "lavaboy2013",
            "EpicMiner_HD", "SteveFan2010", "dirt_house", "zombiehunter12", "jacob_mc", "Mine4Life",
            "IronGolem_77", "EnderBoy2011", "sheepfarmer", "Diamondz4Life", "GrassBlock_", "torchman",
            "OldServerKid", "lostplayer_2012", "NoName_2010", "Player2011", "AFK_Steve", "cavekid_09",
            "BlockyMcBlock", "xXPixelXx", "redstone_rob", "MrCobblestone", "obsidianfan", "GoldenPig2011",
            "the_miner_2010", "Herobrine_fan", "notchfan2010", "SkyWarsKid", "TNT_Tom", "BedrockBen",
            "CraftMaster2012", "MCplayer2009", "BetaPlayer_10", "AlphaTester09", "IndevKid", "classic_dan",
            "chunkloader", "lost_world_11", "sp_world_2012", "seed_404", "Spawn_Point", "nobody_09",
            "WaitingForYou", "StillHere_10", "Player_404", "OldWorld_2010",
    };

    /**
     * The example names from the design document. They are returned verbatim, so one of them
     * ({@code xX_DiamondKing_Xx}, 17 characters) is the only name that can exceed
     * {@link #MAX_LENGTH}; simulated names never become real game profiles, so that is harmless.
     */
    private static final Set<String> DESIGN_EXAMPLES = Set.of(
            "JamesHou99", "Notch_2009", "Steve12345", "xX_DiamondKing_Xx", "Player_2012", "Herobrine_OLD",
            "MinecraftFan2008", "TheRealSteve", "OldAccount_2011");

    /**
     * Exact (case-insensitive) names that are never produced: well-known real accounts,
     * creators, famous players and names of real people. Containing one of these as part of a
     * longer name ({@code Notch_2009}) is fine.
     */
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "notch", "notch_", "jeb", "jeb_", "dinnerbone", "grumm", "searge", "evilseph", "c418",
            "jens", "lydia", "agnes", "ladyagnes", "kingbdogz", "slicedlime", "xlson", "marc", "marcwatson",
            "mojang", "mojangstudios", "minecraft", "microsoft", "herobrine", "steve", "alex",
            "technoblade", "dream", "dreamwastaken", "georgenotfound", "sapnap", "tommyinnit", "tubbo", "tubbo_",
            "wilbursoot", "wilbur", "philza", "ph1lza", "ranboo", "captainsparklez", "skeppy", "badboyhalo",
            "quackity", "karljacobs", "punz", "antfrost", "antvenom", "skydoesminecraft", "skydoeseverything",
            "stampylongnose", "stampycat", "stampy", "iballisticsquid", "dantdm", "thediamondminecart",
            "prestonplayz", "preston", "unspeakable", "unspeakablegaming", "aphmau", "ldshadowlady",
            "smajor1995", "xisuma", "xisumavoid", "mumbo", "mumbojumbo", "grian", "goodtimeswithscar",
            "etho", "ethoslab", "bdoubleo100", "keralis", "iskall85", "impulsesv", "zombiecleo", "tangotek",
            "docm77", "hypnotizd", "cubfan135", "stressmonster101", "rendog", "vintagebeef", "joehills",
            "welsknight", "falsesymmetry", "pearlescentmoon", "geminitay", "popularmmos", "jeromeasf",
            "bajancanadian", "mitch", "ssundee", "vikkstar123", "vikkstar", "miniminter", "ksi", "w2s",
            "wroetoshaw", "lachlan", "bodil40", "pewdiepie", "markiplier", "jacksepticeye", "mrbeast",
            "ninja", "pokimane", "xqc", "tfue", "shroud", "sykkuno", "coryxkenshin", "corpse",
            "corpsehusband", "valkyrae", "ludwig", "jschlatt", "schlatt", "fundy", "eret", "niki",
            "nihachu", "purpled", "foolish", "awesamdude", "hbomb94", "parrotx2", "fruitberries",
            "illumina", "feinberg", "couriway", "spoke", "fitmc", "popbob", "hausemaster", "salc1",
            "sb737", "fwhip", "smallishbeans", "solidarity", "jevin", "ijevin", "krtzyy", "pixlriffs",
            "wifies", "seapeekay", "sneegsnag", "kurtjmac", "direwolf20", "sethbling", "cavinator1",
            "paulsoaresjr", "generikb", "zisteau", "guude", "pauseunpause", "kurt", "elon", "elonmusk",
            "billgates", "satyanadella", "herobrine_", "player", "test", "admin", "moderator", "owner",
            "null", "nobody", "unknown", "system", "server", "console"));

    private interface Pattern {
        String make(RandomSource r);
    }

    private static final Pattern[] PATTERNS;
    private static final int[] WEIGHTS;
    private static final int TOTAL_WEIGHT;

    static {
        Object[][] table = {
                {12, (Pattern) r -> pick(r, FIRST_NAMES) + pick(r, SURNAME_BITS) + twoDigits(r)},
                {9, (Pattern) r -> pick(r, ADJECTIVES) + pick(r, NOUNS) + (r.nextInt(3) == 0 ? digits(r) : "")},
                {7, (Pattern) r -> pick(r, NOUNS) + pick(r, NOUNS)},
                {9, (Pattern) r -> pick(r, FIRST_NAMES) + digits(r)},
                {5, (Pattern) r -> r.nextBoolean()
                        ? "xX_" + pick(r, NOUNS) + pick(r, NOUNS) + "_Xx"
                        : "xX" + pick(r, ADJECTIVES) + pick(r, NOUNS) + "Xx"},
                {4, (Pattern) r -> r.nextBoolean() ? "Player_" + year(r) : "Player" + (100 + r.nextInt(9900))},
                {4, (Pattern) r -> (r.nextBoolean() ? pick(r, NOUNS) : pick(r, FIRST_NAMES)) + "_" + year(r)},
                {3, (Pattern) r -> (r.nextBoolean() ? pick(r, NOUNS) : pick(r, FIRST_NAMES))
                        + (r.nextInt(3) == 0 ? "_old" : "_OLD")},
                {3, (Pattern) r -> pick(r, new String[] {"Minecraft", "Creeper", "Block", "Craft", "Mine", "Pixel", "Cube"})
                        + "Fan" + year(r)},
                {4, (Pattern) r -> "TheReal" + (r.nextBoolean() ? pick(r, FIRST_NAMES) : pick(r, NOUNS))},
                {2, (Pattern) r -> switch (r.nextInt(3)) {
                    case 0 -> "OldAccount_" + year(r);
                    case 1 -> "OldAcc" + year(r);
                    default -> "Old_" + pick(r, FIRST_NAMES);
                }},
                {6, (Pattern) r -> pick(r, PREFIXES) + (r.nextBoolean() ? pick(r, NOUNS) : pick(r, FIRST_NAMES))
                        + (r.nextInt(4) == 0 ? pick(r, SUFFIXES) : "")},
                {6, (Pattern) r -> r.nextBoolean()
                        ? lower(pick(r, FIRST_NAMES)) + "_" + lower(pick(r, NOUNS))
                        : lower(pick(r, FIRST_NAMES)) + year(r)},
                {5, (Pattern) r -> (r.nextBoolean() ? pick(r, NOUNS) : pick(r, FIRST_NAMES)) + pick(r, SUFFIXES)},
                {2, (Pattern) r -> leet(pick(r, ADJECTIVES) + pick(r, NOUNS), r)},
                {2, (Pattern) r -> pick(r, RULERS) + "Of" + pick(r, REALMS)},
                {3, (Pattern) r -> pick(r, FIRST_NAMES) + "The" + pick(r, TITLES)},
                {3, (Pattern) r -> pick(r, NOUNS) + pick(r, new String[] {"Miner", "Builder", "Crafter", "Digger", "Hunter", "Slayer"})
                        + (r.nextBoolean() ? twoDigits(r) : "")},
                {2, (Pattern) r -> lower(pick(r, NOUNS)) + lower(pick(r, NOUNS)) + (r.nextBoolean() ? twoDigits(r) : "")},
                {7, (Pattern) r -> pick(r, CURATED)},
        };
        PATTERNS = new Pattern[table.length];
        WEIGHTS = new int[table.length];
        int total = 0;
        for (int i = 0; i < table.length; i++) {
            WEIGHTS[i] = (Integer) table[i][0];
            PATTERNS[i] = (Pattern) table[i][1];
            total += WEIGHTS[i];
        }
        TOTAL_WEIGHT = total;
    }

    private UsernameGenerator() {
    }

    // ------------------------------------------------------------------ API

    /** A random believable username. */
    public static String random(RandomSource random) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String name = PATTERNS[pickPattern(random)].make(random);
            if (isAcceptable(name)) {
                return name;
            }
        }
        return "Player" + (1000 + random.nextInt(9000));
    }

    /** The same name for the same seed, every time. */
    public static String fromSeed(long seed) {
        return random(RandomSource.create(seed));
    }

    /** A name that is not in {@code taken} (case-insensitive), falling back to a numbered variant. */
    public static String unique(RandomSource random, Set<String> takenLowerCase) {
        for (int attempt = 0; attempt < 12; attempt++) {
            String name = random(random);
            if (!takenLowerCase.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        String base = random(random);
        for (int n = 2; n < 1000; n++) {
            String suffix = Integer.toString(n);
            String candidate = (base.length() + suffix.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH - suffix.length()) : base) + suffix;
            if (isValid(candidate) && !isBlocked(candidate) && !takenLowerCase.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return "Player" + (10000 + random.nextInt(89999));
    }

    /** 3-16 characters, letters, digits and underscores only. */
    public static boolean isValid(String name) {
        if (name == null || name.length() < MIN_LENGTH || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * True for every name {@link #random} may return: {@link #isValid valid} or one of the
     * design examples, and never {@link #isBlocked blocked}.
     */
    public static boolean isAcceptable(String name) {
        return (isValid(name) || isDesignExample(name)) && !isBlocked(name);
    }

    /** True for the example names from the design document, which are always possible outputs. */
    public static boolean isDesignExample(String name) {
        return name != null && DESIGN_EXAMPLES.contains(name);
    }

    /** True for exact (case-insensitive) matches of names this generator must never produce. */
    public static boolean isBlocked(String name) {
        return name != null && BLOCKED.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The hand-written whole names the generator can return, including the design examples. */
    public static List<String> curated() {
        return Collections.unmodifiableList(Arrays.asList(CURATED));
    }

    /** Number of distinct fragments in the database (first names, surnames, words, affixes, whole names). */
    public static int fragmentCount() {
        Set<String> all = new HashSet<>();
        for (String[] list : new String[][] {FIRST_NAMES, SURNAME_BITS, NOUNS, ADJECTIVES, PREFIXES, SUFFIXES, RULERS, REALMS, TITLES, CURATED}) {
            all.addAll(Arrays.asList(list));
        }
        return all.size();
    }

    // ------------------------------------------------------------------ helpers

    private static int pickPattern(RandomSource r) {
        int pick = r.nextInt(TOTAL_WEIGHT);
        for (int i = 0; i < WEIGHTS.length; i++) {
            pick -= WEIGHTS[i];
            if (pick < 0) {
                return i;
            }
        }
        return WEIGHTS.length - 1;
    }

    private static String pick(RandomSource r, String[] values) {
        return values[r.nextInt(values.length)];
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static int year(RandomSource r) {
        return 2009 + r.nextInt(5);
    }

    private static String twoDigits(RandomSource r) {
        int n = r.nextInt(100);
        return n < 10 ? "0" + n : Integer.toString(n);
    }

    /** The kinds of numbers people put after their names. */
    private static String digits(RandomSource r) {
        return switch (r.nextInt(9)) {
            case 0 -> Integer.toString(1 + r.nextInt(9));
            case 1, 2 -> twoDigits(r);
            case 3 -> Integer.toString(year(r));
            case 4 -> Integer.toString(1990 + r.nextInt(16));
            case 5 -> "12345".substring(0, 3 + r.nextInt(3));
            case 6 -> {
                char d = (char) ('1' + r.nextInt(9));
                yield String.valueOf(new char[] {d, d, d});
            }
            case 7 -> "0" + (1 + r.nextInt(9));
            default -> Integer.toString(100 + r.nextInt(900));
        };
    }

    private static String leet(String s, RandomSource r) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lc = Character.toLowerCase(c);
            boolean swap = r.nextInt(3) != 0;
            if (swap && lc == 'a') {
                out.append('4');
            } else if (swap && lc == 'e') {
                out.append('3');
            } else if (swap && lc == 'i') {
                out.append('1');
            } else if (swap && lc == 'o') {
                out.append('0');
            } else if (swap && lc == 's' && i > 0) {
                out.append('5');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
