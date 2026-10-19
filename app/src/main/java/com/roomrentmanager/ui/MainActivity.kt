package com.roomrentmanager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RentTheme {
                RentApp()
            }
        }
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
