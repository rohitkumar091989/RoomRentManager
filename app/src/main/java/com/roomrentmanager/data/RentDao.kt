package com.roomrentmanager.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RentDao {
    @Query("SELECT * FROM rooms ORDER BY name COLLATE NOCASE")
    fun rooms(): Flow<List<RentalRoom>>

    @Insert
    suspend fun insertRoom(room: RentalRoom): Long

    @Update
    suspend fun updateRoom(room: RentalRoom)

    @Delete
    suspend fun deleteRoom(room: RentalRoom)

    @Query(
        """
        SELECT t.*, r.name AS roomName, r.monthlyRent AS monthlyRent
        FROM tenants t LEFT JOIN rooms r ON t.roomId = r.id
        ORDER BY t.name COLLATE NOCASE
        """
    )
    fun tenants(): Flow<List<TenantRow>>

    @Insert
    suspend fun insertTenant(tenant: Tenant): Long

    @Update
    suspend fun updateTenant(tenant: Tenant)

    @Delete
    suspend fun deleteTenant(tenant: Tenant)

    @Query(
        """
        SELECT p.*, t.name AS tenantName
        FROM payments p JOIN tenants t ON p.tenantId = t.id
        ORDER BY p.paidOn DESC, p.id DESC
        """
    )
    fun payments(): Flow<List<PaymentRow>>

    @Insert
    suspend fun insertPayment(payment: Payment): Long

    @Delete
    suspend fun deletePayment(payment: Payment)
}
