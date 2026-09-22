package com.smartspend.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspend.app.BuildConfig
import com.smartspend.app.DeleteAccountPayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch

@Composable
fun DeleteAccountDialog(
    jwtToken: String?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    var confirmationText by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val isDeleteTyped = confirmationText.trim().equals("DELETE", ignoreCase = true)

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) onDismiss()
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SmartSpendTheme.colors.negativeContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = SmartSpendTheme.colors.negative,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Delete My Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SmartSpendTheme.colors.negative
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "This action is permanent and cannot be undone. All your data will be permanently purged from our servers:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmartSpendTheme.colors.negativeContainer, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DeletedItemRow("All logged expense & income transactions")
                    DeletedItemRow("Monthly budget limits & alert settings")
                    DeletedItemRow("Learned merchant categorizations")
                    DeletedItemRow("Device notification tokens & profile data")
                }

                Text(
                    text = "Password (required for password accounts):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = SmartSpendTheme.colors.inkMuted
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    placeholder = { Text("Enter account password") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Type DELETE to confirm:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { confirmationText = it; errorMessage = null },
                    placeholder = { Text("DELETE") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isDeleteTyped) SmartSpendTheme.colors.negative else SmartSpendTheme.colors.inkMuted,
                        unfocusedBorderColor = SmartSpendTheme.colors.hairline
                    )
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = SmartSpendTheme.colors.negative,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isDeleteTyped || isLoading) return@Button
                    isLoading = true
                    errorMessage = null

                    scope.launch {
                        try {
                            val payload = DeleteAccountPayload(
                                confirmation_text = confirmationText.trim(),
                                password = password.takeIf { it.isNotBlank() }
                            )

                            val response = if (BuildConfig.DEV_SKIP_AUTH || jwtToken.isNullOrEmpty()) {
                                RetrofitClient.apiService.deleteMyAccountNoAuth(payload)
                            } else {
                                RetrofitClient.apiService.deleteMyAccount(
                                    token = "Bearer $jwtToken",
                                    payload = payload
                                )
                            }

                            if (response.isSuccessful) {
                                isLoading = false
                                onDeleted()
                            } else {
                                isLoading = false
                                val errorBody = response.errorBody()?.string() ?: ""
                                errorMessage = when {
                                    response.code() == 401 -> "Incorrect password. Please try again."
                                    response.code() == 400 -> "Please type DELETE exactly to confirm."
                                    errorBody.contains("detail") -> {
                                        errorBody.substringAfter("\"detail\":\"").substringBefore("\"")
                                    }
                                    else -> "Deletion failed (${response.code()}). Please try again."
                                }
                            }
                        } catch (e: Exception) {
                            isLoading = false
                            errorMessage = e.localizedMessage ?: "Network error. Please try again."
                        }
                    }
                },
                enabled = isDeleteTyped && !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartSpendTheme.colors.negative,
                    disabledContainerColor = SmartSpendTheme.colors.negative.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Deleting...", color = Color.White)
                } else {
                    Text("Delete My Account", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel", color = SmartSpendTheme.colors.inkMuted)
            }
        }
    )
}

@Composable
private fun DeletedItemRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = SmartSpendTheme.colors.negative, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
