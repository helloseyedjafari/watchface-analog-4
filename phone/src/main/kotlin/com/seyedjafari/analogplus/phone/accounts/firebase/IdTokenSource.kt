package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The person has no Google account on this phone. */
class NoGoogleAccountException : Exception("No Google account found on this phone.")

/** Something that can hand Firebase a Google ID token: the real Google chooser, or a fake one for the Auth emulator. */
fun interface IdTokenSource {
    /** The ID token, or null when the person closed the chooser. Throws [NoGoogleAccountException] when there is no account. */
    suspend fun fetchIdToken(): String?
}

/**
 * "Sign in with Google" through Credential Manager. [activity] must return an Activity context (the chooser is a UI).
 *
 * NOT VERIFIED in this repository: the chooser needs a real Google account on a device, a Firebase project with Google
 * sign-in enabled and the app's SHA-1 registered. The code follows the documented `GetSignInWithGoogleOption` flow only.
 */
class GoogleIdTokenSource(
    private val credentialManager: CredentialManager,
    private val webClientId: String,
    private val activity: () -> Context?,
) : IdTokenSource {
    override suspend fun fetchIdToken(): String? {
        val context = activity() ?: throw IllegalStateException("The app is not on screen.")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        val credential = try {
            credentialManager.getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw NoGoogleAccountException()
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Unexpected credential type ${credential.type}")
    }
}

/**
 * For the Firebase Auth emulator only: it accepts a JSON string as a Google ID token, so no Google account or chooser is
 * needed. [AccountsFactory][com.seyedjafari.analogplus.phone.accounts.AccountsFactory] hands this source out in emulator
 * mode, which release builds never have. The `sub` is the part of the address before the `@`, so one address is one account.
 */
class EmulatorIdTokenSource(private val email: () -> String) : IdTokenSource {
    override suspend fun fetchIdToken(): String? {
        val address = email()
        return buildJsonObject {
            put("sub", address.substringBefore('@'))
            put("email", address)
            put("email_verified", true)
        }.toString()
    }
}
