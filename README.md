# JARVIS

A native Android personal assistant — wake word, voice in, Claude in the
middle, spoken answers out, behind a dark arc-reactor HUD.

Built for a Poco X8 Pro (Android 14/15, HyperOS), but nothing in it is
device-specific: minSdk 26, targetSdk 35.

## Stack

- Kotlin + Jetpack Compose (Material 3), single dark theme
- Foreground service for always-on listening and speech playback
- Android `SpeechRecognizer` for wake word + commands, `TextToSpeech` for voice
- Anthropic Messages API called straight from the device — no backend

## Status

Being built in reviewable steps:

1. **Project scaffold** — Gradle, manifest, permissions, Compose shell ✅
2. Core UI shell — HUD screen, status states, transcript, input bar
3. TTS module
4. STT + wake-word module
5. Claude API client + secure API key storage
6. Orchestration (voice/text → Claude → speech)
7. Tool use
8. Foreground service
9. Polish

## Getting the app on your phone (no computer needed)

Every push builds an APK in GitHub Actions:

1. Open this repo's **Actions** tab.
2. Open the latest **Build APK** run (green check).
3. Under **Artifacts**, download `jarvis-debug-apk` (a `.zip`).
4. Unzip and tap `app-debug.apk` to install; allow "install from unknown
   sources" for your browser/files app when asked.

With a computer: `adb install -r app-debug.apk`.

## Your API key

JARVIS talks to Claude with **your own** Anthropic API key, entered on first
launch and stored in `EncryptedSharedPreferences` (Android Keystore-backed).
It is never hardcoded, never logged, and never sent anywhere except
`api.anthropic.com`.
