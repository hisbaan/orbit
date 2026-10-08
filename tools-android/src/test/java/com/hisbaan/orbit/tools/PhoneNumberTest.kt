package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberTest {
    @Test
    fun `numbers in their usual formats are dialable`() {
        assertEquals("5551234567", CallContactTool.phoneNumber("(555) 123-4567"))
        assertEquals("+15551234567", CallContactTool.phoneNumber("+1 (555) 123-4567"))
        assertEquals("5551234567", CallContactTool.phoneNumber("555.123.4567"))
        assertEquals("911", CallContactTool.phoneNumber("911"))
    }

    @Test
    fun `names aren't numbers`() {
        assertNull(CallContactTool.phoneNumber("Alex"))
        assertNull(CallContactTool.phoneNumber("Mom"))
        assertNull(CallContactTool.phoneNumber("Unit 42"))
        assertNull(CallContactTool.phoneNumber("12"))
    }
}
