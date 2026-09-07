# Island Classification + System Hardware States Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route regular notifications (SMS, social, email) to a floating banner below the island while reserving the island for live activities and system hardware events (charging, ringer, DND, privacy dots).

**Architecture:** A new `IslandNotificationClassifier` decides routing at the `NotificationListener` boundary. A new `IslandSystemTracker` registers broadcast receivers for battery/ringer/DND events and an `AppOpsManager` watcher for camera/mic. `OverlayForegroundService` gains a second `WindowManager` view (the floating banner) and a third (the privacy dot), plus a new `SYSTEM_STATE` mode. Flutter preview mirrors both the banner card and privacy dot via two new EventChannels.

**Tech Stack:** Kotlin (Android API 26+), Flutter/Dart, WindowManager, AppOpsManager, BroadcastReceiver, EventChannel.

## Global Constraints

- minSdk 26. All dp conversions use the existing `private fun dp(v: Int): Int` helper in `OverlayForegroundService`.
- No new Gradle dependencies. No INTERNET permission.
- No notification content written to disk or logged.
- All new Kotlin files use `package com.example.dynamic_island_app`.
- EventChannel names: `com.example.dynamic_island_app/<name>`.
- New tracker objects follow the `IslandMediaTracker` pattern: `object`, `start(context)`, `stop(context)`.
- New drawables: white fill `#FFFFFFFF`, 24×24dp viewport.

---

## File Map

| File | Role |
|------|------|
| `IslandNotificationClassifier.kt` | NEW — pure classifier, no side-effects |
| `IslandSystemTracker.kt` | NEW — battery, ringer, DND, AppOps receivers |
| `NotificationListener.kt` | Modified — use classifier to route alert vs banner |
| `DynamicIsland.kt` | Modified — banner + system state fields, sinks, dispatch |
| `OverlayForegroundService.kt` | Modified — banner view, SYSTEM_STATE mode, privacy dot view |
| `AndroidManifest.xml` | Modified — add PACKAGE_USAGE_STATS |
| `MainActivity.kt` | Modified — privacy indicator permission tile |
| `res/drawable/ic_charging.xml` | NEW |
| `res/drawable/ic_battery.xml` | NEW |
| `res/drawable/ic_battery_low.xml` | NEW |
| `res/drawable/ic_ring.xml` | NEW |
| `res/drawable/ic_vibrate.xml` | NEW |
| `res/drawable/ic_silent.xml` | NEW |
| `res/drawable/ic_focus.xml` | NEW |
| `lib/src/native_bridge.dart` | Modified — bannerEvents(), privacyEvents() |
| `lib/src/island_preview.dart` | Modified — BannerPreview widget, privacy dot |
| `lib/main.dart` | Modified — System hardware states settings section |

---

### Task 1: Notification classifier + routing

**Files:**
- Create: `android/app/src/main/kotlin/com/example/dynamic_island_app/IslandNotificationClassifier.kt`
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/NotificationListener.kt`

**Interfaces:**
- Produces: `IslandNotificationClassifier.isLiveActivity(sbn): Boolean` consumed by `NotificationListener`

- [ ] **Step 1: Create `IslandNotificationClassifier.kt`**

```kotlin
package com.example.dynamic_island_app

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Decides whether an incoming notification belongs in the Dynamic Island
 * (live activity / system state) or the floating banner below it.
 *
 * Island-worthy: call, navigation, transport, ongoing service/progress.
 * Banner-worthy: message, email, social, event, promo, uncategorised non-ongoing.
 */
object IslandNotificationClassifier {

    fun isLiveActivity(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return when (n.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT -> true
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS -> sbn.isOngoing
            else -> false
        }
    }
}
```

- [ ] **Step 2: Update `NotificationListener.onNotificationPosted()` to use the classifier**

Find the line `DynamicIsland.dispatchAlert(this, alert)` near the bottom of `onNotificationPosted()`. Replace it with:

```kotlin
if (IslandNotificationClassifier.isLiveActivity(sbn)) {
    DynamicIsland.dispatchAlert(this, alert)
} else {
    DynamicIsland.dispatchBanner(this, alert)
}
```

The `island-only` shade-cancel block runs before this call and already guards on `sbn.isOngoing`. No change needed there — banner notifications (non-ongoing) are already handled by the existing cancel logic when `islandOnly` is enabled.

- [ ] **Step 3: Analyze and build**

```bash
cd /Users/niranjanrimal/Desktop/dynamic_island_app
flutter analyze android/ 2>&1 | tail -5
```

Expected: `DynamicIsland.dispatchBanner` will be unresolved (we add it in Task 2). The error confirms the call site is wired correctly. Note the exact error text for Task 2 verification.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/IslandNotificationClassifier.kt \
        android/app/src/main/kotlin/com/example/dynamic_island_app/NotificationListener.kt
git commit -m "feat: notification classifier — routes live activities to island, rest to banner"
```

---

### Task 2: DynamicIsland — banner + system state plumbing

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/DynamicIsland.kt`

**Interfaces:**
- Consumes: `NotificationAlert` (already exists)
- Produces:
  - `DynamicIsland.dispatchBanner(context, alert)` used by `NotificationListener`
  - `DynamicIsland.onBannerRemoved(context, key)` used by `NotificationListener`
  - `DynamicIsland.pendingBanner: NotificationAlert?` used by `OverlayForegroundService`
  - `DynamicIsland.onSystemStateChanged(event)` used by `IslandSystemTracker`
  - `DynamicIsland.currentSystemState: SystemStateEvent?` used by `OverlayForegroundService`
  - `DynamicIsland.cameraActive: Boolean`, `DynamicIsland.micActive: Boolean` used by `OverlayForegroundService`
  - `DynamicIsland.setBannerSink()`, `DynamicIsland.setPrivacySink()` used by `MainActivity`
  - `DynamicIsland.forwardBannerState()`, `DynamicIsland.forwardPrivacyState()` used by `OverlayForegroundService`

- [ ] **Step 1: Add `SystemStateEvent` data class at the top of `DynamicIsland.kt`** (after the existing `TimerState` class)

```kotlin
/** What the island shows during a brief system-hardware flash. RAM only. */
data class SystemStateEvent(
    val iconRes: Int,
    val label: String,
    val bodyText: String,
    val accentColor: Int,   // ARGB tint applied to the icon
    val holdMs: Long
)
```

- [ ] **Step 2: Add new `@Volatile` state fields to the `DynamicIsland` object**

After the existing `@Volatile var timerState: TimerState? = null` line, add:

```kotlin
/** Banner notification queued while the banner view is already showing. */
@Volatile
var pendingBanner: NotificationAlert? = null

