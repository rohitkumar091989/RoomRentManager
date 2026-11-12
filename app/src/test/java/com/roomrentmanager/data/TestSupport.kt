package com.roomrentmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roomrentmanager.AppContainer
import com.roomrentmanager.data.local.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move forward to simulate days passing. */
class MutableClock(var date: LocalDate) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = date.atTime(12, 0).toInstant(ZoneOffset.UTC)
}

fun inMemoryDatabase(): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return AppDatabase.configure(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
        .allowMainThreadQueries()
        .build()
}

fun testContainer(clock: MutableClock, db: AppDatabase = inMemoryDatabase()) = AppContainer(db, clock)
