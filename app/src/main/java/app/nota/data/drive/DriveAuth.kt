package app.nota.data.drive

import android.content.Context
import android.content.IntentSender
import app.nota.data.auth.awaitTask
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A Drive failure with user-presentable copy (never a raw SDK string). */
class DriveError(message: String) : Exception(message)

/**
 * The Drive consent step needs the user: GMS returned a resolution
 * intent instead of a token. The UI launches [intentSender] and, on
 * success, retries — the token then comes back directly. Carrying the
 * sender (not an Activity) keeps this layer UI-free.
 */
class DriveAuthNeededException(val intentSender: IntentSender) :
    Exception("Drive access needs your consent.")

/**
 * Google Drive authorization (Phase 3b), mirroring 1.x: the MODERN
 * AuthorizationClient flow (play-services-auth) with the **drive.file**
 * scope ONLY — the app can see and touch just the files it creates,
 * nothing else in the user's Drive. No token is ever stored by Nota:
 * GMS brokers access per call, keyed to the signed-in Google account,
 * so sign-out / account switch can never leak one account's Drive
 * access into another (the 1.x lesson — no credential material
 * outlives its connection).
 */
open class DriveAuth(private val context: Context) {

    open suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request).awaitTask()
        } catch (e: Exception) {
            throw DriveError("Couldn't reach Google Drive. Check your connection and try again.")
        }
        result.pendingIntent?.let { throw DriveAuthNeededException(it.intentSender) }
        result.accessToken
            ?: throw DriveError("Google didn't grant Drive access. Try connecting again.")
    }

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}
