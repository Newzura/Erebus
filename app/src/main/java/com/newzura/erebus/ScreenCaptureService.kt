package com.newzura.erebus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "Erebus"
        const val CHANNEL_ID = "erebus_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.newzura.erebus.ACTION_START"
        const val ACTION_STOP = "com.newzura.erebus.ACTION_STOP"
        const val ACTION_UPDATE_SURFACE = "com.newzura.erebus.ACTION_UPDATE_SURFACE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var isServiceRunning = false
            private set

        var activeSurface: Surface? = null
            private set
        var surfaceWidth: Int = 0
            private set
        var surfaceHeight: Int = 0
            private set
        var surfaceDpi: Int = 0
            private set

        // Appelé depuis ErebusCarScreen
        fun onSurfaceAvailable(
            context: Context,
            surface: Surface,
            width: Int,
            height: Int,
            dpi: Int
        ) {
            activeSurface = surface
            surfaceWidth = width
            surfaceHeight = height
            surfaceDpi = dpi

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autostart = prefs.getBoolean("pref_autostart_aa", true)
            val mirrorEnabled = prefs.getBoolean("pref_mirror_enabled", true)

            Log.i(TAG, "ScreenCaptureService: onSurfaceAvailable reçu (${width}x${height}, dpi=$dpi). Service en cours=$isServiceRunning, Autostart=$autostart")

            if (isServiceRunning) {
                val intent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ACTION_UPDATE_SURFACE
                }
                context.startService(intent)
            } else if (autostart && mirrorEnabled && lastResultCode != 0 && lastResultData != null) {
                // Relance auto si token de capture déjà accordé
                startServiceWithToken(context, lastResultCode, lastResultData!!)
            }
        }

        fun onSurfaceDestroyed() {
            Log.i(TAG, "ScreenCaptureService: onSurfaceDestroyed reçu")
            activeSurface = null
            instance?.releaseVirtualDisplay()
        }

        var lastResultCode: Int = 0
        var lastResultData: Intent? = null

        var instance: ScreenCaptureService? = null
            private set

        fun startServiceWithToken(context: Context, resultCode: Int, data: Intent) {
            lastResultCode = resultCode
            lastResultData = data

            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCaptureService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.i(TAG, "ScreenCaptureService créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "ScreenCaptureService: arrêt demandé")
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && resultData != null) {
                    lastResultCode = resultCode
                    lastResultData = resultData
                }

                startForegroundNotification()
                setupWakeLock()
                initMediaProjection(lastResultCode, lastResultData)
            }
            ACTION_UPDATE_SURFACE -> {
                Log.i(TAG, "ScreenCaptureService: mise à jour de la surface reçue")
                setupVirtualDisplay()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_capture_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupWakeLock() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val keepAwake = prefs.getBoolean("pref_keep_awake", true)

        if (keepAwake && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "erebus:ScreenCaptureLock").apply {
                acquire(10 * 60 * 60 * 1000L) // max 10h
            }
            Log.d(TAG, "WakeLock PARTIAL_WAKE_LOCK acquis")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock relâché")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lors du relâchement du WakeLock", e)
        }
        wakeLock = null
    }

    private fun initMediaProjection(resultCode: Int, data: Intent?) {
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "M2: ResultCode ou intent null, impossible d'initialiser MediaProjection")
            stopSelf()
            return
        }

        try {
            mediaProjection?.stop()
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "M2: getMediaProjection a retourné null -> stopSelf")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "M2: MediaProjection callback onStop reçu")
                    releaseVirtualDisplay()
                    stopSelf()
                }
            }, null)

            Log.i(TAG, "M2: MediaProjection initialisé avec succès")
            setupVirtualDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "M2: Échec initialisation MediaProjection", e)
            stopSelf()
        }
    }

    private fun setupVirtualDisplay() {
        val surface = activeSurface ?: ErebusCarScreen.currentCarSurface
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "M2: Surface AA non disponible ou invalide, attente de onSurfaceAvailable")
            return
        }

        val mp = mediaProjection
        if (mp == null) {
            Log.w(TAG, "M2: MediaProjection null, attente du token")
            return
        }

        // Déterminer dimensions et DPI par défaut depuis DisplayManager
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val defaultSize = Point()
        val defaultMetrics = DisplayMetrics()
        defaultDisplay.getRealSize(defaultSize)
        defaultDisplay.getRealMetrics(defaultMetrics)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val widthAdjust = prefs.getString("pref_width_adjust", "0")?.toIntOrNull() ?: 0
        val heightAdjust = prefs.getString("pref_height_adjust", "0")?.toIntOrNull() ?: 0
        val fixedSize = prefs.getString("pref_fixed_size", "") ?: ""

        var targetWidth = if (surfaceWidth > 0) surfaceWidth else defaultSize.x
        var targetHeight = if (surfaceHeight > 0) surfaceHeight else defaultSize.y
        var targetDpi = if (surfaceDpi > 0) surfaceDpi else defaultMetrics.densityDpi

        if (fixedSize.isNotBlank() && fixedSize.contains("x")) {
            val parts = fixedSize.split("x")
            val fw = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val fh = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (fw != null && fh != null && fw > 0 && fh > 0) {
                targetWidth = fw
                targetHeight = fh
                Log.i(TAG, "M2: Utilisation de la résolution fixe: ${fw}x${fh}")
            }
        }

        targetWidth = (targetWidth + widthAdjust).coerceAtLeast(320)
        targetHeight = (targetHeight + heightAdjust).coerceAtLeast(240)

        Log.i(TAG, "M2: Création VirtualDisplay ($targetWidth x $targetHeight, DPI=$targetDpi) sur surface=$surface")

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

            virtualDisplay = mp.createVirtualDisplay(
                "ErebusVirtualDisplay",
                targetWidth,
                targetHeight,
                targetDpi,
                flags,
                surface,
                null,
                null
            )

            Log.i(TAG, "M2: VirtualDisplay créé avec succès: $virtualDisplay")

            // Si écran off demandé avec backend privilégié
            if (prefs.getBoolean("pref_screen_off", false)) {
                PrivilegedManager.setScreenPowerMode(false)
            }
            // Si adaptation écran format voiture demandée
            if (prefs.getBoolean("pref_car_format", false)) {
                PrivilegedManager.applyCarDisplayOverride(targetWidth, targetHeight, targetDpi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "M2: Erreur lors de createVirtualDisplay", e)
        }
    }

    fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
            Log.i(TAG, "M2: VirtualDisplay libéré proprement")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur release VirtualDisplay", e)
        }
        virtualDisplay = null
    }

    private fun stopCapture() {
        releaseVirtualDisplay()
        try {
            mediaProjection?.stop()
            Log.i(TAG, "M2: MediaProjection arrêté")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur stop MediaProjection", e)
        }
        mediaProjection = null

        // Rétablir l'écran du téléphone si éteint
        PrivilegedManager.setScreenPowerMode(true)
        // Rétablir géométrie native si adaptée
        PrivilegedManager.restoreDisplayOverride()

        releaseWakeLock()
        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
        isServiceRunning = false
        Log.i(TAG, "ScreenCaptureService détruit")
    }
}