/** The system-state event currently flashing in the island (RAM only). */
@Volatile
var currentSystemState: SystemStateEvent? = null

/** Privacy: true while any app actively uses the camera. */
@Volatile
var cameraActive: Boolean = false

/** Privacy: true while any app actively uses the microphone. */
@Volatile
var micActive: Boolean = false
```

- [ ] **Step 3: Add banner + system-state dispatch methods**

After the existing `dispatchAlert()` function, add:

```kotlin
/**
 * Entry point for banner-worthy notifications (message, email, social, etc.).
 * These never enter the island; they show in the floating dark card below it.
 */
fun dispatchBanner(context: Context, alert: NotificationAlert) {
    if (!isOverlayEnabled(context)) return
    storeContentIntent(alert.key, alert.contentIntent)
    forwardAlertPreview(alert)         // mirrors to Flutter preview alert stream
    forwardBannerState(alert)          // new banner stream for BannerPreview widget
    OverlayForegroundService.instance?.showBanner(alert) ?: run {
        pendingBanner = alert
        if (canDrawOverlays(context)) startOverlayService(context)
    }
}

/** Banner notification dismissed (swiped or tapped) — collapse immediately. */
fun onBannerRemoved(context: Context, key: String) {
    if (!isOverlayEnabled(context)) return
    if (pendingBanner?.key == key) pendingBanner = null
    OverlayForegroundService.instance?.hideBannerForKey(key)
}

/**
 * Called by [IslandSystemTracker] whenever a hardware event fires
 * (charging, battery low, ringer mode change, DND change).
 */
fun onSystemStateChanged(event: SystemStateEvent?) {
    currentSystemState = event
    OverlayForegroundService.instance?.refresh()
    notifyStatusChanged()
}

/** Called by [IslandSystemTracker] whenever camera/mic active state changes. */
fun onPrivacyChanged(camera: Boolean, mic: Boolean) {
    cameraActive = camera
    micActive = mic
    OverlayForegroundService.instance?.refreshPrivacyDot()
    forwardPrivacyState()
}
```

- [ ] **Step 4: Add banner + privacy EventChannel sinks**

After the existing `private var mediaSink: EventChannel.EventSink? = null` block, add:

```kotlin
@Volatile private var bannerSink: EventChannel.EventSink? = null
@Volatile private var privacySink: EventChannel.EventSink? = null

fun setBannerSink(sink: EventChannel.EventSink?) { bannerSink = sink }
fun setPrivacySink(sink: EventChannel.EventSink?) { privacySink = sink }

fun forwardBannerState(alert: NotificationAlert?) {
    val sink = bannerSink ?: return
    val payload: Map<String, Any?>? = if (alert == null) null else mapOf(
        "key"      to alert.key,
        "appLabel" to alert.appLabel,
        "title"    to alert.title,
        "text"     to alert.text
    )
    mainHandler.post { sink.success(payload) }
}

fun forwardPrivacyState() {
    val sink = privacySink ?: return
    val cam = cameraActive; val mic = micActive
    val payload: Map<String, Boolean>? =
        if (!cam && !mic) null else mapOf("camera" to cam, "microphone" to mic)
    mainHandler.post { sink.success(payload) }
}
```

- [ ] **Step 5: Wire banner dismiss in `onNotificationRemoved()`**

In the existing `onNotificationRemoved()` function, after `OverlayForegroundService.instance?.onAlertRemoved(key)`, add:

```kotlin
onBannerRemoved(context, key)
```

- [ ] **Step 6: Analyze**

```bash
flutter analyze android/ 2>&1 | tail -5
```

Expected: references to `OverlayForegroundService.showBanner`, `hideBannerForKey`, `refreshPrivacyDot` are unresolved — that's fine, they're added in Tasks 5-6. All other symbol errors should be zero.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/DynamicIsland.kt
git commit -m "feat: DynamicIsland — banner dispatch, system state + privacy fields and sinks"
```

---

### Task 3: Seven new vector drawables

**Files:**
- Create: `android/app/src/main/res/drawable/ic_charging.xml`
- Create: `android/app/src/main/res/drawable/ic_battery.xml`
- Create: `android/app/src/main/res/drawable/ic_battery_low.xml`
- Create: `android/app/src/main/res/drawable/ic_ring.xml`
- Create: `android/app/src/main/res/drawable/ic_vibrate.xml`
- Create: `android/app/src/main/res/drawable/ic_silent.xml`
- Create: `android/app/src/main/res/drawable/ic_focus.xml`

- [ ] **Step 1: Create `ic_charging.xml` (lightning bolt)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M7,2v11h3v9l7,-12h-4l4,-8z"/>
</vector>
```

- [ ] **Step 2: Create `ic_battery.xml` (battery outline)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33C16.4,22 17,21.4 17,20.67V5.33C17,4.6 16.4,4 15.67,4zM15,20H9V6h6V20z"/>
</vector>
```

