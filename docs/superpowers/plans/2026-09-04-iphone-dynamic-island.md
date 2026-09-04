# iPhone-Style Dynamic Island Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign all Dynamic Island overlay modes (notification flash, media, timer, call) to match iPhone Dynamic Island behavior and visual style.

**Architecture:** All primary rendering lives in `OverlayForegroundService.kt` (a WindowManager overlay running even when the Flutter app is closed). The Flutter `IslandPreview` widget mirrors the same behavior. Two new EventChannels (`timer_events`, `call_events`) carry live timer/call state to the Flutter preview.

**Tech Stack:** Kotlin (Android API 26+), Flutter/Dart, WindowManager overlay, Android Canvas drawing, Flutter CustomPainter.

## Global Constraints

- minSdk 26. All API guards use `Build.VERSION.SDK_INT >= Build.VERSION_CODES.X`.
- All dp conversions in Kotlin use the existing `private fun dp(v: Int): Int` helper in `OverlayForegroundService`.
- No new Gradle dependencies. No new Flutter pub packages.
- No INTERNET permission. No notification content logged or persisted.
- EventChannel names follow pattern: `com.example.dynamic_island_app/<name>`.

---

## File Map

| File | What changes |
|------|-------------|
| `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt` | Layout restructure, two-stage flash, all mode expanded views, ArcProgressView, slide-in animation |
| `android/app/src/main/kotlin/com/example/dynamic_island_app/DynamicIsland.kt` | Add `timerSink`, `callSink`, `forwardTimerState()`, `forwardCallState()` |
| `android/app/src/main/kotlin/com/example/dynamic_island_app/MainActivity.kt` | Wire `timer_events` and `call_events` EventChannels |
| `lib/src/native_bridge.dart` | Add `timerEvents()` and `callEvents()` streams |
| `lib/src/island_preview.dart` | Two-stage flash, media 120dp, timer arc, call display |

---

### Task 1: Native — Two-stage notification flash

**Files:** Modify `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

- [ ] **Step 1: Add constants and change `flashExpanded` default**

In the `companion object`, add after `private const val UNLOCK_MS = 1400L`:

```kotlin
private const val FLASH_COMPACT_HOLD_MS = 500L
private const val FLASH_COMPACT_W_DP = 80
private const val FLASH_COMPACT_H_DP = 44
```

Change the existing `private var flashExpanded = true` to:

```kotlin
private var flashExpanded = false
private var flashStartedAtMs = 0L
```

- [ ] **Step 2: Add new fields to the class**

After the `private var flashArmed = false` line, add:

```kotlin
private lateinit var labelRow: LinearLayout
private lateinit var timestampView: TextView
```

After the `private val flashArmedRunnable = Runnable { ... }` block, add:

```kotlin
private val flashExpandRunnable = Runnable {
    if (mode == Mode.FLASH) {
        flashExpanded = true
        render()
    }
}
```

- [ ] **Step 3: Build `timestampView` and `labelRow` in `createWindow()`**

In `createWindow()`, add these two blocks immediately before where `textColumn` is built:

```kotlin
timestampView = TextView(this).apply {
    textSize = 10f
    setTextColor(0xFF6E6E76.toInt())
    maxLines = 1
    gravity = Gravity.END
    visibility = View.GONE
}

