package com.roomrentmanager.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roomrentmanager.AppContainer
import com.roomrentmanager.R
import com.roomrentmanager.domain.model.Reminder
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.usecase.ReminderKinds
import com.roomrentmanager.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Raises reminders derived from stored tenancy, agreement, ledger, deposit and maintenance data.
 * It never posts rent charges (that happens when the app is opened). Already-raised reminders are
 * skipped by key, so running it repeatedly does not duplicate notifications.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = AppContainer.get(applicationContext)
        val raised = container.runReminders(container.settings.reminderSettings.value)
        val notifier = ReminderNotifier(applicationContext)
        raised.forEach { if (notifier.show(it)) container.runReminders.setStatus(it.id, ReminderStatus.SHOWN) }
        Result.success()
    } catch (e: Exception) {
        Log.e("ReminderWorker", "Reminder check failed", e)
        Result.retry()
    }
}

/**
 * WorkManager keeps periodic work across device reboots and app updates by itself. [runSoon] is
 * called after data changes (and on every app open) so reminders reflect the latest records.
 */
object ReminderScheduler {
    private const val DAILY = "reminders-daily"
    private const val SOON = "reminders-soon"

    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(5, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }
}

class ReminderNotifier(private val context: Context) {

    fun canNotify(): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Returns true if a system notification was posted. Without permission the reminder still shows in the app. */
    fun show(reminder: Reminder): Boolean {
        if (!canNotify()) return false
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (reminder.kind) {
            ReminderKinds.RENT_DUE -> "Rent due"
            ReminderKinds.RENT_OVERDUE -> "Rent overdue"
            ReminderKinds.DEPOSIT_FOLLOW_UP -> "Deposit to settle"
            ReminderKinds.MAINTENANCE_FOLLOW_UP -> "Maintenance follow-up"
            else -> "Reminder"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(reminder.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(reminder.key.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rent and maintenance reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
    }
}