- [ ] **Step 3: Create `ic_battery_low.xml` (battery with exclamation)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33C16.4,22 17,21.4 17,20.67V5.33C17,4.6 16.4,4 15.67,4zM13,18h-2v-2h2V18zM13,14h-2V9h2V14z"/>
</vector>
```

- [ ] **Step 4: Create `ic_ring.xml` (bell)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"/>
</vector>
```

- [ ] **Step 5: Create `ic_vibrate.xml` (phone with vibration lines)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M0,15h2V9H0v6zM3,17h2V7H3v10zM22,9v6h2V9h-2zM19,17h2V7h-2v10zM16,5H8C6.9,5 6,5.9 6,7v10c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7C18,5.9 17.1,5 16,5zM16,17H8V7h8v10z"/>
</vector>
```

- [ ] **Step 6: Create `ic_silent.xml` (bell with slash)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M18,10.5V11l-2,-2V7.5c0,-2.28 -1.46,-4.18 -3.5,-4.81V2c0,-0.83 -0.67,-1.5 -1.5,-1.5S9.5,1.17 9.5,2v0.69C8.13,3.06 7,4.14 6.44,5.57L1.27,0.41 0,1.7l3.11,3.11C3.04,5.33 3,5.87 3,6.5V16L1,18v1h14.73l2,2L19,19.73 18,18.73V10.5zM12,22c1.1,0 2,-0.9 2,-2h-4C10,21.1 10.9,22 12,22z"/>
</vector>
```

- [ ] **Step 7: Create `ic_focus.xml` (crescent moon)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4 -0.44,-0.06 -0.9,-0.1 -1.36,-0.1z"/>
</vector>
```

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/res/drawable/ic_charging.xml \
        android/app/src/main/res/drawable/ic_battery.xml \
        android/app/src/main/res/drawable/ic_battery_low.xml \
        android/app/src/main/res/drawable/ic_ring.xml \
        android/app/src/main/res/drawable/ic_vibrate.xml \
        android/app/src/main/res/drawable/ic_silent.xml \
        android/app/src/main/res/drawable/ic_focus.xml
git commit -m "feat: add system-state vector drawables (charging, battery, ringer, focus)"
```

---

### Task 4: IslandSystemTracker

**Files:**
- Create: `android/app/src/main/kotlin/com/example/dynamic_island_app/IslandSystemTracker.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `DynamicIsland.onSystemStateChanged(SystemStateEvent?)`, `DynamicIsland.onPrivacyChanged(Boolean, Boolean)`
- Produces: `IslandSystemTracker.start(context)`, `IslandSystemTracker.stop(context)` used by `OverlayForegroundService`

- [ ] **Step 1: Create `IslandSystemTracker.kt`**

```kotlin
package com.example.dynamic_island_app

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Monitors system hardware events and forwards state to [DynamicIsland].
 *
 * SECURITY: all data is RAM-only and transient; no content is written
 * to disk or transmitted. Privacy-dot monitoring (camera/mic) is opt-in
 * and requires the user to grant PACKAGE_USAGE_STATS in Settings.
 */
object IslandSystemTracker {

    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var opsWatcher: AppOpsManager.OnOpActiveChangedListener? = null

