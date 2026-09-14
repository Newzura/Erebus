package com.newzura.erebus

import android.app.Application
import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell

class ErebusApp : Application() {

    companion object {
        const val TAG = "Erebus"
        lateinit var instance: ErebusApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Configure libsu defaults (silent failure if root not available)
        try {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
            )
            Log.d(TAG, "ErebusApp initialisé")
        } catch (t: Throwable) {
            Log.w(TAG, "Erreur configuration Shell libsu: ${t.message}")
        }
    }
}
