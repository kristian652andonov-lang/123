# ᴄᴏᴍʙᴀᴛʟᴏɢ

A combat tagging plugin for **Paper 1.20.1**, built for box PvP.

Hit someone and a small-caps countdown appears above your hotbar. While it runs
you cannot log out for free, you cannot run a teleport command, and — the part
that matters most on a box server — **you cannot get into a safe zone**. A wall
of red glass seals every entrance in front of you, and it follows you along the
border until your timer runs out.

Only the player in combat can see that wall. It is sent straight to their
client, so the world is never modified and nobody else's screen changes.

It also keeps a log of every fight, kill, death and logout, with a copy of what
the loser was carrying — so when somebody loses their kit to a bug you can hand
it straight back from `/combatlog history`.

---

## Installing

The built jar lives in [`dist/CombatLog-1.0.0.jar`](dist/CombatLog-1.0.0.jar).

1. Drop `CombatLog-1.0.0.jar` into your server's `plugins/` folder.
2. Install [WorldGuard](https://enginehub.org/worldguard) (and WorldEdit)
   if you have not already — this is what the safe zone protection reads.
3. Start the server once to generate `plugins/CombatLog/config.yml`.
4. Edit the config to taste, then `/combatlog reload`.

PlaceholderAPI is optional. If it is installed you get
`%combatlog_in_combat%`, `%combatlog_time%`, `%combatlog_seconds%`,
`%combatlog_opponent%` and `%combatlog_tagged%` for your scoreboard.

## Marking a region as a safe zone

Two ways, and a region only needs to match one of them:

```
/rg flag spawn pvp deny        # recommended - any region with pvp:deny counts
```

or name the region something in `worldguard.safe-zone-detection.region-names`
(`spawn`, `safezone`, `hub`, `lobby` out of the box; partial matching is on, so
`spawn-2` and `mainspawn` are picked up too).

Not sure whether the plugin can see your region? Stand in it and run
`/combatlog zones` — it prints the regions you are inside, the safe zones near
you, and whether it currently considers you protected.

> The world-wide `__global__` region is ignored on purpose. Otherwise a server
> with `pvp deny` set globally would turn the whole map into one safe zone.
> Change that with `worldguard.ignore-global-region`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/combatlog` · `/cl` · `/combat` | `combatlog.use` | Help menu |
| `/combatlog status` | `combatlog.use` | Your own timer |
| `/combatlog check <player>` | `combatlog.check` | Somebody else's timer |
| `/combatlog history [player]` | `combatlog.history` | Open the combat log browser |
| `/combatlog clearhistory` | `combatlog.admin` | Wipe the recorded log |
| `/combatlog tag <player> [seconds]` | `combatlog.admin` | Put a player in combat |
| `/combatlog untag <player>` | `combatlog.admin` | Free a player |
| `/combatlog zones` | `combatlog.admin` | What the plugin sees around you |
| `/combatlog reload` | `combatlog.admin` | Re-read `config.yml` |

## Permissions

| Node | Default | Effect |
| --- | --- | --- |
| `combatlog.use` | everyone | Use the command |
| `combatlog.check` | op | Check other players |
| `combatlog.admin` | op | Tag, untag, zones, clear the log, reload |
| `combatlog.history` | op | Open the combat log browser |
| `combatlog.rollback` | op | Restore, take or drop a recorded inventory |
| `combatlog.bypass` | no | Never gets tagged at all |
| `combatlog.bypass.commands` | no | Blocked commands still work |
| `combatlog.bypass.flight` | no | Keep flying in combat |
| `combatlog.bypass.elytra` | no | Keep gliding in combat |
| `combatlog.bypass.cooldowns` | no | Ignore every item cooldown |

## What you can configure

`config.yml` is grouped and commented end to end. The headlines:

- **general** — combat duration, whether the timer refreshes on every hit, who
  gets tagged (victim, attacker, projectiles, pets), disabled worlds, whether a
  kill frees the killer.
- **theme** — the light blue and orange the whole plugin is drawn from. Change
  these two hex values and every message follows.
- **font** — small caps on or off.
- **actionbar** — the format string, the progress bar (length, symbol, colours,
  the colour it switches to when the timer gets low) and the end-of-timer flash.
- **bossbar / titles / sounds** — all optional, all off or subtle by default.
- **punishment** — kill on quit, drop inventory and XP, lightning, broadcast,
  and any console commands you want run on the player who logged.
- **worldguard** — how a region is recognised as a safe zone, whether walking,
  teleporting and pearling in are refused, and how hard players get pushed back.
- **barrier** — the red glass wall: radius, height, update rate, whether corners
  are sealed, the animation (`WAVE`, `PULSE` or `STATIC`), the blocks it cycles
  through, and its particles.
- **restrictions** — a command blacklist (or whitelist), flight, elytra,
  riptide and chorus fruit.
- **cooldowns** — per-item cooldowns keyed by material. Golden apples, enchanted
  golden apples, ender pearls and firework rockets are set up already; add any
  other material as a new entry. Each one can be set to apply always or only in
  combat, and rockets can be limited to when the player is actually gliding.
  There is also one shared cooldown for all other food.
- **elytra** — block gliding in combat, a cooldown after combat ends, a minimum
  gap between glides, and whether an active glide is cut short on tagging.
- **history** — what gets recorded, how many entries are kept, how many of them
  keep their item data, and how often the log is written to disk.
- **gui** — the browser's title, player heads on or off, whether a rollback asks
  for confirmation, and the icon used for each kind of entry.

## The combat log browser

`/combatlog history` opens a paginated list of everything that happened, newest
first. Each entry is the loser's head:

```
 ᴋʀɪsᴛɪᴀɴ ᴋɪʟʟᴇᴅ sᴛᴇᴠᴇ
 4ᴍ ᴀɢᴏ · ᴡᴏʀʟᴅ 128, 64, -302
 ᴄᴀᴜsᴇ › ᴇɴᴛɪᴛʏ ᴀᴛᴛᴀᴄᴋ - ᴋʀɪsᴛɪᴀɴ
 ʜᴇʟᴅ › ɴᴇᴛʜᴇʀɪᴛᴇ sᴡᴏʀᴅ
 › ᴄʟɪᴄᴋ ᴛᴏ ᴏᴘᴇɴ ᴛʜᴇɪʀ ɪɴᴠᴇɴᴛᴏʀʏ
```

The hopper at the bottom cycles the filter — everything, fights, kills, deaths,
combat logs, rollbacks. `/combatlog history Steve` narrows it to one player, and
that works for offline players too, because the log remembers their UUID.

Clicking an entry shows both players, the cause, the weapon and the exact spot,
with a button to teleport there.

## Rolling an inventory back

Open an entry and click **open their inventory**. You get the victim's gear laid
out exactly as they had it — main inventory, hotbar, armour and off hand, with
every enchantment and custom name intact. Three buttons:

- **give it back to \<player>** — replaces what they are carrying now. They have
  to be online. Their current inventory is saved to the log as a `ROLLBACK`
  entry first, so if you restore the wrong thing you can undo it the same way.
  Behind a confirmation screen unless you turn `gui.confirm-rollback` off.
- **put it in my inventory** — hands the items to you instead. Anything that
  does not fit drops at your feet.
- **drop it where it happened** — spills the items back at the death spot.

Only the newest `history.max-snapshots` entries keep their items — that is the
bulky part of the file. Older entries still show who killed who.

## Notes

- Item cooldowns are deliberately **kept across a reconnect**, so relogging
  cannot be used to reset a gapple. They are held in memory, so they do reset on
  a full server restart.
- `punishment.punish-on-kick` is off by default. Turn it on only if you are sure
  your server does not kick people for lag, or you will be killing players for
  your own timeouts.
- The log lives in `plugins/CombatLog/history.yml`. It is written every
  `history.save-interval-seconds` on a background thread and always on shutdown.
- The wall is drawn only when a tagged player is *outside* a safe zone. If
  somebody is tagged while already standing in spawn they are not walled in —
  they can walk out, they just cannot walk back in.

## Building from source

```
mvn clean package
```

Java 17+, output lands in `target/CombatLog-1.0.0.jar`.
