package com.roomrentmanager.ui

import androidx.compose.ui.test.ExperimentalTestApi
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

    /** Waits until [text] is shown (not inside a text field), scrolling lazy lists to it if needed. */
    private fun waitFor(text: String, substring: Boolean = false) {
        val matcher = hasText(text, substring = substring) and !hasSetTextAction()
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
                val lists = compose.onAllNodes(hasScrollToNodeAction())
                repeat(lists.fetchSemanticsNodes().size) { i -> runCatching { lists[i].performScrollToNode(matcher) } }
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("'$text' not shown. On screen:\n" + screenText(), e)
        }
    }

    private val isSnackbarMessage = listOf(
        "Tenancy started.", "Payment recorded.", "Payment reversed.", "Rent agreement saved.", "Tenancy ended. The room is now vacant.",
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
        val node = compose.onNode(hasText(text) and hasClickAction())
        // A confirmation snackbar covers the bottom of the screen for a few seconds, as on a device.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(isSnackbarMessage).fetchSemanticsNodes().isEmpty() }
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
}
