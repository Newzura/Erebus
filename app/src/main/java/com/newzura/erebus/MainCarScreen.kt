package com.newzura.erebus

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Navigateur")
                    .addText("Ouvrir le navigateur web")
                    .setOnClickListener {
                        // Ouvrir MainActivity ou navigateur
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Paramètres")
                    .addText("Configuration de l'application")
                    .setOnClickListener {
                        // Ouvrir les paramètres
                    }
                    .build()
            )

        return ListTemplate.Builder()
            .setTitle("Erebus")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
