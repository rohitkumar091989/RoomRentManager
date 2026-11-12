package com.roomrentmanager.data.settings

import android.content.Context
import com.roomrentmanager.domain.repository.SettingsRepository
import com.roomrentmanager.domain.usecase.ReminderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reminder and app-lock preferences are per-device conveniences, not financial records, so they live in
 * SharedPreferences rather than the database (and are not part of a data backup).
 */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val reminderSettings: StateFlow<ReminderSettings> = state.asStateFlow()

    private fun read(): ReminderSettings {
        val defaults = ReminderSettings()
        return runCatching {
            ReminderSettings(
                enabled = prefs.getBoolean("enabled", defaults.enabled),
                daysBeforeDue = prefs.getInt("daysBeforeDue", defaults.daysBeforeDue),
                overdue = prefs.getBoolean("overdue", defaults.overdue),
                depositFollowUpDays = prefs.getInt("depositFollowUpDays", defaults.depositFollowUpDays),
                maintenanceFollowUpDays = prefs.getInt("maintenanceFollowUpDays", defaults.maintenanceFollowUpDays),
            )
        }.getOrDefault(defaults)
    }

    override fun update(settings: ReminderSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putInt("daysBeforeDue", settings.daysBeforeDue)
            .putBoolean("overdue", settings.overdue)
            .putInt("depositFollowUpDays", settings.depositFollowUpDays)
            .putInt("maintenanceFollowUpDays", settings.maintenanceFollowUpDays)
            .apply()
        state.value = settings
    }

    private val lock = MutableStateFlow(prefs.getBoolean("appLock", false))
    override val appLockEnabled: StateFlow<Boolean> = lock.asStateFlow()
    override fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("appLock", enabled).apply()
        lock.value = enabled
    }
}

/** For tests and previews. */
class InMemorySettingsRepository(initial: ReminderSettings = ReminderSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val reminderSettings: StateFlow<ReminderSettings> = state.asStateFlow()
    override fun update(settings: ReminderSettings) {
        state.value = settings
    }

    private val lock = MutableStateFlow(false)
    override val appLockEnabled: StateFlow<Boolean> = lock.asStateFlow()
    override fun setAppLockEnabled(enabled: Boolean) {
        lock.value = enabled
    }
}
