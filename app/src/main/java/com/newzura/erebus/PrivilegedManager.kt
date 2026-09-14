package com.newzura.erebus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku
import java.io.File

object PrivilegedManager {

    private const val TAG = "ErebusPrivileged"

    enum class Backend {
        NONE,
        GRANTED_PERMISSIONS,
        SHIZUKU,
        ROOT
    }

    private var activeBackend = Backend.NONE
    private var privilegedService: IPrivilegedService? = null
    private val clientBinderToken = Binder()

    // Dimensions actuelles du téléphone pour le mapping des coordonnées
    private var phoneWidth: Int = 1080
    private var phoneHeight: Int = 2400

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                PrivilegedServiceImpl::class.java.name
            )
        )
            .version(12)
            .processNameSuffix("privileged")
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "M4: Shizuku UserService connecté")
            if (service != null) {
                privilegedService = IPrivilegedService.Stub.asInterface(service)
                try {
                    privilegedService?.attachClient(clientBinderToken)
                } catch (e: Exception) {
                    Log.e(TAG, "M4: Échec attachClient sur Shizuku", e)
                }
                activeBackend = Backend.SHIZUKU
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "M4: Shizuku UserService déconnecté")
            privilegedService = null
            activeBackend = Backend.NONE
        }
    }

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "M4: RootService connecté via libsu")
            if (service != null) {
                privilegedService = IPrivilegedService.Stub.asInterface(service)
                try {
                    privilegedService?.attachClient(clientBinderToken)
                } catch (e: Exception) {
                    Log.e(TAG, "M4: Échec attachClient sur Root", e)
                }
                activeBackend = Backend.ROOT
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "M4: RootService déconnecté")
            privilegedService = null
            activeBackend = Backend.NONE
        }
    }

    fun init(context: Context) {
        updatePhoneDimensions(context)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefBackend = prefs.getString("pref_privileged_backend", "auto") ?: "auto"

        if (prefBackend == "none") {
            Log.i(TAG, "Backend privilégié désactivé par préférences utilisateur")
            return
        }

        // 0. Voie "Permissions accordées" (via ordinateur / adb pm grant)
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "M4: Permission WRITE_SECURE_SETTINGS accordée. Utilisation directe IWindowManager et InputManager.")
            val directService = PrivilegedServiceImpl(isDirectPermissionMode = true)
            privilegedService = directService
            try {
                directService.attachClient(clientBinderToken)
            } catch (e: Exception) {
                Log.e(TAG, "M4: Échec attachClient direct", e)
            }
            activeBackend = Backend.GRANTED_PERMISSIONS
            return
        }

        // 1. Détection et liaison Shizuku
        if (prefBackend == "auto" || prefBackend == "shizuku") {
            try {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "Shizuku disponible et autorisé, binding version 12...")
                        Shizuku.bindUserService(shizukuArgs, shizukuConnection)
                        return
                    } else {
                        Log.w(TAG, "Shizuku en attente de permission utilisateur")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Shizuku non disponible: ${e.message}")
            }
        }

        // 2. Détection et liaison Root (su binaires ou packages gestionnaires)
        if (prefBackend == "auto" || prefBackend == "root") {
            if (isRootAvailable(context)) {
                Log.i(TAG, "Root détecté, tentative de binding RootService via libsu...")
                try {
                    val intent = Intent(context, PrivilegedRootService::class.java)
                    RootService.bind(intent, rootConnection)
                } catch (e: Exception) {
                    Log.e(TAG, "Échec RootService.bind", e)
                }
            }
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur demande permission Shizuku", e)
        }
    }

    fun isRootAvailable(context: Context): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su"
        )
        if (suPaths.any { File(it).exists() }) return true

        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch"
        )
        for (pkg in rootPackages) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return true
            } catch (e: Exception) {
                // Non installé
            }
        }

        return try {
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            false
        }
    }

    fun getActiveBackend(): Backend = activeBackend

    fun getActiveBackendName(): String {
        return when (activeBackend) {
            Backend.GRANTED_PERMISSIONS -> "Permissions accordées (via ordinateur)"
            Backend.SHIZUKU -> "Shizuku"
            Backend.ROOT -> "Root"
            Backend.NONE -> "Aucun accès privilégié"
        }
    }

    private fun updatePhoneDimensions(context: Context) {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            display.getRealSize(size)
            phoneWidth = size.x
            phoneHeight = size.y
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lecture dimensions téléphone", e)
        }
    }

    fun setScreenPowerMode(on: Boolean) {
        val service = privilegedService ?: return
        val mode = if (on) PrivilegedServiceImpl.POWER_MODE_NORMAL else PrivilegedServiceImpl.POWER_MODE_OFF
        try {
            val res = service.setDisplayPowerMode(mode)
            Log.i(TAG, "M4: setScreenPowerMode($on) résultat: $res")
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur appel setDisplayPowerMode", e)
        }
    }

    fun applyCarDisplayOverride(width: Int, height: Int, dpi: Int): Boolean {
        val service = privilegedService ?: return false
        return try {
            val res = service.applyDisplayOverride(width, height, dpi)
            Log.i(TAG, "M4: applyCarDisplayOverride(${width}x${height} @ $dpi) -> statut $res")
            res == PrivilegedServiceImpl.STATUS_SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur applyDisplayOverride", e)
            false
        }
    }

    fun restoreDisplayOverride() {
        val service = privilegedService ?: return
        try {
            service.applyDisplayOverride(0, 0, 0) // déclenche clear
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur restauration display override", e)
        }
    }

    fun injectClick(carX: Float, carY: Float, carWidth: Int, carHeight: Int) {
        if (carWidth <= 0 || carHeight <= 0) return

        // Calculer les coordonnées transposées sur l'écran du téléphone
        val normX = (carX / carWidth).coerceIn(0f, 1f)
        val normY = (carY / carHeight).coerceIn(0f, 1f)
        val targetX = normX * phoneWidth
        val targetY = normY * phoneHeight

        val service = privilegedService
        if (service != null && service.isTouchInjectionSupported) {
            try {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, targetX, targetY, 0)
                val up = MotionEvent.obtain(now, now + 15, MotionEvent.ACTION_UP, targetX, targetY, 0)

                service.injectMotionEvent(down)
                SystemClock.sleep(20)
                service.injectMotionEvent(up)

                down.recycle()
                up.recycle()
                Log.d(TAG, "M4: Clic injecté via IPrivilegedService à ($targetX, $targetY)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "M4: Échec injection réelle, fallback accessibilité", e)
            }
        }

        // Fallback Accessibilité
        Accessibility.injectTap(targetX, targetY)
    }

    fun injectScroll(distanceX: Float, distanceY: Float, carWidth: Int, carHeight: Int) {
        val startX = (phoneWidth / 2).toFloat()
        val startY = (phoneHeight / 2).toFloat()
        val endX = (startX - distanceX).coerceIn(0f, phoneWidth.toFloat())
        val endY = (startY - distanceY).coerceIn(0f, phoneHeight.toFloat())

        val service = privilegedService
        if (service != null && service.isTouchInjectionSupported) {
            try {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0)
                val move = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, endX, endY, 0)
                val up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, endX, endY, 0)

                service.injectMotionEvent(down)
                SystemClock.sleep(30)
                service.injectMotionEvent(move)
                SystemClock.sleep(30)
                service.injectMotionEvent(up)

                down.recycle()
                move.recycle()
                up.recycle()
                return
            } catch (e: Exception) {
                Log.w(TAG, "M4: Échec injection scroll réelle, fallback accessibilité", e)
            }
        }

        // Fallback Accessibilité
        Accessibility.injectSwipe(startX, startY, endX, endY, 150)
    }
}
