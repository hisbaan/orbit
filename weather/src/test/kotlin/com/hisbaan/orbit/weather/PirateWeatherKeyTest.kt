package com.hisbaan.orbit.weather

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PirateWeatherKeyTest {
    @Test
    fun `request errors don't carry the key`() = runTest {
        // Ktor's timeout and connection errors quote the URL, which holds the key.
        val client = HttpClient(MockEngine { request -> throw IOException("Timed out [url=${request.url}]") })
        val error = runCatching { PirateWeather(client, "s3cret").forecast(43.6, -79.4, 1, false, 1) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error!!.message!!, "s3cret" in error.message!!)
        assertTrue(error.message!!, "<key>" in error.message!!)
    }
}
