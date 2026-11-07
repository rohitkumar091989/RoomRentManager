package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.AgreementTerms
import com.roomrentmanager.domain.finance.BillingCycleCalculator
import com.roomrentmanager.domain.finance.FinanceCalculator
import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.DepositTransaction
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntry
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.RentAgreement
import com.roomrentmanager.domain.model.Tenancy
import com.roomrentmanager.domain.model.TenancyStatus
import com.roomrentmanager.domain.model.Tenant
import com.roomrentmanager.domain.repository.AuditRepository
import com.roomrentmanager.domain.repository.FinanceRepository
import com.roomrentmanager.domain.repository.PropertyRepository
import com.roomrentmanager.domain.repository.TenancyRepository
import com.roomrentmanager.domain.repository.TransactionRunner
import java.time.Clock
import java.time.LocalDate

data class AgreementInput(
    val effectiveFrom: LocalDate,
    val rentPaise: Long,
    /** Day of month rent is due. Only used for a tenancy's first agreement; later ones inherit it. */
    val anchorDay: Int,
    val lateFeePaise: Long = 0,
    val graceDays: Int = 0,
    val notes: String? = null,
)

data class StartTenancyInput(
    val roomId: Long,
    val tenantId: Long,
    val moveIn: LocalDate,
    val agreement: AgreementInput,
    /** Security deposit received at move-in, if any. Recorded separately from rent. */
    val depositPaise: Long? = null,
)

internal fun termsOf(agreements: List<RentAgreement>) =
    agreements.map { AgreementTerms(it.id, it.effectiveFrom, it.effectiveTo, it.rentPaise, it.anchorDay) }

