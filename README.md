# Isle — personal, Android-only Dynamic Island

A **personal** Flutter + native Kotlin app for **one Android device** that
mimics the iPhone Dynamic Island:

- A floating, notch-style **pure-black pill overlay** near the top of the
  screen — it blends into the punch-hole camera when idle.
- On a new notification it **expands** to show the app icon, app name and the
  notification text wrapped over up to 4 compact lines (longer texts are
  ellipsized and stay on screen longer), then **collapses back**.
- **Every** notification in the shade is mirrored, including ongoing ones —
  minimize a music player and its track keeps showing in the island.
- The pill never blocks touches — taps pass straight through to the app below.
- Works **when the app is fully closed** (foreground service) and **restarts
  after reboot** (BOOT_COMPLETED receiver).
- The overlay and the notification listener are **hand-written native Kotlin**
  — no third-party overlay / notification-listener packages, no analytics, no
  crash reporting, no telemetry.

> ⚠️ **Android only.** iOS does not allow apps to draw overlays over other apps
> or to read other apps' notifications, so this feature cannot exist on iOS.
> This project intentionally contains no iOS implementation of the feature.

---

## ⚠️ Security model (read this first)

This app reads the content of **all** notifications — including OTPs, banking
alerts and private messages. These rules are enforced **structurally**, not by
promise:

| Constraint | How it is enforced in this codebase |
|---|---|
| Never persist captured content | No database, no files. The only disk state is the user's ON/OFF boolean in `SharedPreferences`. Notification content exists only as in-memory objects/views and is cleared when the pill collapses (`OverlayForegroundService.clearContent`). |
| Never transmit content | The app's manifest declares **no `android.permission.INTERNET`**. A release build therefore has no network capability at all. |
| In-memory only | Alerts flow: `NotificationListener` (RAM) → `DynamicIsland` (RAM) → overlay views (RAM) → cleared. |
| No content logging | No log statement anywhere prints title/text. Logs are limited to service-state warnings. |
| No opaque third-party packages | The overlay, listener, foreground service and boot receiver are ~4 native Kotlin files you can read end to end. |
| Generic foreground-service notification | Shows only "Notification overlay running". Never content, never a sender icon. |
| No analytics/telemetry | None exist. No dependencies added beyond the Flutter SDK. |

**This app is for you only.** Do not install it on someone else's device or
publish it without re-evaluating each decision above.

---

## What is where

```
lib/
  main.dart                      Settings screen: master switch, permission
                                 deep-links, test button, security reminder.
  src/native_bridge.dart         Thin MethodChannel/EventChannel bridge.
  src/island_preview.dart        The island pill widget (in-app mirror used
                                 as live preview + tests; the always-on
                                 overlay is native).
android/app/src/main/kotlin/com/example/dynamic_island_app/
  MainActivity.kt                Flutter entry; hosts the channels.
  NotificationListener.kt        NotificationListenerService → in-memory alert.
  OverlayForegroundService.kt    Foreground service that draws the WindowManager
                                 island, expands/collapses it, clears content.
  BootReceiver.kt                BOOT_COMPLETED / MY_PACKAGE_REPLACED restart.
  DynamicIsland.kt               Shared config (ON/OFF only), in-memory alert
                                 bus, permission checks.
android/app/src/main/AndroidManifest.xml
                                 Permissions, service/receiver declarations,
                                 and (deliberately) NO INTERNET permission.
android/app/src/main/res/drawable/ic_stat_island.xml
                                 Generic pill icon for the service notification.
```

---

## Build & install

Requires Flutter (stable, recent) + Android SDK on your machine.

```bash
cd dynamic-island
flutter pub get
flutter analyze          # should be clean
flutter run              # on your device, or:
flutter build apk --debug
```

> The sandbox that generated this code could not run Flutter/Gradle, so run
> `flutter analyze` once locally. If anything reports, it will be a small
> signature/deprecation fix — the architecture itself is standard.

---

## Step-by-step setup on the phone (first run)

The app's settings screen walks you through this, but here is the manual
version with the "why" for each step.

### Step 0 — Launch & master switch

Open **Isle** and flip **"Show Isle"** on.
The screen auto-opens the first permission you still need.

### Step 1 — "Display over other apps" (SYSTEM_ALERT_WINDOW)

Android has **no normal permission dialog** for overlays. It must be granted
from a special settings page:

- Settings → Apps → Special app access → **Display over other apps** →
  find **Isle** → allow.

(Or press the **"Display over other apps"** row in the app, which deep-links
there via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.)

Until this is granted the overlay service will not start — that's checked in
`DynamicIsland.startOverlayService` before anything runs.

### Step 2 — Notification access (NotificationListenerService)

Reading other apps' notifications also has **no permission dialog**. It must
be enabled manually:

- Settings → Apps → Special app access → **Notification access** →
  find **Isle notification access** → toggle ON.
  (Android may warn that the app will be able to read all notifications —
  that is exactly what this feature needs, and the content never leaves RAM.)

