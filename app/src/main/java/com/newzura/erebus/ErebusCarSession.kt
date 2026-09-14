package com.newzura.erebus

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class ErebusCarSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ProjectionCoordinator.setCarAppConnected(true)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ProjectionCoordinator.setCarAppConnected(false, carContext)
                ProjectionCoordinator.setCarSurfaceAvailable(false, context = carContext)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        ProjectionCoordinator.setCarAppConnected(true)
        return ErebusCarScreen(carContext)
    }
}

