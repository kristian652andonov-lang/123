# NULL — The World Is Failing

A psychological-horror mod for **Minecraft 1.21.1** on **NeoForge** (21.1.x, Java 21).

NULL never explains itself. From the moment a world loads, Minecraft starts to come apart:
first a black block where a stone should be, a flicker in the sky, static too quiet to be
sure of. Over hours the moon cracks and shatters, black parasites grow out of corrupted
ground, players who are not players watch from the tree line, and finally the world is gone
and something else is running in its place.

There are no weapons, armour, ores, quests, bosses or progress bars. The only goal of the mod
is to make it feel like the game itself is failing.

---

## Installing

1. Install NeoForge for Minecraft 1.21.1.
2. Put `null-<version>.jar` in your `mods/` folder — on the client, and on the server for
   multiplayer (both sides need it). A player with NULL installed can still join servers that
   do not have it; nothing happens there.
3. Start a world. NULL begins immediately.

A ready-built jar is in [`dist/`](dist/). GitHub Actions also builds one on every push
(**Actions → Build NULL mod → Artifacts → `null-mod-jar`**).

The Mods list shows NULL twice: **NULL** and **NULL (module)**. That is expected. The mod id is
`null`, which Java does not accept as a module name, so the jar carries a small technical
second entry whose id names the module. It has no content of its own.

## The five stages

Stages advance with time spent in the world (only while someone is online), never move
backwards on their own, and are saved with the world.

| Stage | Default time | What happens |
|---|---|---|
| 1 · START | immediately | Something is wrong. Black and missing-texture blocks, blocks that blink out, wrong textures on trees, brief chunk flicker, a sky that flickers for a frame, static and footsteps you can't place. |
| 2 · UNDER MIDDLE | 30 min | Reality is breaking. Camera shakes, screen distortion, freezes and blackouts, the sky changes colour, outdated-looking terrain fragments and floating islands appear, the moon starts to crack. |
| 3 · MIDDLE | 90 min | NULLROOT: black parasitic plants grow out of corrupted ground, walls and cave ceilings. Some only watch. Some reach for you. |
| 4 · ABOVE MIDDLE | 180 min | The Visitors: player-shaped things with old usernames that stand and stare, follow, or appear behind you. Bedrock hangs in the sky; abandoned villages, wrong buildings and corridors to nowhere appear. |
| 5 · NULL | 300 min | The world stops. Everything goes quiet and dark, and you wake up in NULL — an endless glowing geometric structure. The player list fills with players who are not there, and chat no longer makes sense. |

Different regions of a world corrupt at different speeds.

## Settings (per player)

Open **Mods → NULL → Config**, or the small **NULL** link on the corrupted main menu.

- **Visual:** screen shake strength, *Reduce screen shaking*, screen flashing strength (at 0,
  flashes become slow fades), *Disable intense visual effects*, number of simultaneous
  effects, shader effects.
- **Audio:** horror sounds on/off, audio corruption strength, and separate switches for
  static, whispers, phantom footsteps, music distortion, wrong pitches and sudden silence.
- **Main menu:** the corrupted menu can be switched off to get the vanilla menu back;
  animation and glitch strength; menu sounds.
- **Desktop interference** (on by default, see below).

All settings are saved in `config/null-client.toml`.

### Desktop interference

NULL shows small black "error" windows on your desktop, outside the game window. It is **on by
default** and can be switched off at any time in NULL's settings (every open window closes at once). Every window is labelled as
coming from the NULL Minecraft mod, can be closed by clicking it or pressing Esc, closes on its
own, never takes keyboard or mouse focus, never imitates real system or security dialogs, and
never appears after Minecraft has closed. NULL does not change anything on your computer and
collects nothing. It is unavailable on macOS and on systems without a desktop, and is never
used on dedicated servers.

Once it is on, the settings screen controls how often windows appear ("Never" stops them
all), the shortest pause between two windows (45 s by default) and a separate chance for each
kind of window: small corner notifications (A), error windows (B), distorted messages (C),
larger NULL reports (D) and the Stage 5 final interference (E). **Show test message** shows one window right away. Stage 1
has almost none; they become more frequent and stranger with every stage.

## Server / world configuration

Each world has `serverconfig/null-server.toml` (copy one into `defaultconfigs/` to change the
defaults for new worlds). Everything is adjustable: stage times and progression speed, event
frequency and intensity, Visitor / NULLROOT / chat frequency, simulated player count, moon
speed, world corruption intensity, upper limits for client screen shake / audio / effect
counts, and whether the final transition happens automatically.

**World safety:** NULL never destroys player builds. It remembers every chunk where a player
has placed blocks and scans for built-looking blocks before changing anything, and it only
ever replaces natural terrain or places blocks into air. `allowWorldModification = false`
makes all corruption purely visual; `replaceNaturalBlocks = false` keeps natural terrain
untouched while still allowing structures in the air. Leaves you placed, waterlogged blocks
and light blocks always count as built. The NULL dimension is separate: the
original world is never deleted.

## Commands (operators)

| Command | |
|---|---|
| `/null status` | stage, progress, time played, speed, moon |
| `/null stage <1-5>` | jump to the start of a stage (forwards or backwards) |
| `/null reset` | back to Stage 1 (run twice to confirm) |
| `/null pause` / `/null resume` | stop / continue progression and random events |
| `/null speed <multiplier>` | speed up or slow down progression |
| `/null events` | list all random events by stage |
| `/null event <id> [players]` | run one random event now |

## Building from source

```bash
cd null-mod
./gradlew build
```

The jar ends up in `null-mod/build/libs/`. `./gradlew runGameTestServer` runs the GameTests;
`./gradlew runClient` starts a development client.

## How it is tested

Every push runs the **Build NULL mod** workflow (`.github/workflows/build-null-mod.yml`):

1. **Compile and package** the jar.
2. **GameTests** on a GameTest server: stage progression, persistence and commands, the
   random event registry, build protection, world corruption, the moon, Visitors, NULLROOT
   growth, capture and release, corrupted chat and the NULL geometry function.
3. **Client smoke test**: a scripted client on a virtual display opens the corrupted main menu
   and NULL's settings, creates a world, goes through every stage with every random event
   forced, watches the moon, is taken into the NULL dimension, opens the player list, returns
   to the overworld and quits, taking screenshots on the way. Its integrated server also runs
   the NULL dimension self-check (generated chunks, arrival platform, decay and exact
   regeneration).
4. **Multiplayer smoke test**: a real NeoForge dedicated server with the built jar, joined by
   the scripted client: stage sync, every stage, the NULL transition over the network and the
   simulated player list. The server must not load a single client class and must pass the
   dimension self-check.

Screenshots from these runs (software rendering on a virtual display, so the overworld looks
plainer than on a real GPU; the "Ran … for 1 player(s)" lines are the test forcing events):

| | |
|---|---|
| ![Corrupted main menu](docs/main-menu.jpg) | ![NULL settings](docs/settings.jpg) |
| The corrupted main menu | NULL's settings |
| ![The shattering moon](docs/shattering-moon.jpg) | ![The NULL dimension](docs/null-dimension.jpg) |
| Stage 4: the moon breaking apart | Stage 5: the NULL dimension |
| ![Distortion in NULL](docs/null-dimension-glitch.jpg) | ![The player list in NULL](docs/player-list.jpg) |
| Distortion and corrupted chat in NULL | Players who are not there |