labelRow = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    addView(
        appLabelView,
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )
    addView(timestampView)
}
```

Replace the existing `textColumn = LinearLayout(this).apply { ... }` block so it uses `labelRow` instead of adding `appLabelView` directly:

```kotlin
textColumn = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
    )
    addView(labelRow)
    addView(bodyView)
    // mediaProgressBar added in Task 2
}
```

- [ ] **Step 4: Update `showAlert()` — compact-first, two-stage timing**

Replace the entire body of `showAlert()`:

```kotlin
fun showAlert(alert: NotificationAlert) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        main.post { showAlert(alert) }
        return
    }
    rootView ?: return
    main.removeCallbacks(hideRunnable)
    main.removeCallbacks(flashArmedRunnable)
    main.removeCallbacks(flashExpandRunnable)
    cancelAnimations()
    currentAlert?.let { DynamicIsland.dropContentIntent(it.key) }
    currentAlert = alert
    flashArmed = false
    flashExpanded = false
    flashStartedAtMs = System.currentTimeMillis()

    render(fromFlashStart = true)   // compact pill appears immediately

    main.postDelayed(flashExpandRunnable, FLASH_COMPACT_HOLD_MS)
    main.postDelayed(flashArmedRunnable, FLASH_COMPACT_HOLD_MS + EXPAND_MS)

    val holdMs = HOLD_MS +
        (alert.text.length * HOLD_PER_CHAR_MS).coerceAtMost(MAX_EXTRA_HOLD_MS)
    main.postDelayed(hideRunnable, FLASH_COMPACT_HOLD_MS + EXPAND_MS + holdMs)
}
```

- [ ] **Step 5: Update `endFlashNow()` and `hideRunnable` to cancel `flashExpandRunnable`**

In `endFlashNow()`, add after existing removeCallbacks lines:

```kotlin
main.removeCallbacks(flashExpandRunnable)
```

Replace the `hideRunnable` at the top of the class:

```kotlin
private val hideRunnable = Runnable {
    main.removeCallbacks(flashExpandRunnable)
    currentAlert?.let { DynamicIsland.dropContentIntent(it.key) }
    currentAlert = null
    flashArmed = false
    render()
}
```

- [ ] **Step 6: Replace tap/long-press handlers on `rootView`**

Replace `setOnClickListener` and `setOnLongClickListener` in the `rootView` build block:

```kotlin
setOnClickListener {
    when (mode) {
        Mode.MEDIA, Mode.CALL, Mode.TIMER -> {
            userExpanded = !userExpanded
            render()
        }
        Mode.FLASH -> when {
            !flashExpanded -> {
                // Compact: skip wait, expand immediately
                main.removeCallbacks(flashExpandRunnable)
                main.removeCallbacks(flashArmedRunnable)
                flashExpanded = true
                flashArmed = false
                main.postDelayed(flashArmedRunnable, EXPAND_MS)
                render()
            }
            flashArmed -> {
                // Expanded + armed: open source app
                currentAlert?.let { a -> DynamicIsland.openAlert(a.key) }
            }
            // expanded but not yet armed — ignore
        }
        else -> Unit
    }
}
// Long-press removed: tap now handles open-app
```

- [ ] **Step 7: Update `applyTouchability` call — flash always touchable**

In `render()`, replace:

```kotlin
applyTouchability(
    mode.isPersistent() || (mode == Mode.FLASH && flashArmed)
)
```

with:

```kotlin
applyTouchability(
    mode.isPersistent() || mode == Mode.FLASH
)
```

- [ ] **Step 8: Rewrite `applyFlashContent()` for compact vs expanded layouts**

Replace `applyFlashContent()`:

```kotlin
private fun applyFlashContent() {
    val alert = currentAlert ?: return
    iconView.visibility = View.VISIBLE
    iconView.scaleType = ImageView.ScaleType.CENTER_CROP
    iconView.imageTintList = null
    iconView.setImageDrawable(alert.icon)
    eqContainer.visibility = View.GONE
    controlsRow.visibility = View.GONE

    if (flashExpanded) {
        setIconSize(dp(36))
        textColumn.visibility = View.VISIBLE
        labelRow.visibility = View.VISIBLE
        appLabelView.text = alert.appLabel
        val elapsedSec = (System.currentTimeMillis() - flashStartedAtMs) / 1000L
        timestampView.text = if (elapsedSec < 5L) "just now" else "${elapsedSec}s ago"
        timestampView.visibility = View.VISIBLE
        bodyView.textSize = 13f
        bodyView.maxLines = MAX_BODY_LINES
        bodyView.text = alert.text.ifBlank { alert.title }
    } else {
        setIconSize(dp(28))
        textColumn.visibility = View.GONE
        timestampView.visibility = View.GONE
    }
}
```

- [ ] **Step 9: Update `computeFlashHeight()` min to 64dp and update `setContentAlpha()`**

In `computeFlashHeight()`, change `dp(56)` to `dp(64)`.

In `setContentAlpha()`, add `timestampView.alpha = alpha` to the list.

In `fadeContent()`, add to `set.playTogether(...)`:
```kotlin
ObjectAnimator.ofFloat(timestampView, "alpha", timestampView.alpha, to),
```

- [ ] **Step 10: Build and test**

```bash
cd /Users/niranjanrimal/Desktop/dynamic_island_app && flutter run
```

Expected:
1. Tap "Show test alert" — compact icon-only pill (~80×44dp) appears.
2. After ~500ms pill expands to show app name + body + "just now" timestamp.
3. Tapping expanded pill opens the source app and collapses island.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: two-stage notification flash — compact-first, tap expanded opens app"
```

---

### Task 2: Native — Media 120dp + call expanded + accent bar

