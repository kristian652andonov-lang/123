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
   multiplayer (both sides need it).
3. Start a world. NULL begins immediately.

The jar is built by GitHub Actions on every push (**Actions → Build NULL mod → Artifacts →
`null-mod-jar`**).

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
- **Desktop interference** (off by default, see below).

All settings are saved in `config/null-client.toml`.

### Desktop interference

NULL can show small black "error" windows on your desktop, outside the game window. This is
**off until you turn it on and accept an explanation screen**. Every window is labelled as
coming from the NULL Minecraft mod, can be closed by clicking it or pressing Esc, closes on its
own, never takes keyboard or mouse focus, never imitates real system or security dialogs, and
never appears after Minecraft has closed. NULL does not change anything on your computer and
collects nothing. It is unavailable on macOS and on systems without a desktop, and is never
used on dedicated servers.

Once it is on, the settings screen controls how often windows appear, the shortest pause
between two windows (45 s by default) and a separate chance for each kind of window: small
corner notifications (A), error windows (B), distorted messages (C), larger NULL reports (D)
and the Stage 5 final interference (E). **Show test message** shows one window right away. Stage 1
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
untouched while still allowing structures in the air. The NULL dimension is separate: the
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
