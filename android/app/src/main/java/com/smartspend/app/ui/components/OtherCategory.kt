package com.smartspend.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.json.JSONArray

/*
 * Naming your own category.
 *
 * Picking "Other" for a payment opens a field where the user can type what it really was
 * ("Pets", "Gym"). The payment is then saved under that name. The server stores a category as
 * a plain name, so a typed one behaves like a built-in one everywhere: totals, the category
 * screens, budgets, and the "file this merchant the same way next time" learning.
 *
 * The names a user has typed are remembered on this phone (per account) so they show up as
 * chips next time. That list is a convenience only: the payments themselves are on the server.
 */

const val MAX_CUSTOM_CATEGORY_LENGTH = 30
private const val MAX_REMEMBERED = 20

/** "Other" and "Transfer" stay last in a spending picker; remembered names go before them. */
private val CatchAlls = listOf("Transfer", "Other")

/**
 * What to save for the text typed under "Other".
 *  - nothing typed            -> null (keep it under Other)
 *  - a built-in category name -> that built-in, in its own spelling ("food & dining" is not new)
 *  - anything else            -> the name, tidied
 */
fun customCategoryFor(typed: String, builtIn: List<String>): String? {
    val name = typed.trim().replace(Regex("\\s+"), " ").take(MAX_CUSTOM_CATEGORY_LENGTH).trim()
    if (name.length < 2) return null
    builtIn.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
    if (name.equals("Needs Review", ignoreCase = true) || name.equals("Miscellaneous", ignoreCase = true)) return null
    return name
}

/** Remembered names slotted in before Transfer and Other, without repeating a built-in one. */
fun List<String>.withCustomCategories(custom: List<String>): List<String> {
    val extra = custom.filter { c -> none { it.equals(c, ignoreCase = true) } }.distinctBy { it.lowercase() }
    if (extra.isEmpty()) return this
    return filterNot { it in CatchAlls } + extra + filter { it in CatchAlls }
}

/** The names this user has typed before, kept on the phone. Compose state, so pickers update at once. */
object CustomCategoryStore {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY = "custom_category_names"
    private const val KEY_OWNER = "custom_category_owner"

    var names by mutableStateOf<List<String>>(emptyList())
        private set

    private fun owner(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getString("user_phone", null) ?: prefs.getString("user_email", null))?.takeIf { it.isNotBlank() }
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedFor = prefs.getString(KEY_OWNER, null)
        val me = owner(context)
        names = if (savedFor != null && me != null && savedFor != me) emptyList() else read(prefs.getString(KEY, null))
    }

    /** Adds [name] (most recent last). A name already there, in any case, is not added twice. */
    fun remember(context: Context, name: String) {
        load(context)
        if (names.any { it.equals(name, ignoreCase = true) }) return
        names = (names + name).takeLast(MAX_REMEMBERED)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(names).toString())
            .putString(KEY_OWNER, owner(context))
            .apply()
    }

    private fun read(json: String?): List<String> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

@Composable
fun rememberCustomCategories(): List<String> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CustomCategoryStore.load(context) }
    return CustomCategoryStore.names
}

/** The field shown under the chips once "Other" is picked. */
@Composable
fun OtherCategoryField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_CUSTOM_CATEGORY_LENGTH)) },
        label = { Text("Name this category") },
        placeholder = { Text("e.g. Pets, Gym, Gifts") },
        singleLine = true,
        supportingText = {
            Text(
                if (value.isBlank()) "Optional. Leave empty to keep it under Other."
                else "${value.length} / $MAX_CUSTOM_CATEGORY_LENGTH · saved as its own category"
            )
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    )
}
