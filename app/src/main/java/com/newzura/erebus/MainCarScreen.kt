package com.newzura.erebus

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import com.newzura.erebus.data.BrowserPreferences

class ErebusCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return MainCarSession()
    }
}

class MainCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val action = intent.action ?: CAR_APP_NAVIGATION_DEFAULT_ACTION
        return when (action) {
            CAR_APP_NAVIGATION_DEFAULT_ACTION -> MainCarScreen(carContext)
            else -> MainCarScreen(carContext)
        }
    }
}

class MainCarScreen(private val carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val bookmarks = BrowserPreferences.getBookmarks(carContext.applicationContext)
        val listBuilder = ItemList.Builder()
        
        // Ajouter les favoris
        bookmarks.forEach { url ->
            val title = getBookmarkTitle(url)
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(title.ifEmpty { url })
                    .addText(url)
                    .setOnClickListener {
                        launchBrowser(url)
                    }
                    .build()
            )
        }
        
        // Si aucun favori, afficher des options par défaut
        if (bookmarks.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Ouvrir le navigateur")
                    .addText("Démarrer la navigation web")
                    .setOnClickListener {
                        launchBrowser("about:blank")
                    }
                    .build()
            )
            
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Page de démarrage")
                    .addText("Afficher la page de démarrage")
                    .setOnClickListener {
                        launchBrowser("chrome://newtab")
                    }
                    .build()
            )
        }
        
        // Ajouter option paramètres
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Paramètres")
                .addText("Configuration de l'application")
                .setOnClickListener {
                    carContext.startActivity(
                        carContext.createIntentBuilder(
                            carContext.packageContext,
                            com.newzura.erebus.settings.SettingsActivity::class.java
                        ).build()
                    )
                }
                .build()
        )
        
        return ListTemplate.Builder()
            .setTitle("Erebus Browser")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
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
            url
        }
    }
    
    private fun launchBrowser(url: String) {
        val intent = Intent(carContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("CAR_LAUNCHED", true)
            putExtra("URL_TO_OPEN", url)
        }
        carContext.startActivity(intent)
    }
}
