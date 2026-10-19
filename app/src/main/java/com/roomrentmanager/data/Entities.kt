package com.roomrentmanager.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rooms")
data class RentalRoom(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val monthlyRent: Double,
)

@Entity(
    tableName = "tenants",
    foreignKeys = [
        ForeignKey(
            entity = RentalRoom::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("roomId")],
)
data class Tenant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val roomId: Long?,
    /** ISO date, e.g. 2026-10-01. */
    val moveInDate: String,
)

@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = Tenant::class,
            parentColumns = ["id"],
            childColumns = ["tenantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tenantId"), Index("month")],
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenantId: Long,
    /** The rent month this payment is for, as ISO year-month, e.g. 2026-10. */
    val month: String,
    val amount: Double,
    /** ISO date the money was received. */
    val paidOn: String,
    val note: String = "",
)

data class TenantRow(
    @Embedded val tenant: Tenant,
    val roomName: String?,
    val monthlyRent: Double?,
)

data class PaymentRow(
    @Embedded val payment: Payment,
    val tenantName: String,
)
