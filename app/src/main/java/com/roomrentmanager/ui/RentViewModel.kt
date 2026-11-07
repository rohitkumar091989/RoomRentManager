package com.roomrentmanager.ui

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomrentmanager.AppContainer
import com.roomrentmanager.domain.finance.AllocationMode
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.Portfolio
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ValidationException
import com.roomrentmanager.domain.usecase.AgreementInput
import com.roomrentmanager.domain.usecase.Dashboard
import com.roomrentmanager.domain.usecase.PaymentInput
import com.roomrentmanager.domain.usecase.PortfolioSummaries
import com.roomrentmanager.domain.usecase.RoomView
import com.roomrentmanager.domain.usecase.StartTenancyInput
import com.roomrentmanager.domain.usecase.TenancyView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class UiState(
    val loaded: Boolean,
    val today: LocalDate,
    val month: YearMonth,
    val portfolio: Portfolio = Portfolio(),
    val tenancies: List<TenancyView> = emptyList(),
    val rooms: List<RoomView> = emptyList(),
    val dashboard: Dashboard? = null,
)

/**
 * Renders state and dispatches user actions to use cases. No financial rules live here.
 * Each action takes an `onSuccess` callback so dialogs close only after a successful save and
 * keep the user's input when validation fails.
 */
class RentViewModel(app: Application) : AndroidViewModel(app) {
    private val c = AppContainer.get(app)
    private val month = MutableStateFlow(YearMonth.now(c.clock))
    private val today = MutableStateFlow(LocalDate.now(c.clock))

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<UiState> =
        combine(c.portfolio.observePortfolio(), month, today) { p, m, t ->
            val views = PortfolioSummaries.tenancyViews(p, t)
            UiState(
                loaded = true, today = t, month = m, portfolio = p, tenancies = views,
                rooms = PortfolioSummaries.roomViews(p, views),
                dashboard = PortfolioSummaries.dashboard(p, views, m, t),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(false, today.value, month.value))

    /** Posts any rent that has fallen due. Safe to call repeatedly (e.g. on every resume). */
    fun refreshCharges() {
        today.value = LocalDate.now(c.clock)
        viewModelScope.launch {
            val result = runCatching { c.generateRent.forAll(today.value) }
            result.exceptionOrNull()?.let { Log.e(TAG, "Rent generation failed", it) }
            result.getOrNull()?.problems?.takeIf { it.isNotEmpty() }?.let {
                Log.w(TAG, it.joinToString("\n"))
                _messages.emit("Rent could not be posted for ${it.size} tenancy(ies). Check their rent agreements.")
            }
        }
    }

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }

    private fun run(onSuccess: () -> Unit = {}, successMessage: String? = null, block: suspend AppContainer.() -> Unit) {
        viewModelScope.launch {
            try {
                c.block()
                onSuccess()
                successMessage?.let { _messages.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ValidationException) {
                _messages.emit(e.message ?: "Please check the details.")
            } catch (e: SQLiteConstraintException) {
                Log.w(TAG, "Integrity rule blocked a write", e)
                _messages.emit("Not saved: this would break a data-integrity rule.")
            } catch (e: Exception) {
                Log.e(TAG, "Action failed", e)
                _messages.emit("Something went wrong. Nothing was changed.")
            }
        }
    }

    // Properties and rooms
    fun saveProperty(id: Long?, name: String, type: PropertyType, address: String?, notes: String?, onSuccess: () -> Unit) =
        run(onSuccess) { manageProperties.saveProperty(id, name, type, address, notes) }
    fun setPropertyActive(id: Long, active: Boolean) = run { manageProperties.setPropertyActive(id, active) }
    fun saveRoom(id: Long?, propertyId: Long, name: String, rentPaise: Long?, notes: String?, onSuccess: () -> Unit) =
        run(onSuccess) { manageProperties.saveRoom(id, propertyId, name, rentPaise, notes) }
    fun setRoomActive(id: Long, active: Boolean) = run { manageProperties.setRoomActive(id, active) }

    // Tenants and tenancies
    fun saveTenant(id: Long?, name: String, phone: String?, email: String?, address: String?, notes: String?, onSuccess: () -> Unit) =
        run(onSuccess) { manageTenancy.saveTenant(id, name, phone, email, address, notes) }
    fun setTenantActive(id: Long, active: Boolean) = run { manageTenancy.setTenantActive(id, active) }
    fun startTenancy(input: StartTenancyInput, onSuccess: () -> Unit) =
        run(onSuccess, "Tenancy started.") { manageTenancy.startTenancy(input) }
    fun addAgreement(tenancyId: Long, input: AgreementInput, onSuccess: () -> Unit) =
        run(onSuccess, "Rent agreement saved.") { manageTenancy.addAgreement(tenancyId, input) }
    fun endTenancy(tenancyId: Long, moveOut: LocalDate, onSuccess: () -> Unit) =
        run(onSuccess, "Tenancy ended. The room is now vacant.") { manageTenancy.endTenancy(tenancyId, moveOut) }

    // Money
    fun recordPayment(input: PaymentInput, onSuccess: () -> Unit) = run(onSuccess, "Payment recorded.") { this.recordPayment(input) }
    fun allocatePayment(paymentId: Long, mode: AllocationMode) = run { this.allocatePayment(paymentId, mode) }
    fun applyCredit(tenancyId: Long) = run { allocatePayment.applyCredit(tenancyId) }
    fun reversePayment(paymentId: Long, reason: String, onSuccess: () -> Unit) =
        run(onSuccess, "Payment reversed.") { this.reversePayment(paymentId, reason) }
    fun recordDeposit(tenancyId: Long, type: DepositTransactionType, amountPaise: Long, date: LocalDate, note: String?, onSuccess: () -> Unit) =
        run(onSuccess) { this.recordDeposit(tenancyId, type, amountPaise, date, note) }
    fun addCharge(tenancyId: Long, type: LedgerEntryType, amountPaise: Long, dueDate: LocalDate, description: String, onSuccess: () -> Unit) =
        run(onSuccess) { adjustments.addCharge(tenancyId, type, amountPaise, dueDate, description) }
    fun addCredit(tenancyId: Long, amountPaise: Long, description: String, onSuccess: () -> Unit) =
        run(onSuccess) { adjustments.addCredit(tenancyId, amountPaise, description) }
    fun cancelCharge(entryId: Long, reason: String, onSuccess: () -> Unit) = run(onSuccess) { adjustments.cancelCharge(entryId, reason) }

    // Imported v1 records
    fun applyLegacyPayment(reviewId: Long, tenancyId: Long) = run { resolveReview.applyPaymentToTenancy(reviewId, tenancyId) }
    fun keepLegacyPayment(reviewId: Long) = run { resolveReview.keepPaymentAsHistory(reviewId) }
    fun markTenantReviewed(reviewId: Long) = run { resolveReview.markTenantReviewed(reviewId) }

    private companion object {
        const val TAG = "RentViewModel"
    }
}
