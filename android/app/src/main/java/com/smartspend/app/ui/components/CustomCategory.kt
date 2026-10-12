package com.smartspend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.CategoryListsResponse
import com.smartspend.app.CustomCategory
import com.smartspend.app.CustomCategoryPayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.auth.serverMessage
import com.smartspend.app.ui.theme.CategoryIcon
import com.smartspend.app.ui.theme.FinzyyTheme
import kotlinx.coroutines.launch

/*
 * The user's own spending categories ("Pets", "Gym"), so a payment that fits none of the
 * built-in ones doesn't have to go under "Other".
 *
 * The server keeps the list and returns the names inside the ordinary spending list, so every
 * picker shows them without special handling. This file adds the two things a picker needs on
 * top: the chip that opens the dialog, and the dialog that creates or deletes a category.
 */

/** Same limit the server enforces; shown as a counter so the limit is not a surprise. */
private const val MAX_NAME_LENGTH = 30

/** The last chip in a spending picker. */
@Composable
fun NewCategoryChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Chip(
        label = "New category",
        selected = false,
        onClick = onClick,
        modifier = modifier,
        leading = {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = FinzyyTheme.colors.accent, modifier = Modifier.size(16.dp))
        }
    )
}

/**
 * Create a category, or delete one of your own.
 *
 * [onCreated] gets the new name once the server has saved it, so the caller can file the
 * payment under it straight away. [onListsChanged] gets the refreshed lists after any change.
 */
@Composable
fun CustomCategoryDialog(
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
    onListsChanged: (CategoryListsResponse) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf(CategoryCache.lists?.custom.orEmpty()) }
    var confirmDelete by remember { mutableStateOf<CustomCategory?>(null) }

    suspend fun reload() {
        runCatching { CategoryCache.refresh() }.getOrNull()?.let {
            mine = it.custom.orEmpty()
            onListsChanged(it)
        }
    }

    fun create() {
        val wanted = name.trim().replace(Regex("\\s+"), " ")
        if (wanted.length < 2 || busy) {
            if (wanted.length < 2) error = "Give the category a name of at least 2 characters."
            return
        }
        busy = true
        error = null
        scope.launch {
            val response = runCatching {
                RetrofitClient.apiService.createCustomCategory(CustomCategoryPayload(wanted))
            }.getOrNull()
            val created = response?.body()
            when {
                response == null -> error = "Can't reach Finzyy. Check your internet and try again."
                response.isSuccessful && created != null -> {
                    reload()
                    onCreated(created.name)
                    onDismiss()
                }
                else -> error = serverMessage(response, "Couldn't add the category (${response.code()}).")
            }
            busy = false
        }
    }

    fun delete(category: CustomCategory) {
        busy = true
        error = null
        scope.launch {
            val response = runCatching { RetrofitClient.apiService.deleteCustomCategory(category.id) }.getOrNull()
            when {
                response == null -> error = "Can't reach Finzyy. Check your internet and try again."
                response.isSuccessful -> {
                    reload()
                    // Payments filed under it are now under "Other" on the server.
                    com.smartspend.app.TransactionEvents.notifyChanged()
                }
                else -> error = serverMessage(response, "Couldn't delete the category (${response.code()}).")
            }
            confirmDelete = null
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH); error = null },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Pets, Gym, Gifts") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "${name.length} / $MAX_NAME_LENGTH · for spending, not income")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )

                if (mine.isNotEmpty()) {
                    Eyebrow("Your categories")
                    Column(
                        Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        mine.forEach { category ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(CategoryIcon(category.name), contentDescription = null, tint = FinzyyTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    category.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = { confirmDelete = category }, enabled = !busy) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Delete ${category.name}", tint = FinzyyTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { create() }, enabled = !busy) { Text(if (busy) "Saving…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )

    confirmDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete \"${category.name}\"?") },
            text = {
                Text("Payments filed under it move to Other. Its budget is removed, and merchants you filed there go back to being sorted automatically.")
            },
            confirmButton = {
                TextButton(onClick = { delete(category) }, enabled = !busy) {
                    Text("Delete", color = FinzyyTheme.colors.negative)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }, enabled = !busy) { Text("Keep") }
            }
        )
    }
}
