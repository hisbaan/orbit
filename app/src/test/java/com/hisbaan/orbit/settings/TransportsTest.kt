package com.hisbaan.orbit.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportsTest {
    @Test
    fun `warns about http to the internet, not to a server at home`() {
        assertTrue(sendsKeyInTheClear("http://api.example.com/v1"))
        assertTrue(sendsKeyInTheClear("http://8.8.8.8:8080/v1"))
        assertFalse(sendsKeyInTheClear("https://api.openai.com/v1"))
        assertFalse(sendsKeyInTheClear("http://192.168.1.20:11434/v1"))
        assertFalse(sendsKeyInTheClear("http://10.0.0.5/v1"))
        assertFalse(sendsKeyInTheClear("http://172.20.0.2:8000/v1"))
        assertFalse(sendsKeyInTheClear("http://localhost:1234/v1"))
        assertFalse(sendsKeyInTheClear("http://ollama.local:11434"))
        assertFalse(sendsKeyInTheClear("http://[::1]:8080"))
    }
}
