package com.roomrentmanager.data.local

import androidx.room.TypeConverter
import com.roomrentmanager.domain.model.BillingMode
import com.roomrentmanager.domain.model.DepositTransactionType
import com.roomrentmanager.domain.model.LedgerEntryType
import com.roomrentmanager.domain.model.MaintenancePriority
import com.roomrentmanager.domain.model.MaintenanceStatus
import com.roomrentmanager.domain.model.PaymentMethod
import com.roomrentmanager.domain.model.PaymentStatus
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.ReminderStatus
import com.roomrentmanager.domain.model.ReviewEntityType
import com.roomrentmanager.domain.model.ReviewStatus
import com.roomrentmanager.domain.model.TenancyStatus

/**
 * Enums are stored as their constant names. Renaming a constant is a schema change and needs a
 * migration. Unknown stored values fail loudly instead of being guessed.
 */
class DatabaseConverters {
    @TypeConverter fun fromPropertyType(v: PropertyType): String = v.name
    @TypeConverter fun toPropertyType(v: String): PropertyType = PropertyType.valueOf(v)

    @TypeConverter fun fromTenancyStatus(v: TenancyStatus): String = v.name
    @TypeConverter fun toTenancyStatus(v: String): TenancyStatus = TenancyStatus.valueOf(v)

    @TypeConverter fun fromBillingMode(v: BillingMode): String = v.name
    @TypeConverter fun toBillingMode(v: String): BillingMode = BillingMode.valueOf(v)

    @TypeConverter fun fromLedgerEntryType(v: LedgerEntryType): String = v.name
    @TypeConverter fun toLedgerEntryType(v: String): LedgerEntryType = LedgerEntryType.valueOf(v)

    @TypeConverter fun fromPaymentMethod(v: PaymentMethod): String = v.name
    @TypeConverter fun toPaymentMethod(v: String): PaymentMethod = PaymentMethod.valueOf(v)

    @TypeConverter fun fromPaymentStatus(v: PaymentStatus): String = v.name
    @TypeConverter fun toPaymentStatus(v: String): PaymentStatus = PaymentStatus.valueOf(v)

    @TypeConverter fun fromDepositType(v: DepositTransactionType): String = v.name
    @TypeConverter fun toDepositType(v: String): DepositTransactionType = DepositTransactionType.valueOf(v)

    @TypeConverter fun fromPriority(v: MaintenancePriority): String = v.name
    @TypeConverter fun toPriority(v: String): MaintenancePriority = MaintenancePriority.valueOf(v)

    @TypeConverter fun fromMaintenanceStatus(v: MaintenanceStatus): String = v.name
    @TypeConverter fun toMaintenanceStatus(v: String): MaintenanceStatus = MaintenanceStatus.valueOf(v)

    @TypeConverter fun fromReminderStatus(v: ReminderStatus): String = v.name
    @TypeConverter fun toReminderStatus(v: String): ReminderStatus = ReminderStatus.valueOf(v)

    @TypeConverter fun fromReviewEntityType(v: ReviewEntityType): String = v.name
    @TypeConverter fun toReviewEntityType(v: String): ReviewEntityType = ReviewEntityType.valueOf(v)

    @TypeConverter fun fromReviewStatus(v: ReviewStatus): String = v.name
    @TypeConverter fun toReviewStatus(v: String): ReviewStatus = ReviewStatus.valueOf(v)
}
