package app.nota.data.auth

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Who is using the app right now (Phase 3a). Firebase is identity
 *  ONLY (spec §2): a session names a database, nothing more — no note
 *  content ever leaves the device through Firebase. */
sealed interface Session {
    data object Guest : Session
    data class SignedIn(
        val uid: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?,
    ) : Session
}

/**
 * Account session manager (Phase 3a).
 *
 * Owns the three things that must move together when identity
 * changes:
 * 1. the [Session] state the UI renders,
 * 2. the active Room database ([DatabaseProvider.switchTo]) —
 *    sign-in opens `nota_<uid>`, sign-out returns to `nota_guest`,
 *    and neither file is ever modified by the switch itself,
 * 3. the guest→account copy offer (see [GuestLibraryTransfer]): on
 *    sign-in, when the guest library has notes and this account has
 *    never answered the offer, [guestMoveOffer] names the uid and
 *    the shell shows the dialog. The outcome is remembered per uid
 *    in preferences, so the offer never nags twice.
 *
 * Firebase's own session persistence decides cold-start identity:
 * the DI graph seeds the provider with the persisted user's uid
 * (see AppModule), and this manager's init re-aligns provider +
 * session from `currentUser` so the two can never disagree.
 */
class AuthManager(
    private val provider: DatabaseProvider,
    private val preferences: PreferencesRepository,
    private val transfer: GuestLibraryTransfer,
) {
    private val auth: FirebaseAuth? by lazy {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    }

    private val _session = MutableStateFlow<Session>(Session.Guest)
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Manager-lifetime scope for the cold-start offer evaluation
     *  (DB + preferences reads, off the main thread). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The uid with a pending guest→account copy offer, or null. */
    private val _guestMoveOffer = MutableStateFlow<String?>(null)
    val guestMoveOffer: StateFlow<String?> = _guestMoveOffer.asStateFlow()

    /** One-shot result message of a completed copy, until acknowledged. */
    private val _guestMoveResult = MutableStateFlow<String?>(null)
    val guestMoveResult: StateFlow<String?> = _guestMoveResult.asStateFlow()

    @Volatile
    private var initialised = false

    /**
     * Aligns session + provider with Firebase's persisted user.
     * Called once by the DI graph after construction (the manager is
     * a lazy Koin single; NotaApplication resolves it at startup so
     * cold-start identity is settled before the first frame).
     */
    fun initialise() {
        if (initialised) return
        initialised = true
        val fb = auth ?: return
        applyUser(fb.currentUser)
        fb.addAuthStateListener { changed ->
            // External changes (token revocation, user deleted
            // elsewhere) re-align the same way local actions do.
            applyUser(changed.currentUser)
        }
        // Cold start with a persisted session: if the sign-in that
        // created it never got its guest-move offer answered (the
        // process died first), the offer is still owed — evaluate it
        // now. A recorded outcome keeps this silent (no re-nagging).
        fb.currentUser?.let { user ->
            scope.launch { evaluateGuestOffer(user.uid) }
        }
    }

    private fun applyUser(user: FirebaseUser?) {
        if (user == null) {
            _session.value = Session.Guest
            _guestMoveOffer.value = null
            provider.switchTo(NotaDatabase.GUEST_ACCOUNT)
        } else {
            _session.value = user.toSession()
            provider.switchTo(user.uid)
        }
    }

    // ------------------------------------------------------------ sign-in

    /** Completes a Google sign-in with the Credential Manager ID
     *  token (the UI layer obtains it — Credential Manager needs an
     *  Activity; the token exchange itself is pure Firebase). */
    suspend fun signInWithGoogle(idToken: String): Result<Session> {
        val fb = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val user = fb.signInWithCredential(credential).awaitTask().user
                ?: return Result.failure(IllegalStateException("Sign-in failed. Try again."))
            applyUser(user)
            evaluateGuestOffer(user.uid)
            Result.success(user.toSession())
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Session> {
        val fb = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        return try {
            val user = fb.signInWithEmailAndPassword(email.trim(), password).awaitTask().user
                ?: return Result.failure(IllegalStateException("Sign-in failed. Try again."))
            applyUser(user)
            evaluateGuestOffer(user.uid)
            Result.success(user.toSession())
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Session> {
        val fb = auth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED))
        return try {
            val user = fb.createUserWithEmailAndPassword(email.trim(), password).awaitTask().user
                ?: return Result.failure(IllegalStateException("Sign-up failed. Try again."))
            applyUser(user)
            evaluateGuestOffer(user.uid)
            Result.success(user.toSession())
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e)))
        }
    }

    suspend fun signOut() {
        auth?.signOut()
        _guestMoveOffer.value = null
        applyUser(null)
    }

    // ------------------------------------------------------------ guest move

    /** Recomputes the offer for [uid] (sign-in paths + cold start). */
    suspend fun evaluateGuestOffer(uid: String) {
        val outcome = preferences.getGuestMoveOutcome(uid)
        val count = runCatching { transfer.guestNoteCount() }.getOrDefault(0)
        _guestMoveOffer.value =
            if (GuestMovePolicy.shouldOffer(count, outcome)) uid else null
    }

    /** The user's "bring my guest notes in" tap. Copy only — the
     *  guest library stays exactly as it was (see GuestLibraryTransfer).
     *  A failed copy (e.g. an attachment binary that can't be
     *  duplicated) aborts before anything is written: no outcome is
     *  recorded, so the offer can be retried — from here on a later
     *  sign-in, or right away from Settings → Account. */
    suspend fun acceptGuestMove() {
        val uid = _guestMoveOffer.value ?: return
        val counts = runCatching { transfer.copyGuestIntoCurrentAccount() }
            .getOrElse {
                _guestMoveOffer.value = null
                _guestMoveResult.value =
                    "The copy couldn't finish, so nothing was brought in and your " +
                        "guest library is unchanged. You can try again from " +
                        "Settings → Account."
                return
            }
        preferences.setGuestMoveOutcome(uid, GuestMovePolicy.OUTCOME_COPIED)
        _guestMoveOffer.value = null
        _guestMoveResult.value = if (counts.isEmpty) {
            "Nothing new to bring in — your guest notes were already here."
        } else {
            "Brought in ${counts.notes} notes, ${counts.folders} folders, " +
                "${counts.tags} tags and ${counts.attachments} attachments. " +
                "Your guest library is unchanged."
        }
    }

    suspend fun declineGuestMove() {
        val uid = _guestMoveOffer.value ?: return
        preferences.setGuestMoveOutcome(uid, GuestMovePolicy.OUTCOME_DECLINED)
        _guestMoveOffer.value = null
    }

    /**
     * Manual re-entry (Settings → Account row): the user explicitly
     * asking to see the offer again is not a nag, so a recorded
     * decline does not block it. Still requires a non-empty guest
     * library — copying nothing is not offered.
     */
    suspend fun requestGuestMove() {
        val current = _session.value as? Session.SignedIn ?: return
        if (runCatching { transfer.guestNoteCount() }.getOrDefault(0) > 0) {
            _guestMoveOffer.value = current.uid
        }
    }

    fun acknowledgeGuestMoveResult() {
        _guestMoveResult.value = null
    }

    // ------------------------------------------------------------ helpers

    private fun FirebaseUser.toSession(): Session.SignedIn = Session.SignedIn(
        uid = uid,
        displayName = displayName,
        email = email,
        photoUrl = photoUrl?.toString(),
    )

    private fun friendlyError(e: Exception): String = when (e) {
        is FirebaseAuthInvalidUserException ->
            "No account found for that email — create one instead?"
        is FirebaseAuthInvalidCredentialsException ->
            "That email or password doesn't match. Check both and try again."
        is FirebaseAuthUserCollisionException ->
            "An account already exists for that email — sign in instead."
        is FirebaseAuthWeakPasswordException ->
            "That password is too weak — use at least 6 characters."
        else -> e.message ?: "Sign-in failed. Check your connection and try again."
    }

    companion object {
        const val NOT_CONFIGURED =
            "Accounts aren't configured in this build (missing Firebase config)."
    }
}

/** Minimal Tasks await (avoids a coroutines-play-services dependency
 *  for three call sites). Cancellation does not cancel the Firebase
 *  task — it only stops waiting for it, which is the safe semantic
 *  for auth operations. */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resume(task.result)
        } else {
            cont.resumeWithException(
                task.exception ?: IllegalStateException("Unknown Firebase error"),
            )
        }
    }
}
