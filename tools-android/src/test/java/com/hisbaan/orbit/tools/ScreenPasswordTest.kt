package com.hisbaan.orbit.tools

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPasswordTest {
    @Test
    fun `password fields are skipped, address bars aren't`() {
        assertTrue(ScreenContext.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(ScreenContext.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(ScreenContext.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        // Same variation bits as the number password, but a URL.
        assertFalse(ScreenContext.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(ScreenContext.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
}