**Files:** Modify `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

- [ ] **Step 1: Add fields**

Add to class fields near `controlsRow`:

```kotlin
private lateinit var callAccentBar: View
private lateinit var mediaProgressBar: android.widget.ProgressBar
```

- [ ] **Step 2: Build `callAccentBar` in `createWindow()`**

Before the `rootView` build block, add:

```kotlin
callAccentBar = View(this).apply {
    setBackgroundColor(0xFF30D158.toInt())
    layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
    visibility = View.GONE
}
```

Inside the `rootView` build block, add `addView(callAccentBar)` as the very first `addView` call (before `addView(iconView)`).

- [ ] **Step 3: Build `mediaProgressBar` and add to `textColumn`**

Before building `textColumn`, add:

```kotlin
mediaProgressBar = android.widget.ProgressBar(
    this, null, android.R.attr.progressBarStyleHorizontal
).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(2)
    ).apply { topMargin = dp(4) }
    max = 1000
    progress = 0
    progressTintList = ColorStateList.valueOf(0xFF7ED6DF.toInt())
    progressBackgroundTintList = ColorStateList.valueOf(0xFF2A2A31.toInt())
    visibility = View.GONE
}
```

In the `textColumn` build block, add `addView(mediaProgressBar)` after `addView(bodyView)`.

- [ ] **Step 4: Replace `applyMediaContent()` — 56dp art, progress bar, 14sp title**

```kotlin
private fun applyMediaContent() {
    val media = DynamicIsland.mediaState ?: return
    iconView.visibility = View.VISIBLE
    iconView.scaleType = ImageView.ScaleType.CENTER_CROP
    iconView.imageTintList = null
    if (media.art != null) {
        iconView.setImageBitmap(media.art)
    } else {
        iconView.setImageResource(R.drawable.ic_music)
        iconView.imageTintList = ColorStateList.valueOf(0xFF9E9EA7.toInt())
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    eqContainer.visibility = View.GONE

    if (userExpanded) {
        setIconSize(dp(56))
        textColumn.visibility = View.VISIBLE
        labelRow.visibility = View.VISIBLE
        appLabelView.text = media.artist.ifBlank { media.appLabel }
        appLabelView.visibility = View.VISIBLE
        timestampView.visibility = View.GONE
        bodyView.text = media.title.ifBlank { media.appLabel }
        bodyView.textSize = 14f
        bodyView.maxLines = 1
        val prog = if (media.durationMs > 0L) {
            (media.positionMs * 1000L / media.durationMs).toInt().coerceIn(0, 1000)
        } else 0
        mediaProgressBar.progress = prog
        mediaProgressBar.visibility = View.VISIBLE
        controlsRow.visibility = View.VISIBLE
        btnPlay.setImageResource(
            if (media.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    } else {
        setIconSize(dp(28))
        textColumn.visibility = View.GONE
        controlsRow.visibility = View.GONE
        mediaProgressBar.visibility = View.GONE
        bodyView.textSize = 13f
    }
}
```

- [ ] **Step 5: Update media expanded height in `render()`**

In the `Mode.MEDIA ->` block, change expanded height:

```kotlin
Mode.MEDIA -> {
    applyMediaContent()
    if (userExpanded) {
        targetW = expandedWidthPx
        targetH = dp(120)   // was dp(76)
    } else {
        targetW = dp(112)
        targetH = dp(44)
    }
}
```

- [ ] **Step 6: Replace `applyCallContent()` — green accent, 20sp text, 80dp expanded**

```kotlin
private fun applyCallContent() {
    iconView.visibility = View.VISIBLE
    setIconSize(if (userExpanded) dp(24) else dp(18))
    iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
    iconView.imageTintList = ColorStateList.valueOf(0xFF30D158.toInt())
    iconView.setImageResource(R.drawable.ic_call)
    textColumn.visibility = View.VISIBLE
    labelRow.visibility = if (userExpanded) View.VISIBLE else View.GONE
    appLabelView.visibility = if (userExpanded) View.VISIBLE else View.GONE
    timestampView.visibility = View.GONE
    appLabelView.text = "Call"
    val start = DynamicIsland.callStartedAtElapsedMs
    bodyView.textSize = if (userExpanded) 20f else 13f
    bodyView.maxLines = 1
    bodyView.text = if (start != null) {
        formatElapsed(SystemClock.elapsedRealtime() - start)
    } else ""
    eqContainer.visibility = View.GONE
    controlsRow.visibility = View.GONE
    mediaProgressBar.visibility = View.GONE
}
```

- [ ] **Step 7: Toggle accent bar and padding in `render()`**

At the top of `render()`, after `val newMode = targetMode()`, add:

```kotlin
val isCallExpanded = newMode == Mode.CALL && userExpanded
callAccentBar.visibility = if (isCallExpanded) View.VISIBLE else View.GONE
rootView?.setPadding(if (isCallExpanded) 0 else dp(10), 0, dp(6), 0)
(iconView.layoutParams as? LinearLayout.LayoutParams)?.marginStart =
    if (isCallExpanded) dp(10) else 0
iconView.requestLayout()
```

Update call dimensions in `render()`:

```kotlin
Mode.CALL -> {
    applyCallContent()
    if (userExpanded) {
        targetW = dp(300)   // was dp(190)
        targetH = dp(80)    // was dp(52)
    } else {
        targetW = dp(118)
        targetH = dp(40)
    }
}
```

- [ ] **Step 8: Include new views in `setContentAlpha()`, `fadeContent()`, `clearContent()`**

`setContentAlpha()` — add `mediaProgressBar.alpha = alpha`.

`fadeContent()` — add to `set.playTogether(...)`:
```kotlin
ObjectAnimator.ofFloat(mediaProgressBar, "alpha", mediaProgressBar.alpha, to),
```

`clearContent()` — add:
```kotlin
mediaProgressBar.visibility = View.GONE
mediaProgressBar.progress = 0
callAccentBar.visibility = View.GONE
bodyView.textSize = 13f
```

- [ ] **Step 9: Build and test**

```bash
flutter run
```

Expected:
1. Play music → compact album art + waveform. Tap → 120dp expanded: 56dp art, title, artist, progress bar, controls.
2. Make call → compact green phone + "0:00". Tap → 80dp expanded: green left bar, "Call" label, large 20sp duration.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: media expanded 120dp + call expanded 80dp with green accent bar"
```

---

### Task 3: Native — Timer arc + expanded view

**Files:** Modify `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

- [ ] **Step 1: Add `ArcProgressView` inner class at bottom of file**

Add before the final closing `}` of `OverlayForegroundService`:

```kotlin
private inner class ArcProgressView(ctx: android.content.Context) : android.view.View(ctx) {
    var progress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var arcColor: Int = 0xFF30D158.toInt()
        set(v) { field = v; invalidate() }

    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        color = 0xFF2A2A31.toInt()
    }
    private val fgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val sw = dp(4).toFloat()
        bgPaint.strokeWidth = sw; fgPaint.strokeWidth = sw; fgPaint.color = arcColor
        val cx = width / 2f; val cy = height / 2f
        val r = (minOf(width, height) / 2f) - sw
        val oval = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, -90f, 360f, false, bgPaint)
        if (progress > 0f) canvas.drawArc(oval, -90f, progress * 360f, false, fgPaint)
    }
}
```

- [ ] **Step 2: Add `arcView` field, build in `createWindow()`, add to rootView**

Add field: `private lateinit var arcView: ArcProgressView`

In `createWindow()`, before `iconView = ImageView(this)...`:

```kotlin
arcView = ArcProgressView(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
    visibility = View.GONE
}
```

In rootView build block, add `addView(arcView)` after `addView(callAccentBar)` and before `addView(iconView)`.

- [ ] **Step 3: Replace `applyTimerContent()` with arc support**

```kotlin
private fun applyTimerContent() {
    val t = DynamicIsland.timerState ?: return
    textColumn.visibility = View.VISIBLE
    labelRow.visibility = if (userExpanded) View.VISIBLE else View.GONE
    appLabelView.visibility = if (userExpanded) View.VISIBLE else View.GONE
    timestampView.visibility = View.GONE
    appLabelView.text = if (t.kind == TimerKind.STOPWATCH) "Stopwatch" else "Timer"
    bodyView.maxLines = 1
    bodyView.text = timerReadout()
    eqContainer.visibility = View.GONE
    controlsRow.visibility = View.GONE
    mediaProgressBar.visibility = View.GONE

    if (userExpanded) {
        iconView.visibility = View.GONE
        arcView.visibility = View.VISIBLE
        arcView.arcColor = if (t.kind == TimerKind.COUNTDOWN) 0xFF30D158.toInt()
                           else 0xFF7ED6DF.toInt()
        arcView.progress = timerArcProgress(t)
        bodyView.textSize = 22f
    } else {
        iconView.visibility = View.VISIBLE
        setIconSize(dp(18))
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.imageTintList = ColorStateList.valueOf(
            if (t.kind == TimerKind.STOPWATCH) 0xFF7ED6DF.toInt() else 0xFFFFD60A.toInt()
        )
        iconView.setImageResource(R.drawable.ic_timer)
        arcView.visibility = View.GONE
        bodyView.textSize = 13f
    }
}

