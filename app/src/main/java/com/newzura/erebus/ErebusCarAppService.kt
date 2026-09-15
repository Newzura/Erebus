package com.newzura.erebus

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ErebusCarAppService : CarAppService() {

    override fun createSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: android.content.Intent): Screen {
                return MainCarScreen(carContext)
            }

            override fun getHostValidator(): HostValidator {
                return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            }
        }
    }
}
