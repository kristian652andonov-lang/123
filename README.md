# Врата и Климатик — Door / AC Reminder

Small Android app with two buttons — **Врата** (door) and **Климатик** (AC).
Tap a button to mark it locked/unlocked or off/on. The app keeps an ongoing
notification in your notification shade at all times, showing the current
status of both, so you can check without opening the app.

Neon pink and black theme. State is saved on the phone (SharedPreferences),
so it's remembered even after closing the app or restarting the phone.

## Getting the app on your phone (no computer needed)

This repo has a GitHub Action that automatically builds the APK on every
push. To get it:

1. On GitHub, open this repo's **Actions** tab.
2. Open the latest run of **Build APK** (should have a green check).
3. Scroll down to **Artifacts** and download `door-ac-reminder-debug-apk`
   (downloads as a `.zip` containing `app-debug.apk`).
4. Unzip it (most Android file managers / "Files by Google" can unzip
   directly), then tap `app-debug.apk` to install.
5. Android will warn about installing from an unknown source — allow it for
   your browser/files app just for this install.
6. Open the app, allow notifications when asked, and you're set.

## How it works

- Tap **Врата** to toggle заключена (locked) / отключена (unlocked).
- Tap **Климатик** to toggle изключен (off) / включен (on).
- The notification updates immediately and stays pinned at the top of your
  notification shade until you change the status again — it does not need
  the app to stay open in the background.
