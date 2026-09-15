package com.newzura.erebus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.newzura.erebus.data.BrowserPreferences

class ErebusMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "ErebusMedia"
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: Initializing ErebusMediaService")

        mediaSession = MediaSessionCompat(this, "ErebusMediaService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "onPlay received")
                    launchBrowserFromMedia("about:blank")
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.i(TAG, "onPlayFromMediaId received: $mediaId")
                    val jellyfinUrl = BrowserPreferences.getJellyfinUrl(this@ErebusMediaService)
                    when (mediaId) {
                        "provider_youtube" -> launchBrowserFromMedia("https://www.youtube.com")
                        "provider_jellyfin" -> launchBrowserFromMedia(jellyfinUrl)
                        "provider_browser" -> launchBrowserFromMedia("about:blank")
                        "provider_startpage" -> launchBrowserFromMedia("chrome://newtab")
                        else -> launchBrowserFromMedia("about:blank")
                    }
                }

                override fun onPause() {
                    Log.i(TAG, "onPause received")
                }

                override fun onStop() {
                    Log.i(TAG, "onStop received")
                }
            })

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
            setPlaybackState(stateBuilder.build())
            isActive = true
        }

        sessionToken = mediaSession?.sessionToken
    }

    private fun launchBrowserFromMedia(url: String) {
        Log.i(TAG, "launchBrowserFromMedia: Launching browser with $url")
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("CAR_LAUNCHED", true)
                putExtra("URL_TO_OPEN", url)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    data = Uri.parse(url)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launchBrowserFromMedia: Error launching browser for $url", e)
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.d(TAG, "onGetRoot called by client: $clientPackageName (uid=$clientUid)")
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        Log.i(TAG, "onLoadChildren called with parentId: $parentId")
        val mediaItems = mutableListOf<MediaItem>()

        val jellyfinUrl = try {
            BrowserPreferences.getJellyfinUrl(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Jellyfin URL", e)
            "http://192.168.1.100:8096"
        }

        fun createProviderItems(): List<MediaItem> {
            val items = mutableListOf<MediaItem>()

            // 1. YouTube
            val ytDesc = MediaDescriptionCompat.Builder()
                .setMediaId("provider_youtube")
                .setTitle("▶️ YouTube")
                .setSubtitle("Lecteur vidéo et musique en streaming")
                .build()
            items.add(MediaItem(ytDesc, MediaItem.FLAG_PLAYABLE))

            // 2. Jellyfin
            val jfDesc = MediaDescriptionCompat.Builder()
                .setMediaId("provider_jellyfin")
                .setTitle("🍿 Jellyfin")
                .setSubtitle("Serveur multimédia personnel ($jellyfinUrl)")
                .build()
            items.add(MediaItem(jfDesc, MediaItem.FLAG_PLAYABLE))

            // 3. Navigateur Web
            val webDesc = MediaDescriptionCompat.Builder()
                .setMediaId("provider_browser")
                .setTitle("🌐 Navigateur Web")
                .setSubtitle("Ouvrir la navigation web")
                .build()
            items.add(MediaItem(webDesc, MediaItem.FLAG_PLAYABLE))

            // 4. Page de démarrage
            val spDesc = MediaDescriptionCompat.Builder()
                .setMediaId("provider_startpage")
                .setTitle("🏠 Page de démarrage")
                .setSubtitle("Raccourcis et recherche")
                .build()
            items.add(MediaItem(spDesc, MediaItem.FLAG_PLAYABLE))

            return items
        }

        if (parentId == "root") {
            // Conteneur principal browsable
            val mainFolderDesc = MediaDescriptionCompat.Builder()
                .setMediaId("erebus_main")
                .setTitle("Erebus")
                .setSubtitle("Navigateur Android Auto")
                .build()
            mediaItems.add(MediaItem(mainFolderDesc, MediaItem.FLAG_BROWSABLE))

            // Providers directement accessibles à la racine
            mediaItems.addAll(createProviderItems())
        } else if (parentId == "erebus_main") {
            // Sous-éléments de erebus_main (évite l'écran vide dans le DHU)
            mediaItems.addAll(createProviderItems())
        }

        Log.i(TAG, "onLoadChildren: Returning ${mediaItems.size} items for parentId $parentId")
        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Releasing ErebusMediaService")
        mediaSession?.release()
        super.onDestroy()
    }
}
