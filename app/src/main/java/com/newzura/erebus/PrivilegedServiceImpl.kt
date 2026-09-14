package com.newzura.erebus

import android.graphics.Point
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

class PrivilegedServiceImpl(private val isDirectPermissionMode: Boolean = false) : IPrivilegedService.Stub() {

    companion object {
        private const val TAG = "ErebusPrivileged"

        const val STATUS_SUCCESS = 0
        const val STATUS_ERR_WINDOW_MANAGER_UNAVAILABLE = 1
        const val STATUS_ERR_METHOD_NOT_FOUND = 2
        const val STATUS_ERR_CALL_REJECTED = 3
        const val STATUS_ERR_OUT_OF_RANGE = 4
        const val STATUS_ERR_VERIFICATION_FAILED = 5
        const val STATUS_ERR_UNSUPPORTED = 6

        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }

    private var clientBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    // Sauvegarde de l'état d'origine de l'écran pour restauration garantie
    private var initialWidth: Int = 0
    private var initialHeight: Int = 0
    private var initialDensity: Int = 0
    private var hasAppliedOverride: Boolean = false

    // Fusible touches pendantes (anti-stuck key)
    private val pendingKeys = mutableSetOf<Int>()

    // Cache des instances de réflexion
    private var windowManager: Any? = null
    private var inputManagerInstance: Any? = null
    private var injectInputEventMethod: Method? = null
    private var systemServerClassLoader: ClassLoader? = null

    init {
        initWindowManager()
        initInputManager()
        readInitialGeometry()
    }

    private fun initWindowManager() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val windowBinder = getService.invoke(null, "window") as? IBinder
            if (windowBinder != null) {
                val stubClass = Class.forName("android.view.IWindowManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                windowManager = asInterface.invoke(null, windowBinder)
                Log.i(TAG, "M4: IWindowManager initialisé avec succès")
            } else {
                Log.e(TAG, "M4: Service window indisponible depuis ServiceManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "M4: Échec initialisation IWindowManager", e)
        }
    }

    private fun readInitialGeometry(): Boolean {
        val wm = windowManager ?: return false
        return try {
            val point = Point()
            var readSuccess = false

            // Essayer getInitialDisplaySize(displayId, Point)
            val getInitSizeMethod = wm.javaClass.methods.firstOrNull {
                it.name == "getInitialDisplaySize" && it.parameterTypes.size == 2
            }
            if (getInitSizeMethod != null) {
                getInitSizeMethod.invoke(wm, 0, point)
                initialWidth = point.x
                initialHeight = point.y
                readSuccess = true
            } else {
                // Fallback getBaseDisplaySize
                val getBaseSizeMethod = wm.javaClass.methods.firstOrNull {
                    it.name == "getBaseDisplaySize" && it.parameterTypes.size == 2
                }
                if (getBaseSizeMethod != null) {
                    getBaseSizeMethod.invoke(wm, 0, point)
                    initialWidth = point.x
                    initialHeight = point.y
                    readSuccess = true
                }
            }

            // Lire densité
            val getDensityMethod = wm.javaClass.methods.firstOrNull {
                it.name in listOf("getInitialDisplayDensity", "getBaseDisplayDensity") && it.parameterTypes.isNotEmpty()
            }
            if (getDensityMethod != null) {
                val d = getDensityMethod.invoke(wm, 0) as? Int
                if (d != null && d > 0) {
                    initialDensity = d
                }
            }

            Log.i(TAG, "M4: Géométrie initiale lue : ${initialWidth}x${initialHeight}, dens=$initialDensity")
            readSuccess && initialWidth > 0 && initialHeight > 0
        } catch (e: Exception) {
            Log.e(TAG, "M4: Impossible de lire la géométrie d'origine", e)
            false
        }
    }

    private fun initInputManager() {
        val candidateClasses = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager"
        )
        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)
                val getInstanceMethod = clazz.methods.firstOrNull {
                    it.name == "getInstance" && it.parameterTypes.isEmpty()
                }
                val instance = getInstanceMethod?.invoke(null) ?: continue

