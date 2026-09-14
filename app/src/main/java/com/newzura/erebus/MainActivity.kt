package com.newzura.erebus

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
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
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Erebus"
    }

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvBackendBadge: TextView
    private lateinit var tvAaStatus: TextView
    private lateinit var btnToggleMirror: MaterialButton
    private lateinit var mediaProjectionManager: MediaProjectionManager

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
            ScreenCaptureService.startServiceWithToken(this, result.resultCode, result.data!!)
            updateUiState()
        } else {
            Log.w(TAG, "M2: Autorisation MediaProjection refusée ou annulée")
            Toast.makeText(this, "Capture d'écran annulée", Toast.LENGTH_SHORT).show()
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
        btnToggleMirror = findViewById(R.id.btn_toggle_mirror)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnToggleMirror.setOnClickListener {
            if (ScreenCaptureService.isServiceRunning) {
                ScreenCaptureService.stopCaptureService(this)
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
    }

    override fun onResume() {
        super.onResume()
        PrivilegedManager.init(this)
        updateUiState()
    }

    private fun updateUiState() {
        // État du miroir
        if (ScreenCaptureService.isServiceRunning) {
            tvStatusTitle.text = getString(R.string.status_active)
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
            btnToggleMirror.text = getString(R.string.stop_mirroring)
            btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.erebus_error))
        } else {
            tvStatusTitle.text = getString(R.string.status_ready)
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_primary))
            btnToggleMirror.text = getString(R.string.start_mirroring)
            btnToggleMirror.setBackgroundColor(ContextCompat.getColor(this, R.color.erebus_primary))
        }

        // État Android Auto
        if (ErebusCarScreen.isSurfaceReady) {
            val w = ErebusCarScreen.surfaceWidth
            val h = ErebusCarScreen.surfaceHeight
            tvAaStatus.text = getString(R.string.status_aa_connected, w, h)
            tvAaStatus.setTextColor(ContextCompat.getColor(this, R.color.erebus_accent))
        } else {
            tvAaStatus.text = getString(R.string.status_aa_disconnected)
            tvAaStatus.setTextColor(ContextCompat.getColor(this, R.color.erebus_text_secondary))
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
