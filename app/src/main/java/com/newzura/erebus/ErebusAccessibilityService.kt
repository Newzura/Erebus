package com.newzura.erebus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité pour Erebus :
 * 1. Automatisation du consentement MediaProjection lors du démarrage automatique
 *    (détection des fenêtres de dialogue systemui / vpndialogs et clic automatique sur le bouton de confirmation).
 * 2. Injection tactile (gestes tap et swipe) pour le contrôle de l'écran depuis Android Auto.
 */
class ErebusAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "Erebus"
        var instance: ErebusAccessibilityService? = null
            private set
        val isServiceRunning: Boolean
            get() = instance != null

        fun injectTap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            return try {
                val path = Path().apply {
                    moveTo(x, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                service.dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Accessibility: Geste tap complété à ($x, $y)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Accessibility: Geste tap annulé")
                    }
                }, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility: Erreur injection tap", e)
                false
            }
        }

        fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 200): Boolean {
            val service = instance ?: return false
            return try {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                service.dispatchGesture(gesture, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility: Erreur injection swipe", e)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ErebusAccessibilityService connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == "com.android.vpndialogs") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                Log.d(TAG, "ErebusAccessibilityService: Événement fenêtre détecté sur $pkg")
                attemptAutoConsent()
                // Retry léger pour les animations de bottom sheet ou dialogues asynchrones (OnePlus / Samsung)
                handler.postDelayed({ attemptAutoConsent() }, 200)
                handler.postDelayed({ attemptAutoConsent() }, 500)
            }
        }
    }

    private fun attemptAutoConsent() {
        val rootNode = rootInActiveWindow ?: return
        try {
            // 1. Cible directe par ID système Android standard : android:id/button1
            val button1List = rootNode.findAccessibilityNodeInfosByViewId("android:id/button1")
            for (node in button1List) {
                if (clickNodeOrDispatch(node)) {
                    Log.i(TAG, "ErebusAccessibilityService: Validation automatique MediaProjection réussie via android:id/button1")
                    return
                }
            }

            // 2. Cible spécifique SystemUI moderne
            val systemUiButtonList = rootNode.findAccessibilityNodeInfosByViewId("com.android.systemui:id/button_start")
            for (node in systemUiButtonList) {
                if (clickNodeOrDispatch(node)) {
                    Log.i(TAG, "ErebusAccessibilityService: Validation automatique MediaProjection réussie via com.android.systemui:id/button_start")
                    return
                }
            }

            // 3. Recherche par libellé textuel AOSP / multilingue
            val targetLabels = listOf(
                "Commencer",
                "Start now",
                "Commencer maintenant",
                "Start",
                "Démarrer",
                "Démarrer maintenant",
                "Partager l\'écran",
                "Share screen",
                "Autoriser",
                "Allow"
            )

            for (target in targetLabels) {
                val matches = rootNode.findAccessibilityNodeInfosByText(target)
                for (node in matches) {
                    val text = node.text?.toString()?.trim() ?: ""
                    if (targetLabels.any { it.equals(text, ignoreCase = true) } || node.isClickable) {
                        if (clickNodeOrDispatch(node)) {
                            Log.i(TAG, "ErebusAccessibilityService: Validation automatique MediaProjection réussie via texte: '$text'")
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ErebusAccessibilityService: Erreur lors de l'automatisation du consentement", e)
        }
    }

    private fun clickNodeOrDispatch(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // Tenter d'abord un ACTION_CLICK sur le nœud ou son premier parent cliquable
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    return true
                }
            }
            current = current.parent
        }

        // Si non cliquable directement via l'arborescence, émuler un tap sur ses coordonnées réelles
        if (node.isEnabled) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                Log.d(TAG, "ErebusAccessibilityService: Injection de tap sur les bornes ($bounds)")
                return injectTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }

        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "ErebusAccessibilityService interrompu")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        instance = null
        Log.i(TAG, "ErebusAccessibilityService détruit")
    }
}
