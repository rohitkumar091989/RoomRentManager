package com.roomrentmanager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.roomrentmanager.worker.ReminderScheduler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: RentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.scheduleDaily(this)
        setContent {
            RentTheme {
                RentApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Post any rent that fell due while the app was in the background (idempotent).
        vm.refreshCharges()
    }
}

private val Green = Color(0xFF2E5E4E)
private val LightGreen = Color(0xFF8FD1B5)

@Composable
fun RentTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = LightGreen, secondary = LightGreen)
    } else {
        lightColorScheme(primary = Green, secondary = Green)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
