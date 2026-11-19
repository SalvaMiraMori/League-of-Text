package com.leagueoftext.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

sealed interface SignInResult {
    data class Success(val user: FirebaseUser) : SignInResult
    data object Cancelled : SignInResult
    data object MissingConfiguration : SignInResult
    data class Failure(val error: Throwable) : SignInResult
}

/**
 * Inici de sessió amb Google mitjançant Credential Manager i Firebase Authentication.
 *
 * La versió anterior feia servir l'API `GoogleSignIn` (obsoleta), no autenticava contra
 * Firebase i tenia els *client secrets* d'OAuth escrits a `strings.xml`. Ara l'id de client
 * web (`default_web_client_id`) el genera el plugin de Google Services a partir de
 * `google-services.json`, i a l'app no hi ha cap secret.
 */
class AuthRepository(private val auth: FirebaseAuth) {

    private val _user = MutableStateFlow(auth.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    init {
        auth.addAuthStateListener { _user.value = it.currentUser }
    }

    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val clientId = webClientId(activityContext) ?: return SignInResult.MissingConfiguration
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignInResult.Failure(IllegalStateException("Credencial inesperada: ${credential.type}"))
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            result.user?.let { SignInResult.Success(it) }
                ?: SignInResult.Failure(IllegalStateException("Firebase no ha retornat cap usuari"))
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Error de Credential Manager", e)
            SignInResult.Failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Error iniciant sessió", e)
            SignInResult.Failure(e)
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** `default_web_client_id` només existeix si `google-services.json` té un client OAuth web. */
    @SuppressLint("DiscouragedApi")
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
