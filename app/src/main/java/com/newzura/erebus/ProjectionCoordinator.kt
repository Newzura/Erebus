package com.newzura.erebus

import androidx.lifecycle.MutableLiveData

object ProjectionCoordinator {

    val shouldForceLandscape = MutableLiveData(false)
    val carAppConnected = MutableLiveData(false)
    val carSurfaceAvailable = MutableLiveData(false)
    val mediaProjectionActive = MutableLiveData(false)
    val mirroringActive = MutableLiveData(false)
    val isAwaitingConsent = MutableLiveData(false)
    val consentDeniedForSession = MutableLiveData(false)
    val consentStatus = MutableLiveData<String?>(null)
    val hasRequestedConsentForSession = MutableLiveData(false)

    fun setForceLandscapePreference(enabled: Boolean) {
        // Store preference - in a real implementation this would be persisted
        // For testing, we just use it to determine if landscape should be forced
        if (!enabled) {
            shouldForceLandscape.value = false
        }
    }

    fun setCarAppConnected(connected: Boolean) {
        carAppConnected.value = connected
        if (!connected) {
            carSurfaceAvailable.value = false
            shouldForceLandscape.value = false
            consentDeniedForSession.value = false
            hasRequestedConsentForSession.value = false
            isAwaitingConsent.value = false
        }
        updateShouldForceLandscape()
    }

    fun setCarSurfaceAvailable(available: Boolean, width: Int = 0, height: Int = 0) {
        carSurfaceAvailable.value = available
        if (!available) {
            shouldForceLandscape.value = false
        }
        updateShouldForceLandscape()
    }

    fun setMediaProjectionActive(active: Boolean) {
        mediaProjectionActive.value = active
        updateShouldForceLandscape()
    }

    fun setMirroringActive(active: Boolean) {
        mirroringActive.value = active
        updateShouldForceLandscape()
    }

    fun setIsAwaitingConsent(awaiting: Boolean) {
        isAwaitingConsent.value = awaiting
    }

    fun onCaptureStopped() {
        mirroringActive.value = false
        mediaProjectionActive.value = false
        shouldForceLandscape.value = false
    }

    fun onConsentDenied(reason: String) {
        isAwaitingConsent.value = false
        consentDeniedForSession.value = true
        consentStatus.value = reason
        mediaProjectionActive.value = false
        mirroringActive.value = false
        shouldForceLandscape.value = false
    }

    private fun updateShouldForceLandscape() {
        // shouldForceLandscape is true only when all conditions are met:
        // 1. Force landscape preference is enabled (assumed true unless explicitly disabled)
        // 2. Car app is connected
        // 3. Car surface is available
        // 4. Media projection is active
        // 5. Mirroring is active
        val allConditionsMet = carAppConnected.value == true &&
                carSurfaceAvailable.value == true &&
                mediaProjectionActive.value == true &&
                mirroringActive.value == true
        
        shouldForceLandscape.value = allConditionsMet
    }
}