    // Battery: track last reported level to avoid repeat flashes
    private var lastBatteryPct = -1
    private var lastCharging = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        registerReceivers(app)
        startOpsWatcher(app)
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        stopOpsWatcher(app)
        lastBatteryPct = -1
        lastCharging = false
        DynamicIsland.currentSystemState = null
        DynamicIsland.cameraActive = false
        DynamicIsland.micActive = false
    }

    // ------------------------------------------------------------------
    // Broadcast receivers
    // ------------------------------------------------------------------

    private fun registerReceivers(app: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                main.post { handleIntent(app, intent) }
            }
        }
        app.registerReceiver(r, filter)
        receiver = r
    }

    private fun handleIntent(app: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> onChargingChanged(app, true)
            Intent.ACTION_POWER_DISCONNECTED -> onChargingChanged(app, false)
            Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
            AudioManager.RINGER_MODE_CHANGED_ACTION -> onRingerChanged(app)
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onDndChanged(app)
        }
    }

    private fun onChargingChanged(app: Context, charging: Boolean) {
        if (charging == lastCharging) return
        lastCharging = charging
        val pct = currentBatteryPct(app)
        if (charging) {
            flash(SystemStateEvent(
                iconRes   = R.drawable.ic_charging,
                label     = "Charging",
                bodyText  = "$pct%",
                accentColor = 0xFF30D158.toInt(),
                holdMs    = 3000L
            ))
        } else {
            flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery,
                label     = "Unplugged",
                bodyText  = "$pct%",
                accentColor = 0xFFFFFFFF.toInt(),
                holdMs    = 2000L
            ))
        }
    }

    private fun onBatteryChanged(intent: Intent) {
        val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        if (charging || pct < 0) return          // charging alerts handled separately
        if (pct == lastBatteryPct) return
        lastBatteryPct = pct
        when {
            pct <= 10 -> flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery_low,
                label     = "Charge Now",
                bodyText  = "$pct%",
                accentColor = 0xFFFF453A.toInt(),
                holdMs    = 5000L
            ))
            pct <= 20 -> flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery_low,
                label     = "Low Battery",
                bodyText  = "$pct%",
                accentColor = 0xFFFFD60A.toInt(),
                holdMs    = 4000L
            ))
        }
    }

    private fun onRingerChanged(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val event = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> SystemStateEvent(
                R.drawable.ic_silent,  "Silent",  "", 0xFFFFFFFF.toInt(), 2000L)
            AudioManager.RINGER_MODE_VIBRATE -> SystemStateEvent(
                R.drawable.ic_vibrate, "Vibrate", "", 0xFFFFFFFF.toInt(), 2000L)
            else -> SystemStateEvent(
                R.drawable.ic_ring,    "Ring",    "", 0xFFFFFFFF.toInt(), 2000L)
        }
        flash(event)
    }

    private fun onDndChanged(app: Context) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val on = nm.currentInterruptionFilter !=
                 NotificationManager.INTERRUPTION_FILTER_ALL
        flash(SystemStateEvent(
            iconRes   = R.drawable.ic_focus,
            label     = if (on) "Focus On" else "Focus Off",
            bodyText  = "",
            accentColor = if (on) 0xFF5E5CE6.toInt() else 0xFFFFFFFF.toInt(),
            holdMs    = 2000L
        ))
    }

    private fun flash(event: SystemStateEvent) {
        DynamicIsland.onSystemStateChanged(event)
        main.postDelayed({ DynamicIsland.onSystemStateChanged(null) }, event.holdMs)
    }

    private fun currentBatteryPct(app: Context): Int {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (scale > 0) level * 100 / scale else -1
    }

    // ------------------------------------------------------------------
    // AppOps: camera + microphone privacy monitoring
    // ------------------------------------------------------------------

    private fun startOpsWatcher(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val aom = app.getSystemService(AppOpsManager::class.java) ?: return
        // GET_USAGE_STATS check: if not granted the call simply does nothing or throws
        val ops = arrayOf(AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO)
        val listener = AppOpsManager.OnOpActiveChangedListener { _, _, op, active ->
            main.post {
                when (op) {
                    AppOpsManager.OPSTR_CAMERA -> DynamicIsland.cameraActive = active
                    AppOpsManager.OPSTR_RECORD_AUDIO -> DynamicIsland.micActive = active
                }
                DynamicIsland.onPrivacyChanged(DynamicIsland.cameraActive, DynamicIsland.micActive)
            }
        }
        runCatching {
            aom.startWatchingActive(ops, app.mainExecutor, listener)
            opsWatcher = listener
        }
        // If PACKAGE_USAGE_STATS is not granted, startWatchingActive throws SecurityException
        // and opsWatcher stays null — privacy dots simply stay hidden.
    }

    private fun stopOpsWatcher(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val watcher = opsWatcher ?: return
        val aom = app.getSystemService(AppOpsManager::class.java) ?: return
        runCatching { aom.stopWatchingActive(watcher) }
        opsWatcher = null
    }
}
```

- [ ] **Step 2: Add `PACKAGE_USAGE_STATS` permission to `AndroidManifest.xml`**

After the `READ_PHONE_STATE` permission block, add:

```xml
<!-- Optional: monitors camera/mic usage across apps for privacy dot indicator.
     Requires user to grant "Usage access" in Settings > Apps > Special app access.
     The privacy dot simply stays hidden if this is not granted. -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions"/>
```

Also add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` tag if not present.

- [ ] **Step 3: Analyze**

```bash
flutter analyze android/ 2>&1 | tail -5
```

Expected: any errors about `OverlayForegroundService.refreshPrivacyDot` (added in Task 6). Zero new errors from this file.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/IslandSystemTracker.kt \
        android/app/src/main/AndroidManifest.xml
git commit -m "feat: IslandSystemTracker — battery, ringer, DND, AppOps camera/mic monitoring"
```

---

### Task 5: OverlayForegroundService — floating banner sub-view

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

**Interfaces:**
- Consumes: `NotificationAlert` (from `DynamicIsland.pendingBanner`), `DynamicIsland.forwardBannerState()`
- Produces: `showBanner(alert)`, `hideBannerForKey(key)` used by `DynamicIsland.dispatchBanner`

- [ ] **Step 1: Add banner fields to `OverlayForegroundService`**

After the `private var shapeAnimator` field group, add:

```kotlin
// Banner sub-view (dark card below the island for non-live notifications)
private var bannerRootView: LinearLayout? = null
private var bannerParams: WindowManager.LayoutParams? = null
private var bannerIconView: ImageView? = null
private var bannerAppLabelView: TextView? = null
private var bannerBodyView: TextView? = null
private var bannerTimestampView: TextView? = null
private var bannerHideRunnable: Runnable? = null
private var currentBannerKey: String? = null
private var bannerVisible = false
private var islandBottomY = 0          // updated in insets callback
```

- [ ] **Step 2: Add `createBannerView()` method and call it from `createWindow()`**

Add after `createWindow()`:

```kotlin
private fun createBannerView() {
    val iconV = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        background = roundedRectBackground(0x00000000, dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }
    val appLabel = TextView(this).apply {
        textSize = 11f
        setTextColor(0xFF9E9EA7.toInt())
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val timestamp = TextView(this).apply {
        textSize = 10f
        setTextColor(0xFF6E6E76.toInt())
        maxLines = 1
        gravity = Gravity.END
    }
    val labelRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(appLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(timestamp)
    }
    val body = TextView(this).apply {
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val textCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(10)
        }
        addView(labelRow)
        addView(body)
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedRectBackground(0xFF000000.toInt(), dp(20))
        setPadding(dp(10), dp(0), dp(10), dp(0))
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(iconV)
        addView(textCol)
        setOnClickListener { currentBannerKey?.let { k -> DynamicIsland.openAlert(k) } }
        alpha = 0f
    }

    bannerIconView = iconV
    bannerAppLabelView = appLabel
    bannerTimestampView = timestamp
    bannerBodyView = body
    bannerRootView = root

    val bParams = WindowManager.LayoutParams(
        dp(300), dp(72),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(200)     // provisional; updated in insets callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = cutoutModeForSdk()
        }
    }
    bannerParams = bParams
    windowManager?.addView(root, bParams)
}
```

- [ ] **Step 3: Call `createBannerView()` at the end of `createWindow()`**

In `createWindow()`, after the line `setContentAlpha(0f)`, add:

```kotlin
createBannerView()
```

- [ ] **Step 4: Update the `setOnApplyWindowInsetsListener` to also position the banner**

Inside the existing `root.setOnApplyWindowInsetsListener` block, after the island `y` update, add:

```kotlin
val newIslandY = islandTopY(insets)
islandBottomY = newIslandY + compactHeightPx + dp(8)
bannerParams?.let { bp ->
    bp.y = islandBottomY
    bannerRootView?.let { bv -> runCatching { windowManager?.updateViewLayout(bv, bp) } }
}
```

- [ ] **Step 5: Add `showBanner()`, `hideBannerForKey()`, `hideBannerNow()` methods**

```kotlin
fun showBanner(alert: NotificationAlert) {
    if (Looper.myLooper() != Looper.getMainLooper()) { main.post { showBanner(alert) }; return }
    bannerRootView ?: return
    // Cancel any pending hide
    bannerHideRunnable?.let { main.removeCallbacks(it) }
    currentBannerKey?.let { DynamicIsland.dropContentIntent(it) }
    currentBannerKey = alert.key

    // Populate content
    bannerIconView?.setImageDrawable(alert.icon)
    bannerAppLabelView?.text = alert.appLabel
    bannerTimestampView?.text = "just now"
    bannerBodyView?.text = alert.text.ifBlank { alert.title }

    // Make touchable
    bannerParams?.let { bp ->
        bp.flags = baseFlags()
        bannerRootView?.let { runCatching { windowManager?.updateViewLayout(it, bp) } }
    }

    // Animate in: slide down from -72dp + fade
    bannerRootView?.let { bv ->
        bv.translationY = -dp(72).toFloat()
        bv.alpha = 0f
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(bv, "translationY", -dp(72).toFloat(), 0f),
            ObjectAnimator.ofFloat(bv, "alpha", 0f, 1f)
        )
        set.duration = 220L
        set.interpolator = springExpand
        set.start()
    }
    bannerVisible = true

    val holdMs = 3000L + (alert.text.length * 8L).coerceAtMost(2000L)
    val hideR = Runnable { hideBannerNow() }
    bannerHideRunnable = hideR
    main.postDelayed(hideR, holdMs)
}

