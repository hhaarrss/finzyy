package com.smartspend.app.ui.auth

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcomes of one verifyPhoneNumber() call. */
internal sealed interface PhoneVerificationEvent {
    /** The device confirmed the number itself (instant verification / SMS auto-read). */
    data class AutoVerified(val credential: PhoneAuthCredential) : PhoneVerificationEvent
    data class CodeSent(
        val verificationId: String,
        val resendToken: PhoneAuthProvider.ForceResendingToken
    ) : PhoneVerificationEvent
    data class Failed(val message: String) : PhoneVerificationEvent
}

/**
 * Coroutine wrapper around Firebase's callback-based Phone Auth API. Firebase sends the SMS and
 * checks the code; the app only ever passes the resulting ID token to the backend.
 */
internal object PhoneAuthController {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /**
     * Sends (or re-sends, when [resendToken] is given) the OTP to [phoneNumber] (E.164, e.g.
     * "+919876543210"). Emits CodeSent first, and may later emit AutoVerified if Android reads
     * the SMS by itself before the user types the code.
     */
    fun verify(
        activity: Activity,
        phoneNumber: String,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null
    ): Flow<PhoneVerificationEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                trySend(PhoneVerificationEvent.AutoVerified(credential))
                close()
            }

            override fun onVerificationFailed(exception: FirebaseException) {
                trySend(PhoneVerificationEvent.Failed(readableError(exception)))
                close()
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                // Stay open: auto-retrieval can still complete after the code is sent.
                trySend(PhoneVerificationEvent.CodeSent(verificationId, token))
            }

            override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                close()
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .apply { if (resendToken != null) setForceResendingToken(resendToken) }
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)

        awaitClose { }
    }

    fun credentialFor(verificationId: String, code: String): PhoneAuthCredential =
        PhoneAuthProvider.getCredential(verificationId, code)

    /** Signs in to Firebase with [credential] and returns a fresh Firebase ID token. */
    suspend fun signIn(credential: AuthCredential): Result<String> = try {
        val result = auth.signInWithCredential(credential).await()
        val idToken = result.user?.getIdToken(true)?.await()?.token
        if (idToken.isNullOrEmpty()) {
            Result.failure(IllegalStateException("Signed in, but Firebase didn't return a token. Try again."))
        } else {
            Result.success(idToken)
        }
    } catch (e: Exception) {
        Result.failure(Exception(readableError(e), e))
    }

    fun signOut() {
        runCatching { auth.signOut() }
    }

    fun readableError(e: Throwable): String = when {
        e is FirebaseAuthInvalidCredentialsException && e.errorCode == "ERROR_INVALID_VERIFICATION_CODE" ->
            "That code didn't match. Check the SMS and try again."
        e is FirebaseAuthInvalidCredentialsException && e.errorCode == "ERROR_SESSION_EXPIRED" ->
            "This code has expired. Tap Resend to get a new one."
        e is FirebaseAuthInvalidCredentialsException ->
            "That phone number doesn't look right. Check it and try again."
        e is FirebaseTooManyRequestsException ->
            "Too many attempts from this device. Please wait a while and try again."
        e.message?.contains("network", ignoreCase = true) == true ->
            "No internet connection. Check your network and try again."
        else -> e.message ?: "Something went wrong. Try again."
    }
}

/** Minimal Task.await() so we don't need kotlinx-coroutines-play-services for two calls. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resume(task.result)
        } else {
            cont.resumeWithException(task.exception ?: IllegalStateException("Task failed"))
        }
    }
}
