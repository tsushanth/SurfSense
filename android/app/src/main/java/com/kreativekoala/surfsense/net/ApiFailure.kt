package com.kreativekoala.surfsense.net

import com.kreativekoala.surfsense.R
import android.content.Context
import com.google.gson.JsonParser
import retrofit2.Response
import java.io.IOException

/** An HTTP error response from the backend. [serverMessage] is the `error` field of its JSON body, if any. */
class ApiFailure(val code: Int, val serverMessage: String?) : Exception("HTTP $code")

fun <T> Response<T>.toFailure(): ApiFailure {
    val message = try {
        val body = errorBody()?.string().orEmpty()
        val element = JsonParser.parseString(body)
        if (element.isJsonObject && element.asJsonObject.get("error")?.isJsonPrimitive == true) {
            element.asJsonObject.get("error").asString
        } else null
    } catch (e: Exception) {
        null
    }
    return ApiFailure(code(), message)
}

object ErrorMessages {
    /** Same messages as [from], in the app language. */
    fun from(context: Context, t: Throwable): String = when {
        t is IOException -> context.getString(R.string.error_no_connection)
        t is ApiFailure -> when {
            t.code == 401 || t.code == 403 -> context.getString(R.string.error_device_not_recognized)
            t.code == 429 -> context.getString(R.string.error_too_many_requests)
            t.code >= 500 -> context.getString(R.string.error_server_trouble)
            !t.serverMessage.isNullOrBlank() -> t.serverMessage
            else -> context.getString(R.string.error_something_wrong_code, t.code)
        }
        else -> context.getString(R.string.error_something_wrong)
    }

    /** English text, used by unit tests and code without a Context. */
    fun from(t: Throwable): String = when {
        t is IOException -> "No connection. Check your internet and try again."
        t is ApiFailure -> when {
            t.code == 401 || t.code == 403 ->
                "This device isn't recognized by the server. Close and reopen the app to reconnect."
            t.code == 429 -> "Too many requests. Try again in a minute."
            t.code >= 500 -> "The server is having trouble. Try again soon."
            !t.serverMessage.isNullOrBlank() -> t.serverMessage
            else -> "Something went wrong (${t.code})."
        }
        else -> "Something went wrong."
    }
}
