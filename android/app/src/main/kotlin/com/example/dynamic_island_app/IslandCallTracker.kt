package com.example.dynamic_island_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Detects an active phone call so the island can run a live duration timer.
 *
 * SCOPE OF READ_PHONE_STATE HERE: the app registers ONLY
 * TelephonyManager.listen(LISTEN_CALL_STATE). The callback's phone-number
 * argument is deliberately ignored and never stored; no telephony identifiers
 * (IMEI / subscriber id / line number) are ever queried. The permission is
 * revocable at any time — without it this tracker simply stays inert and the
 * call mode never activates.
 */
object IslandCallTracker {

    private var started = false

    @Suppress("DEPRECATION")
    private var listener: PhoneStateListener? = null

    @Suppress("DEPRECATION")
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val l = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                // `phoneNumber` is intentionally ignored — see class docs.
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (DynamicIsland.callStartedAtElapsedMs == null) {
                            DynamicIsland.callStartedAtElapsedMs =
                                SystemClock.elapsedRealtime()
                            DynamicIsland.onLiveStateChanged()
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (DynamicIsland.callStartedAtElapsedMs != null) {
                            DynamicIsland.callStartedAtElapsedMs = null
                            DynamicIsland.onLiveStateChanged()
                        }
                    }
                    else -> Unit // ringing: show nothing
                }
            }
        }
        listener = l
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
        started = true
    }

    @Suppress("DEPRECATION")
    fun stop(context: Context) {
        if (!started) return
        started = false
        val app = context.applicationContext
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        listener?.let { runCatching { tm?.listen(it, PhoneStateListener.LISTEN_NONE) } }
        listener = null
        if (DynamicIsland.callStartedAtElapsedMs != null) {
            DynamicIsland.callStartedAtElapsedMs = null
            DynamicIsland.onLiveStateChanged()
        }
    }
}