/** Tenants and their tenancies: contact details, start, rent changes, end. History is kept. */
class ManageTenancyUseCase(
    private val tx: TransactionRunner,
    private val properties: PropertyRepository,
    private val tenancies: TenancyRepository,
    private val finance: FinanceRepository,
    private val audit: AuditRepository,
    private val generateRent: GenerateRentDueUseCase,
    private val clock: Clock,
) {
    private fun today() = LocalDate.now(clock)

    suspend fun saveTenant(id: Long?, name: String, phone: String?, email: String?, address: String?, notes: String?): Long = tx.inTransaction {
        val cleanName = name.cleaned() ?: fail("Enter the tenant's name.")
        val cleanPhone = phone.cleaned()
        if (cleanPhone != null && !cleanPhone.matches(Regex("""\+?[0-9 ()-]{5,20}"""))) fail("Enter a valid phone number.")
        val cleanEmail = email.cleaned()
        if (cleanEmail != null && !cleanEmail.matches(Regex("""[^@\s]+@[^@\s]+\.[^@\s]+"""))) fail("Enter a valid email address.")
        if (id == null) {
            val newId = tenancies.insertTenant(Tenant(0, cleanName, cleanPhone, cleanEmail, address.cleaned(), notes.cleaned(), true))
            audit.log("TENANT_CREATED", "TENANT", newId, "Added tenant '$cleanName'.")
            newId
        } else {
            val existing = tenancies.getTenant(id) ?: fail("This tenant no longer exists.")
            tenancies.updateTenant(existing.copy(name = cleanName, phone = cleanPhone, email = cleanEmail, address = address.cleaned(), notes = notes.cleaned()))
            audit.log("TENANT_UPDATED", "TENANT", id, "Updated contact details for '$cleanName'.")
            id
        }
    }

    /** Archiving hides a tenant from pickers; their tenancies, payments and deposits stay. */
    suspend fun setTenantActive(id: Long, active: Boolean) = tx.inTransaction {
        val tenant = tenancies.getTenant(id) ?: fail("This tenant no longer exists.")
        if (!active && tenancies.countActiveForTenant(id) > 0) fail("End ${tenant.name}'s active tenancy before archiving.")
        tenancies.updateTenant(tenant.copy(isActive = active))
        audit.log(if (active) "TENANT_RESTORED" else "TENANT_ARCHIVED", "TENANT", id, "${if (active) "Restored" else "Archived"} '${tenant.name}'. History kept.")
    }

    suspend fun startTenancy(input: StartTenancyInput): Long {
        val tenancyId = tx.inTransaction {
            val room = properties.getRoom(input.roomId) ?: fail("Choose a room.")
            if (!room.isActive) fail("'${room.name}' is archived.")
            val tenant = tenancies.getTenant(input.tenantId) ?: fail("Choose a tenant.")
            if (!tenant.isActive) fail("${tenant.name} is archived. Restore them first.")
            val occupant = tenancies.activeTenancyForRoom(room.id)
            if (occupant != null) fail("'${room.name}' already has an active tenant. End that tenancy first.")
            if (input.moveIn.isAfter(today().plusYears(1))) fail("Move-in date is too far in the future.")
            if (input.agreement.effectiveFrom.isBefore(input.moveIn)) fail("Rent cannot start before the move-in date.")
            validateTerms(input.agreement)
            if (input.depositPaise != null && input.depositPaise !in 1..Money.MAX_PAISE) fail("Enter a valid deposit amount.")

            val id = tenancies.insertTenancy(Tenancy(0, room.id, tenant.id, input.moveIn, null, TenancyStatus.ACTIVE))
            val agreement = input.agreement.toAgreement(id, input.agreement.anchorDay)
            checkTerms(input.moveIn, null, listOf(agreement))
            tenancies.insertAgreement(agreement)
            if (input.depositPaise != null) {
                finance.insertDeposit(DepositTransaction(0, id, DepositTransactionType.RECEIVED, input.depositPaise, input.moveIn, "Deposit at move-in"))
            }
            audit.log(
                "TENANCY_STARTED", "TENANCY", id,
                "${tenant.name} moved into '${room.name}' on ${input.moveIn}. Rent ${Money.format(input.agreement.rentPaise)} due on day ${input.agreement.anchorDay}" +
                    (input.depositPaise?.let { ", deposit ${Money.format(it)}" } ?: "") + ".",
            )
            id
        }
        generateRent.forTenancy(tenancyId, today())
        return tenancyId
    }

    /**
     * Adds a new effective-dated agreement. For a tenancy with an open agreement this is a rent
     * change: the old agreement is closed the day before [AgreementInput.effectiveFrom], which must
     * be a billing date (mid-period changes are not supported until a proration policy exists).
     * Already-posted charges keep their original amounts.
     */
    suspend fun addAgreement(tenancyId: Long, input: AgreementInput) {
        tx.inTransaction {
            val tenancy = tenancies.getTenancy(tenancyId) ?: fail("This tenancy no longer exists.")
            if (tenancy.status != TenancyStatus.ACTIVE) fail("Rent can only be set on an active tenancy.")
            validateTerms(input)
            val existing = tenancies.agreementsFor(tenancyId)
            val anchor = existing.firstOrNull()?.anchorDay ?: input.anchorDay
            if (!BillingCycleCalculator.isBillingDate(input.effectiveFrom, anchor)) {
                val next = BillingCycleCalculator.billingDateOnOrAfter(input.effectiveFrom, anchor)
                fail("Rent can only change at the start of a billing period (rent day $anchor). The next one is $next.")
            }
            if (input.effectiveFrom.isBefore(tenancy.moveIn)) fail("Rent cannot start before the move-in date.")
            val lastCharged = finance.ledgerFor(tenancyId)
                .filter { it.type == LedgerEntryType.RENT_CHARGE }.mapNotNull { it.periodStart }.maxOrNull()
            if (lastCharged != null && !input.effectiveFrom.isAfter(lastCharged)) {
                fail("Rent has already been charged for the period starting $lastCharged. Choose a later start date.")
            }
            val open = existing.lastOrNull { it.effectiveTo == null }
            if (existing.any { !it.effectiveFrom.isBefore(input.effectiveFrom) }) fail("An agreement already starts on or after ${input.effectiveFrom}.")

            val newAgreement = input.toAgreement(tenancyId, anchor)
            val closed = existing.map { if (it.id == open?.id) it.copy(effectiveTo = input.effectiveFrom.minusDays(1)) else it }
            checkTerms(tenancy.moveIn, tenancy.moveOut, closed + newAgreement.copy(id = -1))
            if (open != null) tenancies.closeAgreement(open.id, input.effectiveFrom.minusDays(1))
            val id = tenancies.insertAgreement(newAgreement)
            audit.log(
                if (open == null) "AGREEMENT_CREATED" else "RENT_CHANGED", "TENANCY", tenancyId,
                (if (open == null) "Rent set to " else "Rent changed from ${Money.format(open.rentPaise)} to ") +
                    "${Money.format(input.rentPaise)} from ${input.effectiveFrom} (agreement $id).",
            )
        }
        generateRent.forTenancy(tenancyId, today())
    }

    /**
     * Ends a tenancy. The move-out date is the handover day. Unpaid rent charges for periods that
     * start on or after it are cancelled with compensating REVERSAL entries; a charge that already
     * has payments allocated must have those payments reversed first. Nothing is deleted.
     */
    suspend fun endTenancy(tenancyId: Long, moveOut: LocalDate) = tx.inTransaction {
        val tenancy = tenancies.getTenancy(tenancyId) ?: fail("This tenancy no longer exists.")
        if (tenancy.status != TenancyStatus.ACTIVE) fail("This tenancy has already ended.")
        if (moveOut.isBefore(tenancy.moveIn)) fail("Move-out cannot be before move-in (${tenancy.moveIn}).")
        if (moveOut.isAfter(today())) fail("Record the move-out on or after the day it happens.")

        val balance = FinanceCalculator.balance(
            tenancyId, finance.ledgerFor(tenancyId), finance.paymentsFor(tenancyId), finance.allocationsForTenancy(tenancyId),
        )
        val toCancel = balance.charges.filter {
            it.entry.type == LedgerEntryType.RENT_CHARGE && it.entry.periodStart != null && !it.entry.periodStart.isBefore(moveOut)
        }
        toCancel.firstOrNull { it.allocatedPaise > 0 }?.let {
            fail("Rent for ${it.entry.periodStart} already has a payment allocated. Reverse that payment before ending the tenancy on $moveOut.")
        }
        toCancel.filter { it.outstandingPaise > 0 }.forEach {
            finance.insertLedgerEntry(
                LedgerEntry(
                    0, tenancyId, it.entry.agreementId, LedgerEntryType.REVERSAL, it.entry.periodStart, it.entry.periodEnd,
                    it.entry.dueDate, -it.outstandingPaise, "Cancelled: tenancy ended on $moveOut", null, it.entry.id, 0,
                ),
            )
        }
        tenancies.updateTenancy(tenancy.copy(moveOut = moveOut, status = TenancyStatus.ENDED))
        audit.log(
            "TENANCY_ENDED", "TENANCY", tenancyId,
            "Tenancy ended on $moveOut." + if (toCancel.isNotEmpty()) " Cancelled ${toCancel.size} rent charge(s) for later periods." else "",
        )
    }

    private fun validateTerms(input: AgreementInput) {
        if (input.rentPaise !in 1..Money.MAX_PAISE) fail("Enter a rent greater than zero.")
        if (input.anchorDay !in 1..31) fail("Rent day must be between 1 and 31.")
        if (input.lateFeePaise !in 0..Money.MAX_PAISE) fail("Enter a valid late fee.")
        if (input.graceDays !in 0..60) fail("Grace days must be between 0 and 60.")
    }

    private fun checkTerms(moveIn: LocalDate, moveOut: LocalDate?, agreements: List<RentAgreement>) {
        val problems = BillingCycleCalculator.validate(moveIn, moveOut, termsOf(agreements))
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun AgreementInput.toAgreement(tenancyId: Long, anchor: Int) =
        RentAgreement(0, tenancyId, effectiveFrom, null, rentPaise, anchor, lateFeePaise, graceDays, notes.cleaned())
}