fun hideBannerForKey(key: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        main.post { hideBannerForKey(key) }; return
    }
    if (currentBannerKey == key) hideBannerNow()
}

private fun hideBannerNow() {
    val bv = bannerRootView ?: return
    bannerHideRunnable?.let { main.removeCallbacks(it) }
    bannerHideRunnable = null
    val set = AnimatorSet()
    set.playTogether(
        ObjectAnimator.ofFloat(bv, "translationY", 0f, -dp(40).toFloat()),
        ObjectAnimator.ofFloat(bv, "alpha", bv.alpha, 0f)
    )
    set.duration = 180L
    set.interpolator = easeCollapse
    set.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            currentBannerKey?.let { DynamicIsland.dropContentIntent(it) }
            currentBannerKey = null
            bannerVisible = false
            bannerIconView?.setImageDrawable(null)
            bannerParams?.let { bp ->
                bp.flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                bannerRootView?.let { runCatching { windowManager?.updateViewLayout(it, bp) } }
            }
            DynamicIsland.forwardBannerState(null)
        }
    })
    set.start()
}
```

- [ ] **Step 6: Remove the banner view in `removeWindow()`**

In the existing `removeWindow()` method, add before `rootView = null`:

```kotlin
runCatching { bannerRootView?.let { windowManager?.removeView(it) } }
bannerRootView = null
bannerParams = null
```

- [ ] **Step 7: Consume `pendingBanner` in `onStartCommand()`**

After the existing `DynamicIsland.pendingAlert?.let { ... }` block in `onStartCommand()`, add:

```kotlin
DynamicIsland.pendingBanner?.let { pending ->
    DynamicIsland.pendingBanner = null
    showBanner(pending)
}
```

- [ ] **Step 8: Analyze**

```bash
flutter analyze android/ 2>&1 | tail -5
```

Expected: only unresolved `refreshPrivacyDot` and `SYSTEM_STATE` mode references remain (Task 6). Zero new errors.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: floating banner sub-view below island for non-live notifications"
```

---

### Task 6: OverlayForegroundService — SYSTEM_STATE mode + privacy dot + system tracker wiring

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

**Interfaces:**
- Consumes: `DynamicIsland.currentSystemState: SystemStateEvent?`, `DynamicIsland.cameraActive`, `DynamicIsland.micActive`, `IslandSystemTracker.start/stop`
- Produces: `refreshPrivacyDot()` called by `DynamicIsland.onPrivacyChanged`

- [ ] **Step 1: Add `SYSTEM_STATE` to the `Mode` enum**

Find `private enum class Mode { IDLE, FLASH, UNLOCK, MEDIA, CALL, TIMER }` and add `SYSTEM_STATE`:

```kotlin
private enum class Mode { IDLE, FLASH, UNLOCK, MEDIA, CALL, TIMER, SYSTEM_STATE }
```

- [ ] **Step 2: Add privacy dot fields**

After the `bannerVisible` field, add:

```kotlin
// Privacy dot: third WindowManager view (camera=green, mic=orange)
private var dotView: View? = null
private var dotParams: WindowManager.LayoutParams? = null
```

- [ ] **Step 3: Add `createPrivacyDotView()` and call it from `createWindow()`**

