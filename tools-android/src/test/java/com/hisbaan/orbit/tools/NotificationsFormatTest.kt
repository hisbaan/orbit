package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsFormatTest {
    private val now = 10 * 60 * 60_000L

    @Test
    fun `lists newest first with age, title and recent messages`() {
        val items = listOf(
            NotificationText("Messages", "Alex", listOf("Alex: one", "Alex: two", "Alex: three", "Alex: four", "Alex: five"), now - 30_000),
            NotificationText("Gmail", "Invoice", listOf("Your invoice is ready"), now - 2 * 60 * 60_000),
        )
        assertEquals(
            """
            2 notifications, newest first:
            - [Messages, just now] Alex (1 earlier messages)
              Alex: two
              Alex: three
              Alex: four
              Alex: five
            - [Gmail, 2 h ago] Invoice
              Your invoice is ready
            """.trimIndent(),
            NotificationsTool.format(items, limit = 10, now = now),
        )
    }

    @Test
    fun `says how many were left out, and handles none`() {
        val items = List(3) { NotificationText("App", "t$it", emptyList(), now - it * 60_000L) }
        assertEquals(
            "3 notifications, newest first (showing 1):\n- [App, just now] t0",
            NotificationsTool.format(items, limit = 1, now = now),
        )
        assertEquals("No notifications from Slack.", NotificationsTool.format(emptyList(), 10, now, app = "Slack"))
    }
}
