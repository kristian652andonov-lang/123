package com.kristian.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                BootScreen()
            }
        }
    }
}

/** Placeholder shell. The real HUD lands in the next step. */
@Composable
private fun BootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisPalette.Void),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "J A R V I S",
            style = MaterialTheme.typography.titleLarge,
            color = JarvisPalette.Cyan,
            textAlign = TextAlign.Center
        )
    }
}

@Preview
@Composable
private fun BootScreenPreview() {
    JarvisTheme { BootScreen() }
}
