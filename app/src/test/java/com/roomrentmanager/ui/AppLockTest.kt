package com.roomrentmanager.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roomrentmanager.ui.lock.AppLockGate
import com.roomrentmanager.ui.lock.AppLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLockTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun policy() {
        assertTrue(AppLockPolicy.lockOnStart(true))
        assertFalse(AppLockPolicy.lockOnStart(false))
        assertFalse("never backgrounded", AppLockPolicy.lockOnReturn(true, null, 100_000))
        assertFalse("quick switch away and back", AppLockPolicy.lockOnReturn(true, 100_000, 129_999))
        assertTrue(AppLockPolicy.lockOnReturn(true, 100_000, 130_000))
        assertFalse("lock off", AppLockPolicy.lockOnReturn(false, 0, 1_000_000))
    }

    @Test
    fun lockedGateHidesContentAndAsksToUnlock() {
        var requests = 0
        compose.setContent { AppLockGate(locked = true, onUnlockRequest = { requests++ }) { Text("Secret tenant data") } }
        compose.onNodeWithText("Room Rent Manager is locked").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Secret tenant data")).fetchSemanticsNodes().size)
        assertEquals("prompts automatically", 1, requests)
        compose.onNodeWithText("Unlock").performClick()
        assertEquals(2, requests)
    }

    @Test
    fun unlockedGateShowsContent() {
        compose.setContent { AppLockGate(locked = false, onUnlockRequest = {}) { Text("Secret tenant data") } }
        compose.onNodeWithText("Secret tenant data").assertIsDisplayed()
    }
}
