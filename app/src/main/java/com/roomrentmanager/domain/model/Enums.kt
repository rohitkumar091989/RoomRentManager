package com.roomrentmanager.domain.model

/** Controlled status values. Stored by name via explicit converters in DatabaseConverters. */

enum class PropertyType { RESIDENTIAL, COMMERCIAL }

enum class TenancyStatus { ACTIVE, ENDED, CANCELLED }

enum class BillingMode { ANNIVERSARY }

/**
 * Ledger sign convention (used everywhere, see FinanceCalculator):
 * positive amount = owed by the tenant; negative amount = credit to the tenant.
 * - RENT_CHARGE, LATE_FEE: positive.
 * - ADJUSTMENT: positive (extra charge) or negative (credit).
 * - CREDIT: negative.
 * - REVERSAL: negative, cancels the remaining amount of the entry in `reversesEntryId`.
 */
enum class LedgerEntryType { RENT_CHARGE, ADJUSTMENT, LATE_FEE, CREDIT, REVERSAL }

enum class PaymentMethod { CASH, UPI, BANK_TRANSFER, OTHER }

enum class PaymentStatus { POSTED, REVERSED }

enum class DepositTransactionType { RECEIVED, REFUND, DEDUCTION, ADJUSTMENT }

enum class MaintenancePriority { LOW, NORMAL, HIGH, URGENT }

enum class MaintenanceStatus { OPEN, IN_PROGRESS, COMPLETED, CANCELLED }

enum class ReminderStatus { PENDING, SHOWN, DISMISSED }

enum class ReviewEntityType { PAYMENT, TENANT }

/**
 * Migration review state for records imported from v1 that could not be reconciled automatically.
 * Payments in PENDING or KEPT_AS_HISTORY do not count as tenant credit.
 */
enum class ReviewStatus { PENDING, KEPT_AS_HISTORY, APPLIED_TO_TENANCY, RESOLVED }
