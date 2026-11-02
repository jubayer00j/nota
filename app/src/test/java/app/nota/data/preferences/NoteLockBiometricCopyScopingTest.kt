package app.nota.data.preferences

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Per-note fingerprint copies are scoped per account (Phase 3a,
 * Codex P2): the guest→account copy preserves note ids, so an
 * unscoped store would let two libraries clobber each other's
 * copies. Pre-3a (legacy, unscoped) copies belong to the guest
 * library — the only one that existed — and migrate on first
 * guest write.
 */
@RunWith(RobolectricTestRunner::class)
class NoteLockBiometricCopyScopingTest {

    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        prefs = PreferencesRepository(RuntimeEnvironment.getApplication())
    }

    @Test
    fun copiesAreScopedPerAccount() = runBlocking {
        prefs.setNoteLockBiometricCopy("guest", "n1", "guest-copy")
        prefs.setNoteLockBiometricCopy("uid-a", "n1", "account-copy")
        assertEquals("guest-copy", prefs.getNoteLockBiometricCopy("guest", "n1"))
        assertEquals("account-copy", prefs.getNoteLockBiometricCopy("uid-a", "n1"))
        assertNull(prefs.getNoteLockBiometricCopy("uid-b", "n1"))

        // Removing one library's copy leaves the other's intact.
        prefs.removeNoteLockBiometricCopy("uid-a", "n1")
        assertNull(prefs.getNoteLockBiometricCopy("uid-a", "n1"))
        assertEquals("guest-copy", prefs.getNoteLockBiometricCopy("guest", "n1"))
    }
}
