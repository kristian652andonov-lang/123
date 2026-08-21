# JARVIS

A voice assistant for your Android phone, in the Iron Man mould: you talk, it
answers out loud in a dry British voice, and it can actually operate the phone
— torch, alarms, timers, volume, apps, calls, texts, calendar, location, web
search. The intelligence is Claude, called directly from the phone over the
Anthropic Messages API.

It is a separate app from the Door & AC reminder in this repo; both live here
and both are built by the same GitHub Action.

## Getting it on your phone (no computer needed)

1. On GitHub, open this repo's **Actions** tab.
2. Open the newest **Build APK** run with a green tick.
3. Under **Artifacts**, download `jarvis-debug-apk` (a `.zip` with the APK in it).
4. Unzip it and tap the APK to install. Android will warn about installing from
   an unknown source — allow it for your browser or files app.
5. Open Jarvis. It asks for the microphone and notification permissions, then
   for an API key.

## The API key

Jarvis talks to Claude with **your** Anthropic API key, so the usage is billed
to your account and nobody else is in the middle.

- Get one at [console.anthropic.com](https://console.anthropic.com) → API keys.
- Paste it into the setup screen.
- It is encrypted with a key held in the phone's hardware-backed keystore and
  stored in the app's private storage. It is sent to `api.anthropic.com` and
  nowhere else.

A note on the trade-off: any key that lives on a phone is only as safe as the
phone. This is the right design for a personal assistant you run yourself, but
don't put a key with a large budget on a device you don't control, and revoke
the key in the console if you lose the phone.

## Talking to it

- **Tap the microphone** and speak. It transcribes, thinks, and replies aloud.
- **Type instead** if you'd rather not talk — the text box does the same thing.
- **Say "Jarvis, …"** with the wake word switched on, and it answers without
  you touching anything.
- **Quick Settings tile**: add the Jarvis tile to your quick settings and one
  tap opens it with the microphone already live.
- **Assistant gesture**: Android's assist gesture / long-press can be pointed
  at Jarvis, since it registers as an assistant.
- Tap the button again while it is talking to cut it off. It stops mid-word,
  as it should.

## What it can actually do

Claude decides when to reach for these; you never name a tool yourself.

| | |
|---|---|
| Phone state | battery, charging, network, volumes, ringer, do-not-disturb, storage, time |
| Hardware | torch on/off, volume per stream, silent/vibrate/normal, play–pause–skip |
| Apps | list installed apps, open one by name, open a URL, open a settings page |
| People | search contacts, call a number, send a text |
| Time | set an alarm, start a timer, add a calendar event |
| Place | last known location with a street address |
| Memory | remember and forget facts about you, kept across restarts |
| World | web search, for anything current — news, weather, prices, opening hours |
| Misc | copy text to the clipboard |

Calling and texting are **off** by default. With them off Jarvis still does the
work — it opens the dialer with the number in, or the messaging app with the
text written — and you press the last button. Turn them on in settings if you
want it to go the whole way on its own.

## Settings worth knowing about

- **Model** — Opus 5 by default. Sonnet 5 is quicker and cheaper; Haiku 4.5 is
  cheapest and fastest of all, and noticeably less sharp.
- **Thinking effort** — low by default, because a voice assistant that pauses
  to ponder is annoying. Raise it for harder questions.
- **What Jarvis calls you** — "sir" by default. Put your name in if you prefer.
- **Voice speed and pitch** — the default is a slightly lowered British voice.
  Install a high-quality en-GB voice in Android's text-to-speech settings and
  it sounds substantially better.
- **Wake word** — see below.

## The wake word, honestly

Android has no free always-on wake word engine, so this runs the normal speech
recogniser in a loop and looks for "Jarvis" in what comes back. That means:

- It needs a foreground service, so there is a permanent notification. That is
  Android's rule, not a choice.
- It uses noticeably more battery than the app sitting idle.
- It listens only while Jarvis is otherwise idle, so the wake loop and a real
  conversation never fight over the microphone.
- To have it appear on screen when you call it from another app, grant
  **Display over other apps** in settings. Without that, Android silently
  refuses to let a background app open anything.
- After a reboot on Android 12 or newer you have to open Jarvis once to start
  it again. Android forbids starting a microphone service from the background,
  and there is no way around it.

If you don't want any of that, leave it off and use the tile or the assist
gesture — one tap, no battery cost.

## What it costs

Every exchange is one or more Messages API calls billed to your key. Replies
are short by design, the tool definitions and persona are marked for prompt
caching so the fixed part of each request is cheap to resend, and the effort
setting is low by default. Ordinary use is pennies; leaving it on Opus at high
effort and holding long conversations is not.

## How it is put together

```
core/ClaudeClient.kt   streaming Messages API client and the tool loop
core/JarvisEngine.kt   the state machine: microphone → Claude → voice
core/Persona.kt        the system prompt, split into a cached half and a live half
core/Prefs.kt          settings and long-term memory
core/SecretStore.kt    keystore-backed encryption for the API key
tools/ToolCatalog.kt   tool definitions sent to the API
tools/ToolRunner.kt    the code that actually operates the phone
voice/Listener.kt      speech recognition
voice/Speaker.kt       text to speech, fed a sentence at a time
service/…              wake word service, quick settings tile, boot receiver
ui/…                   the arc-reactor HUD and screens, in Compose
```

Two details worth calling out:

**It speaks while Claude is still writing.** The reply streams in as text
deltas; the engine buffers them until it has a whole sentence and hands that to
the synthesiser. Waiting for the full answer before speaking made it feel
half-broken.

**The prompt is split in two.** The persona and the tool list never change, so
they sit at the front behind a cache breakpoint. The clock, the battery and
whatever Jarvis remembers about you go after it, where they can change every
turn without throwing the cache away.

## Known limits

- Speech recognition is Android's, so it needs the Google app on most phones,
  and usually a network connection.
- Android does not let an app write calendar events silently, so that one opens
  the calendar with the fields filled in and you tap save.
- It cannot toggle Wi-Fi or Bluetooth directly — no app has been allowed to
  since Android 10 — so it opens the relevant settings page instead.
- The conversation lives in memory. Closing the app starts a fresh one; what it
  has explicitly remembered survives.
