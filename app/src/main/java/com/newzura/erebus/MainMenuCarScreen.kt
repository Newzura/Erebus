package com.newzura.erebus

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.preference.PreferenceManager

/**
 * Écran d'accueil principal sur Android Auto proposant :
 * - Le miroir d'écran complet (Erebus)
 * - YouTube en natif 16:9
 * - Jellyfin en streaming
 */
class MainMenuCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
        val jellyfinUrl = prefs.getString("pref_jellyfin_url", "http://192.168.1.100:8096") ?: "http://192.168.1.100:8096"

        val listBuilder = ItemList.Builder()

        // 1. Miroir complet
        listBuilder.addItem(
            Row.Builder()
                .setTitle("📱 " + carContext.getString(R.string.mode_mirror))
                .addText("Projeter l'écran entier de votre téléphone avec tactile")
                .setOnClickListener {
                    screenManager.push(ErebusCarScreen(carContext, ErebusCarScreen.Mode.MIRROR))
                }
                .build()
        )

        // 2. YouTube
        listBuilder.addItem(
            Row.Builder()
                .setTitle("▶️ " + carContext.getString(R.string.mode_youtube))
                .addText("Lecteur web fluide optimisé sans surchauffe (Support YouTube Premium)")
                .setOnClickListener {
                    val useDesktop = prefs.getBoolean("pref_youtube_desktop", true)
                    val url = if (useDesktop) "https://www.youtube.com" else "https://m.youtube.com"
                    screenManager.push(ErebusCarScreen(carContext, ErebusCarScreen.Mode.YOUTUBE, url))
                }
                .build()
        )

        // 3. Jellyfin
        listBuilder.addItem(
            Row.Builder()
                .setTitle("🍿 " + carContext.getString(R.string.mode_jellyfin))
                .addText("Vos films, séries et musiques personnels : $jellyfinUrl")
                .setOnClickListener {
                    screenManager.push(ErebusCarScreen(carContext, ErebusCarScreen.Mode.JELLYFIN, jellyfinUrl))
                }
                .build()
        )

        val actionStrip = ActionStrip.Builder()
            .addAction(Action.APP_ICON)
            .build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
}
