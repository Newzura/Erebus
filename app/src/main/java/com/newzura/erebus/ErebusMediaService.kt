package com.newzura.erebus

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

class ErebusMediaService : MediaBrowserServiceCompat() {

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "ErebusMediaService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    // Action play
                }
                override fun onPause() {
                    // Action pause
                }
                override fun onStop() {
                    // Action stop
                }
            })

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
            setPlaybackState(stateBuilder.build())
            isActive = true
        }

        sessionToken = mediaSession?.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val mediaItems = mutableListOf<MediaItem>()
        
        if (parentId == "root") {
            val description = MediaDescriptionCompat.Builder()
                .setMediaId("erebus_main")
                .setTitle("Erebus")
                .setSubtitle("Navigateur Android Auto")
                .build()

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Erebus")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Erebus Team")
                .build()

            val mediaItem = MediaItem(description, MediaItem.FLAG_PLAYABLE)
            mediaItems.add(mediaItem)
        }

        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }
}