private fun timerArcProgress(t: TimerState): Float {
    val elapsed = SystemClock.elapsedRealtime() - t.startedAtElapsedMs
    return if (t.kind == TimerKind.COUNTDOWN && t.durationMs > 0L)
        (t.durationMs - elapsed).coerceAtLeast(0L).toFloat() / t.durationMs
    else
        (elapsed % 60_000L).toFloat() / 60_000f
}
```

- [ ] **Step 4: Update timer dimensions in `render()` and arc tick in `tickOnce()`**

In `render()`, timer expanded height:

```kotlin
Mode.TIMER -> {
    applyTimerContent()
    if (userExpanded) {
        targetW = expandedWidthPx
        targetH = dp(110)   // was dp(52)
    } else {
        targetW = dp(118)
        targetH = dp(40)
    }
}
```

In `tickOnce()`, timer branch — add arc refresh after existing text update:

```kotlin
if (userExpanded && arcView.visibility == View.VISIBLE) {
    DynamicIsland.timerState?.let { arcView.progress = timerArcProgress(it) }
}
```

- [ ] **Step 5: Include `arcView` in `setContentAlpha()`, `fadeContent()`, `clearContent()`**

`setContentAlpha()`: add `arcView.alpha = alpha`.

`fadeContent()`: add `ObjectAnimator.ofFloat(arcView, "alpha", arcView.alpha, to)`.

`clearContent()`: add `arcView.visibility = View.GONE` and `arcView.progress = 0f`.

- [ ] **Step 6: Build and test**

```bash
flutter run
```

Expected:
1. Tap "1 min" — compact timer icon + "1:00". Tap island → 110dp expanded: green arc draining clockwise, "Timer" label, 22sp countdown.
2. Tap "Stopwatch" — compact. Tap island → teal arc fills one revolution per minute.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: timer expanded with ArcProgressView + 22sp readout"
```

---

### Task 4: Native — Content slide-in animation

**Files:** Modify `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`

- [ ] **Step 1: Replace `fadeContent()` with translationY slide**

```kotlin
private fun fadeContent(to: Float, startDelayMs: Long) {
    val fromTransY = if (to == 1f) dp(8).toFloat() else 0f
    val views = listOf(iconView, textColumn, eqContainer, controlsRow,
                       unlockView, arcView, mediaProgressBar)
    val anims = views.flatMap { v ->
        listOf(
            ObjectAnimator.ofFloat(v, "alpha", v.alpha, to),
            ObjectAnimator.ofFloat(v, "translationY", fromTransY, 0f)
        )
    }
    val set = AnimatorSet()
    @Suppress("SpreadOperator")
    set.playTogether(*anims.toTypedArray())
    set.duration = 180L
    set.startDelay = startDelayMs
    fadeAnimator?.cancel()
    fadeAnimator = set
    set.start()
}
```

- [ ] **Step 2: Reset `translationY` in `setContentAlpha()`**

```kotlin
private fun setContentAlpha(alpha: Float) {
    listOf(iconView, textColumn, eqContainer, controlsRow,
           unlockView, arcView, mediaProgressBar, timestampView).forEach {
        it.alpha = alpha
        it.translationY = 0f
    }
}
```

- [ ] **Step 3: Build and verify**

```bash
flutter run
```