Only notifications whose per-channel switch is ON in the sender's own app
settings are delivered (Android handles that filtering for you).

### Step 3 — Notifications permission (Android 13+)

If prompted, allow notifications. This only lets the small persistent item
("Notification overlay running") appear. If you deny it, the overlay **still
works** — the service item is simply hidden.

### Step 4 — Battery optimization exemption

To reliably survive "app closed" and reboot:

- Settings → Apps → Special app access → **Battery optimization** → choose
  **Isle** → **Don't optimize**.

The app can also raise the one-time `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
dialog (button in-app). Without the exemption, aggressive vendors may kill the
service after you close the app.

### Step 5 — Verify

Press **"Show test alert"** in the app: the pill at the top of the screen
expands with a synthetic multi-line alert, holds a few seconds and collapses.
Then:
1. Send yourself a real notification from another app.
2. Swipe Isle away from Recents — send another notification.
3. Reboot the phone — wait ~30 s — send another notification.

---

## How the pieces work

1. **NotificationListener** (native) — Android binds it to the system after
   you grant notification access. On each posted notification it builds one
   `NotificationAlert` (RAM only): key, package, app label, title, text, icon
   — and hands it to `DynamicIsland.dispatchAlert`.

   **Island-only mode (default ON, opt-out in Permissions):** regular
   notifications appear *only* in the island — right after reading one, the
   listener cancels the system copy (`cancelNotification(key)`), so the
   notification shade never accumulates them. The trade-off you choose: once
   the brief flash ends, there is no shade history or quick reply for that
   notice (nothing is stored to disk). Ongoing notifications (media players,
   calls, downloads) are **exempt** — cancelling them would strip live
   playback/call controls and the apps re-post them anyway — and keep
   mirroring into the island as before.

2. **DynamicIsland** (native) — in-memory alert bus. Forwards to the running
   overlay service (or holds one alert while the service starts). Also exposes
   the ON/OFF boolean and permission checks.

3. **OverlayForegroundService** (native) — a `TYPE_APPLICATION_OVERLAY`
   window. While idle or flashing a notification it sets
   `FLAG_NOT_TOUCHABLE` so all taps pass through; while a live mode (media /
   call / timer) is showing it becomes touchable so you can tap to expand. It posts the generic foreground notification (required
   by Android). The idle pill is a slim **empty black capsule (60×34 dp)
   positioned in place of the front camera** — it centers itself vertically on
   the display-cutout camera rect (falling back to the status-bar band), and
   its top is pinned there, so on an alert it morphs (60×34 → ≤300 wide,
   height grows with the body, capped at 160 dp) via a `ValueAnimator`, then
   fades in the app icon + label + the text (up to 4 lines, ellipsized) once
   the pill has grown wide enough, holds a few seconds (longer for longer
   text), collapses back to the camera-sized capsule, and calls
   `clearContent()` (drop all references). Ongoing notifications (e.g. a
   minimized media player) are mirrored too.

4. **BootReceiver** (native) — on `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`,
   restarts the service **only if** the user's ON/OFF pref is ON and the
   overlay permission is still granted.

5. **Foreground service type** — Android 14+ requires a declared type; this
   uses `specialUse` with a human-readable subtype, declared in the manifest.

### Live island modes (priority: call > timer > media > flash)

The pill is one unified component with several modes; when several are active
at the same time the highest priority wins the screen:

1. **Call** — `IslandCallTracker` registers ONLY
   `TelephonyManager.listen(LISTEN_CALL_STATE)` (needs `READ_PHONE_STATE`,
   runtime-revocable; the callback's phone-number argument is ignored and no
   telephony identifiers are ever read — see the manifest comment). While a
   call is off-hook the island shows a live duration timer.
2. **Timer / stopwatch** — started from the app's "Live island modes" card;
   the countdown/stopwatch state lives **only in RAM** (by design it does not
   survive a process restart) and ticks live in the pill.
3. **Media** — `IslandMediaTracker` uses `MediaSessionManager`
   (gated behind the notification-access grant, no extra permission) to track
   active sessions of any app. Only sessions whose PlaybackState is PLAYING
   qualify (paused/stopped sessions are ignored — many apps keep a paused
   session registered forever, which would pin a permanent pill on screen);
   among playing ones the most recently active wins, measured by
   `PlaybackState.lastPositionUpdateTime`.
   While something plays, the compact pill shows album art + an animated
   waveform; tapping expands to title/artist + real play/pause/skip buttons
   driven by `MediaController.transportControls` (also exposed to Flutter as
   `mediaPlayPause` / `mediaNext` / `mediaPrev` method calls). Metadata,
   position/duration and a PNG byte copy of the art are pushed to the in-app
   mirror over the `media_events` EventChannel whenever the native
   callbacks fire — never polled, never persisted.
4. **Notification flash** — a *temporary interrupt*: it briefly shows on top
   of any live persistent mode, then the pill reverts to the persistent state
   underneath (media/call/timer are never lost). Among persistent modes the
   priority is call > timer > media. **Tap-to-open:** once the flash has
   expanded (armed after the 220 ms expand), tapping it fires the original
   notification's `contentIntent` — exactly what tapping it in the shade
   would do — and collapses. A `CanceledException` (source app gone) just
   collapses silently. The `PendingIntent` lives in a RAM-only map keyed by
   notification key and is discarded on replace, auto-collapse, shade
   removal, tap, or service death; only the key ever crosses the channel
   (`openNotification(id)`).

Tap a persistent pill to expand/collapse its detail view. Expand/collapse use
a spring-like `PathInterpolator(0.34, 1.56, 0.64, 1)` (mirrored in Dart by
`Curves.easeOutBack`).

**Lock screen:** Android never allows `TYPE_APPLICATION_OVERLAY` windows on
top of a secure (PIN/pattern/biometric) lock screen — an OS-level guarantee
this app respects and does not try to bypass (no `FLAG_SHOW_WHEN_LOCKED`).
The island reappears the moment the phone is unlocked.

**"…is displaying over other apps" notice:** Android re-posts this system
transparency warning whenever an overlay app becomes visible (e.g. after
each unlock). The OS cannot be prevented from posting it, but with
notification access granted, `NotificationListener` recognizes exactly that
phrase ("displaying/displayed/showing/appearing over other apps") and calls
`cancelNotification(key)` for it — nothing else is ever dismissed. On by
default per user request; opt out via the switch in "Permissions & special
access".

5. **Unlock flourish (OPT-IN, default OFF)** — the service registers a
   receiver for `ACTION_USER_PRESENT`, the standard broadcast fired when the
   device becomes unlocked & interactive (regardless of method; Android does
   not let apps observe *how* it was unlocked, and this app never touches
   biometric APIs). If — and only if — the user enables "Unlock flourish" in
   the app, the compact pill shows a pulsing open-lock glyph for ~1.4 s and
   then reverts to whatever was underneath (idle, media, call, timer). With
   the default setting, nothing appears over your apps after an unlock. The
   flourish can only play after unlock anyway, since the OS hides the
   overlay on the secure lock screen.

### Why there are two "islands"

The always-on island is the native Kotlin window above — it works with zero
Flutter code running, which is what makes "works when app is fully closed"
and "restarts after reboot" possible. `IslandPreview` (Dart) is an in-app
mirror of the same pill: same sizes/colors/timings. When the app UI is open,
native events are also streamed to it so you can watch the island's behavior
documented live in Dart, and it makes the widget easy to test. Nothing about
the real feature depends on it.

### Method/event channels (all in-process, none persisted)

| Channel | Direction | Payload |
|---|---|---|
| `service` | Dart → native | getStatus, setEnabled, open *settings deep-links, fireTestAlert, request*Permission, startCountdown, startStopwatch, stopTimer, openNotification, mediaPlayPause, mediaNext, mediaPrev |
| `status_events` | native → Dart | `"status"` ping (UI re-reads flags) |
| `alert_events` | native → Dart | transient alert preview while the app UI is open (icon intentionally stays native) |
| `media_events` | native → Dart | live media preview (title/artist/art PNG bytes/playing/position/duration), pushed on MediaController callbacks; `null` when idle |

**Why MethodChannel for commands but EventChannel for alerts?**
A `MethodChannel` is request/response: Dart invokes a method and awaits one
reply — perfect for `getStatus`, `setEnabled`, settings deep-links or
`startCountdown`. Notification alerts are the opposite shape: the native
`NotificationListenerService` produces an unbounded stream of events at
times Dart never requests. `EventChannel` models exactly that — Dart opens a
broadcast stream (`receiveBroadcastStream`) and the native side holds an
`EventSink` to push into whenever an alert arrives, closing it when the UI
detaches. Using a MethodChannel here would force polling or inverted calls;
the EventChannel is the push-based primitive made for this.

**Replace, not queue.** When a new notification arrives while one is
showing, the island *replaces* it in place (height/content morph) instead of
queueing: the island mirrors the "now" of the shade, queued stale alerts
would pop up late and surprising, and bursts (chats, downloads) would keep
the pill expanded for minutes. See `showAlert`'s doc comment.

---

## Tuning

All times/colors live in one obvious place per implementation:

- Native: constants at the top of `OverlayForegroundService.kt`
  (`EXPAND_MS`, `HOLD_MS`, `COLLAPSE_MS`, pill/expanded sizes).
- Dart mirror: `island_preview.dart` (`_expandDuration`, `_holdDuration`,
  `_collapseDuration`, sizes).

To include ongoing notifications (media players, other apps' foreground
services) in the island, delete the `if (sbn.isOngoing) return` block in
`NotificationListener.kt`.

## License / ownership

Personal use. Do not distribute.