```kotlin
private fun createPrivacyDotView() {
    val dot = View(this).apply {
        background = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val cam = DynamicIsland.cameraActive
                val mic = DynamicIsland.micActive
                val r = bounds.width() / 2f
                if (cam && mic) {
                    paint.color = 0xFF30D158.toInt()
                    canvas.drawCircle(r, r * 0.45f, r * 0.45f, paint)
                    paint.color = 0xFFFF9F0A.toInt()
                    canvas.drawCircle(r, r * 1.55f, r * 0.45f, paint)
                } else {
                    paint.color = if (cam) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt()
                    canvas.drawCircle(r, r, r, paint)
                }
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        visibility = View.GONE
    }

    val dParams = WindowManager.LayoutParams(
        dp(12), dp(24),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = dp(36)     // offset right of island center (compactWidthPx/2 + 4dp gap)
        y = dp(0)      // updated in insets callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = cutoutModeForSdk()
        }
    }
    dotView = dot
    dotParams = dParams
    windowManager?.addView(dot, dParams)
}
```

In `createWindow()`, after `createBannerView()`, add:

```kotlin
createPrivacyDotView()
```

Also update `setOnApplyWindowInsetsListener` to position the dot at island center-Y:

```kotlin
dotParams?.let { dp2 ->
    dp2.y = newIslandY + (compactHeightPx - dp(12)) / 2
    dotView?.let { dv -> runCatching { windowManager?.updateViewLayout(dv, dp2) } }
}
```

- [ ] **Step 4: Add `refreshPrivacyDot()` method**

```kotlin
fun refreshPrivacyDot() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        main.post { refreshPrivacyDot() }; return
    }
    val active = DynamicIsland.cameraActive || DynamicIsland.micActive
    dotView?.let { dv ->
        dv.visibility = if (active) View.VISIBLE else View.GONE
        dv.invalidate()
    }
}
```

- [ ] **Step 5: Update `targetMode()` to include `SYSTEM_STATE`**

Replace `targetMode()`:

```kotlin
private fun targetMode(): Mode = when {
    currentAlert != null -> Mode.FLASH
    unlockActive -> Mode.UNLOCK
    DynamicIsland.callStartedAtElapsedMs != null -> Mode.CALL
    DynamicIsland.currentSystemState != null -> Mode.SYSTEM_STATE
    DynamicIsland.timerState != null -> Mode.TIMER
    DynamicIsland.mediaState != null -> Mode.MEDIA
    else -> Mode.IDLE
}
```

- [ ] **Step 6: Add `applySystemStateContent()` and wire it into `render()`**

Add the method:

```kotlin
private fun applySystemStateContent() {
    val event = DynamicIsland.currentSystemState ?: return
    iconView.visibility = View.VISIBLE
    setIconSize(dp(22))
    iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
    iconView.imageTintList = ColorStateList.valueOf(event.accentColor)
    iconView.setImageResource(event.iconRes)
    textColumn.visibility = View.VISIBLE
    labelRow.visibility = View.VISIBLE
    appLabelView.text = event.label
    appLabelView.visibility = View.VISIBLE
    timestampView.visibility = View.GONE
    bodyView.maxLines = 1
    bodyView.textSize = 13f
    bodyView.text = event.bodyText
    eqContainer.visibility = View.GONE
    controlsRow.visibility = View.GONE
    mediaProgressBar.visibility = View.GONE
    arcView.visibility = View.GONE
}
```

In `render()`'s `when (mode)` block, add after `Mode.UNLOCK`:

```kotlin
Mode.SYSTEM_STATE -> {
    applySystemStateContent()
    targetW = dp(190)
    targetH = dp(44)
}
```

