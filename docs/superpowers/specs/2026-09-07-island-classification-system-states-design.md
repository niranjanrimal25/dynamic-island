# Island Classification + System Hardware States — Design Spec

**Date:** 2026-09-07
**Branch:** arena/01a06180-dynamic-island
**Scope:** Sub-projects A + B — notification classification, floating banner, system hardware states, privacy dots.

---

## Goal

Make the Dynamic Island behave like iPhone: the pill is reserved for live activities and system hardware states only. Regular notifications (SMS, WhatsApp, social, email) never enter the island — they appear as a dark floating banner card below the island instead.

---

## Architecture Overview

Four new responsibilities, split across clean units:

| Unit | Responsibility |
|------|---------------|
| `IslandNotificationClassifier.kt` | Decides whether a notification is island-worthy or banner-worthy |
| `IslandSystemTracker.kt` | Detects battery/charging, ringer mode, DND, and camera/mic privacy events |
| `OverlayForegroundService.kt` | Gains a floating banner sub-view and new SYSTEM_STATE + PRIVACY modes |
| `DynamicIsland.kt` | New banner + system state singletons; forward state to Flutter |

Flutter preview gets a `BannerPreview` widget beneath the island preview and a privacy dot indicator.

---

## Notification Classification

### `IslandNotificationClassifier.kt` (new file)

Single public function:

```kotlin
fun isLiveActivity(sbn: StatusBarNotification): Boolean
```

**Returns `true` (→ island flash):**
- `Notification.CATEGORY_CALL`
- `Notification.CATEGORY_NAVIGATION`
- `Notification.CATEGORY_TRANSPORT`
- `Notification.CATEGORY_SERVICE` AND `sbn.isOngoing`
- `Notification.CATEGORY_PROGRESS` AND `sbn.isOngoing`

**Returns `false` (→ floating banner):**
- Everything else: `CATEGORY_MESSAGE`, `CATEGORY_EMAIL`, `CATEGORY_SOCIAL`, `CATEGORY_EVENT`, `CATEGORY_PROMO`, `CATEGORY_RECOMMENDATION`, uncategorised non-ongoing

### `NotificationListener.kt` changes

Replace the single `DynamicIsland.dispatchAlert()` call with:
```kotlin
if (IslandNotificationClassifier.isLiveActivity(sbn)) {
    DynamicIsland.dispatchAlert(this, alert)
} else {
    DynamicIsland.dispatchBanner(this, alert)
}
```

The `islandOnly` cancel-from-shade logic still applies to banner notifications (the shade copy is auto-dismissed after the banner shows, if enabled). Live activity notifications are never cancelled from the shade.

---

## Floating Banner Card

### Visual design

- **Position:** `y = islandBottomEdge + 8dp`, horizontally centered
- **Size:** `300 × 72dp` (same width as expanded island)
- **Shape:** Rounded rect, `radius = 20dp`, background `#000000`
- **Content (horizontal row):**
  - Left: 36×36dp app icon (rounded square, same as island flash)
  - Middle: vertical column — app name (11sp `#9E9EA7`) + title/body (13sp white, max 2 lines)
  - Right: timestamp "just now" (10sp `#6E6E76`)

### Animation

- **Enter:** slides down from `translationY = -72dp` → `0` with spring interpolator (same `PathInterpolator(0.34, 1.56, 0.64, 1)`) + alpha 0→1 over 220ms
- **Hold:** 3 seconds (longer for longer text: +8ms/char, max +2s)
- **Exit:** alpha 1→0 + `translationY 0 → -40dp` over 180ms ease-in
- **Tap:** fires `contentIntent`, dismisses banner immediately
- **Queue:** at most 1 banner visible; a new one replaces the current (smooth cross-fade content)

### Implementation

Managed inside `OverlayForegroundService` as a second `WindowManager` view (`bannerRootView: LinearLayout?`). Added in `createWindow()`, positioned dynamically in the `setOnApplyWindowInsetsListener` callback after `islandTopY` is known. Separated from the island view so it can animate independently.

### `DynamicIsland.kt` additions

```kotlin
@Volatile var bannerAlert: NotificationAlert? = null
@Volatile var pendingBanner: NotificationAlert? = null

fun dispatchBanner(context: Context, alert: NotificationAlert)
fun onBannerRemoved(context: Context, key: String)
```

---

## System Hardware States

### `IslandSystemTracker.kt` (new file)

A helper that lives inside `OverlayForegroundService` (started/stopped with the service). Manages:

**Receivers registered dynamically (no manifest entry needed):**

| Broadcast | State detected |
|-----------|---------------|
| `ACTION_POWER_CONNECTED` | Charging started |
| `ACTION_POWER_DISCONNECTED` | Charger removed |
| `ACTION_BATTERY_CHANGED` | Low battery thresholds (20%, 10%) |
| `AudioManager.RINGER_MODE_CHANGED_ACTION` | Silent / Vibrate / Ring |
| `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED` | DND / Focus on/off |

**Privacy monitoring (API 28+):**
`AppOpsManager.startWatchingActive(arrayOf(OPSTR_CAMERA, OPSTR_RECORD_AUDIO), listener)` — requires `GET_USAGE_STATS` special permission (opt-in, user grants in settings). Falls back gracefully if permission not granted.

