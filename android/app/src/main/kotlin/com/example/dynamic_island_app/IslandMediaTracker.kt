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
import android.os.SystemClock

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

    /**
     * Picks the session to show and publishes it.
     *
     * Selection logic (multiple simultaneous sessions are rare but possible):
     *  1. A PLAYING session always beats a paused one — audible media is what
     *     the island should mirror.
     *  2. Within that group, the MOST RECENTLY ACTIVE session wins, measured
     *     by PlaybackState.lastPositionUpdateTime (the elapsedRealtime of the
     *     last position report — i.e. the session that last actually moved).
     *  3. Only if nothing is playing do we fall back to paused sessions,
     *     again most-recent first, so a just-paused player stays resumable
     *     from the island.
     */
    private fun update(context: Context) {
        val playing = controllers.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val paused = controllers.filter {
            it.playbackState?.state == PlaybackState.STATE_PAUSED
        }
        val chosen = (playing.ifEmpty { paused })
            .maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }

        val state = chosen?.let { c ->
            val meta = c.metadata
            val ps = c.playbackState
            val isPlaying = ps?.state == PlaybackState.STATE_PLAYING
            val art = runCatching {
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }.getOrNull()?.downsample(128)
            val basePosition = ps?.position?.coerceAtLeast(0L) ?: 0L
            val position = if (isPlaying && ps != null) {
                // Extrapolate: position advances while playing.
                basePosition +
                    (SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime)
            } else {
                basePosition
            }
            MediaState(
                appLabel = labelFor(context, c.packageName),
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                art = art,
                playing = isPlaying,
                positionMs = position.coerceAtLeast(0L),
                durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    ?: 0L).coerceAtLeast(0L),
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
