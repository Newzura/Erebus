package com.newzura.erebus

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.preference.PreferenceManager

class ErebusCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    companion object {
        private const val TAG = "ErebusCarApp"
        var currentCarSurface: Surface? = null
            private set
        var surfaceWidth: Int = 0
            private set
        var surfaceHeight: Int = 0
            private set
        var surfaceDpi: Int = 0
            private set
        var isSurfaceReady: Boolean = false
            private set
    }

    private var surfaceCallbackRegistered = false

    override fun onGetTemplate(): Template {
        // Enregistrer le callback au premier appel de onGetTemplate()
        // conformément au cycle de vie de Screen
        if (!surfaceCallbackRegistered) {
            try {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
                surfaceCallbackRegistered = true
                Log.i(TAG, "M1: SurfaceCallback enregistré avec succès sur AppManager")
            } catch (e: Exception) {
                Log.e(TAG, "M1: Échec de l'enregistrement de SurfaceCallback", e)
            }
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(Action.APP_ICON)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        currentCarSurface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        isSurfaceReady = true

        Log.i(TAG, "M1: onSurfaceAvailable reçu - Dimensions: ${surfaceWidth}x${surfaceHeight}, DPI: $surfaceDpi, Surface: $currentCarSurface")

        ProjectionCoordinator.setCarSurfaceAvailable(true, surfaceWidth, surfaceHeight)

        // Notifier ScreenCaptureService de la disponibilité de la surface
        surfaceContainer.surface?.let { surf ->
            ScreenCaptureService.onSurfaceAvailable(
                carContext,
                surf,
                surfaceWidth,
                surfaceHeight,
                surfaceDpi
            )
        }

        // Vérification des conditions de démarrage automatisé du miroir
        // 1. MediaProjection inactif
        // 2. Auto-start activé (pref_autostart_aa)
        // 3. Aucune demande en cours ou effectuée pour cette session
        val isMediaProjectionInactive = !ProjectionCoordinator.mediaProjectionActive.value && !ScreenCaptureService.isServiceRunning
        val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
        val isAutoStartEnabled = prefs.getBoolean("pref_autostart_aa", true)
        val hasRequested = ProjectionCoordinator.hasRequestedConsentForSession.value
        val isDenied = ProjectionCoordinator.consentDeniedForSession.value

        if (isMediaProjectionInactive && isAutoStartEnabled && !hasRequested && !isDenied) {
            Log.i(TAG, "M1: Conditions auto-start validées — affichage notification haute priorité 'Erebus prêt'")
            ProjectionCoordinator.postReadyNotification(carContext)
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "onVisibleAreaChanged: $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "onStableAreaChanged: $stableArea")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "M1/M2: onSurfaceDestroyed reçu")
        isSurfaceReady = false
        currentCarSurface = null

        // Annuler la notification 'Erebus prêt' et mettre à jour le coordinateur
        ProjectionCoordinator.cancelReadyNotification(carContext)
        ProjectionCoordinator.setCarSurfaceAvailable(false, context = carContext)

        // Libérer le VirtualDisplay immédiatement
        ScreenCaptureService.onSurfaceDestroyed()
    }

    override fun onClick(x: Float, y: Float) {
        Log.d(TAG, "onClick reçu sur AA: x=$x, y=$y")
        PrivilegedManager.injectClick(x, y, surfaceWidth, surfaceHeight)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        Log.d(TAG, "onScroll reçu sur AA: dx=$distanceX, dy=$distanceY")
        PrivilegedManager.injectScroll(distanceX, distanceY, surfaceWidth, surfaceHeight)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        Log.d(TAG, "onFling reçu sur AA: vx=$velocityX, vy=$velocityY")
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        Log.d(TAG, "onScale reçu sur AA: focus=($focusX, $focusY), factor=$scaleFactor")
    }
}
