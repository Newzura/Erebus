package com.newzura.erebus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Paysage automatique
        findPreference<SwitchPreferenceCompat>("pref_force_landscape")?.setOnPreferenceChangeListener { _, newValue ->
            ProjectionCoordinator.setForceLandscapePreference(newValue as? Boolean ?: true)
            true
        }

        // Permission Notifications / MediaSession
        findPreference<Preference>("pref_perm_notif")?.setOnPreferenceClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            true
        }

        // Permission Overlay
        findPreference<Preference>("pref_perm_overlay")?.setOnPreferenceClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            startActivity(intent)
            true
        }

        // Permission Accessibilité
        findPreference<Preference>("pref_perm_accessibility")?.setOnPreferenceClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            true
        }

        // Backend privilégié
        findPreference<ListPreference>("pref_privileged_backend")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "shizuku") {
                PrivilegedManager.requestShizukuPermission(1002)
            }
            true
        }

        // À propos
        findPreference<Preference>("pref_about_author")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage("Erebus par newzura, 2026\n\nDuplication d'écran vers Android Auto avec proxy média et fonctions privilégiées (Shizuku / Root).")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        // Raccourcis de lancement : initialiser les résumés
        listOf("pref_shortcut_1", "pref_shortcut_2", "pref_shortcut_3", "pref_shortcut_4").forEachIndexed { index, key ->
            findPreference<EditTextPreference>(key)?.apply {
                summary = text?.ifBlank { null } ?: getString(R.string.pref_shortcut_summary_empty)
                setOnPreferenceChangeListener { _, newVal ->
                    val str = newVal as? String
                    summary = str?.ifBlank { null } ?: getString(R.string.pref_shortcut_summary_empty)
                    true
                }
            }
        }

        // Application auto
        findPreference<EditTextPreference>("pref_auto_launch_pkg")?.apply {
            summary = text?.ifBlank { null } ?: getString(R.string.pref_auto_launch_app_summary)
            setOnPreferenceChangeListener { _, newVal ->
                val str = newVal as? String
                summary = str?.ifBlank { null } ?: getString(R.string.pref_auto_launch_app_summary)
                true
            }
        }

        updatePrivilegedStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegedStatus()
    }

    private fun updatePrivilegedStatus() {
        findPreference<Preference>("pref_privileged_status")?.summary =
            PrivilegedManager.getActiveBackendName()
    }
}
