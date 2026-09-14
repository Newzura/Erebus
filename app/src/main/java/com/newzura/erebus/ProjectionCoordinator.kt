package com.newzura.erebus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Composant central coordonnant l'état de projection et l'orientation d'Erebus.
 *
 * Détermine selon des conditions strictes et réversibles si l'application doit
 * demander le mode paysage capteur (ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) :
 *
 * shouldForceLandscape = preferenceForceLandscapeEnabled &&
 *                        carAppConnected &&
 *                        carSurfaceAvailable &&
 *                        mediaProjectionActive &&
 *                        mirroringActive
 */
object ProjectionCoordinator {

    private const val TAG = "Erebus"
    const val CHANNEL_READY_ID = "erebus_auto_ready"
    const val NOTIFICATION_READY_ID = 2001

    // 1. Android Auto session connectée
    private val _carAppConnected = MutableStateFlow(false)
    val carAppConnected: StateFlow<Boolean> = _carAppConnected.asStateFlow()

    // 2. Surface Android Auto disponible
    private val _carSurfaceAvailable = MutableStateFlow(false)
    val carSurfaceAvailable: StateFlow<Boolean> = _carSurfaceAvailable.asStateFlow()

    // Dimensions géométriques de la surface voiture (largeur x hauteur)
    private val _surfaceDimensions = MutableStateFlow<Pair<Int, Int>?>(null)
    val surfaceDimensions: StateFlow<Pair<Int, Int>?> = _surfaceDimensions.asStateFlow()

    // 3. MediaProjection active (token valide et callback enregistré)
    private val _mediaProjectionActive = MutableStateFlow(false)
    val mediaProjectionActive: StateFlow<Boolean> = _mediaProjectionActive.asStateFlow()

    // 4. Mirroring actif (VirtualDisplay actif projetant sur la surface)
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    // 5. Préférence utilisateur "Paysage automatique avec Android Auto" (true par défaut)
    private val _forceLandscapePreference = MutableStateFlow(true)
    val forceLandscapePreference: StateFlow<Boolean> = _forceLandscapePreference.asStateFlow()

    // 6. État calculé pour l'orientation
    private val _shouldForceLandscape = MutableStateFlow(false)
    val shouldForceLandscape: StateFlow<Boolean> = _shouldForceLandscape.asStateFlow()

    // 7. Garde-fous auto-start par session de connexion
    private val _hasRequestedConsentForSession = MutableStateFlow(false)
    val hasRequestedConsentForSession: StateFlow<Boolean> = _hasRequestedConsentForSession.asStateFlow()

    private val _consentDeniedForSession = MutableStateFlow(false)
    val consentDeniedForSession: StateFlow<Boolean> = _consentDeniedForSession.asStateFlow()

    private val _isAwaitingConsent = MutableStateFlow(false)
    val isAwaitingConsent: StateFlow<Boolean> = _isAwaitingConsent.asStateFlow()

    private val _consentStatus = MutableStateFlow<String?>(null)
    val consentStatus: StateFlow<String?> = _consentStatus.asStateFlow()

    fun setCarAppConnected(connected: Boolean, context: Context? = null) {
        _carAppConnected.value = connected
        if (!connected) {
            _carSurfaceAvailable.value = false
            _surfaceDimensions.value = null
            // Réinitialiser les états de session lors d'une déconnexion
            _hasRequestedConsentForSession.value = false
            _consentDeniedForSession.value = false
            _isAwaitingConsent.value = false
            _consentStatus.value = null
            context?.let { cancelReadyNotification(it) }
        } else {
            // Nouvelle connexion : autoriser une tentative pour cette nouvelle session
            _hasRequestedConsentForSession.value = false
            _consentDeniedForSession.value = false
            _consentStatus.value = null
        }
        evaluateShouldForceLandscape()
    }

    fun setCarSurfaceAvailable(available: Boolean, width: Int = 0, height: Int = 0, context: Context? = null) {
        _carSurfaceAvailable.value = available
        if (available && width > 0 && height > 0) {
            _surfaceDimensions.value = Pair(width, height)
        } else if (!available) {
            _surfaceDimensions.value = null
            context?.let { cancelReadyNotification(it) }
        }
        evaluateShouldForceLandscape()
    }

    fun setMediaProjectionActive(active: Boolean, context: Context? = null) {
        _mediaProjectionActive.value = active
        if (active) {
            context?.let { cancelReadyNotification(it) }
        } else {
            _mirroringActive.value = false
        }
        evaluateShouldForceLandscape()
    }

    fun setMirroringActive(active: Boolean, context: Context? = null) {
        _mirroringActive.value = active
        if (active) {
            context?.let { cancelReadyNotification(it) }
        }
        evaluateShouldForceLandscape()
    }

    fun setForceLandscapePreference(enabled: Boolean) {
        _forceLandscapePreference.value = enabled
        evaluateShouldForceLandscape()
    }

    fun setIsAwaitingConsent(awaiting: Boolean) {
        _isAwaitingConsent.value = awaiting
    }

    fun onConsentDenied(status: String) {
        Log.w(TAG, "M2: Consentement MediaProjection refusé: $status")
        _consentDeniedForSession.value = true
        _isAwaitingConsent.value = false
        _consentStatus.value = status
        _mediaProjectionActive.value = false
        _mirroringActive.value = false
        evaluateShouldForceLandscape()
    }

    fun onCaptureStopped(context: Context? = null) {
        _mediaProjectionActive.value = false
        _mirroringActive.value = false
        context?.let { cancelReadyNotification(it) }
        evaluateShouldForceLandscape()
    }

    fun resetSessionState() {
        _hasRequestedConsentForSession.value = false
        _consentDeniedForSession.value = false
        _isAwaitingConsent.value = false
        _consentStatus.value = null
    }

    /**
     * Affiche la notification haute priorité "Erebus prêt" invitant à partager l'écran.
     * Plafonnée à une seule demande par session de connexion Android Auto.
     */
    fun postReadyNotification(context: Context) {
        if (_hasRequestedConsentForSession.value || _consentDeniedForSession.value) {
            Log.d(TAG, "postReadyNotification ignoré: déjà demandé ou refusé dans cette session")
            return
        }

        _hasRequestedConsentForSession.value = true
        Log.i(TAG, "postReadyNotification: émission de la notification haute priorité 'Erebus prêt'")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_READY_ID,
                context.getString(R.string.notification_channel_ready),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_ready_desc)
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val consentIntent = Intent(context, ProjectionConsentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val consentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_READY_ID)
            .setContentTitle(context.getString(R.string.notification_ready_title))
            .setContentText(context.getString(R.string.notification_ready_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .setContentIntent(consentPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_READY_ID, notification)
    }

    /**
     * Annule la notification "Erebus prêt".
     */
    fun cancelReadyNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_READY_ID)
    }

    private fun evaluateShouldForceLandscape() {
        val should = _forceLandscapePreference.value &&
                _carAppConnected.value &&
                _carSurfaceAvailable.value &&
                _mediaProjectionActive.value &&
                _mirroringActive.value
        _shouldForceLandscape.value = should
    }
}
