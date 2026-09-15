package com.newzura.erebus

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class ErebusCarAppService : CarAppService() {

    companion object {
        private const val TAG = "ErebusCar"
    }

    override fun createHostValidator(): HostValidator {
        Log.d(TAG, "createHostValidator: ALLOW_ALL_HOSTS_VALIDATOR")
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "onCreateSession: creating MainCarSession (displayType=${sessionInfo.displayType})")
        return MainCarSession()
    }
}

class MainCarSession : Session() {

    companion object {
        private const val TAG = "ErebusCar"
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "MainCarSession.onCreateScreen called with intent: $intent")
        return MainCarScreen(carContext)
    }
}