Any mode expansion — content should visibly rise 8dp into place as it fades in. Collapse has no slide (only alpha fades to 0).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt
git commit -m "feat: content slide-in animation on all mode expansions"
```

---

### Task 5: Flutter — Two-stage flash + media expanded

**Files:** Modify `lib/src/island_preview.dart`

- [ ] **Step 1: Add constants, fields, and `_flashExpandTimer`**

Change `static const double _mediaExpandedH = 76` to `120`.

After `bool _mediaExpanded = false;`, add:

```dart
bool _flashExpanded = false;
Timer? _flashExpandTimer;
```

- [ ] **Step 2: Replace `_onAlert()` with compact-first two-stage logic**

```dart
void _onAlert(Map<String, dynamic> map) {
  if (!mounted) return;
  final alert = AlertPreview.fromMap(map);
  _flashExpandTimer?.cancel();
  _armTimer?.cancel();
  _hideTimer?.cancel();
  setState(() {
    _current = alert;
    _flashExpanded = false;
    _flashArmed = false;
  });
  _retarget();

  _flashExpandTimer = Timer(const Duration(milliseconds: 500), () {
    if (!mounted || _current != alert) return;
    setState(() => _flashExpanded = true);
    _retarget();
    _armTimer = Timer(_animDuration, () {
      if (mounted) setState(() => _flashArmed = true);
    });
  });

  final hold = const Duration(milliseconds: 500) +
      _animDuration +
      _baseHoldDuration +
      Duration(milliseconds: (alert.summary.length * 8).clamp(0, 4000));
  _hideTimer = Timer(hold, () {
    if (!mounted) return;
    _flashExpandTimer?.cancel();
    setState(() { _current = null; _flashExpanded = false; _flashArmed = false; });
    _retarget();
  });
}
```

- [ ] **Step 3: Update `dispose()` and `_onTap()`**

In `dispose()`, add `_flashExpandTimer?.cancel();` before existing cancel calls.

Replace `_onTap()`:

```dart
void _onTap() {
  if (_current != null) {
    if (!_flashExpanded) {
      _flashExpandTimer?.cancel();
      _armTimer?.cancel();
      setState(() { _flashExpanded = true; _flashArmed = false; });
      _retarget();
      _armTimer = Timer(_animDuration, () {
        if (mounted) setState(() => _flashArmed = true);
      });
    } else if (_flashArmed) {
      _onFlashTap();
    }
  } else if (_media != null) {
    setState(() => _mediaExpanded = !_mediaExpanded);
    _retarget();
  }
}
```

- [ ] **Step 4: Update `_retarget()` for compact flash size**

Replace the flash branch:

```dart
void _retarget() {
  if (_current != null) {
    _animateTo(_flashExpanded ? _expandedW : 80,
               _flashExpanded ? _flashHeight(_current!) : 44);
  } else if (_media != null) {
    if (_mediaExpanded) {
      _animateTo(_expandedW, _mediaExpandedH);
    } else {
      _animateTo(_mediaCompactW, _mediaCompactH);
    }
  } else {
    _animateTo(_compactW, _compactH);
  }
}
```

- [ ] **Step 5: Add `_withSlideIn()` helper**

```dart
Widget _withSlideIn(Widget child) {
  return AnimatedBuilder(
    animation: _t,
    builder: (_, __) => Transform.translate(
      offset: Offset(0, (1 - _t.value).clamp(0.0, 1.0) * 8),
      child: Opacity(opacity: _t.value.clamp(0.0, 1.0), child: child),
    ),
  );
}
```

- [ ] **Step 6: Update `build()` to use `_withSlideIn` and handle compact flash**

In `build()`, update the `child:` of the `Container`:

```dart
child: _current != null
    ? _withSlideIn(_flashContent(_current!))
    : _media != null
        ? _withSlideIn(_mediaExpanded
            ? _mediaExpandedContent(_media!)
            : _mediaCompactContent(_media!))
        : null,
```

Update `interactive`:

```dart
final bool interactive = _current != null || _media != null;
```

- [ ] **Step 7: Rewrite `_flashContent()` for compact vs expanded layouts**

```dart
Widget _flashContent(AlertPreview alert) {
  if (!_flashExpanded) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: _AppIconBadge(appLabel: alert.appLabel),
      ),
    );
  }
  return Padding(
    padding: const EdgeInsets.only(left: 10, right: 10),
    child: Row(
      children: [
        _AppIconBadge(appLabel: alert.appLabel, size: 36),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Expanded(
                  child: Text(alert.appLabel,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: _labelStyle),
                ),
                const Text('just now',
                    style: TextStyle(fontSize: 10, color: Color(0xFF6E6E76))),
              ]),
              Text(alert.summary,
                  maxLines: _maxBodyLines,
                  overflow: TextOverflow.ellipsis,
                  style: _bodyStyle),
            ],
          ),
        ),
      ],
    ),
  );
}
```

- [ ] **Step 8: Update `_AppIconBadge` to accept optional `size`**

```dart
class _AppIconBadge extends StatelessWidget {
  const _AppIconBadge({required this.appLabel, this.size = 32});
  final String appLabel;
  final double size;

