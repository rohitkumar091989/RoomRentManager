package com.roomrentmanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomrentmanager.data.AppDatabase
import com.roomrentmanager.data.Payment
import com.roomrentmanager.data.PaymentRow
import com.roomrentmanager.data.RentalRoom
import com.roomrentmanager.data.Tenant
import com.roomrentmanager.data.TenantRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Rent owed and received for one tenant in one month. */
data class TenantDue(val row: TenantRow, val due: Double, val paid: Double) {
    val balance: Double get() = (due - paid).coerceAtLeast(0.0)
}

data class MonthSummary(
    val month: YearMonth,
    val dues: List<TenantDue>,
) {
    val expected: Double get() = dues.sumOf { it.due }
    val collected: Double get() = dues.sumOf { it.paid }
    val pending: Double get() = dues.sumOf { it.balance }
}

class RentViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).rentDao()

    val rooms: StateFlow<List<RentalRoom>> =
        dao.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tenants: StateFlow<List<TenantRow>> =
        dao.tenants().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val payments: StateFlow<List<PaymentRow>> =
        dao.payments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val summary: StateFlow<MonthSummary> =
        combine(tenants, payments, _month) { tenantRows, paymentRows, month ->
            val key = month.toString()
            val paidByTenant = paymentRows
                .filter { it.payment.month == key }
                .groupBy { it.payment.tenantId }
                .mapValues { (_, list) -> list.sumOf { it.payment.amount } }
            val dues = tenantRows
                .filter { it.monthlyRent != null && movedInBy(it.tenant, month) }
                .map { TenantDue(it, it.monthlyRent ?: 0.0, paidByTenant[it.tenant.id] ?: 0.0) }
            MonthSummary(month, dues)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MonthSummary(YearMonth.now(), emptyList()),
        )

    fun previousMonth() = _month.update { it.minusMonths(1) }
    fun nextMonth() = _month.update { it.plusMonths(1) }

    fun saveRoom(room: RentalRoom) = viewModelScope.launch {
        if (room.id == 0L) dao.insertRoom(room) else dao.updateRoom(room)
    }

    fun deleteRoom(room: RentalRoom) = viewModelScope.launch { dao.deleteRoom(room) }

    fun saveTenant(tenant: Tenant) = viewModelScope.launch {
        if (tenant.id == 0L) dao.insertTenant(tenant) else dao.updateTenant(tenant)
    }

    fun deleteTenant(tenant: Tenant) = viewModelScope.launch { dao.deleteTenant(tenant) }

    fun addPayment(payment: Payment) = viewModelScope.launch { dao.insertPayment(payment) }

    fun deletePayment(payment: Payment) = viewModelScope.launch { dao.deletePayment(payment) }

    private fun movedInBy(tenant: Tenant, month: YearMonth): Boolean {
        val moveIn = runCatching { YearMonth.from(LocalDate.parse(tenant.moveInDate)) }.getOrNull()
        return moveIn == null || !moveIn.isAfter(month)
    }
}
