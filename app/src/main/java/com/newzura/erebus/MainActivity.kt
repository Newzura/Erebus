package com.newzura.erebus

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Erebus"
    }

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvBackendBadge: TextView
    private lateinit var tvAaStatus: TextView
    private lateinit var tvTechAa: TextView
    private lateinit var tvTechSurface: TextView
    private lateinit var tvTechCapture: TextView
    private lateinit var tvTechOrientation: TextView
    private lateinit var btnToggleMirror: MaterialButton
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == "pref_force_landscape") {
            val forceLandscape = sp.getBoolean(key, true)
            ProjectionCoordinator.setForceLandscapePreference(forceLandscape)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "La permission de notification est requise pour le service de capture", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "M2: Autorisation MediaProjection accordée par l'utilisateur")
            ProjectionCoordinator.cancelReadyNotification(this)
            ScreenCaptureService.startServiceWithToken(this, result.resultCode, result.data!!)
            updateUiState()
        } else {
            Log.w(TAG, "M2: Autorisation MediaProjection refusée ou annulée")
            ProjectionCoordinator.setMediaProjectionActive(false, this)
            ProjectionCoordinator.onConsentDenied(getString(R.string.status_sharing_denied))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Toast.makeText(this, getString(R.string.status_sharing_denied), Toast.LENGTH_SHORT).show()
            updateUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvBackendBadge = findViewById(R.id.tv_backend_badge)
        tvAaStatus = findViewById(R.id.tv_aa_status)
        tvTechAa = findViewById(R.id.tv_tech_aa)
        tvTechSurface = findViewById(R.id.tv_tech_surface)
        tvTechCapture = findViewById(R.id.tv_tech_capture)
        tvTechOrientation = findViewById(R.id.tv_tech_orientation)
        btnToggleMirror = findViewById(R.id.btn_toggle_mirror)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialiser la préférence paysage automatique (true par défaut)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val forceLandscape = prefs.getBoolean("pref_force_landscape", true)
        ProjectionCoordinator.setForceLandscapePreference(forceLandscape)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        btnToggleMirror.setOnClickListener {
            if (ScreenCaptureService.isServiceRunning) {
                ScreenCaptureService.stopCaptureService(this)
                ProjectionCoordinator.onCaptureStopped(this)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                updateUiState()
            } else {
                startMirroringFlow()
            }
        }

        // Intégrer les préférences dans le conteneur
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        // Vérifier permission notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Initialiser PrivilegedManager
        PrivilegedManager.init(this)

        // Observer les flux de ProjectionCoordinator pour l'orientation et l'UI
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ProjectionCoordinator.shouldForceLandscape.collect { shouldForce ->
                        Log.i(TAG, "Mise à jour orientation: shouldForceLandscape = $shouldForce")
                        if (shouldForce) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
                launch {
                    combine(
                        ProjectionCoordinator.carAppConnected,
                        ProjectionCoordinator.carSurfaceAvailable,
                        ProjectionCoordinator.mediaProjectionActive,
                        ProjectionCoordinator.mirroringActive,
                        ProjectionCoordinator.shouldForceLandscape,
                        ProjectionCoordinator.surfaceDimensions,
                        ProjectionCoordinator.consentDeniedForSession
                    ) { _ -> }.collect {
                        updateUiState()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PrivilegedManager.init(this)
        updateUiState()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)

        // Restaurer l'orientation à la destruction de MainActivity sauf si le miroir reste actif en arrière-plan
        if (!ProjectionCoordinator.mirroringActive.value || isFinishing) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun updateUiState() {
        val isMirrorRunning = ScreenCaptureService.isServiceRunning || ProjectionCoordinator.mirroringActive.value
        val isCarConnected = ProjectionCoordinator.carAppConnected.value || ErebusCarScreen.isSurfaceReady
        val isSurfaceAvail = ProjectionCoordinator.carSurfaceAvailable.value || ErebusCarScreen.isSurfaceReady
        val isCaptureActive = ProjectionCoordinator.mediaProjectionActive.value || ScreenCaptureService.isServiceRunning
        val isLandscape = ProjectionCoordinator.shouldForceLandscape.value

        // État du miroir
        if (isMirrorRunning) {
            tvStatusTitle.text = getString(R.string.status_active)
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
            btnToggleMirror.text = getString(R.string.stop_mirroring)
            btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.erebus_error))
        } else {
            val consentDenied = ProjectionCoordinator.consentDeniedForSession.value
            if (consentDenied) {
                tvStatusTitle.text = getString(R.string.status_sharing_denied)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.erebus_error))
            } else {
                tvStatusTitle.text = getString(R.string.status_ready)
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_primary))
            }
            btnToggleMirror.text = getString(R.string.start_mirroring)
            btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.erebus_primary))
        }

        // État Android Auto
        val dims = ProjectionCoordinator.surfaceDimensions.value
        if (isSurfaceAvail && dims != null) {
            tvAaStatus.text = getString(R.string.status_aa_connected, dims.first, dims.second)
            tvAaStatus.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else if (isCarConnected) {
            val w = ErebusCarScreen.surfaceWidth
            val h = ErebusCarScreen.surfaceHeight
            if (w > 0 && h > 0) {
                tvAaStatus.text = getString(R.string.status_aa_connected, w, h)
            } else {
                tvAaStatus.text = getString(R.string.tech_status_aa_connected)
            }
            tvAaStatus.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvAaStatus.text = getString(R.string.status_aa_disconnected)
            tvAaStatus.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
        }

        // 1. Android Auto : connecté / déconnecté
        if (isCarConnected) {
            tvTechAa.text = getString(R.string.tech_status_aa_connected)
            tvTechAa.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvTechAa.text = getString(R.string.tech_status_aa_disconnected)
            tvTechAa.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
        }

        // 2. Surface voiture : disponible / indisponible
        if (isSurfaceAvail) {
            val w = dims?.first ?: ErebusCarScreen.surfaceWidth
            val h = dims?.second ?: ErebusCarScreen.surfaceHeight
            tvTechSurface.text = getString(R.string.tech_status_surface_available, w, h)
            tvTechSurface.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvTechSurface.text = getString(R.string.tech_status_surface_unavailable)
            tvTechSurface.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
        }

        // 3. Capture écran : active / inactive
        if (isCaptureActive) {
            tvTechCapture.text = getString(R.string.tech_status_capture_active)
            tvTechCapture.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvTechCapture.text = getString(R.string.tech_status_capture_inactive)
            tvTechCapture.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
        }

        // 4. Orientation Erebus : automatique paysage / système
        if (isLandscape) {
            tvTechOrientation.text = getString(R.string.tech_status_orientation_landscape)
            tvTechOrientation.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvTechOrientation.text = getString(R.string.tech_status_orientation_system)
            tvTechOrientation.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
        }

        // Badge Privilèges
        tvBackendBadge.text = PrivilegedManager.getActiveBackendName()
    }

    private fun startMirroringFlow() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val singleApp = prefs.getBoolean("pref_single_app", false)

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()

        // M2: Si mirroring mono-app activé (SDK 35+), construire et attacher LaunchCookie
        if (singleApp) {
            attachSingleAppLaunchCookie(captureIntent)
        }

        screenCaptureLauncher.launch(captureIntent)
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

