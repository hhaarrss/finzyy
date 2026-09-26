package com.smartspend.app

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Where the network layer reads the signed-in user's token from.
 *
 * The screens were written against the dev stub backend and call many endpoints without an
 * Authorization header, which only worked while AUTH_STUB=true. Rather than threading the
 * token through every call site, [AuthHeaderInterceptor] fills it in for any request that
 * doesn't already carry one. Requests that set their own header (SmsReceiver, login) pass
 * through untouched.
 */
object SessionStore {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY_TOKEN = "jwt_token"

    @Volatile
    private var appContext: Context? = null

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the backend rejects the stored token, so the UI can send the user back to login. */
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun token(): String? = appContext?.let { token(it) }

    /** Last stored value seen and its decrypted form, so each request doesn't hit the Keystore. */
    @Volatile
    private var cache: Pair<String, String>? = null

    /**
     * The signed-in user's token, or null. It is stored encrypted ([LocalCrypto]); a copy left
     * unencrypted by an older build is re-saved encrypted the first time it is read.
     */
    fun token(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        cache?.let { (s, plain) -> if (s == stored) return plain }
        val plain = LocalCrypto.decrypt(stored)?.takeIf { it.isNotBlank() } ?: return null
        if (!LocalCrypto.isEncrypted(stored)) {
            val encrypted = LocalCrypto.encrypt(plain)
            if (LocalCrypto.isEncrypted(encrypted)) {
                prefs.edit().putString(KEY_TOKEN, encrypted).apply()
                cache = encrypted to plain
                return plain
            }
        }
        cache = stored to plain
        return plain
    }

    internal fun reportUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}

class AuthHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val existing = original.header("Authorization")
        val token = SessionStore.token()

        // Screens pass "Bearer " (or nothing) when they had no token in hand at call time.
        val needsToken = existing.isNullOrBlank() || existing.trim() == "Bearer"
        val request = if (needsToken && token != null) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }

        val response = chain.proceed(request)
        val sentToken = !request.header("Authorization").isNullOrBlank()
        val isAuthEndpoint = request.url.encodedPath.contains("/auth/")
        if (response.code == 401 && sentToken && !isAuthEndpoint) {
            SessionStore.reportUnauthorized()
        }
        return response
    }
}
