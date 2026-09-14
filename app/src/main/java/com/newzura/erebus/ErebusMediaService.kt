package com.newzura.erebus

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat

class ErebusMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "Erebus"
        private const val ROOT_ID = "erebus_media_root"
        private var instance: ErebusMediaService? = null

        fun onNotificationListenerConnected() {
            instance?.refreshActiveSessions()
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var mediaSessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private var currentTargetController: MediaController? = null

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.d(TAG, "M3: Changement dans les sessions actives détecté (${controllers?.size ?: 0} sessions)")
            updateTargetController(controllers)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            syncPlaybackState(state)
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            super.onMetadataChanged(metadata)
            syncMetadata(metadata)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        mediaSession = MediaSessionCompat(this, "ErebusMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        refreshActiveSessions()
        Log.i(TAG, "M3: ErebusMediaService initialisé")
    }

    fun refreshActiveSessions() {
        if (!NotificationListener.isNotificationListenerEnabled(this)) {
            Log.w(TAG, "M3: Accès aux notifications non activé, impossible de lister les MediaSessions")
            return
        }

        try {
            val cn = ComponentName(this, NotificationListener::class.java)
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
            mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, cn)
            val controllers = mediaSessionManager?.getActiveSessions(cn)
            updateTargetController(controllers)
        } catch (e: SecurityException) {
            Log.e(TAG, "M3: SecurityException lors de l'accès aux sessions actives", e)
        } catch (e: Exception) {
            Log.e(TAG, "M3: Erreur lors du rafraîchissement des sessions", e)
        }
    }

    private fun isNativeCarApp(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val hasCarMeta = appInfo.metaData?.containsKey("com.google.android.gms.car.application") == true
            if (hasCarMeta) {
                Log.d(TAG, "M3: Exclusion de $packageName (application native Android Auto)")
            }
            hasCarMeta
        } catch (e: Exception) {
            false
        }
    }

    private fun updateTargetController(controllers: List<MediaController>?) {
        currentTargetController?.unregisterCallback(controllerCallback)

        if (controllers.isNullOrEmpty()) {
            currentTargetController = null
            Log.d(TAG, "M3: Aucune session média active sur le téléphone")
            return
        }

        // Exclure les applications natives Android Auto
        val filtered = controllers.filter { !isNativeCarApp(it.packageName) }

        // Sélectionner la session playing (state == 3), sinon la première
        val selected = filtered.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: filtered.firstOrNull()

        currentTargetController = selected

        if (selected != null) {
            Log.i(TAG, "M3: Session média cible sélectionnée : ${selected.packageName} (state=${selected.playbackState?.state})")
            selected.registerCallback(controllerCallback)
            syncPlaybackState(selected.playbackState)
            syncMetadata(selected.metadata)
        } else {
            Log.d(TAG, "M3: Aucune session éligible après filtrage des apps natives AA")
        }
    }

    private fun syncPlaybackState(state: PlaybackState?) {
        val stateInt = state?.state ?: PlaybackStateCompat.STATE_NONE
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1.0f
        val actions = state?.actions ?: (
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )

        val compatState = PlaybackStateCompat.Builder()
            .setState(stateInt, position, speed)
            .setActions(actions)
            .build()

        mediaSession.setPlaybackState(compatState)
    }

    private fun syncMetadata(metadata: android.media.MediaMetadata?) {
        if (metadata == null) {
            mediaSession.setMetadata(null)
            return
        }

        val builder = MediaMetadataCompat.Builder()

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)

        if (title != null) builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        if (artist != null) builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        if (album != null) builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        if (duration > 0) builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        // Downscale de l'artwork à 512px max
        val artBitmap = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)

        if (artBitmap != null) {
            val scaled = downscaleBitmap(artBitmap, 512)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, scaled)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, scaled)
        }

        mediaSession.setMetadata(builder.build())
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDim: Int = 512): Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetW = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt()
        val targetH = if (ratio >= 1f) (maxDim / ratio).toInt() else maxDim
        return Bitmap.createScaledBitmap(bitmap, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            try {
                if (currentTargetController != null) {
                    currentTargetController?.transportControls?.play()
                } else {
                    fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            } catch (e: Exception) {
                Log.w(TAG, "M3: Exception onPlay transport, fallback KeyEvent", e)
                fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }

        override fun onPause() {
            try {
                if (currentTargetController != null) {
                    currentTargetController?.transportControls?.pause()
                } else {
                    fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "M3: Exception onPause transport, fallback KeyEvent", e)
                fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }

        override fun onSkipToNext() {
            try {
                if (currentTargetController != null) {
                    currentTargetController?.transportControls?.skipToNext()
                } else {
                    fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            } catch (e: Exception) {
                Log.w(TAG, "M3: Exception onSkipToNext transport, fallback KeyEvent", e)
                fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        }

        override fun onSkipToPrevious() {
            try {
                if (currentTargetController != null) {
                    currentTargetController?.transportControls?.skipToPrevious()
                } else {
                    fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "M3: Exception onSkipToPrevious transport, fallback KeyEvent", e)
                fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        }

        override fun onStop() {
            try {
                if (currentTargetController != null) {
                    currentTargetController?.transportControls?.stop()
                } else {
                    fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP)
                }
            } catch (e: Exception) {
                fallbackKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP)
            }
        }

        override fun onSeekTo(pos: Long) {
            try {
                currentTargetController?.transportControls?.seekTo(pos)
            } catch (e: Exception) {
                Log.w(TAG, "M3: Exception onSeekTo", e)
            }
        }
    }

    private fun fallbackKeyEvent(keyCode: Int) {
        try {
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.d(TAG, "M3: Fallback AudioManager dispatchMediaKeyEvent envoyé ($keyCode)")
        } catch (e: Exception) {
            Log.e(TAG, "M3: Échec fallbackKeyEvent", e)
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

        val meta = mediaSession.controller.metadata
        val title = meta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Erebus Proxy Média"
        val subtitle = meta?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Contrôle des médias du téléphone"

        val description = MediaDescriptionCompat.Builder()
            .setMediaId("erebus_current_track")
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()

        mediaItems.add(MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentTargetController?.unregisterCallback(controllerCallback)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (e: Exception) {
            // Ignorer
        }
        mediaSession.release()
        instance = null
        Log.i(TAG, "M3: ErebusMediaService détruit")
    }
}
