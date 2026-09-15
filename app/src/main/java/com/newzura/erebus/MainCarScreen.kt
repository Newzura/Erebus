package com.newzura.erebus

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Place
import androidx.car.app.navigation.model.PlaceMarker

class MainCarScreen(private val carContext: CarContext) : Screen() {

    override fun onGetTemplate(): Template {
        val listBuilder = ListTemplate.Builder()
            .setTitle("Erebus")
            .setHeaderAction(Action.Builder()
                .setTitle("Accueil")
                .setOnClickListener { /* Action Accueil */ }
                .build())
            .addItem(
                ListItem.Builder()
                    .setTitle("Navigateur")
                    .setText("Ouvrir le navigateur web")
                    .setOnClickListener {
                        // Ouvrir MainActivity ou navigateur
                    }
                    .build()
            )
            .addItem(
                ListItem.Builder()
                    .setTitle("Paramètres")
                    .setText("Configuration de l'application")
                    .setOnClickListener {
                        // Ouvrir les paramètres
                    }
                    .build()
            )

        return listBuilder.build()
    }
}