  @override
  Widget build(BuildContext context) {
    const List<Color> palette = [
      Color(0xFF6C5CE7), Color(0xFF00B894), Color(0xFFE17055),
      Color(0xFF0984E3), Color(0xFFE84393), Color(0xFF00CEC9),
    ];
    final tint = palette[appLabel.hashCode.abs() % palette.length];
    return Container(
      width: size, height: size,
      decoration: BoxDecoration(
        color: Color.alphaBlend(tint.withValues(alpha: 0.22), const Color(0xFF17171C)),
        borderRadius: BorderRadius.circular(size * 0.25),
      ),
      alignment: Alignment.center,
      child: Text(
        appLabel.isEmpty ? '?' : appLabel.substring(0, 1).toUpperCase(),
        style: TextStyle(color: Colors.white, fontSize: size * 0.47,
                         fontWeight: FontWeight.w600),
      ),
    );
  }
}
```

- [ ] **Step 9: Update `_mediaExpandedContent()` — 56dp art, 120dp, progress bar**

```dart
Widget _mediaExpandedContent(MediaPreview media) {
  final double progress = media.durationMs > 0
      ? (media.positionMs / media.durationMs).clamp(0.0, 1.0)
      : 0;
  return Padding(
    padding: const EdgeInsets.only(left: 10, right: 6),
    child: Row(
      children: [
        _art(media, 56),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(media.title.isEmpty ? media.appLabel : media.title,
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white, fontSize: 14,
                                         fontWeight: FontWeight.w500)),
              Text(media.artist.isEmpty ? media.appLabel : media.artist,
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: _labelStyle),
              const SizedBox(height: 6),
              LinearProgressIndicator(
                value: progress, minHeight: 2,
                backgroundColor: const Color(0xFF2A2A31),
                valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFF7ED6DF)),
              ),
            ],
          ),
        ),
        _ControlButton(icon: Icons.skip_previous, onTap: () => NativeBridge.mediaPrev()),
        _ControlButton(
          icon: media.playing ? Icons.pause : Icons.play_arrow,
          onTap: () => NativeBridge.mediaPlayPause(),
        ),
        _ControlButton(icon: Icons.skip_next, onTap: () => NativeBridge.mediaNext()),
      ],
    ),
  );
}
```

- [ ] **Step 10: Analyze and test**

```bash
flutter analyze lib/src/island_preview.dart
flutter run
```

Expected: no analyzer errors. In-app preview: test alert shows compact → auto-expands; tap expanded = collapses. Media expanded shows 120dp height with progress bar.

- [ ] **Step 11: Commit**

```bash
git add lib/src/island_preview.dart
git commit -m "feat: Flutter preview — two-stage flash, 120dp media, slide-in animation"
```

---

### Task 6: Flutter/Native — Timer and call live preview

**Files:**
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/DynamicIsland.kt`
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt`
- Modify: `android/app/src/main/kotlin/com/example/dynamic_island_app/MainActivity.kt`
- Modify: `lib/src/native_bridge.dart`
- Modify: `lib/src/island_preview.dart`

- [ ] **Step 1: Add timer/call sinks to `DynamicIsland.kt`**

After `private var mediaSink: EventChannel.EventSink? = null`, add:

```kotlin
@Volatile private var timerSink: EventChannel.EventSink? = null
@Volatile private var callSink: EventChannel.EventSink? = null

fun setTimerSink(sink: EventChannel.EventSink?) { timerSink = sink }
fun setCallSink(sink: EventChannel.EventSink?) { callSink = sink }

fun forwardTimerState() {
    val sink = timerSink ?: return
    val t = timerState
    val payload: Map<String, Any?>? = if (t == null) null else {
        val elapsed = android.os.SystemClock.elapsedRealtime() - t.startedAtElapsedMs
        mapOf(
            "kind" to if (t.kind == TimerKind.COUNTDOWN) "countdown" else "stopwatch",
            "positionMs" to elapsed,
            "durationMs" to t.durationMs
        )
    }
    mainHandler.post { sink.success(payload) }
}

fun forwardCallState() {
    val sink = callSink ?: return
    val start = callStartedAtElapsedMs
    val payload: Map<String, Any?>? = if (start == null) null else
        mapOf("elapsedMs" to (android.os.SystemClock.elapsedRealtime() - start))
    mainHandler.post { sink.success(payload) }
}
```

- [ ] **Step 2: Call forward methods from `onLiveStateChanged()`**

In `DynamicIsland.onLiveStateChanged()`, add after `forwardMediaState()`:

```kotlin
forwardTimerState()
forwardCallState()
```

- [ ] **Step 3: Add per-tick forwarding in `OverlayForegroundService.tickOnce()`**

At the end of the `Mode.TIMER ->` branch, add `DynamicIsland.forwardTimerState()`.
At the end of the `Mode.CALL ->` branch, add `DynamicIsland.forwardCallState()`.

- [ ] **Step 4: Wire new EventChannels in `MainActivity.kt`**

In `configureFlutterEngine()`, add after the existing `media_events` block:

```kotlin
EventChannel(
    flutterEngine.dartExecutor.binaryMessenger,
    "com.example.dynamic_island_app/timer_events"
).setStreamHandler(object : EventChannel.StreamHandler {
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        DynamicIsland.setTimerSink(events)
        DynamicIsland.forwardTimerState()
    }
    override fun onCancel(arguments: Any?) = DynamicIsland.setTimerSink(null)
})

EventChannel(
    flutterEngine.dartExecutor.binaryMessenger,
    "com.example.dynamic_island_app/call_events"
).setStreamHandler(object : EventChannel.StreamHandler {
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        DynamicIsland.setCallSink(events)
        DynamicIsland.forwardCallState()
    }
    override fun onCancel(arguments: Any?) = DynamicIsland.setCallSink(null)
})
```

In `onDestroy()`, add before `super.onDestroy()`:

```kotlin
DynamicIsland.setTimerSink(null)
DynamicIsland.setCallSink(null)
```

- [ ] **Step 5: Add streams to `native_bridge.dart`**

After `static const EventChannel _mediaEvents = ...`, add:

```dart
static const EventChannel _timerEvents =
    EventChannel('com.example.dynamic_island_app/timer_events');
