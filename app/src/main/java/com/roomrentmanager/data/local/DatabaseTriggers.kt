package com.roomrentmanager.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database-level guards for financial integrity. The repositories already validate these rules;
 * the triggers make sure no code path (including future ones) can break them.
 *
 * Room does not validate triggers, so they are created with IF NOT EXISTS on every open as well as
 * in migrations. Changing a trigger's body requires a migration that drops and recreates it.
 */
object DatabaseTriggers {

    private fun abort(message: String) = "SELECT RAISE(ABORT, '$message');"

    val ALL: List<String> = listOf(
        // --- Tenancies: one active tenancy per room via the unique activeRoomId column ---------
        """
        CREATE TRIGGER IF NOT EXISTS trg_tenancies_check_insert BEFORE INSERT ON tenancies
        WHEN (NEW.status = 'ACTIVE' AND (NEW.activeRoomId IS NULL OR NEW.activeRoomId != NEW.roomId))
          OR (NEW.status != 'ACTIVE' AND NEW.activeRoomId IS NOT NULL)
          OR (NEW.moveOutDate IS NOT NULL AND NEW.moveOutDate < NEW.moveInDate)
        BEGIN ${abort("invalid tenancy: activeRoomId/status mismatch or move-out before move-in")} END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_tenancies_check_update BEFORE UPDATE ON tenancies
        WHEN (NEW.status = 'ACTIVE' AND (NEW.activeRoomId IS NULL OR NEW.activeRoomId != NEW.roomId))
          OR (NEW.status != 'ACTIVE' AND NEW.activeRoomId IS NOT NULL)
          OR (NEW.moveOutDate IS NOT NULL AND NEW.moveOutDate < NEW.moveInDate)
          OR (OLD.status != 'ACTIVE' AND NEW.status = 'ACTIVE')
          OR NEW.roomId != OLD.roomId OR NEW.tenantId != OLD.tenantId
        BEGIN ${abort("invalid tenancy update")} END
        """,
        "CREATE TRIGGER IF NOT EXISTS trg_tenancies_no_delete BEFORE DELETE ON tenancies BEGIN ${abort("tenancies cannot be deleted")} END",

        // --- Rent ledger: append-only, sign rules, reversals bounded by what is outstanding ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_ledger_check_insert BEFORE INSERT ON rent_ledger
        WHEN (NEW.entryType IN ('RENT_CHARGE', 'LATE_FEE') AND NEW.amountPaise <= 0)
          OR (NEW.entryType IN ('CREDIT', 'REVERSAL') AND NEW.amountPaise >= 0)
          OR (NEW.entryType = 'ADJUSTMENT' AND NEW.amountPaise = 0)
          OR (NEW.entryType = 'REVERSAL') != (NEW.reversesEntryId IS NOT NULL)
        BEGIN ${abort("ledger entry violates the sign convention")} END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_ledger_check_reversal BEFORE INSERT ON rent_ledger
        WHEN NEW.entryType = 'REVERSAL' AND (
          (SELECT tenancyId FROM rent_ledger WHERE id = NEW.reversesEntryId) IS NOT NEW.tenancyId
          OR (SELECT amountPaise FROM rent_ledger WHERE id = NEW.reversesEntryId) <= 0
          OR -NEW.amountPaise > (SELECT amountPaise FROM rent_ledger WHERE id = NEW.reversesEntryId)
               - (SELECT COALESCE(SUM(amountPaise), 0) FROM payment_allocations WHERE ledgerEntryId = NEW.reversesEntryId)
        )
        BEGIN ${abort("reversal exceeds the outstanding amount of the entry")} END
        """,
        "CREATE TRIGGER IF NOT EXISTS trg_ledger_no_update BEFORE UPDATE ON rent_ledger BEGIN ${abort("ledger entries are immutable")} END",
        "CREATE TRIGGER IF NOT EXISTS trg_ledger_no_delete BEFORE DELETE ON rent_ledger BEGIN ${abort("ledger entries are immutable")} END",

        // --- Payments: never deleted; only status (POSTED -> REVERSED) and a null tenancy change
        """
        CREATE TRIGGER IF NOT EXISTS trg_payments_check_insert BEFORE INSERT ON payments
        WHEN NEW.amountPaise <= 0 OR NEW.status != 'POSTED'
        BEGIN ${abort("payments must be inserted as POSTED with a positive amount")} END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_payments_check_update BEFORE UPDATE ON payments
        WHEN NEW.amountPaise != OLD.amountPaise
          OR NEW.paymentDate != OLD.paymentDate
          OR NEW.createdAt != OLD.createdAt
          OR (OLD.tenancyId IS NOT NULL AND NEW.tenancyId IS NOT OLD.tenancyId)
          OR (OLD.status = 'REVERSED' AND NEW.status != 'REVERSED')
        BEGIN ${abort("posted payment details are immutable; reverse it instead")} END
        """,
        "CREATE TRIGGER IF NOT EXISTS trg_payments_no_delete BEFORE DELETE ON payments BEGIN ${abort("payments cannot be deleted; reverse them")} END",

        // --- Allocations: append-only and bounded by both the payment and the charge -----------
        """
        CREATE TRIGGER IF NOT EXISTS trg_allocations_check_insert BEFORE INSERT ON payment_allocations
        WHEN NEW.amountPaise = 0
          OR (NEW.amountPaise < 0) != (NEW.reversesAllocationId IS NOT NULL)
          OR (NEW.amountPaise > 0 AND (SELECT status FROM payments WHERE id = NEW.paymentId) != 'POSTED')
          OR (SELECT tenancyId FROM payments WHERE id = NEW.paymentId) IS NOT
             (SELECT tenancyId FROM rent_ledger WHERE id = NEW.ledgerEntryId)
          OR (NEW.reversesAllocationId IS NOT NULL AND (
               (SELECT amountPaise FROM payment_allocations WHERE id = NEW.reversesAllocationId) != -NEW.amountPaise
               OR (SELECT paymentId FROM payment_allocations WHERE id = NEW.reversesAllocationId) != NEW.paymentId
               OR (SELECT ledgerEntryId FROM payment_allocations WHERE id = NEW.reversesAllocationId) != NEW.ledgerEntryId))
        BEGIN ${abort("invalid payment allocation")} END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_allocations_check_limits AFTER INSERT ON payment_allocations
        WHEN (SELECT SUM(amountPaise) FROM payment_allocations WHERE paymentId = NEW.paymentId)
               > (SELECT amountPaise FROM payments WHERE id = NEW.paymentId)
          OR (SELECT SUM(amountPaise) FROM payment_allocations WHERE paymentId = NEW.paymentId) < 0
          OR (SELECT SUM(amountPaise) FROM payment_allocations WHERE ledgerEntryId = NEW.ledgerEntryId)
               + (SELECT COALESCE(-SUM(amountPaise), 0) FROM rent_ledger WHERE reversesEntryId = NEW.ledgerEntryId)
               > (SELECT amountPaise FROM rent_ledger WHERE id = NEW.ledgerEntryId)
          OR (SELECT SUM(amountPaise) FROM payment_allocations WHERE ledgerEntryId = NEW.ledgerEntryId) < 0
        BEGIN ${abort("allocation exceeds the payment or the outstanding charge")} END
        """,
        "CREATE TRIGGER IF NOT EXISTS trg_allocations_no_update BEFORE UPDATE ON payment_allocations BEGIN ${abort("allocations are immutable")} END",
        "CREATE TRIGGER IF NOT EXISTS trg_allocations_no_delete BEFORE DELETE ON payment_allocations BEGIN ${abort("allocations are immutable")} END",

        // --- Deposits and audit log: append-only -------------------------------------------------
        """
        CREATE TRIGGER IF NOT EXISTS trg_deposits_check_insert BEFORE INSERT ON deposit_transactions
        WHEN (NEW.transactionType != 'ADJUSTMENT' AND NEW.amountPaise <= 0) OR NEW.amountPaise = 0
        BEGIN ${abort("deposit amount must be positive (signed only for ADJUSTMENT)")} END
        """,
        "CREATE TRIGGER IF NOT EXISTS trg_deposits_no_update BEFORE UPDATE ON deposit_transactions BEGIN ${abort("deposit transactions are immutable")} END",
        "CREATE TRIGGER IF NOT EXISTS trg_deposits_no_delete BEFORE DELETE ON deposit_transactions BEGIN ${abort("deposit transactions are immutable")} END",
        "CREATE TRIGGER IF NOT EXISTS trg_audit_no_update BEFORE UPDATE ON audit_log BEGIN ${abort("audit log is append-only")} END",
        "CREATE TRIGGER IF NOT EXISTS trg_audit_no_delete BEFORE DELETE ON audit_log BEGIN ${abort("audit log is append-only")} END",
    ).map { it.trimIndent() }

    fun create(db: SupportSQLiteDatabase) = ALL.forEach(db::execSQL)
}
