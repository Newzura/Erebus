package com.newzura.erebus

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.preference.PreferenceManager

/**
 * Écran d'affichage multimédia et miroir sur Android Auto.
 * Prend en charge :
 * - Mode.MIRROR : Duplication d'écran via ScreenCaptureService et MediaProjection
 * - Mode.YOUTUBE : Lecture web YouTube sur VirtualDisplay/WebView sans surchauffe
 * - Mode.JELLYFIN : Accès Jellyfin web sur VirtualDisplay/WebView sans surchauffe
 */
class ErebusCarScreen(
    carContext: CarContext,
    private val mode: Mode = Mode.MIRROR,
    private val targetUrl: String = ""
) : Screen(carContext), SurfaceCallback {

    enum class Mode {
        MIRROR,
        YOUTUBE,
        JELLYFIN
    }

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
        if (!surfaceCallbackRegistered) {
            try {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
                surfaceCallbackRegistered = true
                Log.i(TAG, "SurfaceCallback enregistré avec succès sur AppManager pour mode $mode")
            } catch (e: Exception) {
                Log.e(TAG, "Échec enregistrement SurfaceCallback", e)
            }
        }

        val actionStripBuilder = ActionStrip.Builder()

        // Bouton retour au menu principal
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("☰ " + carContext.getString(R.string.mode_menu))
                .setOnClickListener {
                    cleanupActiveMedia()
                    screenManager.pop()
                }
                .build()
        )

        // Actions spécifiques pour les modes Web (YouTube / Jellyfin)
        if (mode == Mode.YOUTUBE || mode == Mode.JELLYFIN) {
            // Précédent dans la WebView
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("◀ " + carContext.getString(R.string.action_back))
                    .setOnClickListener {
                        CarStreamPresentation.getActive()?.goBack()
                    }
                    .build()
            )

            // Recharger la page
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("↻ " + carContext.getString(R.string.action_refresh))
                    .setOnClickListener {
                        CarStreamPresentation.getActive()?.reload()
                    }
                    .build()
            )
        }

        return NavigationTemplate.Builder()
            .setActionStrip(actionStripBuilder.build())
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        currentCarSurface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        isSurfaceReady = true

        Log.i(TAG, "onSurfaceAvailable reçu (Mode $mode): ${surfaceWidth}x${surfaceHeight}, DPI: $surfaceDpi")

        ProjectionCoordinator.setCarSurfaceAvailable(true, surfaceWidth, surfaceHeight)

        val surf = surfaceContainer.surface ?: return

        when (mode) {
            Mode.MIRROR -> {
                CarStreamPresentation.dismissCurrent()
                ScreenCaptureService.onSurfaceAvailable(
                    carContext,
                    surf,
                    surfaceWidth,
                    surfaceHeight,
                    surfaceDpi
                )

                // Vérifier auto-start pour le miroir
                val isMediaProjectionInactive = !ProjectionCoordinator.mediaProjectionActive.value && !ScreenCaptureService.isServiceRunning
                val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
                val isAutoStartEnabled = prefs.getBoolean("pref_autostart_aa", true)
                val hasRequested = ProjectionCoordinator.hasRequestedConsentForSession.value
                val isDenied = ProjectionCoordinator.consentDeniedForSession.value

                if (isMediaProjectionInactive && isAutoStartEnabled && !hasRequested && !isDenied) {
                    Log.i(TAG, "Conditions auto-start miroir validées — notification 'Erebus prêt'")
                    ProjectionCoordinator.postReadyNotification(carContext)
                }
            }
            Mode.YOUTUBE, Mode.JELLYFIN -> {
                // Arrêter ScreenCaptureService pour libérer la surface et économiser la batterie
                if (ScreenCaptureService.isServiceRunning) {
                    ScreenCaptureService.stopCaptureService(carContext)
                }

                // Démarrer la présentation CarStream sur la Surface
                CarStreamPresentation.showPresentation(
                    carContext,
                    surf,
                    surfaceWidth,
                    surfaceHeight,
                    surfaceDpi,
                    targetUrl
                )
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "onVisibleAreaChanged: $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "onStableAreaChanged: $stableArea")
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceDestroyed reçu pour mode $mode")
        isSurfaceReady = false
        currentCarSurface = null

        ProjectionCoordinator.cancelReadyNotification(carContext)
        ProjectionCoordinator.setCarSurfaceAvailable(false, context = carContext)

        cleanupActiveMedia()
    }

    private fun cleanupActiveMedia() {
        if (mode == Mode.MIRROR) {
            ScreenCaptureService.onSurfaceDestroyed()
        } else {
            CarStreamPresentation.dismissCurrent()
        }
    }

    override fun onClick(x: Float, y: Float) {
        Log.d(TAG, "onClick reçu sur AA (mode $mode): x=$x, y=$y")
        if (mode == Mode.MIRROR) {
            PrivilegedManager.injectClick(x, y, surfaceWidth, surfaceHeight)
        } else {
            CarStreamPresentation.injectTouch(x, y, MotionEvent.ACTION_DOWN)
            CarStreamPresentation.injectTouch(x, y, MotionEvent.ACTION_UP)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        Log.d(TAG, "onScroll reçu sur AA (mode $mode): dx=$distanceX, dy=$distanceY")
        if (mode == Mode.MIRROR) {
            PrivilegedManager.injectScroll(distanceX, distanceY, surfaceWidth, surfaceHeight)
        } else {
            // Dans CarStreamPresentation WebView
            CarStreamPresentation.injectTouch(-distanceX, -distanceY, MotionEvent.ACTION_MOVE)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        Log.d(TAG, "onFling: vx=$velocityX, vy=$velocityY")
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        Log.d(TAG, "onScale: factor=$scaleFactor")
    }
}
