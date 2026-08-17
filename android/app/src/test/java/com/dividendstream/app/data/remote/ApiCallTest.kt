package com.dividendstream.app.data.remote

import com.dividendstream.app.core.AppResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The distinction these tests protect is a small one to write and an easy one to lose: a
 * timeout is not the same failure as having no network, and telling a user with four bars of
 * signal that they are offline sends them to fix something that is not broken.
 */
class ApiCallTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun codeOf(result: AppResult<String>) = (result as AppResult.Failure).error.code

    @Test
    fun `a read timeout is reported as the server waking up`() = runTest {
        // What a sleeping free-tier container looks like from the client: the connection is
        // made, then nothing comes back.
        val result = apiCall<String>(json) { throw SocketTimeoutException("timeout") }
        assertEquals("SERVER_WAKING", codeOf(result))
    }

    @Test
    fun `OkHttp's whole-call timeout is reported as the server waking up`() = runTest {
        // It arrives as the supertype rather than SocketTimeoutException, which is exactly the
        // sort of detail a single catch of IOException would have swallowed.
        val result = apiCall<String>(json) { throw InterruptedIOException("timeout") }
        assertEquals("SERVER_WAKING", codeOf(result))
    }

    @Test
    fun `genuine connection failures are still reported as offline`() = runTest {
        val result = apiCall<String>(json) { throw UnknownHostException("no dns") }
        assertEquals("OFFLINE", codeOf(result))

        val refused = apiCall<String>(json) { throw ConnectException("refused") }
        assertEquals("OFFLINE", codeOf(refused))
    }

    @Test
    fun `a success passes the value straight through`() = runTest {
        val result = apiCall(json) { "ok" }
        assertEquals("ok", (result as AppResult.Success).data)
    }

    @Test
    fun `an unexpected exception never leaks its message to the user`() = runTest {
        val result = apiCall<String>(json) {
            throw IllegalStateException("jdbc:postgresql://user:hunter2@db.internal/prod")
        }
        assertEquals("UNEXPECTED", codeOf(result))
        assertEquals(
            "Something went wrong. Please try again.",
            (result as AppResult.Failure).error.message,
        )
    }
}
