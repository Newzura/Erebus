package com.newzura.erebus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class Accessibility : AccessibilityService() {

    companion object {
        private const val TAG = "Erebus"
        var instance: Accessibility? = null
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
        Log.i(TAG, "Accessibility Service Erebus connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Pas d'action requise sur les événements d'accessibilité
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Erebus interrompu")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service Erebus détruit")
    }
}