All events call `DynamicIsland.onSystemStateChanged(state)`.

### New island modes

#### `Mode.SYSTEM_STATE` (brief flash — same structure as existing FLASH)

Priority sits BELOW CALL/TIMER/MEDIA but ABOVE IDLE. Like FLASH, it interrupts persistent modes temporarily then reverts.

| Event | Icon | Text | Color | Duration |
|-------|------|------|-------|----------|
| Charging connected | `ic_charging` | "Charging · XX%" | Green `#30D158` | 3s |
| Charger removed | `ic_battery` | "XX% — Unplugged" | White | 2s |
| Battery 20% | `ic_battery_low` | "Low Battery · 20%" | Yellow `#FFD60A` | 4s |
| Battery 10% | `ic_battery_low` | "Charge Now · 10%" | Red `#FF453A` | 5s |
| Ring mode | `ic_ring` | "Ring" | White | 2s |
| Vibrate mode | `ic_vibrate` | "Vibrate" | White | 2s |
| Silent mode | `ic_silent` | "Silent" | White | 2s |
| DND on | `ic_focus` | "Focus On" | Indigo `#5E5CE6` | 2s |
| DND off | `ic_focus` | "Focus Off" | White | 2s |

Reuses existing `applyFlashContent()` / `hideRunnable` infrastructure with a new `applySystemStateContent()` variant.

#### Privacy dot (`Mode` stays current — dot is an overlay, not a mode)

A small `View` (10dp circle) added to the WindowManager as a **third overlay view** positioned to the RIGHT of the island capsule at the same Y-center. Not inside the pill — alongside it, just like iPhone's placement in the camera cutout area.

- Orange `#FF9F0A` = microphone active (`OPSTR_RECORD_AUDIO`)
- Green `#30D158` = camera active (`OPSTR_CAMERA`)
- Both active = two stacked dots (green on top, orange below, 4dp gap)
- Appears instantly when active, disappears when ops stop
- Only visible when `GET_USAGE_STATS` permission is granted

---

## New Drawables Required

| File | Description |
|------|-------------|
| `ic_charging.xml` | Lightning bolt |
| `ic_battery.xml` | Battery outline |
| `ic_battery_low.xml` | Battery with exclamation |
| `ic_ring.xml` | Bell |
| `ic_vibrate.xml` | Phone with vibration lines |
| `ic_silent.xml` | Bell with slash |
| `ic_focus.xml` | Moon / crescent |

All as vector drawables, white fill (tinted at display time).

---

## Settings Screen Updates (`main.dart`)

New section: **"System Hardware States"**
- Toggle: "Privacy indicators" — explains `GET_USAGE_STATS` requirement, links to settings page
- Read-only bullets listing: charging alerts, low battery warnings, ringer mode flash, Focus/DND flash

New permission tile for `GET_USAGE_STATS` (same pattern as `canDrawOverlays`).

---

## Flutter Preview Updates (`island_preview.dart`)

- `BannerPreview` widget: positioned below `IslandPreview`, shows when a `bannerStream` event arrives (new `banner_events` EventChannel). Same dark card design.
- Privacy dot indicator: small colored circle to the right of the `IslandPreview` widget when `privacyStream` emits non-null.
- New `NativeBridge.bannerEvents()` and `NativeBridge.privacyEvents()` streams.

---

## AndroidManifest.xml Changes

```xml
<!-- For privacy dot camera/mic monitoring (opt-in, user grants in settings) -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions"/>
```

---

## Files Changed

| File | Change |
|------|--------|
| `IslandNotificationClassifier.kt` | NEW — live activity classifier |
| `IslandSystemTracker.kt` | NEW — battery, ringer, DND, privacy receivers |
| `NotificationListener.kt` | Route to dispatchAlert vs dispatchBanner |
| `DynamicIsland.kt` | Banner state, system state, privacy dot state, new sinks |
| `OverlayForegroundService.kt` | Banner sub-view, SYSTEM_STATE mode, privacy dot overlay, system tracker wiring |
| `MainActivity.kt` | GET_USAGE_STATS permission UI |
| `AndroidManifest.xml` | PACKAGE_USAGE_STATS permission |
| `res/drawable/ic_charging.xml` | NEW |
| `res/drawable/ic_battery.xml` | NEW |
| `res/drawable/ic_battery_low.xml` | NEW |
| `res/drawable/ic_ring.xml` | NEW |
| `res/drawable/ic_vibrate.xml` | NEW |
| `res/drawable/ic_silent.xml` | NEW |
| `res/drawable/ic_focus.xml` | NEW |
| `lib/src/native_bridge.dart` | bannerEvents(), privacyEvents() |
| `lib/src/island_preview.dart` | BannerPreview widget, privacy dot |
| `lib/main.dart` | System hardware states settings section |

---

## Out of Scope

- AirPods battery (Bluetooth companion pairing — Sub-project C)
- AirDrop / Nearby Share progress (Sub-project C)
- Third-party live activities parsing (delivery, ride-share, sports — Sub-project D)
- Face ID / biometric confirmation (Sub-project D)