Also update `Mode.SYSTEM_STATE.isPersistent()`: in the `isPersistent()` extension, keep returning `false` for SYSTEM_STATE (it's transient like FLASH).

- [ ] **Step 7: Start/stop `IslandSystemTracker` in `onCreate()` / `onDestroy()`**

In `onCreate()`, after `IslandCallTracker.start(this)`, add:

```kotlin
IslandSystemTracker.start(this)
```

In `onDestroy()`, after `IslandCallTracker.stop(this)`, add:

```kotlin
IslandSystemTracker.stop(this)
```

- [ ] **Step 8: Remove dot view in `removeWindow()`**

```kotlin
runCatching { dotView?.let { windowManager?.removeView(it) } }
dotView = null
dotParams = null
```

- [ ] **Step 9: Analyze**

```bash
flutter analyze android/ 2>&1 | tail -5
```

Expected: no errors.

- [ ] **Step 10: Build APK to catch Kotlin compile errors**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: SYSTEM_STATE mode + privacy dot overlay + IslandSystemTracker wiring"
```

---

### Task 7: MainActivity + permission UI

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/MainActivity.kt`
- Modify: `lib/main.dart`

**Interfaces:**
- Produces: `banner_events` and `privacy_events` EventChannels wired in `configureFlutterEngine`

- [ ] **Step 1: Wire banner + privacy EventChannels in `MainActivity.configureFlutterEngine()`**

After the existing `call_events` EventChannel block, add:

```kotlin
EventChannel(
    flutterEngine.dartExecutor.binaryMessenger,
    "com.example.dynamic_island_app/banner_events"
).setStreamHandler(object : EventChannel.StreamHandler {
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        DynamicIsland.setBannerSink(events)
        DynamicIsland.forwardBannerState(null)
    }
    override fun onCancel(arguments: Any?) = DynamicIsland.setBannerSink(null)
})

EventChannel(
    flutterEngine.dartExecutor.binaryMessenger,
    "com.example.dynamic_island_app/privacy_events"
).setStreamHandler(object : EventChannel.StreamHandler {
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        DynamicIsland.setPrivacySink(events)
        DynamicIsland.forwardPrivacyState()
    }
    override fun onCancel(arguments: Any?) = DynamicIsland.setPrivacySink(null)
})
```

In `onDestroy()`, add after existing sink nulling:

```kotlin
DynamicIsland.setBannerSink(null)
DynamicIsland.setPrivacySink(null)
```

- [ ] **Step 2: Add `usageStatsGranted()` helper to `statusMap()` in `MainActivity`**

In `statusMap()`, add to the returned map:

```kotlin
"usageStatsGranted" to isUsageStatsGranted(),
```

Add helper:

```kotlin
private fun isUsageStatsGranted(): Boolean {
    val aom = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        aom.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
    } else {
        @Suppress("DEPRECATION")
        aom.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
```

- [ ] **Step 3: Add `usageStatsGranted` field to `IslandStatus` in `native_bridge.dart`**

In `lib/src/native_bridge.dart`, add to `IslandStatus`:

```dart
final bool usageStatsGranted;
```

Add to `IslandStatus` constructor and `fromMap()`:

```dart
// In constructor
this.usageStatsGranted = false,

// In fromMap
usageStatsGranted: b('usageStatsGranted'),
```

- [ ] **Step 4: Add "System Hardware States" section to `lib/main.dart`**

In `_HomeScreenState.build()`, add `_buildSystemStatesSection()` to the `ListView` children after `_buildLiveModesSection()`:

```dart
const SizedBox(height: 16),
_buildSystemStatesSection(),
```

Add the method:

```dart
Widget _buildSystemStatesSection() {
  return Card(
    margin: EdgeInsets.zero,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const ListTile(
          leading: Icon(Icons.hardware_outlined),
          title: Text('System hardware states'),
          subtitle: Text(
            'Brief island flashes for charging, low battery, ringer mode, '
            'and Focus/DND changes. Always active when the overlay is running.',
          ),
        ),
        _PermissionTile(
          icon: Icons.privacy_tip_outlined,
          title: 'Privacy indicators',
          subtitle: 'Shows an orange dot (microphone) or green dot (camera) '
              'alongside the island when any app accesses them. Requires '
              '"Usage access" — a special system permission.',
          why: 'Android\'s AppOpsManager.startWatchingActive() can monitor '
              'camera and microphone usage across all apps, but requires the '
              '"Usage access" special permission (PACKAGE_USAGE_STATS). '
              'Without it the privacy dots simply stay hidden — everything '
              'else keeps working normally.',
          whyActionLabel: 'Open Usage access settings',
          granted: _status.usageStatsGranted,
          grantedLabel: 'Active',
          actionLabel: 'Enable',
          onAction: () async {
            await NativeBridge.openUsageAccessSettings();
          },
        ),
        const _Bullet(
          icon: Icons.battery_charging_full_outlined,
          text: 'Charging connected or unplugged — brief green/white flash.',
        ),
        const _Bullet(
          icon: Icons.battery_alert_outlined,
          text: 'Low battery at 20% (yellow) and 10% (red).',
        ),
        const _Bullet(
          icon: Icons.volume_off_outlined,
          text: 'Silent / Vibrate / Ring toggle — 2-second flash.',
        ),
        const _Bullet(
          icon: Icons.do_not_disturb_on_outlined,
          text: 'Focus Mode / Do Not Disturb on or off — 2-second flash.',
        ),
        const SizedBox(height: 8),
      ],
    ),
  );
}
```

- [ ] **Step 5: Add `openUsageAccessSettings()` to `NativeBridge`**

In `lib/src/native_bridge.dart`:

```dart
static Future<void> openUsageAccessSettings() =>
    _service.invokeMethod<void>('openUsageAccessSettings');
```

In `MainActivity.kt` MethodChannel handler, add case:

```kotlin
"openUsageAccessSettings" -> {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
    result.success(null)
}
```

- [ ] **Step 6: Analyze**

```bash
flutter analyze lib/ android/ 2>&1 | tail -5
```

Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/MainActivity.kt \
        lib/src/native_bridge.dart \
        lib/main.dart
git commit -m "feat: banner/privacy EventChannels, usage-access permission tile, system states UI"
```

---

### Task 8: Flutter — BannerPreview widget + privacy dot in island_preview.dart

**Files:**
- Modify: `lib/src/native_bridge.dart`
- Modify: `lib/src/island_preview.dart`

**Interfaces:**
- Consumes: `NativeBridge.bannerEvents()`, `NativeBridge.privacyEvents()`
- Produces: updated `IslandPreview` with `BannerPreview` below it, privacy dot to the right

- [ ] **Step 1: Add `bannerEvents()` and `privacyEvents()` to `native_bridge.dart`**

After `callEvents()`, add:

```dart
static const EventChannel _bannerEvents =
    EventChannel('com.example.dynamic_island_app/banner_events');
static const EventChannel _privacyEvents =
    EventChannel('com.example.dynamic_island_app/privacy_events');

/// Emits {key, appLabel, title, text} when a banner notification arrives,
/// null when it dismisses.
static Stream<Map<String, dynamic>?> bannerEvents() =>
    _bannerEvents.receiveBroadcastStream().map(
          (e) => (e as Map?)?.cast<String, dynamic>(),
        );

/// Emits {camera: bool, microphone: bool} when privacy state changes,
/// null when neither is active.
static Stream<Map<String, dynamic>?> privacyEvents() =>
    _privacyEvents.receiveBroadcastStream().map(
          (e) => (e as Map?)?.cast<String, dynamic>(),
        );
```

- [ ] **Step 2: Update `IslandPreview` constructor to accept banner and privacy streams**

```dart
class IslandPreview extends StatefulWidget {
  const IslandPreview({
    super.key,
    this.previewStream,
    this.mediaStream,
    this.timerStream,
    this.callStream,
    this.bannerStream,    // ← NEW
    this.privacyStream,   // ← NEW
  });

  final Stream<Map<String, dynamic>>? previewStream;
  final Stream<Map<String, dynamic>?>? mediaStream;
  final Stream<Map<String, dynamic>?>? timerStream;
  final Stream<Map<String, dynamic>?>? callStream;
  final Stream<Map<String, dynamic>?>? bannerStream;
  final Stream<Map<String, dynamic>?>? privacyStream;

  @override
  State<IslandPreview> createState() => _IslandPreviewState();
}
```

- [ ] **Step 3: Add banner + privacy state fields and subscriptions to `_IslandPreviewState`**

After `_callSub`, add fields:

```dart
Map<String, dynamic>? _bannerData;
Map<String, dynamic>? _privacyData;
StreamSubscription<Map<String, dynamic>?>? _bannerSub;
StreamSubscription<Map<String, dynamic>?>? _privacySub;
```

In `initState()`, after `_callSub = ...`:

```dart
_bannerSub = (widget.bannerStream ?? NativeBridge.bannerEvents()).listen(
  (data) { if (mounted) setState(() => _bannerData = data); },
  onError: (Object _, StackTrace __) {},
);
_privacySub = (widget.privacyStream ?? NativeBridge.privacyEvents()).listen(
  (data) { if (mounted) setState(() => _privacyData = data); },
  onError: (Object _, StackTrace __) {},
);
```

In `dispose()`:

```dart
_bannerSub?.cancel();
_privacySub?.cancel();
```

- [ ] **Step 4: Wrap `IslandPreview` content in a `Column` with the banner below and privacy dot to the right**

The existing `build()` returns a `Semantics > AnimatedBuilder > GestureDetector > ClipRRect > Container`. Wrap the entire `Semantics` widget in a `Column` that also shows the `_BannerPreview`, and in a `Row` that shows the privacy dot:

```dart
@override
Widget build(BuildContext context) {
  final bool interactive =
      _current != null || _callData != null || _timerData != null || _media != null;

  return AnimatedBuilder(
    animation: _t,
    builder: (_, __) {
      final double w = _nowW;
      final double h = _nowH;
      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Semantics(
                label: 'Isle preview',
                child: GestureDetector(
                  onTap: interactive ? _onTap : null,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(24),
                    child: Container(
                      width: w,
                      height: h,
                      color: const Color(0xFF000000),
                      child: _islandContent(),
                    ),
                  ),
                ),
              ),
              if (_privacyData != null) ...[
                const SizedBox(width: 4),
                _PrivacyDot(data: _privacyData!),
              ],
            ],
          ),
          if (_bannerData != null) ...[
            const SizedBox(height: 8),
            _BannerCard(data: _bannerData!),
          ],
        ],
      );
    },
  );
}

