package com.kreativekoala.surfsense.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class ApiFailureTest {
    private fun err(code: Int, body: String) =
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType()))

    @Test fun parsesTheServersErrorField() {
        val f = err(400, """{"success":false,"error":"Invalid or expired code"}""").toFailure()
        assertEquals(400, f.code)
        assertEquals("Invalid or expired code", f.serverMessage)
    }

    @Test fun ignoresNonJsonOrMissingFields() {
        assertNull(err(502, "<html>bad gateway</html>").toFailure().serverMessage)
        assertNull(err(400, "{}").toFailure().serverMessage)
        assertNull(err(500, "").toFailure().serverMessage)
    }

    @Test fun messagesByCause() {
        assertEquals("No connection. Check your internet and try again.", ErrorMessages.from(IOException("x")))
        assertEquals("No connection. Check your internet and try again.", ErrorMessages.from(SocketTimeoutException()))
        assertEquals("This device isn't recognized by the server. Close and reopen the app to reconnect.", ErrorMessages.from(ApiFailure(401, null)))
        assertEquals("This device isn't recognized by the server. Close and reopen the app to reconnect.", ErrorMessages.from(ApiFailure(403, "Not authorized")))
        assertEquals("Too many requests. Try again in a minute.", ErrorMessages.from(ApiFailure(429, "Too many linking attempts, please try again later")))
        assertEquals("The server is having trouble. Try again soon.", ErrorMessages.from(ApiFailure(503, null)))
        assertEquals("Invalid or expired code", ErrorMessages.from(ApiFailure(400, "Invalid or expired code")))
        assertEquals("Something went wrong (404).", ErrorMessages.from(ApiFailure(404, null)))
        assertEquals("Something went wrong.", ErrorMessages.from(IllegalStateException("x")))
    }
}
