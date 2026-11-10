package com.roomrentmanager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ValidationException
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.ExpenseInput
import com.roomrentmanager.domain.usecase.MaintenanceInput
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.Period
import com.roomrentmanager.domain.usecase.PortfolioSummaries
import com.roomrentmanager.domain.usecase.ReminderKinds
import com.roomrentmanager.domain.usecase.ReminderPlanner
import com.roomrentmanager.domain.usecase.ReminderSettings
import com.roomrentmanager.domain.usecase.Reports
import com.roomrentmanager.domain.usecase.StartTenancyInput
import com.roomrentmanager.domain.usecase.TenancyView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OperationsTest {

    private val clock = MutableClock(LocalDate.parse("2026-04-05"))
    private lateinit var db: AppDatabase
    private lateinit var app: AppContainer
    private var villa = 0L
    private var plaza = 0L
    private var room1 = 0L
    private var room2 = 0L
    private var shop = 0L
    private var asha = 0L
    private var bilal = 0L

    private fun d(s: String) = LocalDate.parse(s)
    private suspend fun portfolio(): Portfolio = app.portfolio.observePortfolio().first()
    private suspend fun views(): List<TenancyView> = PortfolioSummaries.tenancyViews(portfolio(), clock.date)

    private suspend inline fun expectValidation(crossinline block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: ValidationException) {
            return e.message!!
        }
        fail("Expected a ValidationException")
        error("unreachable")
    }

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        app = testContainer(clock, db)
        villa = app.manageProperties.saveProperty(null, "Green Villa", PropertyType.RESIDENTIAL, null, null)
        plaza = app.manageProperties.saveProperty(null, "Market Plaza", PropertyType.COMMERCIAL, null, null)
        room1 = app.manageProperties.saveRoom(null, villa, "Room 1", 10_000_00, null)
        room2 = app.manageProperties.saveRoom(null, villa, "Room 2", 8_000_00, null)
        shop = app.manageProperties.saveRoom(null, plaza, "Shop 1", 25_000_00, null)
        asha = app.manageTenancy.saveTenant(null, "Asha", null, null, null, null)
        bilal = app.manageTenancy.saveTenant(null, "Bilal Traders", null, null, null, null)
    }

    @After
    fun tearDown() = db.close()

    /** Asha in Room 1 from 10 Jan, ₹10,000 due on the 10th: charges 10 Jan, 10 Feb, 10 Mar (today is 5 Apr). */
    private suspend fun startAsha(deposit: Long? = null) = app.manageTenancy.startTenancy(
        StartTenancyInput(room1, asha, d("2026-01-10"), AgreementInput(d("2026-01-10"), 10_000_00, 10), deposit),
    )

    private suspend fun startBilal() = app.manageTenancy.startTenancy(
        StartTenancyInput(shop, bilal, d("2026-03-01"), AgreementInput(d("2026-03-01"), 25_000_00, 1)),
    )

    // --- Expenses ---------------------------------------------------------------------------

    @Test
    fun expensesAreValidatedAndCorrectionsAudited() = runTest {
        val input = ExpenseInput(villa, room1, "Repairs", 2_500_00, d("2026-04-01"), "Tap replaced", "Ravi Plumbing")
        assertTrue(expectValidation { app.manageExpenses.save(null, input.copy(roomId = shop)) }.contains("not in"))
        expectValidation { app.manageExpenses.save(null, input.copy(amountPaise = 0)) }
        expectValidation { app.manageExpenses.save(null, input.copy(date = d("2026-04-06"))) }
        expectValidation { app.manageExpenses.save(null, input.copy(description = " ")) }

        val id = app.manageExpenses.save(null, input)
        app.manageExpenses.save(id, input.copy(amountPaise = 2_000_00))
        val expense = portfolio().expenses.single()
        assertEquals(2_000_00L, expense.amountPaise)
        assertEquals("Ravi Plumbing", expense.vendor)
        val audit = db.auditDao().forEntity("EXPENSE", id).map { it.description }
        assertTrue(audit.last(), audit.last().contains("₹2,500.00") && audit.last().contains("₹2,000.00"))
    }

    // --- Maintenance --------------------------------------------------------------------------

    @Test
    fun maintenanceLinksTenancyAndCanRecordCostOnce() = runTest {
        val t = startAsha()
        val base = MaintenanceInput(villa, room1, "Leaking tap", "", MaintenancePriority.HIGH, MaintenanceStatus.OPEN, d("2026-04-01"), null, null)
        val id = app.manageMaintenance.save(null, base)
        assertEquals(t, portfolio().maintenance.single().tenancyId)

        assertTrue(expectValidation { app.manageMaintenance.save(id, base.copy(status = MaintenanceStatus.COMPLETED)) }.contains("completion date"))
        expectValidation { app.manageMaintenance.save(id, base.copy(status = MaintenanceStatus.COMPLETED, completedDate = d("2026-03-30"))) }

        val done = base.copy(status = MaintenanceStatus.COMPLETED, completedDate = d("2026-04-03"), costPaise = 1_200_00, recordCostAsExpense = true)
        app.manageMaintenance.save(id, done)
        app.manageMaintenance.save(id, done.copy(description = "Washer replaced")) // already completed: no second expense
        val expenses = portfolio().expenses
        assertEquals(1, expenses.size)
        assertEquals("Maintenance", expenses.single().category)
        assertEquals(1_200_00L, expenses.single().amountPaise)
        assertEquals(d("2026-04-03"), expenses.single().date)
        assertEquals(MaintenanceStatus.COMPLETED, portfolio().maintenance.single().status)
    }

    // --- Reports ------------------------------------------------------------------------------

    @Test
    fun incomeAndExpensesPerPropertyOnCashBasis() = runTest {
        val t = startAsha(deposit = 20_000_00)
        val b = startBilal()
        app.recordPayment(PaymentInput(t, 15_000_00, d("2026-03-15"), PaymentMethod.UPI))
        app.recordPayment(PaymentInput(t, 5_000_00, d("2026-04-02"), PaymentMethod.CASH))
        app.recordPayment(PaymentInput(b, 25_000_00, d("2026-03-02"), PaymentMethod.BANK_TRANSFER))
        app.manageExpenses.save(null, ExpenseInput(villa, null, "Property tax", 3_000_00, d("2026-03-20"), "Half-yearly tax"))
        app.manageExpenses.save(null, ExpenseInput(villa, room1, "Repairs", 500_00, d("2026-03-21"), "Bulb"))
        app.manageExpenses.save(null, ExpenseInput(plaza, null, "Electricity", 1_000_00, d("2026-04-01"), "Common area"))

        val march = Reports.incomeAndExpenses(portfolio(), Period.month(YearMonth.of(2026, 3))).associateBy { it.property.name }
        assertEquals(15_000_00L, march.getValue("Green Villa").rentReceivedPaise) // the deposit is not income
        assertEquals(3_500_00L, march.getValue("Green Villa").expensesPaise)
        assertEquals(11_500_00L, march.getValue("Green Villa").netPaise)
        assertEquals(mapOf("Property tax" to 3_000_00L, "Repairs" to 500_00L), march.getValue("Green Villa").expensesByCategory)
        assertEquals(25_000_00L, march.getValue("Market Plaza").rentReceivedPaise)
        assertEquals(0L, march.getValue("Market Plaza").expensesPaise)

        val fy = Reports.incomeAndExpenses(portfolio(), Period.financialYear(2025)).sumOf { it.netPaise }
        assertEquals(15_000_00L + 25_000_00 - 3_500_00, fy) // FY 2025-26 ends 31 March
    }

    @Test
    fun rentCollectionByMonth() = runTest {
        val t = startAsha()
        app.recordPayment(PaymentInput(t, 10_000_00, d("2026-02-12"), PaymentMethod.CASH))
        val rows = Reports.rentCollection(portfolio(), views(), Period(d("2026-01-01"), d("2026-04-30")))
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.month.monthValue })
        assertEquals(listOf(10_000_00L, 10_000_00L, 10_000_00L, 0L), rows.map { it.expectedPaise })
        assertEquals(listOf(0L, 10_000_00L, 0L, 0L), rows.map { it.collectedPaise })
        assertEquals(listOf(0L, 10_000_00L, 10_000_00L, 0L), rows.map { it.outstandingPaise }) // Feb payment paid January (oldest first)
        assertEquals(12, Period.financialYear(2026).months.size)
    }

    @Test
    fun outstandingIsAgedByDaysPastDue() = runTest {
        startAsha()
        startBilal()
        clock.date = d("2026-04-15")
        app.generateRent.forAll(clock.date)
        // On 15 Apr, Asha's charges are 95, 64, 36 and 5 days past due; Bilal's are 45 and 14.
        val rows = Reports.outstanding(views(), clock.date).associateBy { it.tenancy.tenant.name }
        val a = rows.getValue("Asha")
        assertEquals(listOf(0L, 10_000_00L, 10_000_00L, 10_000_00L, 10_000_00L), listOf(a.notYetDuePaise, a.days1To30Paise, a.days31To60Paise, a.days61To90Paise, a.over90Paise))
        val b = rows.getValue("Bilal Traders")
        assertEquals(25_000_00L, b.days1To30Paise)
        assertEquals(25_000_00L, b.days31To60Paise)
        assertEquals("most owed first", "Bilal Traders", Reports.outstanding(views(), clock.date).first().tenancy.tenant.name)
    }

    @Test
    fun tenantStatementRunningBalanceMatchesNet() = runTest {
        val t = startAsha()
        val p1 = app.recordPayment(PaymentInput(t, 12_000_00, d("2026-02-11"), PaymentMethod.UPI))
        app.recordPayment(PaymentInput(t, 2_000_00, d("2026-03-01"), PaymentMethod.CASH, allocation = AllocationMode.KeepAsCredit))
        app.reversePayment(p1, "UPI failed")
        app.adjustments.addCredit(t, 500_00, "Goodwill")
        val lines = Reports.tenantStatement(portfolio(), t, ZoneOffset.UTC)
        val net = views().first { it.tenancy.id == t }.balance.netPaise
        assertEquals(net, lines.last().balancePaise)
        assertEquals(30_000_00L - 2_000_00 - 500_00, net)
        assertTrue(lines.any { it.description.startsWith("Payment reversed: UPI failed") && it.debitPaise == 12_000_00L })
        assertEquals(lines.map { it.date }, lines.map { it.date }.sortedBy { it })
    }

    @Test
    fun depositStatementAndOccupancy() = runTest {
        val t = startAsha(deposit = 20_000_00)
        app.recordDeposit(t, DepositTransactionType.DEDUCTION, 3_000_00, d("2026-04-01"), "Painting")
        app.recordDeposit(t, DepositTransactionType.REFUND, 7_000_00, d("2026-04-02"), null)
        val lines = Reports.depositStatement(portfolio(), t)
        assertEquals(listOf(20_000_00L, -3_000_00L, -7_000_00L), lines.map { it.signedPaise })
        assertEquals(listOf(20_000_00L, 17_000_00L, 10_000_00L), lines.map { it.heldPaise })

        val occ = Reports.occupancy(portfolio(), views()).associateBy { it.property.name }
        assertEquals(2, occ.getValue("Green Villa").activeUnits)
        assertEquals(1, occ.getValue("Green Villa").occupied)
        assertEquals(listOf("Room 2"), occ.getValue("Green Villa").vacantUnits.map { it.name })
        assertEquals(50, occ.getValue("Green Villa").occupancyPercent)
        assertEquals(0, occ.getValue("Market Plaza").occupancyPercent)
    }

    // --- Reminders ----------------------------------------------------------------------------

    @Test
    fun remindersAreRaisedOnceAndNeverCharge() = runTest {
        val t = startAsha() // overdue since 10 Jan; next due 10 Apr
        val settings = ReminderSettings(daysBeforeDue = 5)
        val ledgerBefore = portfolio().ledger.size

        val first = app.runReminders(settings)
        assertEquals(setOf("due:$t:2026-04-10", "overdue:$t:2026-01-10"), first.map { it.key }.toSet())
        assertTrue(app.runReminders(settings).isEmpty())
        assertEquals("reminders never post charges", ledgerBefore, portfolio().ledger.size)

        // Paying the oldest charge moves the overdue reminder to the next unpaid one.
        app.recordPayment(PaymentInput(t, 10_000_00, d("2026-04-05"), PaymentMethod.CASH))
        assertEquals(listOf("overdue:$t:2026-02-10"), app.runReminders(settings).map { it.key })
        assertEquals(3, portfolio().reminders.size)
    }

    @Test
    fun reminderPlanningRules() = runTest {
        val t = startAsha(deposit = 5_000_00)
        app.manageMaintenance.save(null, MaintenanceInput(villa, room2, "Fan noisy", "", MaintenancePriority.NORMAL, MaintenanceStatus.OPEN, d("2026-04-01"), null, null))
        app.manageMaintenance.save(null, MaintenanceInput(plaza, null, "Shutter jammed", "", MaintenancePriority.URGENT, MaintenanceStatus.IN_PROGRESS, d("2026-04-05"), null, null))
        suspend fun plan(today: String, s: ReminderSettings = ReminderSettings()): List<com.roomrentmanager.domain.model.Reminder> {
            val p = portfolio()
            val date = d(today)
            return ReminderPlanner.plan(p, PortfolioSummaries.tenancyViews(p, date), date, s)
        }

        val first = plan("2026-04-05")
        assertTrue("due outside the 2-day window", first.none { it.kind == ReminderKinds.RENT_DUE })
        assertTrue("urgent maintenance reminds on day 0", first.any { it.message.contains("Shutter jammed") && it.key.endsWith(":0") })
        assertTrue("normal maintenance waits a week", first.none { it.message.contains("Fan noisy") })
        val keys = plan("2026-04-08").map { it.key }
        assertTrue(keys.contains("due:$t:2026-04-10"))
        val week = plan("2026-04-08", ReminderSettings(maintenanceFollowUpDays = 7)).filter { it.kind == ReminderKinds.MAINTENANCE_FOLLOW_UP }
        assertTrue(week.any { it.message.contains("Fan noisy") && it.key.endsWith(":1") })
        assertTrue(plan("2026-04-05", ReminderSettings(enabled = false)).isEmpty())
        assertTrue(plan("2026-04-05", ReminderSettings(overdue = false)).none { it.kind == ReminderKinds.RENT_OVERDUE })

        // Deposit follow-up: only after move-out + N days, and only while a deposit is held.
        app.manageTenancy.endTenancy(t, d("2026-04-05"))
        assertTrue(plan("2026-04-18").none { it.kind == ReminderKinds.DEPOSIT_FOLLOW_UP })
        assertEquals(listOf("deposit:$t"), plan("2026-04-19").filter { it.kind == ReminderKinds.DEPOSIT_FOLLOW_UP }.map { it.key })
        app.recordDeposit(t, DepositTransactionType.REFUND, 5_000_00, d("2026-04-05"), null)
        assertTrue(plan("2026-04-19").none { it.kind == ReminderKinds.DEPOSIT_FOLLOW_UP })
    }

    @Test
    fun tenantMessageMentionsWhatIsOwed() = runTest {
        startAsha()
        val v = views().single()
        val text = ReminderPlanner.tenantMessage(v, clock.date)
        assertTrue(text, text.startsWith("Hello Asha"))
        assertTrue(text, text.contains("₹30,000.00 is outstanding") && text.contains("Room 1, Green Villa"))

        app.recordPayment(PaymentInput(v.tenancy.id, 31_000_00, clock.date, PaymentMethod.CASH))
        val credit = ReminderPlanner.tenantMessage(views().single(), clock.date)
        assertTrue(credit, credit.contains("₹10,000.00 for Room 1, Green Villa is due on 2026-04-10") && credit.contains("advance credit of ₹1,000.00"))
    }
}
