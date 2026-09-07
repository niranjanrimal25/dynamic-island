package com.example.dynamic_island_app

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter entry point. The MethodChannel/EventChannel plumbing below carries
 * STATUS FLAGS and ALERT PREVIEW data between Dart and native.
 *
 * SECURITY:
 *  - Only in-memory status flags and transient alert preview data flow over
 *    the channels; nothing is persisted, logged, or transmitted.
 *  - The always-on overlay is 100% native (see OverlayForegroundService /
 *    NotificationListener). When this activity is closed, no Dart code is
 *    involved at all: the native foreground service keeps rendering alerts
 *    in the island window, so the feature works even when the app is closed
 *    and after reboot.
 */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.example.dynamic_island_app/service"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatus" -> result.success(statusMap())
                "setEnabled" -> {
                    val on = call.argument<Boolean>("enabled") ?: false
                    DynamicIsland.setOverlayEnabled(this, on)
                    if (on) DynamicIsland.startOverlayService(this)
                    else DynamicIsland.stopOverlayService(this)
                    // Notify the UI (and any listener) that state changed.
                    DynamicIsland.notifyStatusChanged()
                    result.success(null)
                }
                "setUnlockFlourish" -> {
                    val on = call.argument<Boolean>("enabled") ?: false
                    DynamicIsland.setUnlockFlourishEnabled(this, on)
                    result.success(null)
                }
                "setDismissOverlayWarning" -> {
                    val on = call.argument<Boolean>("enabled") ?: true
                    DynamicIsland.setDismissOverlayWarningEnabled(this, on)
                    result.success(null)
                }
                "setIslandOnly" -> {
                    val on = call.argument<Boolean>("enabled") ?: true
                    DynamicIsland.setIslandOnlyEnabled(this, on)
                    result.success(null)
                }
                "openOverlayPermissionSettings" -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                    result.success(null)
                }
                "openNotificationAccessSettings" -> {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                    result.success(null)
                }
                "openUsageAccessSettings" -> {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                    result.success(null)
                }
                "openBatteryOptimizationSettings" -> {
                    // ACTION_REQUEST_... shows the one-time "Allow" dialog;
                    // if the app is already exempt nothing appears and we
                    // fall back to the full list screen.
                    val alreadyExempt =
                        DynamicIsland.isIgnoringBatteryOptimizations(this)
                    if (!alreadyExempt && Build.VERSION.SDK_INT >= 23) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { openBatteryOptimizationList() }
                    }
                    result.success(alreadyExempt)
                }
                "openAppDetailsSettings" -> {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                    result.success(null)
                }
                "fireTestAlert" -> {
                    // Dev/test helper: shows a synthetic alert (no real
                    // notification is posted) so you can see the island
                    // without waiting for another app. Synthetic content only.
                    val label = runCatching {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                    }.getOrDefault("Isle")
                    val icon = runCatching {
                        packageManager.getApplicationIcon(packageName)
                    }.getOrNull()
                    DynamicIsland.dispatchAlert(
                        this,
                        NotificationAlert(
                            key = "test-${System.currentTimeMillis()}",
                            packageName = packageName,
                            appLabel = label,
                            title = "Test alert",
                            text = "Isle is working\n" +
                                "Longer notifications — like a whole email — " +
                                "wrap over multiple lines and stay on screen " +
                                "longer so you can read them.",
                            icon = icon
                        )
                    )
                    result.success(null)
                }
                "requestPostNotificationsPermission" -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            1001
                        )
                    }
                    result.success(null)
                }
                "requestPhoneStatePermission" -> {
                    // Call-timer mode only; see AndroidManifest for the exact
                    // scope of READ_PHONE_STATE in this app.
                    requestPermissions(
                        arrayOf(Manifest.permission.READ_PHONE_STATE),
                        1002
                    )
                    result.success(null)
                }
                "startCountdown" -> {
                    val seconds = call.argument<Int>("seconds") ?: 60
                    DynamicIsland.startCountdown(seconds)
                    result.success(null)
                }
                "startStopwatch" -> {
                    DynamicIsland.startStopwatch()
                    result.success(null)
                }
                "stopTimer" -> {
                    DynamicIsland.stopTimer()
                    result.success(null)
                }
                // Tap-to-open from the (Flutter) island preview: fires the
                // stored contentIntent of the shown notification — the same
                // action tapping it in the shade would trigger — then the
                // flash collapses. The PendingIntent itself never crosses
                // the channel; only its in-memory key does.
                "openNotification" -> {
                    val id = call.argument<String>("id").orEmpty()
                    DynamicIsland.openAlert(id)
                    result.success(null)
                }
                // Playback control for the island's media mode: forwarded to
                // the currently mirrored session's transport controls.
                "mediaPlayPause" -> {
                    val c = DynamicIsland.mediaState?.controller
                    if (DynamicIsland.mediaState?.playing == true) {
                        c?.transportControls?.pause()
                    } else {
                        c?.transportControls?.play()
                    }
                    result.success(null)
                }
                "mediaNext" -> {
                    DynamicIsland.mediaState?.controller?.transportControls
                        ?.skipToNext()
                    result.success(null)
                }
                "mediaPrev" -> {
                    DynamicIsland.mediaState?.controller?.transportControls
                        ?.skipToPrevious()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Stream of status-change notifications ("status" events). The Dart
        // UI listens so the settings screen always reflects reality, e.g.
        // after the user returns from the system permission screens.
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.example.dynamic_island_app/status_events"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                DynamicIsland.setStatusSink(events)
                // Push current state as soon as the UI subscribes.
                DynamicIsland.notifyStatusChanged()
            }

            override fun onCancel(arguments: Any?) {
                DynamicIsland.setStatusSink(null)
            }
        })

        // Stream of in-memory alert *previews*: when a notification arrives
        // while the app UI is open, a copy of the alert summary is forwarded
        // to Dart so the in-app island preview can mirror what the native
        // overlay is showing. Only used for transient UI; never persisted.
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.example.dynamic_island_app/alert_events"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                DynamicIsland.setAlertSink(events)
            }

            override fun onCancel(arguments: Any?) {
                DynamicIsland.setAlertSink(null)
            }
        })

        // Stream of live *media* previews (title/artist/art bytes/playing/
        // position/duration), pushed whenever the native MediaController
        // callbacks fire — event-driven, never polled. In-memory only.
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.example.dynamic_island_app/media_events"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                DynamicIsland.setMediaSink(events)
            }

            override fun onCancel(arguments: Any?) {
                DynamicIsland.setMediaSink(null)
            }
        })

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
    }

    private fun statusMap(): Map<String, Any?> {
        val postNotif = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val phoneState =
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        return mapOf(
            "overlayEnabled" to DynamicIsland.isOverlayEnabled(this),
            "overlayRunning" to DynamicIsland.isOverlayServiceRunning(),
            "canDrawOverlays" to DynamicIsland.canDrawOverlays(this),
            "notificationAccessGranted" to DynamicIsland.isNotificationAccessGranted(this),
            "ignoringBatteryOptimizations" to
                DynamicIsland.isIgnoringBatteryOptimizations(this),
            "postNotificationsPermission" to postNotif,
            "phoneStatePermission" to phoneState,
            "unlockFlourish" to DynamicIsland.isUnlockFlourishEnabled(this),
            "dismissOverlayWarning" to
                DynamicIsland.isDismissOverlayWarningEnabled(this),
            "islandOnly" to DynamicIsland.isIslandOnlyEnabled(this),
            "timerActive" to (DynamicIsland.timerState != null),
            "callActive" to (DynamicIsland.callStartedAtElapsedMs != null),
            "mediaActive" to (DynamicIsland.mediaState != null),
            "usageStatsGranted" to isUsageStatsGranted()
        )
    }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 &&
            grantResults.contains(PackageManager.PERMISSION_GRANTED)
        ) {
            // Call-timer mode can activate now; the tracker no-ops without
            // the permission and starts cleanly once it is granted.
            IslandCallTracker.start(this)
            DynamicIsland.notifyStatusChanged()
        }
    }

    private fun openBatteryOptimizationList() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onDestroy() {
        // This activity's engine is going away; stop pointing the native
        // event sinks at its (now dead) messenger. The native overlay service
        // is independent of Flutter and keeps running.
        DynamicIsland.setStatusSink(null)
        DynamicIsland.setAlertSink(null)
        DynamicIsland.setTimerSink(null)
        DynamicIsland.setCallSink(null)
        DynamicIsland.setBannerSink(null)
        DynamicIsland.setPrivacySink(null)
        super.onDestroy()
    }
}
