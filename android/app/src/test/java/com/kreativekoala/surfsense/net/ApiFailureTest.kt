package com.kreativekoala.surfsense.net

import com.kreativekoala.surfsense.R
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

    @Test fun parsesTheStableCode() {
        val f = err(400, """{"success":false,"error":"Code expired","code":"LINK_CODE_EXPIRED"}""").toFailure()
        assertEquals("LINK_CODE_EXPIRED", f.serverCode)
        assertNull(err(400, """{"error":"x"}""").toFailure().serverCode)
        assertNull(err(502, "<html>").toFailure().serverCode)
    }

    private fun res(t: Throwable) = ErrorMessages.messageFor(t).res

    @Test fun messagesByCause() {
        assertEquals(R.string.error_no_connection, res(IOException("x")))
        assertEquals(R.string.error_no_connection, res(SocketTimeoutException()))
        assertEquals(R.string.error_device_not_recognized, res(ApiFailure(401, null)))
        assertEquals(R.string.error_device_not_recognized, res(ApiFailure(403, "Not authorized")))
        assertEquals(R.string.error_too_many_requests, res(ApiFailure(429, "Too many requests, please try again later", "RATE_LIMITED")))
        assertEquals(R.string.error_server_trouble, res(ApiFailure(503, null)))
        assertEquals(R.string.error_something_wrong, res(IllegalStateException("x")))
    }

    @Test fun backendCodesMapToLocalizedMessages() {
        assertEquals(R.string.error_link_code_invalid, res(ApiFailure(400, "Invalid or expired code", "LINK_CODE_INVALID")))
        assertEquals(R.string.error_link_code_expired, res(ApiFailure(400, "Code expired", "LINK_CODE_EXPIRED")))
        assertEquals(R.string.error_link_self, res(ApiFailure(400, "Cannot link a device to itself", "LINK_SELF")))
        assertEquals(R.string.error_link_code_format, res(ApiFailure(400, "Code must be exactly 6 numeric digits", "INVALID_CODE_FORMAT")))
        assertEquals(R.string.error_linking_rate_limited, res(ApiFailure(429, "Too many linking attempts, please try again later", "LINKING_RATE_LIMITED")))
    }

    @Test fun serverEnglishTextIsNeverShown() {
        // Unknown or missing code: generic localized message, not the server's `error` string.
        val m = ErrorMessages.messageFor(ApiFailure(404, "Item not found", "SOMETHING_NEW"))
        assertEquals(R.string.error_something_wrong_code, m.res)
        assertEquals(listOf<Any>(404), m.args)
        assertEquals(R.string.error_invalid_request, res(ApiFailure(400, "Invalid or expired code")))
        assertEquals(R.string.error_something_wrong_code, res(ApiFailure(404, null)))
    }
}
