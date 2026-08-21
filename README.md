# JARVIS

A native Android personal assistant — wake word, voice in, Claude in the
middle, spoken answers out, behind a dark arc-reactor HUD.

Built for a Poco X8 Pro (Android 14/15, HyperOS); nothing in it is
device-specific. minSdk 26, targetSdk 35.

## What it does

- **Wake word.** Say "Jarvis" and it starts listening for a command. Also a
  mic button, and a text field that's always available if you'd rather type.
- **Talks to Claude directly.** The Anthropic Messages API, called from the
  phone. No backend, no middleman. Your own API key, stored encrypted.
- **Speaks.** Android's own text-to-speech, set to the best British English
  voice on the device, at a rate and pitch tuned to sound unhurried. Replies
  are spoken sentence by sentence as they stream in, so it starts talking
  about a second after Claude starts writing.
- **Remembers the conversation.** Follow-ups continue rather than restart.
- **Does things.** Opens URLs, sets reminders, keeps notes, and (optionally)
  searches the web.
- **Narrate mode.** Toggle it, or say "walk me through it" — it thinks out
  loud step by step instead of handing you a conclusion.
- **Keeps running.** A foreground service owns the microphone and the voice,
  so it works with the app backgrounded and the screen off.

On the voice: this uses the device's own synthesiser, tuned for tone. It does
not clone or imitate any actor's performance from the film.

## Getting it on your phone (no computer needed)

Every push builds an APK in GitHub Actions:

1. Open this repo's **Actions** tab.
2. Open the latest **Build APK** run (green check).
3. Under **Artifacts**, download `jarvis-debug-apk` (a `.zip`).
4. Unzip and tap `app-debug.apk` to install; allow "install from unknown
   sources" for your browser or files app when asked.

With a computer: `adb install -r app-debug.apk`.

## First run

1. Paste your Anthropic API key (starts with `sk-ant-`). It goes into
   EncryptedSharedPreferences, with its master key in the Android Keystore.
   It is never logged and goes nowhere except `api.anthropic.com`.
2. Allow the microphone and notifications when asked. Notifications aren't
   optional decoration — Android requires an ongoing notification for the
   always-listening service.
3. Say "Jarvis", wait for the dial to brighten to LISTENING, then speak.

## Settings

Tap the slider icon at the top right.

- **Voice** — pick from the British English voices installed, adjust rate and
  pitch, preview, or turn spoken replies off entirely.
- **Wake word** — turn continuous listening on or off, change the word.
- **Claude** — model, web search, replace the API key.
- **Persona** — replace the system prompt with your own. Blank restores the
  built-in one. It's also in `Persona.kt` if you'd rather edit code.
- **Conversation** — clear the history.

## Real-world notes for the Poco X8 Pro

**Battery.** Continuous wake-word detection is the expensive part: Android's
recogniser is restarted in a loop the whole time JARVIS is on standby. Expect
a noticeable drain if you leave it armed all day. Three ways to deal with it:

- Turn the wake word off in settings and use the mic button. Standby then
  costs essentially nothing.
- Use "Stand down" on the ongoing notification when you don't need it.
- Swap in a dedicated hotword engine. `PorcupineWakeWordEngine` is a
  documented stub with the four steps written down; nothing above the
  `WakeWordEngine` interface changes.

**HyperOS battery management** will kill background services aggressively.
For it to survive the screen being off:

- Settings → Apps → JARVIS → **Battery saver → No restrictions**
- Lock the app in Recents (drag the card down until the padlock shows)
- Settings → Apps → JARVIS → **Autostart → on**

**Latency.** Roughly a second for the wake word, then speech recognition,
then the model. `claude-opus-5` is the default; `claude-sonnet-5` in settings
noticeably shortens the wait, and `claude-haiku-4-5` more so.

**Cost.** Every reply is billed to your key. Effort is set to `low` for
ordinary spoken answers and raised only in narrate mode, and the system prompt
is sent as a cached prefix, so a normal exchange stays cheap. Web search bills
per search, which is why it's off by default.

**The HUD image.** The dial is drawn in code, so it animates and scales. If
you drop a `jarvis_hud.png` into `app/src/main/res/drawable/`, it is picked up
at runtime and layered underneath the live rings — no code change needed.

## Architecture

```
MainActivity (Compose)  ──binds──►  JarvisService (foreground, microphone)
        │                                    │
        │ observes JarvisUiState             │ owns
        ▼                                    ▼
   HUD / transcript  ◄────────────────  JarvisEngine
                                             │
              ┌──────────────────────────────┼──────────────────────────┐
              ▼                              ▼                          ▼
      VoiceController              AnthropicClient                  JarvisTts
   (wake word + commands)      (streaming /v1/messages)     (British TTS, chunked)
              │                              │
     WakeWordEngine                   ToolRegistry
  (SpeechRecognizer today,        (open_url, set_reminder,
   Porcupine drop-in stub)          notes, web search)
```

`VoiceController` is the only thing that touches the microphone — Android
allows one recognition session at a time, so wake-word and command listening
can never fight over it.

## Building locally

```
./gradlew assembleDebug        # or: gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35).
