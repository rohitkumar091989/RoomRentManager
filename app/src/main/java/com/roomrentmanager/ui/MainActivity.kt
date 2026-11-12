package com.roomrentmanager.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.roomrentmanager.AppContainer
import com.roomrentmanager.ui.lock.AppLockGate
import com.roomrentmanager.ui.lock.AppLockPolicy
import com.roomrentmanager.worker.ReminderScheduler

/** FragmentActivity because BiometricPrompt needs one; otherwise a plain Compose host. */
class MainActivity : FragmentActivity() {
    private val vm: RentViewModel by viewModels()
    private val settings by lazy { AppContainer.get(this).settings }
    private var locked by mutableStateOf(false)
    private var backgroundedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.scheduleDaily(this)
        locked = savedInstanceState?.getBoolean(KEY_LOCKED) ?: AppLockPolicy.lockOnStart(settings.appLockEnabled.value)
        applySecureFlag()
        setContent {
            RentTheme {
                AppLockGate(locked = locked, onUnlockRequest = ::authenticate) {
                    RentApp(vm, onAppLockChange = ::changeAppLock)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCKED, locked)
    }

    override fun onStart() {
        super.onStart()
        if (AppLockPolicy.lockOnReturn(settings.appLockEnabled.value, backgroundedAt, SystemClock.elapsedRealtime())) locked = true
        backgroundedAt = null
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // Post any rent that fell due while the app was in the background (idempotent).
        vm.refreshCharges()
    }

    /** While the lock is on, hide the app's content from the recent-apps preview and screenshots. */
    private fun applySecureFlag() {
        if (settings.appLockEnabled.value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun canAuthenticate() = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate(onSuccess: () -> Unit = { locked = false }, onFailure: (String) -> Unit = {}) {
        if (!canAuthenticate()) {
            // The phone's screen lock was removed: the app lock cannot work, so do not trap the user.
            settings.setAppLockEnabled(false)
            applySecureFlag()
            locked = false
            onFailure("Set a screen lock on your phone to use the app lock.")
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Room Rent Manager")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    /** Turning the lock on requires one successful unlock first, so nobody locks themselves out. */
    private fun changeAppLock(enabled: Boolean, onResult: (String?) -> Unit) {
        if (!enabled) {
            settings.setAppLockEnabled(false)
            applySecureFlag()
            onResult(null)
            return
        }
        if (!canAuthenticate()) {
            onResult("Set a screen lock (PIN, pattern, fingerprint or face) on your phone first.")
            return
        }
        authenticate(
            onSuccess = {
                settings.setAppLockEnabled(true)
                applySecureFlag()
                onResult(null)
            },
            onFailure = { onResult(it) },
        )
    }

    private companion object {
        const val KEY_LOCKED = "locked"
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
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
