package com.smartspend.app.ui.addtransaction

import com.google.gson.JsonParser
import com.smartspend.app.RetrofitClient
import com.smartspend.app.TransactionCreatePayload
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.money
import kotlinx.coroutines.delay
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import kotlin.math.abs

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/**
 * Creates a manual transaction and reports what actually happened on the server.
 *
 * A timeout or 5xx doesn't mean "not saved": a slow server (e.g. a Render instance waking up)
 * can finish the insert after the app has stopped waiting. That is what produced "couldn't
 * save" for rows that were in fact stored. So on an unclear result we look for the row before
 * telling the user anything, and only report failure when it genuinely isn't there.
 */
suspend fun submitTransaction(payload: TransactionCreatePayload, day: LocalDate): SaveOutcome {
    val response: Response<*>? = try {
        RetrofitClient.apiService.createTransactionNoAuth(payload)
    } catch (_: IOException) {
        null // timeout / dropped connection — outcome unknown
    }

    return when {
        response?.isSuccessful == true -> SaveOutcome.Saved

        response == null || response.code() >= 500 ->
            if (landed(payload, day)) SaveOutcome.Saved
            else SaveOutcome.Failed("Couldn't reach SmartSpend, so nothing was saved. Check your connection and try again.")

        // The backend fingerprints user + amount + day + card, so a second manual entry with
        // the same amount on the same day is rejected as a duplicate.
        response.code() == 409 ->
            SaveOutcome.Failed("You already have ${money(payload.amount)} on this date — SmartSpend treats a second one as a duplicate.")

        response.code() == 401 -> SaveOutcome.Failed("Your session expired. Sign in again to save.")

        else -> SaveOutcome.Failed(serverDetail(response) ?: "Couldn't save (error ${response.code()}).")
    }
}

/** Whether a matching manual row exists — checked twice, since a slow insert may still be landing. */
private suspend fun landed(payload: TransactionCreatePayload, day: LocalDate): Boolean {
    repeat(2) { attempt ->
        if (attempt > 0) delay(2500)
        val found = runCatching {
            SpendData.transactions(day, day, type = payload.type, maxPages = 3).any { tx ->
                abs(tx.amount - payload.amount) < 0.005 &&
                    tx.merchant.orEmpty().trim().equals(payload.merchant.orEmpty().trim(), ignoreCase = true)
            }
        }.getOrDefault(false)
        if (found) return true
    }
    return false
}

/** FastAPI puts the reason in `detail` — a string, or a list of validation errors for a 422. */
private fun serverDetail(response: Response<*>): String? = runCatching {
    val body = response.errorBody()?.string().orEmpty()
    val detail = JsonParser.parseString(body).asJsonObject.get("detail")
    when {
        detail == null -> null
        detail.isJsonPrimitive -> detail.asString
        detail.isJsonArray -> detail.asJsonArray.firstOrNull()?.asJsonObject?.get("msg")?.asString
        else -> null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
