package com.smartspend.app.ui.home

import android.content.Context
import com.google.gson.Gson
import com.smartspend.app.HomeData
import com.smartspend.app.LocalCrypto

/**
 * The last Home screen the server sent, kept on the phone so Home can appear at once.
 *
 * The server sleeps when idle and can take up to a minute to answer the first request; with
 * this the user sees their last numbers straight away while fresh ones load. The saved copy is
 * encrypted like the login token (Android Keystore), belongs to one account, and is never
 * written at all if encryption is unavailable on the device.
 */
internal object HomeCache {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY_DATA = "home_cache_v1"
    private const val KEY_BUDGET = "home_cache_budget"
    private const val KEY_OWNER = "home_cache_owner"
    private const val KEY_SAVED_AT = "home_cache_saved_at"

    /** Older than this and the numbers are too stale to show as "your Home". */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = Gson()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun owner(context: Context): String? {
        val p = prefs(context)
        return (p.getString("user_phone", null) ?: p.getString("user_email", null))?.takeIf { it.isNotBlank() }
    }

    /** The saved Home and overall budget for the signed-in account, or null. */
    fun read(context: Context): Pair<HomeData, Double?>? = runCatching {
        val p = prefs(context)
        val me = owner(context) ?: return null
        if (p.getString(KEY_OWNER, null) != me) return null
        if (System.currentTimeMillis() - p.getLong(KEY_SAVED_AT, 0L) > MAX_AGE_MS) return null
        val json = LocalCrypto.decrypt(p.getString(KEY_DATA, null)) ?: return null
        val home = gson.fromJson(json, HomeData::class.java) ?: return null
        val budget = p.getString(KEY_BUDGET, null)?.toDoubleOrNull()
        home to budget
    }.getOrNull()

    fun write(context: Context, home: HomeData, overallBudget: Double?) {
        runCatching {
            val me = owner(context) ?: return
            val encrypted = LocalCrypto.encrypt(gson.toJson(home))
            // Financial figures are only kept when they can be kept encrypted.
            if (!LocalCrypto.isEncrypted(encrypted)) return
            prefs(context).edit()
                .putString(KEY_DATA, encrypted)
                .putString(KEY_BUDGET, overallBudget?.toString())
                .putString(KEY_OWNER, me)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_DATA).remove(KEY_BUDGET).remove(KEY_OWNER).remove(KEY_SAVED_AT)
            .apply()
    }
}
