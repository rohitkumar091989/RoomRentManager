package com.roomrentmanager.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.MutableClock
import com.roomrentmanager.data.testContainer
import com.roomrentmanager.domain.model.PropertyType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoreUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clock = MutableClock(LocalDate.parse("2026-04-05"))

    @After
    fun tearDown() = AppContainer.setForTests(null)

    private fun propertyNames(c: AppContainer) = runBlocking { c.portfolio.observePortfolio().first().properties.map { it.name } }

    @Test
    fun restoreAsksForConfirmationBeforeReplacingData() {
        // A backup from "another phone".
        val other = testContainer(clock)
        val file = File(app.cacheDir, "backup.json")
        runBlocking {
            other.manageProperties.saveProperty(null, "Backed-up Villa", PropertyType.RESIDENTIAL, null, null)
            file.writeText(other.backup.createBackupJson())
        }
        // This phone has its own data.
        val phone = AppContainer(com.roomrentmanager.data.inMemoryDatabase(), clock, safetyDir = File(app.filesDir, "ui-restore"))
        runBlocking { phone.manageProperties.saveProperty(null, "Current Shop", PropertyType.COMMERCIAL, null, null) }
        AppContainer.setForTests(phone)
        val vm = RentViewModel(app)
        compose.setContent { RentTheme { RentApp(vm) } }

        vm.inspectBackup(Uri.fromFile(file))
        compose.waitUntilAtLeastOneExists(hasText("Replace all data?"), 10_000)
        compose.onNodeWithText("The backup is complete and undamaged.").assertExists()
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsNotEnabled()
        assertEquals("nothing changes before confirming", listOf("Current Shop"), propertyNames(phone))

        compose.onNodeWithText("I understand everything on this phone will be replaced by the backup.").performClick()
        compose.onNode(hasText("Restore") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { propertyNames(phone) == listOf("Backed-up Villa") }
        compose.waitUntilAtLeastOneExists(hasText("Backup restored", substring = true), 10_000)
    }

    @Test
    fun damagedBackupIsRefusedWithAMessage() {
        val phone = AppContainer(com.roomrentmanager.data.inMemoryDatabase(), clock)
        runBlocking { phone.manageProperties.saveProperty(null, "Current Shop", PropertyType.COMMERCIAL, null, null) }
        AppContainer.setForTests(phone)
        val vm = RentViewModel(app)
        compose.setContent { RentTheme { RentApp(vm) } }
        val file = File(app.cacheDir, "not-a-backup.json").apply { writeText("{\"hello\":1}") }

        vm.inspectBackup(Uri.fromFile(file))
        compose.waitUntilAtLeastOneExists(hasText("This is not a Room Rent Manager backup file."), 10_000)
        assertEquals(0, compose.onAllNodes(hasText("Replace all data?")).fetchSemanticsNodes().size)
        assertEquals(listOf("Current Shop"), propertyNames(phone))
    }
}