static const EventChannel _callEvents =
    EventChannel('com.example.dynamic_island_app/call_events');
```

After `mediaEvents()`, add:

```dart
static Stream<Map<String, dynamic>?> timerEvents() =>
    _timerEvents.receiveBroadcastStream().map(
          (e) => (e as Map?)?.cast<String, dynamic>(),
        );

static Stream<Map<String, dynamic>?> callEvents() =>
    _callEvents.receiveBroadcastStream().map(
          (e) => (e as Map?)?.cast<String, dynamic>(),
        );
```

- [ ] **Step 6: Update `IslandPreview` widget with new stream parameters**

```dart
class IslandPreview extends StatefulWidget {
  const IslandPreview({
    super.key,
    this.previewStream,
    this.mediaStream,
    this.timerStream,
    this.callStream,
  });

  final Stream<Map<String, dynamic>>? previewStream;
  final Stream<Map<String, dynamic>?>? mediaStream;
  final Stream<Map<String, dynamic>?>? timerStream;
  final Stream<Map<String, dynamic>?>? callStream;

  @override
  State<IslandPreview> createState() => _IslandPreviewState();
}
```

- [ ] **Step 7: Add timer/call state fields and subscriptions to `_IslandPreviewState`**

After `bool _mediaExpanded = false;`, add:

```dart
Map<String, dynamic>? _timerData;
Map<String, dynamic>? _callData;
bool _timerExpanded = false;
bool _callExpanded = false;
StreamSubscription<Map<String, dynamic>?>? _timerSub;
StreamSubscription<Map<String, dynamic>?>? _callSub;
```

In `initState()`, after `_mediaSub = ...`:

```dart
_timerSub = (widget.timerStream ?? NativeBridge.timerEvents()).listen(
  (data) {
    if (!mounted) return;
    setState(() { _timerData = data; if (data == null) _timerExpanded = false; });
    if (_current == null && _media == null) _retarget();
  },
  onError: (Object _, StackTrace __) {},
);
_callSub = (widget.callStream ?? NativeBridge.callEvents()).listen(
  (data) {
    if (!mounted) return;
    setState(() { _callData = data; if (data == null) _callExpanded = false; });
    if (_current == null && _media == null) _retarget();
  },
  onError: (Object _, StackTrace __) {},
);
```

In `dispose()`, add `_timerSub?.cancel();` and `_callSub?.cancel();`.

- [ ] **Step 8: Update `_retarget()`, `_onTap()`, and `build()` for call/timer**

Replace `_retarget()`:

```dart
void _retarget() {
  if (_current != null) {
    _animateTo(_flashExpanded ? _expandedW : 80,
               _flashExpanded ? _flashHeight(_current!) : 44);
  } else if (_callData != null) {
    _animateTo(_callExpanded ? _expandedW : 118, _callExpanded ? 80 : 40);
  } else if (_timerData != null) {
    _animateTo(_timerExpanded ? _expandedW : 118, _timerExpanded ? 110 : 40);
  } else if (_media != null) {
    _animateTo(_mediaExpanded ? _expandedW : _mediaCompactW,
               _mediaExpanded ? _mediaExpandedH : _mediaCompactH);
  } else {
    _animateTo(_compactW, _compactH);
  }
}
```

Replace `_onTap()`:

```dart
void _onTap() {
  if (_current != null) {
    if (!_flashExpanded) {
      _flashExpandTimer?.cancel(); _armTimer?.cancel();
      setState(() { _flashExpanded = true; _flashArmed = false; });
      _retarget();
      _armTimer = Timer(_animDuration, () {
        if (mounted) setState(() => _flashArmed = true);
      });
    } else if (_flashArmed) { _onFlashTap(); }
  } else if (_callData != null) {
    setState(() => _callExpanded = !_callExpanded); _retarget();
  } else if (_timerData != null) {
    setState(() => _timerExpanded = !_timerExpanded); _retarget();
  } else if (_media != null) {
    setState(() => _mediaExpanded = !_mediaExpanded); _retarget();
  }
}
```

In `build()`, update `interactive` and content `child:`:

```dart
final bool interactive =
    _current != null || _callData != null || _timerData != null || _media != null;
```

```dart
child: _current != null
    ? _withSlideIn(_flashContent(_current!))
    : _callData != null
        ? _withSlideIn(_callExpanded
            ? _callExpandedContent(_callData!)
            : _callCompactContent(_callData!))
        : _timerData != null
            ? _withSlideIn(_timerExpanded
                ? _timerExpandedContent(_timerData!)
                : _timerCompactContent(_timerData!))
            : _media != null
                ? _withSlideIn(_mediaExpanded
                    ? _mediaExpandedContent(_media!)
                    : _mediaCompactContent(_media!))
                : null,
