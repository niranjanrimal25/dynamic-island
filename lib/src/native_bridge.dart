import 'package:flutter/services.dart';

/// Snapshot of the app's runtime state on the Android side.
///
/// SECURITY: these are status FLAGS only (booleans). No notification content
/// ever crosses the method channel except the transient alert *preview*
/// described on [NativeBridge.alertPreviews].
class IslandStatus {
  const IslandStatus({
    required this.overlayEnabled,
    required this.overlayRunning,
    required this.canDrawOverlays,
    required this.notificationAccessGranted,
    required this.ignoringBatteryOptimizations,
    required this.postNotificationsPermission,
    this.phoneStatePermission = false,
    this.timerActive = false,
    this.callActive = false,
    this.mediaActive = false,
  });

  factory IslandStatus.fromMap(Map<dynamic, dynamic> map) {
    bool b(String key) => map[key] == true;
    return IslandStatus(
      overlayEnabled: b('overlayEnabled'),
      overlayRunning: b('overlayRunning'),
      canDrawOverlays: b('canDrawOverlays'),
      notificationAccessGranted: b('notificationAccessGranted'),
      ignoringBatteryOptimizations: b('ignoringBatteryOptimizations'),
      postNotificationsPermission: b('postNotificationsPermission'),
      phoneStatePermission: b('phoneStatePermission'),
      timerActive: b('timerActive'),
      callActive: b('callActive'),
      mediaActive: b('mediaActive'),
    );
  }

  final bool overlayEnabled;
  final bool overlayRunning;
  final bool canDrawOverlays;
  final bool notificationAccessGranted;
  final bool ignoringBatteryOptimizations;
  final bool postNotificationsPermission;

  /// READ_PHONE_STATE granted — enables the live call-timer mode only.
  final bool phoneStatePermission;

  /// Live-mode flags (RAM-only state on the native side).
  final bool timerActive;
  final bool callActive;
  final bool mediaActive;

  bool get allSetupDone =>
      overlayEnabled &&
      canDrawOverlays &&
      notificationAccessGranted &&
      ignoringBatteryOptimizations;
}

/// Thin, dependency-free bridge to the native Kotlin code.
///
/// No third-party plugins are used anywhere in this app: the overlay and the
/// notification listener are native Kotlin (see the `android/` folder), and
/// this class only talks to our own MethodChannel/EventChannel handlers in
/// [MainActivity].
class NativeBridge {
  NativeBridge._();

  static const MethodChannel _service =
      MethodChannel('com.example.dynamic_island_app/service');
  static const EventChannel _statusEvents =
      EventChannel('com.example.dynamic_island_app/status_events');
  static const EventChannel _alertEvents =
      EventChannel('com.example.dynamic_island_app/alert_events');
  static const EventChannel _mediaEvents =
      EventChannel('com.example.dynamic_island_app/media_events');

  static Future<IslandStatus> fetchStatus() async {
    final map = await _service.invokeMapMethod<dynamic, dynamic>('getStatus');
    return IslandStatus.fromMap(map ?? const {});
  }

  /// Turns the whole feature on/off. This only writes the user's ON/OFF
  /// boolean in native SharedPreferences — never notification content.
  static Future<void> setEnabled(bool enabled) =>
      _service.invokeMethod<void>('setEnabled', {'enabled': enabled});

  static Future<void> openOverlayPermissionSettings() =>
      _service.invokeMethod<void>('openOverlayPermissionSettings');

  static Future<void> openNotificationAccessSettings() =>
      _service.invokeMethod<void>('openNotificationAccessSettings');

  static Future<void> openBatteryOptimizationSettings() =>
      _service.invokeMethod<void>('openBatteryOptimizationSettings');

  static Future<void> openAppDetailsSettings() =>
      _service.invokeMethod<void>('openAppDetailsSettings');

  static Future<void> requestPostNotificationsPermission() =>
      _service.invokeMethod<void>('requestPostNotificationsPermission');

  /// Grants the call-timer mode (read-only call-state observation; see the
  /// AndroidManifest comment for the exact scope).
  static Future<void> requestPhoneStatePermission() =>
      _service.invokeMethod<void>('requestPhoneStatePermission');

  /// Live island modes: countdown / stopwatch fed into the native overlay.
  /// RAM-only on the native side — never persisted.
  static Future<void> startCountdown(int seconds) =>
      _service.invokeMethod<void>('startCountdown', {'seconds': seconds});

  static Future<void> startStopwatch() =>
      _service.invokeMethod<void>('startStopwatch');

  static Future<void> stopTimer() => _service.invokeMethod<void>('stopTimer');

  /// Developer helper: makes the island show a synthetic alert (native side
  /// builds a fake alert in memory; no real notification is posted).
  static Future<void> fireTestAlert() =>
      _service.invokeMethod<void>('fireTestAlert');

  /// Emitted when the native status changes (e.g. the user returns from a
  /// permission screen and the listener/overlay state is different).
  static Stream<void> statusChanges() =>
      _statusEvents.receiveBroadcastStream().map((_) {});

  /// SECURITY: transient, in-memory alert summaries that arrive while the app
  /// UI is open, used only to mirror the overlay in the in-app preview. The
  /// icon is deliberately NOT forwarded — it stays native-only. The event is
  /// dropped the moment the UI closes (the native overlay keeps working).
  static Stream<Map<String, dynamic>> alertPreviews() =>
      _alertEvents.receiveBroadcastStream().map(
            (e) => (e as Map).cast<String, dynamic>(),
          );

  /// Live media-session previews (title/artist/art bytes/playing/position/
  /// duration), pushed event-driven from the native MediaController
  /// callbacks. Emits `null` when no session is active. In-memory only —
  /// the art bytes are rendered straight from RAM, never written to disk.
  static Stream<Map<String, dynamic>?> mediaEvents() =>
      _mediaEvents.receiveBroadcastStream().map(
            (e) => (e as Map?)?.cast<String, dynamic>(),
          );

  /// Playback controls for the mirrored media session (forwarded to
  /// MediaController.transportControls on the native side).
  static Future<void> mediaPlayPause() =>
      _service.invokeMethod<void>('mediaPlayPause');

  static Future<void> mediaNext() => _service.invokeMethod<void>('mediaNext');

  static Future<void> mediaPrev() => _service.invokeMethod<void>('mediaPrev');
}
