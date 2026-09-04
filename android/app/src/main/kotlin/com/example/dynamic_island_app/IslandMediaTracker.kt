package com.example.dynamic_island_app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Tracks active media sessions of ANY app (Spotify, YouTube Music, ...) via
 * [MediaSessionManager] and publishes the most relevant one to
 * [DynamicIsland.mediaState] so the island can show a persistent compact view
 * (album art + waveform) and real transport controls.
 *
 * Gating: Android exposes the session list only to apps with notification
 * access, so this works exactly when the user has enabled "Isle notification
 * access" — no extra permission needed.
 *
 * SECURITY: everything stays in this process's RAM — metadata strings, a
 * small downsampled album-art bitmap and the controller handle used to drive
 * playback. Nothing is written to disk or transmitted.
 */
object IslandMediaTracker {

    private val main = Handler(Looper.getMainLooper())

    private var started = false
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val manager =
            app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return
        val component = ComponentName(app, NotificationListener::class.java)
        val ok = runCatching {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
                main.post { rebuild(app, sessions) }
            }
            sessionsListener = listener
            manager.addOnActiveSessionsChangedListener(listener, component)
            rebuild(app, manager.getActiveSessions(component))
            true
        }.getOrDefault(false)
        started = ok
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        val app = context.applicationContext
        runCatching {
            val manager =
                app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        }
        sessionsListener = null
        unregisterAll()
        controllers = emptyList()
        if (DynamicIsland.mediaState != null) {
            DynamicIsland.mediaState = null
            DynamicIsland.onLiveStateChanged()
        }
    }

    // ------------------------------------------------------------------

    private fun unregisterAll() {
        callbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        callbacks.clear()
    }

    private fun rebuild(context: Context, sessions: List<MediaController>?) {
        controllers = sessions.orEmpty()
        unregisterAll()
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    main.post { update(context) }
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    main.post { update(context) }
                }

                override fun onSessionDestroyed() {
                    main.post {
                        val app = context.applicationContext
                        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE)
                            as? MediaSessionManager
                        val component = ComponentName(app, NotificationListener::class.java)
                        rebuild(
                            app,
                            runCatching { manager?.getActiveSessions(component) }.getOrNull()
                        )
                    }
                }
            }
            controller.registerCallback(callback, main)
            callbacks[controller] = callback
        }
        update(context)
    }

    /** Picks the playing (else paused) session and publishes it. */
    private fun update(context: Context) {
        val chosen = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        }

        val state = chosen?.let { c ->
            val meta = c.metadata
            val art = runCatching {
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }.getOrNull()?.downsample(128)
            MediaState(
                appLabel = labelFor(context, c.packageName),
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                art = art,
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                controller = c
            )
        }

        DynamicIsland.mediaState = state
        DynamicIsland.onLiveStateChanged()
    }

    private fun labelFor(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Keeps the in-memory art bitmap tiny regardless of source resolution. */
    private fun Bitmap.downsample(max: Int): Bitmap {
        if (width <= max && height <= max) return this
        val scale = max.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
