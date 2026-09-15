package com.newzura.erebus

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class ErebusCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Autorise tous les hôtes pour le test (DHU inclus)
        // En production, remplace par une liste restrictive
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return MainCarSession()
    }
}

class MainCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}
