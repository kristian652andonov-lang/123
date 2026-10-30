# NULL — architecture

Package root: `dev.kristian.nullmod` (mod id `null`). Everything a dedicated server loads
lives outside `client`; everything that draws, plays sound or opens windows lives under
`client` and is only reached from `NullClient`.

## Core (`core`, `event`, `network`, `config`, `registry`, `command`)

- **`CorruptionData`** (SavedData on the overworld, `data/null_corruption.dat`): stage,
  highest stage, the scaled *corruption clock*, real active time, pause, speed, moon state,
  world seed for corruption patterns, and a free-form compound per system
  (`systemTag(name)`).
- **`CorruptionManager`** advances the clock every server tick while someone is online
  (`progression.speed × /null speed`), moves the stage forward when a threshold from the
  config is crossed (never backwards on its own), ticks every registered
  `CorruptionSystem`, isolates a failing system instead of crashing the server, and syncs
  the state to clients (`StageSyncPayload`) on join, on change and every 20 s.
- **`CorruptionStageManager`** turns config minutes into thresholds and progress values.
- **`RegionCorruption`** is a seeded value-noise field: regions of the world corrupt at
  different speeds without storing anything.
- **`RandomEventManager`** gives every player their own schedule (shorter waits in later
  stages, × event frequency), picks one eligible `NullEventDefinition` by weight (stage
  range, per-player and world cooldowns, recent-history exclusion, config switches and
  per-tag frequency multipliers), and runs it: server action → client effect → sound.
  `/null event <id>` forces one.
- **`WorldMemory`** remembers recent happenings so chat and events can refer back to them.
- **`BuildProtection`** guards every block change outside the NULL dimension: chunks where
  players placed blocks are tagged (chunk attachment), and areas are scanned for
  built-looking blocks and block entities; only `#null:corruptible` terrain may be replaced
  and structures only go into air.
- **Payloads:** `StageSyncPayload`, `ClientEffectPayload` (effect id + seed, intensity,
  duration, optional position, data, text), `FakePlayersPayload`, `TransitionPayload`.
  Client handlers are installed through `ClientPacketSink`, so the server never loads a
  client class.
- **Config:** `NullConfig` (per-world, synced to clients) and `NullClientSettings`
  (per-player; can only weaken effects — combined in `ClientLimits`).

## Systems

Each system has a `*Module.init(modBus, gameBus)` (and a `*ClientModule` on the client),
registers its own registries, listeners, `CorruptionSystem` and random events, and keeps
its persistent state in `CorruptionData.systemTag`.

| Area | Server | Client |
|---|---|---|
| World corruption & structures | `world`, `world.structure` | — |
| Visual distortion | `visual` (events) | `client.visual` |
| Audio corruption | `audio` (events) | `client.audio` |
| Shattering moon | `moon` | `client.moon` |
| NULLROOT | `entity.nullroot` | `client.nullroot` |
| Visitors, simulated players | `entity`, `entity.visitor`, `entity.fakeplayer` | `client.visitor`, `client.fakeplayer` |
| Corrupted chat | `chat` | — |
| NULL dimension & transition | `dimension` | `client.dimension` |
| Corrupted main menu | — | `client.menu` |
| Desktop interference, settings | — | `client.desktop`, `client.settings` |

## Testing

- `gametest/*GameTests` run on a real GameTest server in CI (`./gradlew runGameTestServer`).
- `client/autotest/ClientAutoTest` is a scripted client session that CI runs on a virtual
  display: main menu → settings → new world → every stage with every event forced → NULL
  dimension → player list → back to Stage 1 → quit, with screenshots. It only runs when the
  JVM has `-Dnull.autotest` set.
- `tools/syntax_check.sh` parses the sources without Minecraft on the classpath.
- `ci/api-*.sh` and the *NULL API query/export* workflows index the real 1.21.1 game jar on a
  runner, for development environments that cannot download Minecraft.