                val injectMethod = clazz.methods.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 2 &&
                            InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (injectMethod != null) {
                    inputManagerInstance = instance
                    injectInputEventMethod = injectMethod
                    Log.i(TAG, "M4: InputManager trouvé ($className.${injectMethod.name})")
                    break
                }
            } catch (e: Exception) {
                Log.d(TAG, "M4: Candidate $className non disponible: ${e.message}")
            }
        }
    }

    private fun getSystemServerClassLoader(): ClassLoader {
        if (systemServerClassLoader != null) return systemServerClassLoader!!

        val classpath = System.getenv("SYSTEMSERVERCLASSPATH") ?: "/system/framework/services.jar"
        systemServerClassLoader = try {
            val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createMethod = factoryClass.methods.firstOrNull { it.name == "createClassLoader" }
            if (createMethod != null && createMethod.parameterTypes.size >= 4) {
                val args = arrayOfNulls<Any>(createMethod.parameterTypes.size)
                args[0] = classpath
                args[3] = ClassLoader.getSystemClassLoader()
                createMethod.invoke(null, *args) as ClassLoader
            } else {
                PathClassLoader(classpath, ClassLoader.getSystemClassLoader())
            }
        } catch (e: Exception) {
            Log.w(TAG, "M4: Fallback PathClassLoader pour system-server", e)
            PathClassLoader(classpath, ClassLoader.getSystemClassLoader())
        }
        return systemServerClassLoader!!
    }

    // --- Implémentation IPrivilegedService ---

    override fun attachClient(client: IBinder?) {
        this.clientBinder = client
        if (client == null) return

        deathRecipient = IBinder.DeathRecipient {
            Log.w(TAG, "M4: Client mort (binderDied) -> Déclenchement restauration sécurité")
            restoreDisplayOverrideInternal()
            setDisplayPowerMode(POWER_MODE_NORMAL)
            releasePendingKeys()
        }

        try {
            client.linkToDeath(deathRecipient!!, 0)
            Log.i(TAG, "M4: Client lié avec linkToDeath")
        } catch (e: Exception) {
            Log.e(TAG, "M4: Échec linkToDeath", e)
        }
    }

    override fun applyDisplayOverride(w: Int, h: Int, dpi: Int): Int {
        val wm = windowManager ?: return STATUS_ERR_WINDOW_MANAGER_UNAVAILABLE
        if (w < 320 || h < 240 || dpi < 72) {
            Log.w(TAG, "M4: Paramètres d'affichage hors plage (${w}x$h @ $dpi)")
            return STATUS_ERR_OUT_OF_RANGE
        }

        // RÈGLE ABSOLUE : Vérifier qu'on peut relire avant d'écrire
        if (initialWidth <= 0 || initialHeight <= 0) {
            if (!readInitialGeometry()) {
                Log.e(TAG, "M4: Impossible d'écrire une valeur sans capacité de relecture d'origine")
                return STATUS_ERR_VERIFICATION_FAILED
            }
        }

        return try {
            val setSizeMethod = wm.javaClass.methods.firstOrNull {
                it.name == "setForcedDisplaySize" && it.parameterTypes.size >= 3
            } ?: return STATUS_ERR_METHOD_NOT_FOUND

            if (setSizeMethod.parameterTypes.size == 3) {
                setSizeMethod.invoke(wm, 0, w, h)
            } else if (setSizeMethod.parameterTypes.size == 4) {
                setSizeMethod.invoke(wm, 0, w, h, 0)
            }

            // Densité
            if (dpi > 0) {
                val setDensityMethod = wm.javaClass.methods.firstOrNull {
                    it.name in listOf("setForcedDisplayDensityForUser", "setForcedDisplayDensity")
                }
                if (setDensityMethod != null) {
                    if (setDensityMethod.parameterTypes.size == 3) {
                        setDensityMethod.invoke(wm, 0, dpi, 0) // userId 0
                    } else if (setDensityMethod.parameterTypes.size == 2) {
                        setDensityMethod.invoke(wm, 0, dpi)
                    }
                }
            }

            // Relecture-vérification obligatoire
            val current = displayGeometry()
            if (current.size >= 2 && current[0] == w && current[1] == h) {
                hasAppliedOverride = true
                Log.i(TAG, "M4: Override display appliqué et vérifié (${w}x$h @ $dpi)")
                STATUS_SUCCESS
            } else {
                Log.e(TAG, "M4: Relecture échouée après écriture (${current.getOrNull(0)}x${current.getOrNull(1)} != ${w}x$h) -> Annulation")
                restoreDisplayOverrideInternal()
                STATUS_ERR_VERIFICATION_FAILED
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "M4: Appel setForcedDisplaySize refusé", e)
            STATUS_ERR_CALL_REJECTED
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur lors de applyDisplayOverride", e)
            STATUS_ERR_CALL_REJECTED
        }
    }

    private fun restoreDisplayOverrideInternal() {
        if (!hasAppliedOverride) return
        val wm = windowManager ?: return

        try {
            val clearSizeMethod = wm.javaClass.methods.firstOrNull {
                it.name == "clearForcedDisplaySize" && it.parameterTypes.isNotEmpty()
            }
            clearSizeMethod?.invoke(wm, 0)

            val clearDensityMethod = wm.javaClass.methods.firstOrNull {
                it.name in listOf("clearForcedDisplayDensityForUser", "clearForcedDisplayDensity")
            }
            if (clearDensityMethod != null) {
                if (clearDensityMethod.parameterTypes.size == 2) {
                    clearDensityMethod.invoke(wm, 0, 0)
                } else if (clearDensityMethod.parameterTypes.size == 1) {
                    clearDensityMethod.invoke(wm, 0)
                }
            }

            hasAppliedOverride = false
            Log.i(TAG, "M4: Display override restauré avec succès")
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur lors de la restauration de l'affichage", e)
        }
    }

    override fun displayGeometry(): IntArray {
        val wm = windowManager ?: return intArrayOf(0, 0, 0)
        return try {
            val point = Point()
            val getBaseSize = wm.javaClass.methods.firstOrNull {
                it.name == "getBaseDisplaySize" && it.parameterTypes.size == 2
            }
            getBaseSize?.invoke(wm, 0, point)

            var density = 0
            val getDensity = wm.javaClass.methods.firstOrNull {
                it.name in listOf("getBaseDisplayDensity", "getInitialDisplayDensity") && it.parameterTypes.isNotEmpty()
            }
            if (getDensity != null) {
                density = (getDensity.invoke(wm, 0) as? Int) ?: 0
            }

            intArrayOf(point.x, point.y, density)
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur lecture displayGeometry", e)
            intArrayOf(0, 0, 0)
        }
    }

    override fun setDisplayPowerMode(mode: Int): Int {
        if (isDirectPermissionMode) {
            Log.w(TAG, "M4: Extinction de dalle non disponible sur la voie permissions accordées (Shizuku/root uniquement)")
            return STATUS_ERR_UNSUPPORTED
        }
        Log.i(TAG, "M4: Demande setDisplayPowerMode($mode)")
        return try {
            val cl = getSystemServerClassLoader()

            // Obtenir le token du display natif
            var displayToken: IBinder? = null

            // 1. Essai DisplayControl
            try {
                val displayControlClass = cl.loadClass("com.android.server.display.DisplayControl")
                val getPhysicalIds = displayControlClass.methods.firstOrNull { it.name == "getPhysicalDisplayIds" }
                val ids = getPhysicalIds?.invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    val getTokenMethod = displayControlClass.methods.firstOrNull {
                        it.name == "getPhysicalDisplayToken" && it.parameterTypes.isNotEmpty()
                    }
                    displayToken = getTokenMethod?.invoke(null, ids[0]) as? IBinder
                }
            } catch (e: Exception) {
                Log.d(TAG, "M4: DisplayControl non accessible, fallback SurfaceControl: ${e.message}")
            }

            // 2. Fallback SurfaceControl
            if (displayToken == null) {
                val scClass = Class.forName("android.view.SurfaceControl")
                val getInternalToken = scClass.methods.firstOrNull {
                    it.name in listOf("getInternalDisplayToken", "getBuiltInDisplay")
                }
                if (getInternalToken != null) {
                    displayToken = if (getInternalToken.parameterTypes.isEmpty()) {
                        getInternalToken.invoke(null) as? IBinder
                    } else {
                        getInternalToken.invoke(null, 0) as? IBinder
                    }
                }
            }

            if (displayToken == null) {
                Log.e(TAG, "M4: Impossible d'obtenir le token du display natif")
                return STATUS_ERR_UNSUPPORTED
            }

            // Réflexion SurfaceControl.setDisplayPowerMode(IBinder token, int mode)
            val scClass = Class.forName("android.view.SurfaceControl")
            val setModeMethod = scClass.methods.firstOrNull {
                it.name == "setDisplayPowerMode" && it.parameterTypes.size == 2
            } ?: return STATUS_ERR_METHOD_NOT_FOUND

            setModeMethod.invoke(null, displayToken, mode)
            Log.i(TAG, "M4: SurfaceControl.setDisplayPowerMode appliqué avec succès (mode=$mode)")
            STATUS_SUCCESS
        } catch (e: SecurityException) {
            Log.e(TAG, "M4: setDisplayPowerMode refusé par permission", e)
            STATUS_ERR_CALL_REJECTED
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur setDisplayPowerMode", e)
            STATUS_ERR_CALL_REJECTED
        }
    }

    override fun injectMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val im = inputManagerInstance ?: return false
        val method = injectInputEventMethod ?: return false

        return try {
            // Assigner le displayId natif (0) par réflexion
            try {
                val setDisplayIdMethod = event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayIdMethod.invoke(event, 0)
            } catch (e: Exception) {
                // Non bloquant sur anciennes versions
            }

            // Inject async (mode 0)
            val result = method.invoke(im, event, 0) as? Boolean
            result ?: false
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur injection MotionEvent", e)
            false
        }
    }

    override fun injectKeyCode(keyCode: Int): Boolean {
        val im = inputManagerInstance ?: return false
        val method = injectInputEventMethod ?: return false

        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0)

        return try {
            pendingKeys.add(keyCode)
            method.invoke(im, down, 0)
            SystemClock.sleep(15)
            method.invoke(im, up, 0)
            pendingKeys.remove(keyCode)
            true
        } catch (e: Exception) {
            Log.e(TAG, "M4: Erreur injectKeyCode ($keyCode)", e)
            false
        }
    }

    private fun releasePendingKeys() {
        val im = inputManagerInstance ?: return
        val method = injectInputEventMethod ?: return
        val now = SystemClock.uptimeMillis()

        for (code in pendingKeys) {
            try {
                val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0)
                method.invoke(im, up, 0)
                Log.i(TAG, "M4: Fusible anti-stuck : relâchement forcé de la touche $code")
            } catch (e: Exception) {
                // Ignorer
            }
        }
        pendingKeys.clear()
    }

    override fun displaySizeStatus(): Int {
        return if (hasAppliedOverride) STATUS_SUCCESS else STATUS_ERR_UNSUPPORTED
    }

    override fun displayPowerModeStatus(): Int {
        return if (isDirectPermissionMode) STATUS_ERR_UNSUPPORTED else STATUS_SUCCESS
    }

    override fun isTouchInjectionSupported(): Boolean {
        return inputManagerInstance != null && injectInputEventMethod != null
    }

    override fun destroy() {
        Log.i(TAG, "M4: destroy() appelé sur PrivilegedServiceImpl")
        restoreDisplayOverrideInternal()
        if (!isDirectPermissionMode) {
            setDisplayPowerMode(POWER_MODE_NORMAL)
        }
        releasePendingKeys()

        try {
            if (clientBinder != null && deathRecipient != null) {
                clientBinder?.unlinkToDeath(deathRecipient!!, 0)
            }
        } catch (e: Exception) {
            // Ignorer
        }

        // Obligation du cahier des charges : System.exit(0) sur destroy pour le service externe Shizuku/root
        if (!isDirectPermissionMode) {
            Process.killProcess(Process.myPid())
        }
    }
}
