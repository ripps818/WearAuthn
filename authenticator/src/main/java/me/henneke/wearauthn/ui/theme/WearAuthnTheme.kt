package me.henneke.wearauthn.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

@Composable
fun WearAuthnTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { dynamicColorScheme(context) ?: ColorScheme() }
    MaterialTheme(colorScheme = colors, content = content)
}
