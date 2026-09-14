package com.newzura.erebus

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager

/**
 * Activité 100% transparente permettant de solliciter le consentement MediaProjection
 * lors du démarrage automatique initié par la notification haute priorité.
 *
 * Sans interface visuelle ni animation de transition.
 */
class ProjectionConsentActivity : Activity() {

    companion object {
        private const val TAG = "Erebus"
        private const val REQUEST_CODE_CAPTURE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Désactiver toute animation d'entrée et de sortie
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        // Si le service tourne déjà, rien à faire
        if (ScreenCaptureService.isServiceRunning) {
            finish()
            return
        }

        // Signaler qu'un consentement est en attente (pour le service d'accessibilité)
        ProjectionCoordinator.setIsAwaitingConsent(true)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager indisponible")
            ProjectionCoordinator.onConsentDenied("MediaProjection non disponible")
            ProjectionCoordinator.cancelReadyNotification(this)
            finish()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val singleApp = prefs.getBoolean("pref_single_app", false)

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        if (singleApp) {
            attachSingleAppLaunchCookie(captureIntent)
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, REQUEST_CODE_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur au lancement du consentement MediaProjection", e)
            ProjectionCoordinator.onConsentDenied("Erreur de demande de projection")
            ProjectionCoordinator.cancelReadyNotification(this)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_CAPTURE) {
            ProjectionCoordinator.setIsAwaitingConsent(false)
            ProjectionCoordinator.cancelReadyNotification(this)

            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "M2: Autorisation MediaProjection accordée via ProjectionConsentActivity")

                // 1. Démarrer ScreenCaptureService
                ScreenCaptureService.startServiceWithToken(this, resultCode, data)

                // 2. Rattacher la surface DHU existante si déjà prête
                ErebusCarScreen.currentCarSurface?.let { surface ->
                    if (ErebusCarScreen.isSurfaceReady) {
                        Log.i(TAG, "M2: Rattachement immédiat de la surface AA existante")
                        ScreenCaptureService.onSurfaceAvailable(
                            this,
                            surface,
                            ErebusCarScreen.surfaceWidth,
                            ErebusCarScreen.surfaceHeight,
                            ErebusCarScreen.surfaceDpi
                        )
                    }
                }

                // 3. Appliquer la configuration d'orientation (SENSOR_LANDSCAPE)
                if (ProjectionCoordinator.forceLandscapePreference.value) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }

                // 4. Ouvrir l'application cible configurée
                launchConfiguredTargetApp()

                finish()
            } else {
                Log.w(TAG, "M2: Autorisation MediaProjection refusée ou annulée (RESULT_CANCELED)")
                val deniedMessage = getString(R.string.status_sharing_denied)
                ProjectionCoordinator.onConsentDenied(deniedMessage)
                Toast.makeText(this, deniedMessage, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun launchConfiguredTargetApp() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoLaunchPkg = prefs.getString("pref_auto_launch_pkg", "")?.trim()
        if (!autoLaunchPkg.isNullOrEmpty()) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(autoLaunchPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Log.i(TAG, "Lancement automatique de l'application cible: $autoLaunchPkg")
                    startActivity(launchIntent)
                } else {
                    Log.w(TAG, "Application cible introuvable: $autoLaunchPkg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Échec du lancement de l'application cible: $autoLaunchPkg", e)
            }
        }
    }

    private fun attachSingleAppLaunchCookie(captureIntent: Intent) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val options = ActivityOptions.makeBasic()
                val getCookieMethod = options.javaClass.methods.firstOrNull { it.name == "getLaunchCookie" }
                val cookie = getCookieMethod?.invoke(options)
                if (cookie is Parcelable) {
                    captureIntent.putExtra("android.media.projection.extra.EXTRA_LAUNCH_COOKIE", cookie)
                    Log.i(TAG, "M2: LaunchCookie attaché pour mirroring mono-app (SDK 35+)")
                } else {
                    val cookieClass = Class.forName("android.app.ActivityOptions\$LaunchCookie")
                    val ctor = cookieClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                    ctor?.isAccessible = true
                    val newCookie = ctor?.newInstance() as? Parcelable
                    if (newCookie != null) {
                        captureIntent.putExtra("android.media.projection.extra.EXTRA_LAUNCH_COOKIE", newCookie)
                        Log.i(TAG, "M2: LaunchCookie construit et attaché")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "M2: Impossible de créer LaunchCookie mono-app: ${e.message}")
            }
        }
    }
}
