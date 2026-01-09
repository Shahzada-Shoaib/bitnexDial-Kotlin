package com.bitnextechnologies.bitnexdial.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Generic confirmation dialog with customizable title, message, and button text.
 * Use this for simple confirmation dialogs to maintain consistency across the app.
 *
 * @param title The dialog title
 * @param message Primary message to display
 * @param secondaryMessage Optional secondary message (displayed below primary with spacing)
 * @param confirmButtonText Text for the confirm button
 * @param dismissButtonText Text for the dismiss button
 * @param onConfirm Callback when confirm button is clicked
 * @param onDismiss Callback when dismiss button is clicked or dialog is dismissed
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    secondaryMessage: String? = null,
    confirmButtonText: String = "OK",
    dismissButtonText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                secondaryMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissButtonText)
            }
        }
    )
}

/**
 * Generic confirmation dialog with custom content.
 * Use this when you need more complex content than simple text messages.
 *
 * @param title The dialog title
 * @param confirmButtonText Text for the confirm button
 * @param dismissButtonText Text for the dismiss button
 * @param onConfirm Callback when confirm button is clicked
 * @param onDismiss Callback when dismiss button is clicked or dialog is dismissed
 * @param content Composable content for the dialog body
 */
@Composable
fun ConfirmationDialogWithContent(
    title: String,
    confirmButtonText: String = "OK",
    dismissButtonText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { content() },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissButtonText)
            }
        }
    )
}

/**
 * Simple info dialog with only a dismiss button.
 * Use for displaying information that doesn't require a decision.
 *
 * @param title The dialog title
 * @param message The message to display
 * @param buttonText Text for the button
 * @param onDismiss Callback when button is clicked or dialog is dismissed
 */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    buttonText: String = "OK",
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(buttonText)
            }
        }
    )
}

/**
 * Destructive action confirmation dialog with emphasized warning styling.
 * Use for actions that cannot be undone (delete, logout, etc.)
 *
 * @param title The dialog title
 * @param message Warning message to display
 * @param confirmButtonText Text for the destructive action button
 * @param dismissButtonText Text for the cancel button
 * @param onConfirm Callback when destructive action is confirmed
 * @param onDismiss Callback when action is cancelled
 */
@Composable
fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    confirmButtonText: String = "Delete",
    dismissButtonText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmButtonText,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissButtonText)
            }
        }
    )
}
