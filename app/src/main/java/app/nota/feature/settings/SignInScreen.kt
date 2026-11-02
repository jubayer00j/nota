package app.nota.feature.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.nota.data.auth.AuthManager
import app.nota.notes.R
import app.nota.ui.theme.NotaSpacing
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Sign-in (Phase 3a) — the two identity doors 1.x has, in Nota's
 * design language: Google (Credential Manager) and email/password
 * (with a create-account mode). Firebase is identity only: signing
 * in simply opens the account's own database (see AuthManager); no
 * note content is involved, and there is nothing to configure.
 *
 * The screen lives INSIDE the nav shell, which MainActivity only
 * composes while the app is unlocked — sign-in can never render (or
 * be reached) behind the App Lock.
 */
@Composable
fun SignInScreen(
    onDone: () -> Unit,
    authManager: AuthManager = koinInject(),
) {
    val context = LocalContext.current
    val activity: Activity? = LocalActivity.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }

    fun finish(result: Result<*>) {
        busy = false
        result.fold(
            onSuccess = { onDone() },
            onFailure = { error = it.message ?: "Sign-in failed. Try again." },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NotaSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(NotaSpacing.lg))
        Image(
            painterResource(R.drawable.ic_nota_mark),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(NotaSpacing.md))
        Text(
            if (createAccount) "Create your account" else "Welcome back",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Your notes stay on this device. An account gives them " +
                "their own library here, and lets backups and sync use " +
                "your Google Drive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = NotaSpacing.xs),
        )
        Spacer(Modifier.height(NotaSpacing.lg))

        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(NotaSpacing.md))
        }

        Button(
            onClick = {
                val host = activity
                if (host == null) {
                    error = "Google sign-in isn't available here."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val option = GetGoogleIdOption.Builder()
                            .setServerClientId(
                                context.getString(R.string.default_web_client_id),
                            )
                            .setFilterByAuthorizedAccounts(false)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(option)
                            .build()
                        val result = credentialManager.getCredential(
                            request = request,
                            context = host,
                        )
                        val credential = result.credential
                        if (credential is CustomCredential &&
                            credential.type ==
                            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        ) {
                            val idToken =
                                GoogleIdTokenCredential.createFrom(credential.data).idToken
                            finish(authManager.signInWithGoogle(idToken))
                        } else {
                            busy = false
                            error = "That credential can't be used to sign in."
                        }
                    } catch (e: GetCredentialCancellationException) {
                        // The user backed out of the account picker —
                        // a cancellation is not an error.
                        busy = false
                    } catch (e: NoCredentialException) {
                        busy = false
                        error = "No Google account is available on this device. " +
                            "Add one in Android Settings, or use email below."
                    } catch (e: GetCredentialException) {
                        busy = false
                        error = "Google sign-in failed: ${e.message ?: "unknown error"}"
                    } catch (e: Exception) {
                        busy = false
                        error = e.message ?: "Google sign-in failed."
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue with Google") }

        Spacer(Modifier.height(NotaSpacing.md))
        Text(
            "or with email",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NotaSpacing.sm))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NotaSpacing.sm),
            )
        }
        Spacer(Modifier.height(NotaSpacing.md))
        Button(
            onClick = {
                if (email.isBlank() || password.isEmpty()) {
                    error = "Enter your email and password."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    finish(
                        if (createAccount) {
                            authManager.signUpWithEmail(email, password)
                        } else {
                            authManager.signInWithEmail(email, password)
                        },
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (createAccount) "Create account" else "Sign in") }

        OutlinedButton(
            onClick = {
                createAccount = !createAccount
                error = null
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (createAccount) {
                    "Already have an account? Sign in"
                } else {
                    "New to Nota? Create an account"
                },
            )
        }
        TextButton(onClick = onDone, enabled = !busy) { Text("Not now") }
    }
}
