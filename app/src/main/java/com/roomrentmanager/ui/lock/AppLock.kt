package com.roomrentmanager.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * When to ask for the phone's lock (fingerprint, face or screen PIN). Pure, for testing.
 * The app locks on a cold start and after it has been in the background for [timeoutMillis].
 */
object AppLockPolicy {
    const val DEFAULT_TIMEOUT_MILLIS = 30_000L

    fun lockOnStart(enabled: Boolean): Boolean = enabled

    fun lockOnReturn(enabled: Boolean, backgroundedAtMillis: Long?, nowMillis: Long, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean =
        enabled && backgroundedAtMillis != null && nowMillis - backgroundedAtMillis >= timeoutMillis
}

/** Shows [content] only when unlocked. While locked nothing of the app's data is composed. */
@Composable
fun AppLockGate(locked: Boolean, onUnlockRequest: () -> Unit, content: @Composable () -> Unit) {
    if (!locked) {
        content()
        return
    }
    LaunchedEffect(Unit) { onUnlockRequest() }
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Room Rent Manager is locked", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Use your fingerprint, face or screen lock to open it.", textAlign = TextAlign.Center)
                Button(onClick = onUnlockRequest) { Text("Unlock") }
            }
        }
    }
}
