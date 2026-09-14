package com.newzura.erebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectionCoordinatorTest {

    @Before
    fun setUp() {
        // Reset states
        ProjectionCoordinator.setForceLandscapePreference(true)
        ProjectionCoordinator.setCarAppConnected(false)
        ProjectionCoordinator.setCarSurfaceAvailable(false)
        ProjectionCoordinator.setMediaProjectionActive(false)
        ProjectionCoordinator.setMirroringActive(false)
    }

    @Test
    fun shouldForceLandscape_isTrueOnlyWhenAllFiveConditionsMet() {
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)

        // 1. AA connect
        ProjectionCoordinator.setCarAppConnected(true)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)

        // 2. AA surface available
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1920, 1080)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)

        // 3. MediaProjection active
        ProjectionCoordinator.setMediaProjectionActive(true)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)

        // 4. Mirroring active
        ProjectionCoordinator.setMirroringActive(true)
        assertTrue(ProjectionCoordinator.shouldForceLandscape.value)

        // Si Android Auto se déconnecte, shouldForceLandscape repasse à false immédiatement
        ProjectionCoordinator.setCarAppConnected(false)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
    }

    @Test
    fun shouldForceLandscape_revertsWhenMirroringStops() {
        ProjectionCoordinator.setForceLandscapePreference(true)
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1280, 720)
        ProjectionCoordinator.setMediaProjectionActive(true)
        ProjectionCoordinator.setMirroringActive(true)
        assertTrue(ProjectionCoordinator.shouldForceLandscape.value)

        // Arrêt capture
        ProjectionCoordinator.onCaptureStopped()
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
        assertFalse(ProjectionCoordinator.mediaProjectionActive.value)
        assertFalse(ProjectionCoordinator.mirroringActive.value)
    }

    @Test
    fun shouldForceLandscape_revertsWhenSurfaceDestroyed() {
        ProjectionCoordinator.setForceLandscapePreference(true)
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1280, 720)
        ProjectionCoordinator.setMediaProjectionActive(true)
        ProjectionCoordinator.setMirroringActive(true)
        assertTrue(ProjectionCoordinator.shouldForceLandscape.value)

        // Perte de la surface
        ProjectionCoordinator.setCarSurfaceAvailable(false)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
    }

    @Test
    fun shouldForceLandscape_revertsWhenCarDisconnected() {
        ProjectionCoordinator.setForceLandscapePreference(true)
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1280, 720)
        ProjectionCoordinator.setMediaProjectionActive(true)
        ProjectionCoordinator.setMirroringActive(true)
        assertTrue(ProjectionCoordinator.shouldForceLandscape.value)

        // Déconnexion AA
        ProjectionCoordinator.setCarAppConnected(false)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
        assertFalse(ProjectionCoordinator.carSurfaceAvailable.value)
    }

    @Test
    fun shouldForceLandscape_remainsFalseIfPreferenceDisabled() {
        ProjectionCoordinator.setForceLandscapePreference(false)
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1280, 720)
        ProjectionCoordinator.setMediaProjectionActive(true)
        ProjectionCoordinator.setMirroringActive(true)

        // Préférence désactivée ("Ne pas modifier l'orientation")
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
    }

    @Test
    fun consentDenied_setsFlagAndRevertsStates() {
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.setCarSurfaceAvailable(true, 1280, 720)
        ProjectionCoordinator.setIsAwaitingConsent(true)
        assertTrue(ProjectionCoordinator.isAwaitingConsent.value)

        ProjectionCoordinator.onConsentDenied("Partage non autorisé")
        assertFalse(ProjectionCoordinator.isAwaitingConsent.value)
        assertTrue(ProjectionCoordinator.consentDeniedForSession.value)
        assertEquals("Partage non autorisé", ProjectionCoordinator.consentStatus.value)
        assertFalse(ProjectionCoordinator.mediaProjectionActive.value)
        assertFalse(ProjectionCoordinator.mirroringActive.value)
        assertFalse(ProjectionCoordinator.shouldForceLandscape.value)
    }

    @Test
    fun carDisconnection_resetsSessionFlags() {
        ProjectionCoordinator.setCarAppConnected(true)
        ProjectionCoordinator.onConsentDenied("Partage non autorisé")
        assertTrue(ProjectionCoordinator.consentDeniedForSession.value)

        // Déconnexion Android Auto
        ProjectionCoordinator.setCarAppConnected(false)
        assertFalse(ProjectionCoordinator.consentDeniedForSession.value)
        assertFalse(ProjectionCoordinator.hasRequestedConsentForSession.value)
        assertFalse(ProjectionCoordinator.isAwaitingConsent.value)
    }
}
