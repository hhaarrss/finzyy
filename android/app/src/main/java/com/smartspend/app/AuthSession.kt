package com.smartspend.app

import android.content.Context
import android.content.SharedPreferences

/**
 * The signed-in session as stored on the device. Everything else (network interceptor,
 * SmsSyncWorker, Account screen) keeps reading the same `smart_spend_prefs` keys.
 */
object AuthSession {
    private const val PREFS = "smart_spend_prefs"
    const val KEY_TOKEN = "jwt_token"
    private const val KEY_EMAIL = "user_email"
    private const val KEY_PHONE = "user_phone"
    private const val KEY_PHONE_VERIFIED = "user_phone_verified"
    private const val KEY_NAME = "user_name"
    private const val KEY_PROFILE_COMPLETE = "profile_complete"
    private const val KEY_PHONE_PROMPTED = "link_phone_prompted"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, auth: AuthResponse) {
        prefs(context).edit()
            .putString(KEY_TOKEN, LocalCrypto.encrypt(auth.access_token))
            .putString(KEY_EMAIL, auth.email)
            .putString(KEY_PHONE, auth.phone_number)
            .putBoolean(KEY_PHONE_VERIFIED, auth.phone_number != null)
            .putString(KEY_NAME, auth.full_name?.takeIf { it.isNotBlank() })
            .putBoolean(KEY_PROFILE_COMPLETE, auth.profile_complete)
            .commit()
    }

    /** Keeps the cached copy in step after the profile is saved or a phone is linked. */
    fun update(context: Context, user: UserData) {
        prefs(context).edit()
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_PHONE, user.phone_number)
            .putBoolean(KEY_PHONE_VERIFIED, user.phone_number != null)
            .putString(KEY_NAME, user.full_name?.takeIf { it.isNotBlank() })
            .putBoolean(KEY_PROFILE_COMPLETE, user.profile_complete)
            .apply()
    }

    fun isSignedIn(context: Context): Boolean = !prefs(context).getString(KEY_TOKEN, null).isNullOrEmpty()

    /** null = not known yet (signed in before profiles existed); ask the backend. */
    fun profileComplete(context: Context): Boolean? =
        prefs(context).takeIf { it.contains(KEY_PROFILE_COMPLETE) }?.getBoolean(KEY_PROFILE_COMPLETE, false)

    fun hasPhone(context: Context): Boolean = !prefs(context).getString(KEY_PHONE, null).isNullOrBlank()

    /** Accounts without a phone are asked to add one once; they can skip. */
    fun shouldPromptPhoneLink(context: Context): Boolean =
        !hasPhone(context) && !prefs(context).getBoolean(KEY_PHONE_PROMPTED, false)

    fun markPhonePrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PHONE_PROMPTED, true).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EMAIL)
            .remove(KEY_PHONE)
            .remove(KEY_PHONE_VERIFIED)
            .remove(KEY_NAME)
            .remove(KEY_PROFILE_COMPLETE)
            .remove(KEY_PHONE_PROMPTED)
            .apply()
    }
}
