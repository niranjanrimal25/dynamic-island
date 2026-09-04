# iPhone-Style Dynamic Island — Design Spec

**Date:** 2026-09-04  
**Branch:** arena/01a06180-dynamic-island  
**Scope:** Core iPhone feel (Option A) — all existing modes redesigned; no navigation or dual-activity.

---

## Goal

Make the Android Dynamic Island overlay behave and look identical to iPhone's Dynamic Island for all currently supported modes: notification flash, music player, phone call, and timer/stopwatch. The Flutter in-app preview mirrors every change.

---

## Architecture

Two rendering layers, both updated:

1. **Native overlay** (`OverlayForegroundService.kt`) — the always-on WindowManager pill that floats above every app. This is the primary target.
2. **Flutter preview** (`lib/src/island_preview.dart`) — the in-app mirror widget. Gets identical behavioral and layout changes so it stays in sync.

No new modes, no new MethodChannel calls, no new drawables, no pubspec changes.

---

## Mode 1: Notification Flash (FLASH)

### Behavior — two-stage expand (iPhone-identical)

| Stage | Timing | State |
|-------|--------|-------|
| Alert arrives | 0 ms | Compact pill (~80×44dp), icon only |
| Auto-expand | +500 ms delay, 260 ms animate | Full content: app name + body |
| Hold | computed from text length | Stays expanded |
| Auto-collapse | after hold | Back to idle/persistent mode |

- **Tap while compact** → skip the 500ms wait, expand immediately.
- **Tap while expanded** → fire `contentIntent` (open source app), collapse island. This replaces the current long-press-to-open behavior.
- No toggle behavior. Tap on expanded always opens the app.
- Arming: the 220ms expand animation still acts as the "arm delay" so accidental touches on expand can't fire the intent.

### Visual — expanded notification

- Icon: 36×36dp rounded square (up from 32dp)
- App label: 11sp gray (`#9E9EA7`)
- Body text: 13sp white, max 4 lines, ellipsized
- Timestamp: "just now" or "Xs ago" — 10sp gray, top-right corner
- Min height: 64dp. Max height: 160dp (auto-computed from content).

---

## Mode 2: Media Player (MEDIA)

### Compact state (112×44dp — unchanged)

- Circular album art, 28dp
- Animated teal waveform bars (3 bars, existing `_EqBars` / `startEq()`)

### Expanded state (300×120dp — up from 76dp)

Layout (horizontal):

```
┌──────────────────────────────────────────────┐
│  [art]   Song Title                ⏮  ⏸  ⏭  │
│  56dp    Artist Name                          │
│          ──────────────────────────           │
│          [progress bar, 2dp, full width]      │
└──────────────────────────────────────────────┘
```

- Album art: 56×56dp circular (up from 48dp), left side
- Title: 14sp white, 1 line, ellipsized
- Artist: 11sp `#9E9EA7`, 1 line, ellipsized
- Progress bar: 2dp thin `LinearProgressIndicator` (teal fill, dark track), full width below title/artist
- Controls: prev/play-pause/next at right edge, 34×34dp buttons, white icons
- Tap: compact → expand; expand → collapse (unchanged toggle behavior)

---

## Mode 3: Timer / Stopwatch (TIMER)

### Compact state (118×40dp — unchanged)

- Timer icon (18dp, teal for stopwatch, orange/yellow for countdown) + live readout

### Expanded state (300×110dp — new)

Layout (horizontal):

```
┌──────────────────────────────────────────────┐
│                                              │
│   [arc]    Timer           2:47              │
│   64dp     (or Stopwatch)  22sp white mono   │
│                                              │
└──────────────────────────────────────────────┘
```

- **Circular progress arc**: 64×64dp custom `ArcProgressView` (Android) / custom `CustomPainter` (Flutter).
  - Stroke width: 4dp
  - Background ring: `#2A2A31`
  - Foreground arc: green (`#30D158`) for countdown, teal (`#7ED6DF`) for stopwatch
  - Countdown: fills clockwise from 12 o'clock proportional to time remaining vs. original duration
  - Stopwatch: fills clockwise, completes one revolution every 60 seconds
- Label: "Timer" or "Stopwatch", 11sp `#9E9EA7`
- Readout: 22sp white, monospace-ish weight, live-ticking
- Arc is drawn entirely via `Canvas.drawArc()` — no drawable asset required

---

## Mode 4: Phone Call (CALL)

### Compact state (118×40dp — unchanged)

- Green phone icon (18dp) + live duration readout

### Expanded state (300×80dp — up from 52dp)

```
┌──────────────────────────────────────────────┐
│ ▌  ☎  Call              00:47               │
│ green              20sp white mono           │
│ bar                                          │
└──────────────────────────────────────────────┘
```

- **Green left-edge accent bar**: 3dp wide, full height, `#30D158` — same visual signal iPhone uses for active calls
- Phone icon: 24dp (up from 18dp), green
- "Call" label: 11sp `#9E9EA7`
- Duration: 20sp white monospace (up from 13sp) — prominent, live-ticking
- Height: 80dp (up from 52dp)

---

## Animations

### Notification two-stage expand

```
t=0ms      alert arrives       compact pill (80×44dp) springs open from idle
t=+500ms   auto-expand fires   pill animates to full content size
           content entry       alpha 0→1 + translationY 8dp→0dp over 180ms
```

The 500ms wait uses a `Handler.postDelayed`. If the user taps during this window, the delay is cancelled and expand fires immediately.

### Content entry (all modes)

All content (icon, text, controls) enters with:
- Alpha: 0 → 1
- TranslationY: +8dp → 0
- Duration: 180ms, ease-out
- Start delay: 60% of the shape animation duration (same as current fade-in delay, now also applies translationY)

### Collapse (unchanged)

- 260ms ease-in collapse back to idle/compact
- Content alpha fades to 0 before the shape finishes collapsing

### Spring curve (unchanged)

`PathInterpolator(0.34f, 1.56f, 0.64f, 1f)` — same overshoot spring for all expansions.

---

## Flutter Preview (island_preview.dart)

Mirrors every change above:

- `_flashExpanded` starts `false`; `Timer(Duration(milliseconds: 500), _autoExpand)` fires the expansion
- Tap while compact → call `_autoExpand()` immediately (cancel timer)
- Tap while expanded flash → call `_onFlashTap()` (open app, collapse) — no toggle
- Media expanded height: 120dp (up from 76dp); add `LinearProgressIndicator` at bottom
- Timer expanded: add circular arc via `CustomPainter` + large readout
- Call expanded: 80dp height, green accent, bigger duration text

---

## Files Changed

| File | What changes |
|------|-------------|
| `android/.../OverlayForegroundService.kt` | Two-stage flash, new expanded layouts for all modes, `ArcProgressView`, content slide-in animation |
| `lib/src/island_preview.dart` | Mirror: two-stage flash, 120dp media, arc timer, 80dp call |

No changes to: `DynamicIsland.kt`, `NotificationListener.kt`, `MainActivity.kt`, `NativeBridge.dart`, `pubspec.yaml`, Android manifest, drawables.

---

## Out of Scope

- Navigation/maps mode
- Two-activity (dual-bubble) view
- Album art color extraction / dynamic background tint
- Caller name display (requires CONTACTS permission)
- End-call button in expanded call view
