package com.newzura.erebus

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class ErebusCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return ErebusCarScreen(carContext)
    }
}
