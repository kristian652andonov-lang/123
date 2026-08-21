package com.kristian.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

/**
 * First-launch key entry. Shown until a key is stored, and reachable again
 * from settings if it needs replacing.
 */
@Composable
fun ApiKeyScreen(
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    existingKeyMasked: String? = null,
    errorMessage: String? = null
) {
    var key by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisPalette.Void)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            HudDial(state = AssistantState.IDLE, size = 150.dp)
        }

        Text(
            text = "J A R V I S",
            style = MaterialTheme.typography.titleLarge,
            color = JarvisPalette.TextPrimary
        )

        Text(
            text = "I need an API key before I can think, sir.",
            style = MaterialTheme.typography.bodyMedium,
            color = JarvisPalette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )

        Text(
            text = "Free: open aistudio.google.com, sign in with any Google account, " +
                "tap Get API key. No card, no credits. The key starts with AIza.",
            style = MaterialTheme.typography.bodySmall,
            color = JarvisPalette.TextPrimary.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )

        Text(
            text = "Paid: an Anthropic key from console.anthropic.com (sk-ant-…) uses Claude " +
                "instead. Paste either one - I'll work out which it is.",
            style = MaterialTheme.typography.bodySmall,
            color = JarvisPalette.TextSecondary.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(
            text = "Stored encrypted on this device in the Android Keystore, never logged, " +
                "and sent only to the provider it belongs to. Note that Google's free tier " +
                "may use your prompts to improve their products.",
            style = MaterialTheme.typography.bodySmall,
            color = JarvisPalette.TextSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        existingKeyMasked?.let {
            Text(
                text = "Currently stored: $it",
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.Cyan,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                localError = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            label = {
                Text(
                    text = "AIza… or sk-ant-…",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextSecondary
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (revealed) "Hide key" else "Show key",
                        tint = JarvisPalette.TextSecondary
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JarvisPalette.Cyan,
                unfocusedBorderColor = JarvisPalette.PanelEdge,
                focusedContainerColor = JarvisPalette.Panel.copy(alpha = 0.6f),
                unfocusedContainerColor = JarvisPalette.Panel.copy(alpha = 0.4f),
                cursorColor = JarvisPalette.Cyan,
                focusedTextColor = JarvisPalette.TextPrimary,
                unfocusedTextColor = JarvisPalette.TextPrimary
            )
        )

        (localError ?: errorMessage)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = JarvisPalette.Danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Button(
            onClick = {
                val trimmed = key.trim()
                if (!SecureKeyStore.looksLikeAnyKey(trimmed)) {
                    localError = "That doesn't look like either kind of key - " +
                        "Google keys start with AIza, Anthropic keys with sk-ant-."
                } else {
                    onSave(trimmed)
                }
            },
            enabled = key.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = JarvisPalette.Cyan.copy(alpha = 0.16f),
                contentColor = JarvisPalette.CyanBright,
                disabledContainerColor = JarvisPalette.Panel,
                disabledContentColor = JarvisPalette.TextSecondary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 28.dp)
        ) {
            Text(text = "ENGAGE", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D, widthDp = 400, heightDp = 860)
@Composable
private fun ApiKeyScreenPreview() {
    JarvisTheme { ApiKeyScreen(onSave = {}) }
}
