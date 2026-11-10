# Room Rent Manager

An offline Android app for landlords to manage properties, rooms or commercial units, tenants,
tenancies, rent, payments and security deposits. Everything is stored on the device (Room / SQLite);
no account, backend or internet connection is needed.

## Features

- **Dashboard**: pick a month to see rent due, received and unpaid; totals for outstanding, overdue
  and advance credit; occupied/vacant counts; rent due in the next 7 days; each tenant's balance
  with a one-tap **Collect**.
- **Units**: properties (residential or commercial) and their rooms, flats or shops. Rooms show
  occupied/vacant and the agreed rent. Properties and rooms are archived, never deleted.
- **Tenants**: contact details and a detail screen per tenant with every tenancy (current and past),
  rent agreements, the rent ledger, payments and deposit history.
- **Tenancies**: one active tenant per room. Start a tenancy with move-in date, rent, rent day and
  an optional deposit; change rent with a new effective-dated agreement; end it with a move-out date.
  Ending a tenancy keeps all history and makes the room vacant.
- **Payments**: full or partial payments by cash, UPI, bank transfer or other, applied oldest-due
  first, to selected charges, or kept as advance credit. Payments are reversed with a reason, never
  deleted.
- **Adjustments**: extra charges, late fees, credits, and cancelling the unpaid part of a charge.
- **Deposits**: received, deducted (with a reason) and refunded, tracked separately from rent.
- **Imported records**: data from app version 1 is migrated automatically. Anything the old version
  could not record reliably is listed under **Payments** for review rather than guessed.
- **Expenses** (More tab): costs per property or per unit, by category, with the vendor. Corrections
  are audited; expenses are never deleted.
- **Maintenance** (More tab): requests with priority, status and cost, linked to the unit's current
  tenancy. Completing a job can also record its cost as an expense.
- **Reports** (More tab): rent collection by month, outstanding rent by age (1–30, 31–60, 61–90,
  90+ days), tenant statement with running balance, deposit statement, property income and
  expenses (month, calendar year or April–March financial year), and occupancy/vacancy. Any report
  can be shared as text.
- **Reminders**: rent due soon, rent overdue, deposits still held after move-out, and open
  maintenance. Shown on the dashboard and, if allowed, as phone notifications. **Remind** on a
  tenant opens your phone's share sheet (SMS, WhatsApp, ...) with a ready-made message; the app
  itself sends nothing.

## How the money works

- Amounts are stored as whole **paise** (`Long`); rupees appear only on screen. No floating point.
- **Anniversary billing**: rent is due in advance on the agreement's rent day each month. If a month
  is shorter (rent day 31 in February), the last day is used, and later months return to the 31st.
- Rent charges are posted to an **append-only ledger** with a unique key per period, so posting is
  idempotent (it runs whenever the app opens).
- A rent change creates a new agreement that starts on a rent day. Charges already posted keep their
  amounts. Mid-period changes are rejected until a proration policy is agreed.
- **Move-out** is the handover day: periods starting on or after it are not charged; a period that
  started before it is charged in full.
- A tenant's **net balance** is never clamped: positive means they owe, negative is advance credit.
- Deposits never pay rent automatically.

Database triggers back these rules up: ledger, allocation, deposit and audit rows cannot be edited
or deleted, payments cannot be deleted, allocations cannot exceed the payment or the charge, and a
room cannot have two active tenancies. Every financial action is written to an audit log.

## Reminders

A WorkManager job runs once a day and shortly after the app is opened or data changes. It works out
reminders from stored tenancies, agreements, the ledger, deposits and maintenance, and records each
one in `app_notifications` under a fixed key (for example `overdue:{tenancy}:{oldest due date}`), so
the same reminder is never raised twice. Reminders never create charges; rent is posted when the
app is opened. WorkManager keeps the daily job across reboots and app updates. On Android 13+ the
app asks for notification permission from **More → Reminders**; without it, reminders still appear
in the app. Reminder preferences are stored on the device only (not in the database).

## Upgrading from version 1

The v1 → v2 migration (`data/local/migrations/Migration1To2.kt`) runs once, in a transaction:

- Room, tenant and payment IDs are kept; rooms are placed under an "Imported Property".
- Rupee amounts are converted to paise (shortest decimal form, rounded half-up).
- A tenancy is created only when a tenant's room and move-in date are valid and no one else shared
  that room. No rent agreements or historical charges are invented, because v1 did not store them.
- Every v1 payment is kept, unlinked, with its original month, date and note, and appears for
  review: count it as credit on a tenancy, or keep it as history only.
- Counts, totals and foreign keys are validated before the old tables are dropped. If anything does
  not match, the migration fails and the v1 data is left untouched.

After upgrading, open each tenant and **Set rent** to start charging rent.

## Project layout

```
app/src/main/java/com/roomrentmanager/
  AppContainer.kt        manual dependency wiring
  domain/
    finance/             Money, BillingCycleCalculator, FinanceCalculator (pure, unit-tested)
    model/               domain models and enums
    repository/          repository interfaces
    usecase/             business rules: tenancy, rent generation, payments, reversal, deposits
  data/
    local/               Room entities, DAOs, converters, triggers, database, migrations
    repository/          Room-backed repository implementations
  data/settings/         device preferences (reminder settings)
  worker/                ReminderWorker, scheduler and notifications
  ui/                    Compose screens, dialogs, ViewModel
app/schemas/             exported Room schemas (committed; used by migration tests)
```

## Tech

Kotlin, Jetpack Compose (Material 3), Room with KSP, ViewModel + StateFlow, coroutines.
`minSdk` 26, `targetSdk` 34. Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, JDK 17+.

## Building and testing

With the Android SDK installed and `ANDROID_HOME` set (or `sdk.dir` in `local.properties`):

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit, database, migration and UI tests (Robolectric, no device needed)
```

Tests cover money conversion, billing dates (month ends, leap years, move-out), allocation, reversal,
deposits, database integrity rules, the v1 → v2 migration against a real v1 database, expenses,
maintenance, every report, reminder planning and de-duplication, the reminder worker's
notifications, and the main screens. Exported schemas are added to debug-build assets so Robolectric migration tests can read
them; release builds do not include them.

Install on a phone with `adb install app/build/outputs/apk/debug/app-debug.apk`, or in Android
Studio use **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

## Not yet built

Planned for the next milestone: backup/restore and data export, app lock, a privacy review (including
the Android backup policy), and accessibility polish. Tenant-facing screens on a tenant's own phone,
and sending reminders to tenants automatically, would need a backend and are out of scope for now.
