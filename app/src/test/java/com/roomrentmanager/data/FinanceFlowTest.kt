package com.roomrentmanager.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.local.AppDatabase
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.finance.TenancyBalance
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.model.ValidationException
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.PortfolioSummaries
import com.roomrentmanager.domain.usecase.StartTenancyInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FinanceFlowTest {

    private val clock = MutableClock(LocalDate.parse("2026-04-05"))
    private lateinit var db: AppDatabase
    private lateinit var app: AppContainer
    private var roomId = 0L
    private var room2Id = 0L
    private var asha = 0L
    private var bilal = 0L

    private fun d(s: String) = LocalDate.parse(s)
    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private suspend fun portfolio(): Portfolio = app.portfolio.observePortfolio().first()
    private suspend fun balance(tenancyId: Long): TenancyBalance =
        PortfolioSummaries.tenancyViews(portfolio(), clock.date).first { it.tenancy.id == tenancyId }.balance

    private suspend inline fun expectValidation(crossinline block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: ValidationException) {
            return e.message!!
        }
        fail("Expected a ValidationException")
        error("unreachable")
    }

    /** Asha in Room 1 from 31 Jan, ₹10,000 due on the 31st (clamped in short months). */
    private suspend fun startAsha(deposit: Long? = null): Long = app.manageTenancy.startTenancy(
        StartTenancyInput(roomId, asha, d("2026-01-31"), AgreementInput(d("2026-01-31"), 10_000_00, 31), deposit),
    )

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        app = testContainer(clock, db)
        val property = app.manageProperties.saveProperty(null, "Green Villa", PropertyType.RESIDENTIAL, "MG Road", null)
        roomId = app.manageProperties.saveRoom(null, property, "Room 1", 10_000_00, null)
        room2Id = app.manageProperties.saveRoom(null, property, "Shop 2", 25_000_00, null)
        asha = app.manageTenancy.saveTenant(null, "Asha", "98765 43210", null, null, null)
        bilal = app.manageTenancy.saveTenant(null, "Bilal Traders", null, "accounts@bilal.example", null, null)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun startingATenancyPostsChargesDueSoFar() = runTest {
        val t = startAsha()
        val ledger = portfolio().ledger
        assertEquals(listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")), ledger.map { it.dueDate })
        assertTrue(ledger.all { it.amountPaise == 10_000_00L && it.type == LedgerEntryType.RENT_CHARGE })
        assertEquals(30_000_00L, balance(t).outstandingPaise)
    }

    @Test
    fun onlyOneActiveTenancyPerRoom() = runTest {
        startAsha()
        val message = expectValidation {
            app.manageTenancy.startTenancy(StartTenancyInput(roomId, bilal, d("2026-04-01"), AgreementInput(d("2026-04-01"), 1, 1)))
        }
        assertTrue(message.contains("already has an active tenant"))
        // The database refuses it too, even if the use case is bypassed.
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO tenancies (roomId, tenantId, moveInDate, status, activeRoomId, createdAt, updatedAt) VALUES ($roomId, $bilal, '2026-04-01', 'ACTIVE', $roomId, 0, 0)")
        }
    }

    @Test
    fun roomCanBeReLetAfterTenancyEnds() = runTest {
        val first = startAsha()
        app.manageTenancy.endTenancy(first, d("2026-04-05"))
        val view = PortfolioSummaries.roomViews(portfolio(), PortfolioSummaries.tenancyViews(portfolio(), clock.date)).first { it.room.id == roomId }
        assertNull("room should be vacant", view.occupant)

        val second = app.manageTenancy.startTenancy(StartTenancyInput(roomId, bilal, d("2026-04-05"), AgreementInput(d("2026-04-05"), 12_000_00, 5)))
        val tenancies = portfolio().tenancies
        assertEquals(TenancyStatus.ENDED, tenancies.first { it.id == first }.status)
        assertEquals(TenancyStatus.ACTIVE, tenancies.first { it.id == second }.status)
        assertEquals(12_000_00L, balance(second).outstandingPaise)
        assertEquals(30_000_00L, balance(first).outstandingPaise) // history and dues preserved
    }

    @Test
    fun chargeGenerationIsIdempotent() = runTest {
        val t = startAsha()
        assertEquals(0, app.generateRent.forAll(clock.date).created)
        assertEquals(0, app.generateRent.forTenancy(t, clock.date))
        assertEquals(3L, count("rent_ledger"))
        clock.date = d("2026-05-01")
        assertEquals(1, app.generateRent.forAll(clock.date).created) // 30 April
        assertEquals(0, app.generateRent.forAll(clock.date).created)
        assertEquals(4L, count("rent_ledger"))
    }

    @Test
    fun partialPaymentsAdvanceCreditReversalAndReallocation() = runTest {
        val t = startAsha() // Jan, Feb, Mar charges: ₹30,000

        val p1 = app.recordPayment(PaymentInput(t, 15_000_00, d("2026-04-01"), PaymentMethod.UPI, "UPI123"))
        balance(t).let {
            assertEquals(15_000_00L, it.outstandingPaise)
            assertFalse(it.charges[0].isOpen) // oldest first
            assertEquals(5_000_00L, it.charges[1].outstandingPaise)
        }

        app.recordPayment(PaymentInput(t, 18_000_00, d("2026-04-02"), PaymentMethod.CASH))
        balance(t).let {
            assertEquals(0L, it.outstandingPaise)
            assertEquals(3_000_00L, it.creditPaise)
            assertEquals(-3_000_00L, it.netPaise) // advance credit is never clamped to zero
        }

        assertTrue(app.reversePayment(p1, "Entered twice"))
        balance(t).let {
            assertEquals(15_000_00L, it.outstandingPaise)
            assertEquals(3_000_00L, it.creditPaise)
            assertEquals(12_000_00L, it.netPaise)
        }
        val allocationsAfterReversal = count("payment_allocations")
        assertFalse("reversal is idempotent", app.reversePayment(p1, "again"))
        assertEquals(allocationsAfterReversal, count("payment_allocations"))
        assertEquals(PaymentStatus.REVERSED, portfolio().payments.first { it.id == p1 }.status)

        assertEquals(3_000_00L, app.allocatePayment.applyCredit(t))
        balance(t).let {
            assertEquals(12_000_00L, it.outstandingPaise)
            assertEquals(0L, it.creditPaise)
        }
        // A reversed payment cannot be allocated again.
        expectValidation { app.allocatePayment(p1, AllocationMode.OldestFirst) }
    }

    @Test
    fun paymentAndAllocationAreAtomic() = runTest {
        val t = startAsha()
        expectValidation {
            app.recordPayment(PaymentInput(t, 5_000_00, d("2026-04-01"), PaymentMethod.CASH, allocation = AllocationMode.Selected(setOf(999L))))
        }
        assertEquals("no payment row may survive a failed allocation", 0L, count("payments"))
        expectValidation { app.recordPayment(PaymentInput(t, 0, d("2026-04-01"), PaymentMethod.CASH)) }
        expectValidation { app.recordPayment(PaymentInput(t, 100, d("2026-04-06"), PaymentMethod.CASH)) } // future
    }

    @Test
    fun selectedAllocationAndKeepAsCredit() = runTest {
        val t = startAsha()
        val march = balance(t).charges.last().entry.id
        app.recordPayment(PaymentInput(t, 10_000_00, d("2026-04-01"), PaymentMethod.CASH, allocation = AllocationMode.Selected(setOf(march))))
        assertFalse(balance(t).charges.last().isOpen)
        assertTrue(balance(t).charges.first().isOpen)
        app.recordPayment(PaymentInput(t, 2_000_00, d("2026-04-01"), PaymentMethod.CASH, allocation = AllocationMode.KeepAsCredit))
        assertEquals(2_000_00L, balance(t).creditPaise)
        assertEquals(20_000_00L, balance(t).outstandingPaise)
    }

    @Test
    fun databaseRejectsOverAllocationAndHistoryEdits() = runTest {
        val t = startAsha()
        val p = app.recordPayment(PaymentInput(t, 1_000_00, d("2026-04-01"), PaymentMethod.CASH, allocation = AllocationMode.KeepAsCredit))
        val charge = balance(t).charges.first().entry.id
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO payment_allocations (paymentId, ledgerEntryId, amountPaise, createdAt) VALUES ($p, $charge, 100001, 0)")
        }
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM payments") }
        assertThrows(SQLiteConstraintException::class.java) { sql("UPDATE payments SET amountPaise = 5") }
        assertThrows(SQLiteConstraintException::class.java) { sql("UPDATE rent_ledger SET amountPaise = 5") }
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM rent_ledger") }
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM tenancies") }
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM tenants WHERE id = $asha") } // FK RESTRICT
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM audit_log") }
    }

    @Test
    fun archivingATenantKeepsAllHistory() = runTest {
        val t = startAsha(deposit = 20_000_00)
        app.recordPayment(PaymentInput(t, 10_000_00, d("2026-04-01"), PaymentMethod.CASH))
        assertTrue(expectValidation { app.manageTenancy.setTenantActive(asha, false) }.contains("End"))
        app.manageTenancy.endTenancy(t, d("2026-04-05"))
        app.manageTenancy.setTenantActive(asha, false)

        val p = portfolio()
        assertFalse(p.tenants.first { it.id == asha }.isActive)
        assertEquals(1, p.tenancies.size)
        assertEquals(3, p.ledger.size)
        assertEquals(1, p.payments.size)
        assertEquals(1, p.deposits.size)
        assertEquals(20_000_00L, balance(t).outstandingPaise)
    }

    @Test
    fun endingATenancyCancelsOnlyUnpaidFuturePeriods() = runTest {
        val t = startAsha() // charges 31 Jan, 28 Feb, 31 Mar
        app.manageTenancy.endTenancy(t, d("2026-03-20"))
        val b = balance(t)
        assertEquals(20_000_00L, b.outstandingPaise)
        assertEquals(4L, count("rent_ledger")) // the 31 Mar charge stays, plus its reversal
        clock.date = d("2026-06-01")
        assertEquals(0, app.generateRent.forAll(clock.date).created) // nothing after move-out
    }

    @Test
    fun endingRefusesWhenAFuturePeriodIsAlreadyPaid() = runTest {
        val t = startAsha()
        val march = balance(t).charges.last().entry.id
        app.recordPayment(PaymentInput(t, 10_000_00, d("2026-04-01"), PaymentMethod.CASH, allocation = AllocationMode.Selected(setOf(march))))
        assertTrue(expectValidation { app.manageTenancy.endTenancy(t, d("2026-03-20")) }.contains("Reverse that payment"))
        assertEquals(TenancyStatus.ACTIVE, portfolio().tenancies.first().status)
    }

    @Test
    fun rentChangeKeepsHistoricalChargesAndIsBoundaryOnly() = runTest {
        val t = startAsha()
        assertTrue(expectValidation { app.manageTenancy.addAgreement(t, AgreementInput(d("2026-04-15"), 12_000_00, 31)) }.contains("start of a billing period"))
        assertTrue(expectValidation { app.manageTenancy.addAgreement(t, AgreementInput(d("2026-03-31"), 12_000_00, 31)) }.contains("already been charged"))

        app.manageTenancy.addAgreement(t, AgreementInput(d("2026-04-30"), 12_000_00, 31))
        clock.date = d("2026-05-31")
        app.generateRent.forAll(clock.date)
        val amounts = portfolio().ledger.filter { it.tenancyId == t }.sortedBy { it.dueDate }.map { it.dueDate.toString() to it.amountPaise }
        assertEquals(
            listOf("2026-01-31" to 10_000_00L, "2026-02-28" to 10_000_00L, "2026-03-31" to 10_000_00L, "2026-04-30" to 12_000_00L, "2026-05-31" to 12_000_00L),
            amounts,
        )
        val agreements = portfolio().agreements
        assertEquals(d("2026-04-29"), agreements.first().effectiveTo)
        assertNull(agreements.last().effectiveTo)
    }

    @Test
    fun depositsAreSeparateFromRent() = runTest {
        val t = startAsha(deposit = 20_000_00)
        assertEquals(30_000_00L, balance(t).outstandingPaise)
        suspend fun held() = PortfolioSummaries.tenancyViews(portfolio(), clock.date).first().depositHeldPaise
        assertEquals(20_000_00L, held())

        expectValidation { app.recordDeposit(t, DepositTransactionType.DEDUCTION, 5_000_00, clock.date, null) } // needs a note
        app.recordDeposit(t, DepositTransactionType.DEDUCTION, 5_000_00, clock.date, "Repainting")
        assertEquals(15_000_00L, held())
        assertTrue(expectValidation { app.recordDeposit(t, DepositTransactionType.REFUND, 16_000_00, clock.date, null) }.contains("Only"))
        app.recordDeposit(t, DepositTransactionType.REFUND, 15_000_00, clock.date, null)
        assertEquals(0L, held())
        assertEquals("deposit never touches rent", 30_000_00L, balance(t).outstandingPaise)
    }

    @Test
    fun adjustmentsCreditsAndCancelledCharges() = runTest {
        val t = startAsha()
        app.adjustments.addCharge(t, LedgerEntryType.LATE_FEE, 500_00, d("2026-04-05"), "Late fee for March")
        app.adjustments.addCredit(t, 1_000_00, "Plumbing paid by tenant")
        assertEquals(30_500_00L, balance(t).outstandingPaise)
        assertEquals(1_000_00L, balance(t).creditPaise)
        val feb = balance(t).charges[1].entry.id
        app.adjustments.cancelCharge(feb, "Rent waived for repairs")
        assertEquals(20_500_00L, balance(t).outstandingPaise)
        expectValidation { app.adjustments.cancelCharge(feb, "again") }
    }

    @Test
    fun importedPaymentsCountOnlyAfterReview() = runTest {
        val t = startAsha()
        sql("INSERT INTO payments (id, tenancyId, amountPaise, paymentDate, paymentMethod, status, createdAt) VALUES (500, NULL, 700000, '2026-01-10', 'OTHER', 'POSTED', 0)")
        sql("INSERT INTO payments (id, tenancyId, amountPaise, paymentDate, paymentMethod, status, createdAt) VALUES (501, NULL, 100000, '2026-01-11', 'OTHER', 'POSTED', 0)")
        sql("INSERT INTO migration_review (entityType, entityId, reason, legacyData, suggestedTenancyId, status, createdAt) VALUES ('PAYMENT', 500, 'LEGACY_PAYMENT', '{}', $t, 'PENDING', 0)")
        sql("INSERT INTO migration_review (entityType, entityId, reason, legacyData, suggestedTenancyId, status, createdAt) VALUES ('PAYMENT', 501, 'LEGACY_PAYMENT', '{}', NULL, 'PENDING', 0)")
        assertEquals(30_000_00L, balance(t).netPaise)

        val reviews = portfolio().reviews
        app.resolveReview.keepPaymentAsHistory(reviews.first { it.entityId == 501L }.id)
        app.resolveReview.applyPaymentToTenancy(reviews.first { it.entityId == 500L }.id, t)
        assertEquals(30_000_00L - 7_000_00L, balance(t).netPaise)
        assertEquals(7_000_00L, balance(t).creditPaise)
        assertNull(portfolio().payments.first { it.id == 501L }.tenancyId)
        assertTrue(portfolio().reviews.none { it.status == ReviewStatus.PENDING })
        expectValidation { app.resolveReview.keepPaymentAsHistory(reviews.first().id) }
    }

    @Test
    fun everyFinancialActionIsAudited() = runTest {
        val t = startAsha(deposit = 1_000_00)
        val p = app.recordPayment(PaymentInput(t, 1_000_00, d("2026-04-01"), PaymentMethod.CASH))
        app.reversePayment(p, "Bounced")
        val events = db.auditDao().observeRecent(50).first().map { it.eventType }
        listOf("PROPERTY_CREATED", "ROOM_CREATED", "TENANT_CREATED", "TENANCY_STARTED", "PAYMENT_RECORDED", "PAYMENT_REVERSED")
            .forEach { assertTrue("missing $it in $events", it in events) }
        assertNotNull(events)
    }

    @Test
    fun archivingRulesForRoomsAndProperties() = runTest {
        startAsha()
        assertTrue(expectValidation { app.manageProperties.setRoomActive(roomId, false) }.contains("End the current tenancy"))
        app.manageProperties.setRoomActive(room2Id, false)
        val propertyId = portfolio().properties.first().id
        assertTrue(expectValidation { app.manageProperties.setPropertyActive(propertyId, false) }.contains("Archive this property's rooms"))
        assertTrue(expectValidation {
            app.manageTenancy.startTenancy(StartTenancyInput(room2Id, bilal, d("2026-04-01"), AgreementInput(d("2026-04-01"), 1_00, 1)))
        }.contains("archived"))
    }
}