Widget? _islandContent() {
  if (_current != null) return _withSlideIn(_flashContent(_current!));
  if (_callData != null) return _withSlideIn(_callExpanded
      ? _callExpandedContent(_callData!)
      : _callCompactContent(_callData!));
  if (_timerData != null) return _withSlideIn(_timerExpanded
      ? _timerExpandedContent(_timerData!)
      : _timerCompactContent(_timerData!));
  if (_media != null) return _withSlideIn(_mediaExpanded
      ? _mediaExpandedContent(_media!)
      : _mediaCompactContent(_media!));
  return null;
}
```

- [ ] **Step 5: Add `_PrivacyDot` widget at the bottom of the file**

```dart
class _PrivacyDot extends StatelessWidget {
  const _PrivacyDot({required this.data});
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) {
    final bool cam = data['camera'] == true;
    final bool mic = data['microphone'] == true;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (cam)
          Container(
            width: 10, height: 10,
            decoration: const BoxDecoration(
              color: Color(0xFF30D158), shape: BoxShape.circle),
          ),
        if (cam && mic) const SizedBox(height: 3),
        if (mic)
          Container(
            width: 10, height: 10,
            decoration: const BoxDecoration(
              color: Color(0xFFFF9F0A), shape: BoxShape.circle),
          ),
      ],
    );
  }
}
```

- [ ] **Step 6: Add `_BannerCard` widget at the bottom of the file**

```dart
class _BannerCard extends StatelessWidget {
  const _BannerCard({required this.data});
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) {
    final appLabel = (data['appLabel'] as String?) ?? '';
    final title    = (data['title']    as String?) ?? '';
    final text     = (data['text']     as String?) ?? '';
    final body     = text.isNotEmpty ? text : title;
    return Container(
      width: 300,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF000000),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        children: [
          _AppIconBadge(appLabel: appLabel, size: 36),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(children: [
                  Expanded(
                    child: Text(appLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 11, color: Color(0xFF9E9EA7))),
                  ),
                  const Text('just now',
                      style: TextStyle(fontSize: 10, color: Color(0xFF6E6E76))),
                ]),
                Text(body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13, color: Colors.white)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
```

- [ ] **Step 7: Analyze**

```bash
flutter analyze lib/src/island_preview.dart lib/src/native_bridge.dart 2>&1 | tail -5
```

Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add lib/src/island_preview.dart lib/src/native_bridge.dart
git commit -m "feat: BannerPreview widget + privacy dot in Flutter island preview"
```

---

### Task 9: Widget test update

**Files:**
- Modify: `test/widget_test.dart`

- [ ] **Step 1: Update test to supply empty banner/privacy streams**

The existing widget test constructs `IslandPreview` with `previewStream` and `mediaStream`. Add the two new optional params with empty streams to prevent real channel subscriptions during tests:

```dart
IslandPreview(
  previewStream: alertStream.stream,
  mediaStream:   mediaStream.stream,
  timerStream:   Stream.empty(),
  callStream:    Stream.empty(),
  bannerStream:  Stream.empty(),   // ← NEW
  privacyStream: Stream.empty(),   // ← NEW
)
```

- [ ] **Step 2: Run tests**

```bash
flutter test test/widget_test.dart --reporter compact
```

Expected: `All tests passed!`

- [ ] **Step 3: Final full analyze**

```bash
flutter analyze lib/ android/ 2>&1 | tail -10
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add test/widget_test.dart
git commit -m "fix: supply empty banner/privacy streams to IslandPreview in widget test"
```
