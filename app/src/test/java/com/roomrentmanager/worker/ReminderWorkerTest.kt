package com.roomrentmanager.worker

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.MutableClock
import com.roomrentmanager.data.testContainer
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.StartTenancyInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = MutableClock(LocalDate.parse("2026-04-05"))
    private lateinit var container: AppContainer

    @Before
    fun setUp() = runBlocking {
        container = testContainer(clock)
        AppContainer.setForTests(container)
        val property = container.manageProperties.saveProperty(null, "Green Villa", PropertyType.RESIDENTIAL, null, null)
        val room = container.manageProperties.saveRoom(null, property, "Room 1", 10_000_00, null)
        val tenant = container.manageTenancy.saveTenant(null, "Asha", null, null, null, null)
        // Rent due on the 10th since January: overdue now, and due again in 5 days.
        container.manageTenancy.startTenancy(StartTenancyInput(room, tenant, LocalDate.parse("2026-01-10"), AgreementInput(LocalDate.parse("2026-01-10"), 10_000_00, 10)))
        Unit
    }

    @After
    fun tearDown() = AppContainer.setForTests(null)

    private fun runWorker(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<ReminderWorker>(context).build().doWork() }
    private fun posted() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun postsEachReminderOnceWhenPermitted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, posted().size) // overdue (rent due on the 10th is outside the default 2-day window)
        assertTrue(posted().single().extras.getString("android.text")!!.contains("overdue"))

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("no duplicate notification on the next run", 1, posted().size)
        val reminders = runBlocking { container.portfolio.observePortfolio().first().reminders }
        assertEquals(listOf(ReminderStatus.SHOWN), reminders.map { it.status })
    }

    @Test
    fun withoutPermissionRemindersStayInTheApp() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(posted().isEmpty())
        val reminders = runBlocking { container.portfolio.observePortfolio().first().reminders }
        assertEquals(listOf(ReminderStatus.PENDING), reminders.map { it.status })
    }
}
