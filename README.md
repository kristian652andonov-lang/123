# Врата и Климатик — Door / AC Reminder

Small Android app with two buttons — **Врата** (door) and **Климатик** (AC).
Tap a button to mark it locked/unlocked or off/on. The app keeps an ongoing
notification in your notification shade at all times, showing the current
status of both, so you can check without opening the app.

Solid black cards with a spinning neon ring around the edge — green when a
button is on, red when it's off — over a pastel beach background, with an
intro-style display font (Bebas Neue, open source/SIL license) for the text.

State is saved on the phone (SharedPreferences), so it's remembered even
after closing the app or restarting the phone. The status notification is
backed by a small foreground service, so it survives closing or swiping away
the app — it doesn't disappear until you actually change a status.

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

- Tap **Врата** to toggle ЗАКЛЮЧЕНА (locked, red edge) / ОТКЛЮЧЕНА (unlocked,
  green edge).
- Tap **Климатик** to toggle ИЗКЛЮЧЕН (off, red edge) / ВКЛЮЧЕН (on, green
  edge).
- The notification updates immediately and stays pinned at the top of your
  notification shade — closing or swiping away the app does not remove it.
