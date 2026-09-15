package com.newzura.erebus

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.newzura.erebus.data.BrowserPreferences

class MainCarScreen(private val carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "ErebusCar"
        private const val YOUTUBE_URL = "https://www.youtube.com"
    }

    private var isLoading = false
    private var bookmarks: List<String> = emptyList()
    private var jellyfinUrl: String = "http://192.168.1.100:8096"

    init {
        loadProviders()
    }

    private fun loadProviders() {
        Log.i(TAG, "loadProviders: Starting loading of content providers and bookmarks")
        isLoading = true
        try {
            val context = carContext.applicationContext
            jellyfinUrl = BrowserPreferences.getJellyfinUrl(context)
            bookmarks = BrowserPreferences.getBookmarks(context)
            Log.i(TAG, "loadProviders: Successfully loaded ${bookmarks.size} bookmarks, Jellyfin URL: $jellyfinUrl")
        } catch (e: Exception) {
            Log.e(TAG, "loadProviders: Error loading providers/bookmarks", e)
            bookmarks = emptyList()
        } finally {
            isLoading = false
            Log.d(TAG, "loadProviders: Finished loading, invalidating screen")
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate called (isLoading=$isLoading)")

        if (isLoading) {
            Log.i(TAG, "Returning loading template for Erebus")
            return ListTemplate.Builder()
                .setTitle("Erebus")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()

        // 1. YouTube Provider
        Log.d(TAG, "Adding YouTube provider option")
        listBuilder.addItem(
            Row.Builder()
                .setTitle("▶️ YouTube")
                .addText("Lecteur vidéo et musique en streaming")
                .setOnClickListener {
                    launchBrowser(YOUTUBE_URL)
                }
                .build()
        )

        // 2. Jellyfin Provider
        Log.d(TAG, "Adding Jellyfin provider option: $jellyfinUrl")
        listBuilder.addItem(
            Row.Builder()
                .setTitle("🍿 Jellyfin")
                .addText("Serveur multimédia personnel ($jellyfinUrl)")
                .setOnClickListener {
                    launchBrowser(jellyfinUrl)
                }
                .build()
        )

        // 3. Navigateur Web (Web Browser)
        Log.d(TAG, "Adding Web Browser option")
        listBuilder.addItem(
            Row.Builder()
                .setTitle("🌐 Navigateur Web")
                .addText("Ouvrir la navigation web")
                .setOnClickListener {
                    launchBrowser("about:blank")
                }
                .build()
        )

        // 4. Page de démarrage (Start Page)
        Log.d(TAG, "Adding Start Page option")
        listBuilder.addItem(
            Row.Builder()
                .setTitle("🏠 Page de démarrage")
                .addText("Raccourcis et recherche")
                .setOnClickListener {
                    launchBrowser("chrome://newtab")
                }
                .build()
        )

        // 5. Favoris (Bookmarks de l'utilisateur s'il y en a)
        val customBookmarks = bookmarks.filter { url ->
            !url.contains("youtube.com", ignoreCase = true) && url.isNotBlank()
        }
        if (customBookmarks.isNotEmpty()) {
            Log.d(TAG, "Adding ${customBookmarks.size} custom bookmarks")
            customBookmarks.take(4).forEach { url ->
                val title = getBookmarkTitle(url)
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("⭐ " + title.ifEmpty { "Favori" })
                        .addText(url)
                        .setOnClickListener {
                            launchBrowser(url)
                        }
                        .build()
                )
            }
        }

        // 6. Paramètres (Settings)
        Log.d(TAG, "Adding Settings option")
        listBuilder.addItem(
            Row.Builder()
                .setTitle("⚙️ Paramètres")
                .addText("Configuration de l'application")
                .setOnClickListener {
                    launchSettings()
                }
                .build()
        )

        // Message explicite si la liste venait à être vide
        listBuilder.setNoItemsMessage("Aucune source disponible")

        val itemList = listBuilder.build()
        Log.i(TAG, "Returning ListTemplate with ${itemList.items.size} items")

        return ListTemplate.Builder()
            .setTitle("Erebus")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemList)
            .build()
    }

    private fun getBookmarkTitle(url: String): String {
        return try {
            val host = java.net.URI(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: url
            val parts = host.split('.')
            when {
                parts.size >= 2 -> {
                    val subTld = if (parts.size >= 3) parts[parts.size - 2] else null
                    val tld = parts.last()
                    val isDoubleTld = (subTld == "co" || subTld == "ne" || subTld == "ac" || subTld == "org" || subTld == "go") && tld.length == 2
                    val mainDomain = if (isDoubleTld && parts.size >= 3) parts[parts.size - 3] else parts[parts.size - 2]
                    mainDomain.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                else -> host.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing bookmark URL: $url", e)
            url
        }
    }

    private fun launchBrowser(url: String) {
        Log.i(TAG, "Launching browser with URL: $url")
        try {
            val intent = Intent(carContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("CAR_LAUNCHED", true)
                putExtra("URL_TO_OPEN", url)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    data = Uri.parse(url)
                }
            }
            carContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser for URL: $url", e)
        }
    }

    private fun launchSettings() {
        Log.i(TAG, "Launching Settings activity")
        try {
            val intent = Intent(carContext, com.newzura.erebus.settings.SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            carContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SettingsActivity", e)
        }
    }
}
