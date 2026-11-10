package com.roomrentmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.MutableClock
import com.roomrentmanager.data.testContainer
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.ExpenseInput
import com.roomrentmanager.domain.usecase.MaintenanceInput
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.ReminderSettings
import com.roomrentmanager.domain.usecase.StartTenancyInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the main screens with sample data to PNG files for visual review, since this suite runs
 * without a device. Opt-in: `SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*ScreenshotTour*'`.
 * Images go to app/build/screenshots/.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScreenshotTour {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    @After
    fun tearDown() = AppContainer.setForTests(null)

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun open(text: String) {
        compose.waitUntilAtLeastOneExists(hasText(text) and hasClickAction(), 10_000)
        compose.onNode(hasText(text) and hasClickAction()).performClick()
    }

    @Test
    fun tour() {
        assumeTrue("set SCREENSHOTS=1 to render screenshots", System.getenv("SCREENSHOTS") != null)
        val d = { s: String -> LocalDate.parse(s) }
        val c = testContainer(MutableClock(d("2026-04-05")))
        runBlocking {
            val villa = c.manageProperties.saveProperty(null, "Green Villa", PropertyType.RESIDENTIAL, "MG Road, Pune", null)
            val plaza = c.manageProperties.saveProperty(null, "Market Plaza", PropertyType.COMMERCIAL, null, null)
            val r1 = c.manageProperties.saveRoom(null, villa, "Room 1", 10_000_00, null)
            val r2 = c.manageProperties.saveRoom(null, villa, "Room 2", 8_000_00, null)
            c.manageProperties.saveRoom(null, villa, "Room 3", 7_500_00, null)
            val shop = c.manageProperties.saveRoom(null, plaza, "Shop 1", 25_000_00, null)
            val asha = c.manageTenancy.saveTenant(null, "Asha Rao", "98765 43210", null, null, null)
            val bilal = c.manageTenancy.saveTenant(null, "Bilal Traders", "91234 56789", null, null, null)
            val chen = c.manageTenancy.saveTenant(null, "Chen Li", null, null, null, null)
            val t1 = c.manageTenancy.startTenancy(StartTenancyInput(r1, asha, d("2026-01-10"), AgreementInput(d("2026-01-10"), 10_000_00, 10), 20_000_00))
            val t2 = c.manageTenancy.startTenancy(StartTenancyInput(shop, bilal, d("2026-02-01"), AgreementInput(d("2026-02-01"), 25_000_00, 1)))
            val t3 = c.manageTenancy.startTenancy(StartTenancyInput(r2, chen, d("2026-03-06"), AgreementInput(d("2026-03-06"), 8_000_00, 6)))
            c.recordPayment(PaymentInput(t1, 25_000_00, d("2026-03-12"), PaymentMethod.UPI, "UPI-8842"))
            c.recordPayment(PaymentInput(t2, 50_000_00, d("2026-03-02"), PaymentMethod.BANK_TRANSFER))
            c.recordPayment(PaymentInput(t3, 10_000_00, d("2026-03-06"), PaymentMethod.CASH, allocation = AllocationMode.OldestFirst))
            c.manageExpenses.save(null, ExpenseInput(villa, r1, "Repairs", 2_500_00, d("2026-04-01"), "Tap replaced", "Ravi Plumbing"))
            c.manageExpenses.save(null, ExpenseInput(plaza, null, "Electricity", 4_200_00, d("2026-04-02"), "Common area"))
            c.manageMaintenance.save(null, MaintenanceInput(villa, r2, "Ceiling fan noisy", "", MaintenancePriority.HIGH, MaintenanceStatus.OPEN, d("2026-04-03"), null, null))
            c.runReminders(ReminderSettings(daysBeforeDue = 5))
        }
        AppContainer.setForTests(c)
        compose.setContent { RentTheme { RentApp() } }

        compose.waitUntilAtLeastOneExists(hasText("Due this month"), 10_000)
        shot("01-dashboard")
        open("Units"); shot("02-units")
        open("Tenants"); open("Asha Rao"); compose.waitUntilAtLeastOneExists(hasText("Remind Asha Rao"), 10_000); shot("03-tenant-detail")
        open("Payments"); shot("05-payments")
        open("More"); shot("06-more")
        open("Reports"); compose.waitUntilAtLeastOneExists(hasText("Rent collection, Apr 2026"), 10_000); shot("07-report-collection")
        open("Report: Rent collection"); open("Outstanding rent"); shot("08-report-outstanding")
        open("More"); open("Maintenance"); shot("09-maintenance")
        open("More"); open("Backup and privacy"); shot("10-backup-privacy")
    }
}
