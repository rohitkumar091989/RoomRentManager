package com.roomrentmanager.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.MutableClock
import com.roomrentmanager.data.testContainer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/** Drives the real screens against an in-memory database with a fixed date (5 April 2026). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RentAppUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val clock = MutableClock(LocalDate.parse("2026-04-05"))
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = testContainer(clock)
        AppContainer.setForTests(container)
        compose.setContent { RentTheme { RentApp() } }
    }

    @After
    fun tearDown() = AppContainer.setForTests(null)

    /** Waits until a node matching [matcher] exists, scrolling lazy lists to it if needed. */
    private fun await(matcher: SemanticsMatcher, what: String) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
                val lists = compose.onAllNodes(hasScrollToNodeAction())
                repeat(lists.fetchSemanticsNodes().size) { i -> runCatching { lists[i].performScrollToNode(matcher) } }
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("$what not shown. On screen:\n" + screenText(), e)
        }
    }

    /** Waits until [text] is shown outside a text field. */
    private fun waitFor(text: String, substring: Boolean = false) =
        await(hasText(text, substring = substring) and !hasSetTextAction(), "'$text'")

    private val isSnackbarMessage = listOf(
        "Tenancy started.", "Payment recorded.", "Payment reversed.", "Rent agreement saved.", "Tenancy ended. The room is now vacant.",
        "Expense recorded.", "Expense corrected.",
    ).map { hasText(it) }.reduce { a, b -> a or b }

    private fun screenText(): String = compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n---\n") { root ->
        buildString {
            fun walk(n: SemanticsNode) {
                n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.let { appendLine(it.joinToString(" | ")) }
                n.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.let { appendLine("[field] $it") }
                n.children.forEach(::walk)
            }
            walk(root)
        }
    }

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun type(label: String, value: String, clear: Boolean = false) {
        field(label).performScrollTo()
        if (clear) field(label).performTextClearance()
        field(label).performTextInput(value)
    }

    private fun click(text: String) {
        val matcher = hasText(text) and hasClickAction()
        await(matcher, "Clickable '$text'")
        // A confirmation snackbar covers the bottom of the screen for a few seconds, as on a device.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(isSnackbarMessage).fetchSemanticsNodes().isEmpty() }
        val node = compose.onNode(matcher)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun tab(label: String) = click(label)

    /** Property, room and tenant created through the UI, then the room is let. */
    private fun setUpLetRoom() {
        tab("Units")
        waitFor("Add a property", substring = true)
        compose.onNodeWithContentDescription("Add").performClick()
        type("Property name", "Green Villa")
        click("Save")
        waitFor("Green Villa")

        compose.onNodeWithContentDescription("Add").performClick()
        type("unit name", "Room 1")
        type("Usual monthly rent", "10000")
        click("Save")
        waitFor("Vacant")

        tab("Tenants")
        compose.onNodeWithContentDescription("Add").performClick()
        type("Name (person or business)", "Asha")
        type("Phone", "9876543210")
        click("Save")
        waitFor("No active tenancy")

        tab("Units")
        waitFor("Let")
        click("Let")
        waitFor("Start tenancy")
        type("Security deposit", "20000")
        click("Start")
        waitFor("Occupied by Asha")
    }

    @Test
    fun addPropertyRoomTenantAndStartTenancy() {
        setUpLetRoom()
        tab("Dashboard")
        waitFor("Owes ₹10,000.00")
        waitFor("1 occupied", substring = true)
    }

    @Test
    fun partialPaymentThenAdvanceCredit() {
        setUpLetRoom()
        tab("Dashboard")
        waitFor("Collect")
        click("Collect")
        waitFor("Record payment")
        type("Amount received", "4000", clear = true)
        click("Save")
        waitFor("Owes ₹6,000.00")

        tab("Tenants")
        waitFor("Asha")
        click("Asha")
        waitFor("Payment")
        click("Payment")
        waitFor("Record payment")
        type("Amount received", "10000", clear = true)
        click("Save")
        waitFor("Advance credit ₹4,000.00")
    }

    @Test
    fun endTenancyMarksRoomVacantAndKeepsHistory() {
        setUpLetRoom()
        click("Occupied by Asha")
        waitFor("End")
        click("End")
        waitFor("Rent periods starting on or after this date", substring = true)
        clock.date = LocalDate.parse("2026-04-20") // two weeks later
        type("Move-out", "2026-04-20", clear = true)
        click("End tenancy")
        waitFor("Moved in 2026-04-05 · moved out 2026-04-20 · ended")
        waitFor("Owes ₹10,000.00") // the period that started before move-out is still owed

        compose.onNode(hasContentDescription("Back")).performClick()
        tab("Units")
        waitFor("Vacant")
    }

    @Test
    fun endingOnTheMoveInDayCancelsThatPeriod() {
        setUpLetRoom()
        click("Occupied by Asha")
        click("End")
        waitFor("Rent periods starting on or after this date", substring = true)
        click("End tenancy")
        waitFor("Moved in 2026-04-05 · moved out 2026-04-05 · ended")
        waitFor("Settled")
        waitFor("Cancelled") // shown as cancelled, never as paid
    }

    @Test
    fun recordDepositRefund() {
        setUpLetRoom()
        click("Occupied by Asha")
        waitFor("deposit held ₹20,000.00", substring = true)
        click("Deposit")
        waitFor("Refunded", substring = true)
        type("Amount", "20000")
        click("Save")
        waitFor("deposit held ₹0.00", substring = true)
        waitFor("Owes ₹10,000.00") // refunds never touch rent
    }

    @Test
    fun expenseFlowsIntoIncomeReport() {
        setUpLetRoom()
        tab("More")
        click("Expenses")
        waitFor("This month", substring = true)
        compose.onNodeWithContentDescription("Add").performClick()
        waitFor("Add expense")
        type("Amount", "2500")
        type("Description", "Tap replaced")
        click("Save")
        waitFor("Repairs · ₹2,500.00")

        compose.onNode(hasContentDescription("Back")).performClick()
        click("Reports")
        waitFor("Rent collection, Apr 2026")
        click("Report: Rent collection")
        click("Property income and expenses")
        waitFor("Income and expenses, Apr 2026")
        waitFor("-₹2,500.00") // nothing received yet, ₹2,500 spent
    }

    @Test
    fun maintenanceRequestAppearsOnDashboard() {
        setUpLetRoom()
        tab("More")
        click("Maintenance")
        compose.onNodeWithContentDescription("Add").performClick()
        waitFor("New maintenance request")
        type("What needs fixing", "Leaking tap")
        click("Save")
        waitFor("Leaking tap")
        tab("Dashboard")
        waitFor("Open maintenance (1)")
    }

    @Test
    fun remindTenantOpensShareSheetWithMessage() {
        setUpLetRoom()
        click("Occupied by Asha")
        click("Remind Asha")
        val chooser = org.robolectric.Shadows.shadowOf(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        val send = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
        val text = send.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        org.junit.Assert.assertTrue(text, text.startsWith("Hello Asha") && text.contains("₹10,000.00"))
    }

    @Test
    fun reminderSettingsCanBeChanged() {
        tab("More")
        click("Reminders")
        waitFor("Reminders on")
        click("Remind before rent is due: 2 days before")
        click("5 days before")
        waitFor("Remind before rent is due: 5 days before")
        org.junit.Assert.assertEquals(5, container.settings.reminderSettings.value.daysBeforeDue)
    }

    @Test
    fun backupAndPrivacySettingsAreReachable() {
        tab("More")
        click("Backup and privacy")
        waitFor("Back up now")
        waitFor("Restore from a backup")
        waitFor("Export as CSV")
        waitFor("Require fingerprint, face or screen lock")
        waitFor("No account, no analytics", substring = true)
    }

    /** A day cell in the open date picker; cells are labelled with the full date, e.g. "April 30, 2026". */
    private fun day(monthDayYear: String) = compose.onNode(hasText(monthDayYear, substring = true) and hasClickAction() and hasAnyAncestor(isDialog()))

    // Robolectric's default 320x470dp screen is shorter than the calendar dialog, so a tap can land
    // outside the clipped window. Day cells and OK are activated through their click action instead.
    @Test
    fun datePickerFillsFieldAndBlocksFutureDays() {
        setUpLetRoom()
        tab("Dashboard")
        click("Collect")
        waitFor("Record payment")
        compose.onNodeWithContentDescription("Choose Received on from calendar").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Received on") and hasAnyAncestor(isDialog()) and !hasSetTextAction(), 10_000)
        day("April 30, 2026").assertIsNotEnabled() // today is 5 April 2026: payments cannot be in the future
        day("April 1, 2026").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("OK") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction() and hasText("2026-04-01")).assertExists()
    }

    @Test
    fun rentChangePickerOffersOnlyRentDays() {
        setUpLetRoom() // rent due on the 5th; April is already charged
        click("Occupied by Asha")
        click("Change rent")
        waitFor("Change rent", substring = false)
        compose.onNodeWithContentDescription("Choose Effective from from calendar").performClick()
        compose.waitUntilAtLeastOneExists(hasText("May 2026", substring = true) and hasAnyAncestor(isDialog()), 10_000)
        day("May 5, 2026").assertIsEnabled()
        day("May 6, 2026").assertIsNotEnabled()
        day("May 4, 2026").assertIsNotEnabled()
    }
}