```

- [ ] **Step 9: Add `_formatElapsed()` helper**

```dart
String _formatElapsed(int ms) {
  final total = (ms / 1000).round().clamp(0, 359999);
  final h = total ~/ 3600;
  final m = (total % 3600) ~/ 60;
  final s = total % 60;
  if (h > 0) return '$h:${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
  return '$m:${s.toString().padLeft(2,'0')}';
}
```

- [ ] **Step 10: Add call content widgets**

```dart
Widget _callCompactContent(Map<String, dynamic> data) {
  return Padding(
    padding: const EdgeInsets.symmetric(horizontal: 10),
    child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      const Icon(Icons.call, color: Color(0xFF30D158), size: 18),
      const SizedBox(width: 6),
      Text(_formatElapsed((data['elapsedMs'] as int?) ?? 0),
          style: const TextStyle(color: Colors.white, fontSize: 13)),
    ]),
  );
}

Widget _callExpandedContent(Map<String, dynamic> data) {
  return Row(children: [
    Container(width: 3, color: const Color(0xFF30D158)),
    const SizedBox(width: 10),
    const Icon(Icons.call, color: Color(0xFF30D158), size: 24),
    const SizedBox(width: 8),
    Expanded(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Call', style: TextStyle(fontSize: 11, color: Color(0xFF9E9EA7))),
          Text(_formatElapsed((data['elapsedMs'] as int?) ?? 0),
              style: const TextStyle(color: Colors.white, fontSize: 20,
                                     fontWeight: FontWeight.w500)),
        ],
      ),
    ),
  ]);
}
```

- [ ] **Step 11: Add `_TimerArcPainter` and timer content widgets**

Add `_TimerArcPainter` at the bottom of the file (outside the state class):

```dart
class _TimerArcPainter extends CustomPainter {
  final double progress;
  final Color arcColor;
  const _TimerArcPainter({required this.progress, required this.arcColor});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = (size.shortestSide / 2) - 4;
    const sw = 4.0;
    final bg = Paint()
      ..style = PaintingStyle.stroke ..strokeWidth = sw
      ..strokeCap = StrokeCap.round ..color = const Color(0xFF2A2A31);
    final fg = Paint()
      ..style = PaintingStyle.stroke ..strokeWidth = sw
      ..strokeCap = StrokeCap.round ..color = arcColor;
    canvas.drawCircle(center, radius, bg);
    if (progress > 0) {
      canvas.drawArc(Rect.fromCircle(center: center, radius: radius),
          -math.pi / 2, progress.clamp(0.0, 1.0) * 2 * math.pi, false, fg);
    }
  }

  @override
  bool shouldRepaint(_TimerArcPainter o) =>
      o.progress != progress || o.arcColor != arcColor;
}
```

Add timer content methods to `_IslandPreviewState`:

```dart
Widget _timerCompactContent(Map<String, dynamic> data) {
  final kind = (data['kind'] as String?) ?? 'countdown';
  final posMs = (data['positionMs'] as int?) ?? 0;
  final durMs = (data['durationMs'] as int?) ?? 0;
  final display = kind == 'countdown' ? (durMs - posMs).clamp(0, durMs) : posMs;
  return Padding(
    padding: const EdgeInsets.symmetric(horizontal: 10),
    child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      Icon(Icons.timer_outlined, size: 18,
          color: kind == 'countdown'
              ? const Color(0xFFFFD60A) : const Color(0xFF7ED6DF)),
      const SizedBox(width: 6),
      Text(_formatElapsed(display),
          style: const TextStyle(color: Colors.white, fontSize: 13)),
    ]),
  );
}

Widget _timerExpandedContent(Map<String, dynamic> data) {
  final kind = (data['kind'] as String?) ?? 'countdown';
  final posMs = (data['positionMs'] as int?) ?? 0;
  final durMs = (data['durationMs'] as int?) ?? 0;
  final double arc;
  final int display;
  if (kind == 'countdown') {
    final rem = (durMs - posMs).clamp(0, durMs);
    arc = durMs > 0 ? rem / durMs : 0;
    display = rem;
  } else {
    arc = (posMs % 60000) / 60000;
    display = posMs;
  }
  final arcColor = kind == 'countdown'
      ? const Color(0xFF30D158) : const Color(0xFF7ED6DF);
  return Padding(
    padding: const EdgeInsets.symmetric(horizontal: 10),
    child: Row(children: [
      CustomPaint(
          size: const Size(64, 64),
          painter: _TimerArcPainter(progress: arc, arcColor: arcColor)),
      const SizedBox(width: 10),
      Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(kind == 'countdown' ? 'Timer' : 'Stopwatch',
              style: const TextStyle(fontSize: 11, color: Color(0xFF9E9EA7))),
          Text(_formatElapsed(display),
              style: const TextStyle(color: Colors.white, fontSize: 22,
                                     fontWeight: FontWeight.w500)),
        ],
      ),
    ]),
  );
}
```

- [ ] **Step 12: Analyze and test**

```bash
flutter analyze lib/
flutter run
```

Expected:
1. Tap "1 min" — preview shows compact "1:00" icon. Tap preview → green arc + large 22sp countdown ticking live.
2. Tap "Stopwatch" → compact; tap → teal arc spinning one revolution per minute.
3. Make phone call → compact green phone timer. Tap → expanded with green left bar, "Call" label, 20sp elapsed.

- [ ] **Step 13: Final commit**

```bash
git add \
  android/app/src/main/kotlin/com/example/dynamic_island_app/DynamicIsland.kt \
  android/app/src/main/kotlin/com/example/dynamic_island_app/OverlayForegroundService.kt \
  android/app/src/main/kotlin/com/example/dynamic_island_app/MainActivity.kt \
  lib/src/native_bridge.dart \
  lib/src/island_preview.dart
git commit -m "feat: timer/call live preview via new EventChannels + arc CustomPainter"
```
