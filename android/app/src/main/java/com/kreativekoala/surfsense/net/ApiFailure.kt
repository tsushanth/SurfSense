package com.kreativekoala.surfsense.net

import com.kreativekoala.surfsense.R
import android.content.Context
import androidx.annotation.StringRes
import com.google.gson.JsonParser
import retrofit2.Response
import java.io.IOException

/**
 * An HTTP error response from the backend. [serverMessage] is the English `error` field of its JSON body (for logs
 * only, never shown), [serverCode] is its stable `code` field, which [ErrorMessages] maps to a localized string.
 */
class ApiFailure(val code: Int, val serverMessage: String?, val serverCode: String? = null) : Exception("HTTP $code")

fun <T> Response<T>.toFailure(): ApiFailure {
    var message: String? = null
    var serverCode: String? = null
    try {
        val element = JsonParser.parseString(errorBody()?.string().orEmpty())
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.get("error")?.isJsonPrimitive == true) message = obj.get("error").asString
            if (obj.get("code")?.isJsonPrimitive == true) serverCode = obj.get("code").asString
        }
    } catch (e: Exception) {
        // not JSON: keep the status code only
    }
    return ApiFailure(code(), message, serverCode)
}

/** A string resource and its format arguments. */
class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

object ErrorMessages {
    /**
     * Backend `code` values (see backend/server.js, linkingFunctions.js, middleware/auth.js) that have a user-facing
     * message of their own. Any other 4xx falls back to a generic message with the status code, never the server text.
     */
    val CODE_MESSAGES: Map<String, Int> = mapOf(
        "LINK_CODE_INVALID" to R.string.error_link_code_invalid,
        "LINK_CODE_EXPIRED" to R.string.error_link_code_expired,
        "LINK_SELF" to R.string.error_link_self,
        "INVALID_CODE_FORMAT" to R.string.error_link_code_format,
        "LINKING_RATE_LIMITED" to R.string.error_linking_rate_limited,
        "BAD_REQUEST" to R.string.error_invalid_request,
    )

    fun messageFor(t: Throwable): UiMessage = when {
        t is IOException -> UiMessage(R.string.error_no_connection)
        t is ApiFailure -> when {
            t.serverCode != null && t.serverCode in CODE_MESSAGES -> UiMessage(CODE_MESSAGES.getValue(t.serverCode))
            t.code == 401 || t.code == 403 -> UiMessage(R.string.error_device_not_recognized)
            t.code == 429 -> UiMessage(R.string.error_too_many_requests)
            t.code >= 500 -> UiMessage(R.string.error_server_trouble)
            t.code == 400 -> UiMessage(R.string.error_invalid_request)
            else -> UiMessage(R.string.error_something_wrong_code, listOf(t.code))
        }
        else -> UiMessage(R.string.error_something_wrong)
    }

    fun from(context: Context, t: Throwable): String {
        val m = messageFor(t)
        return context.getString(m.res, *m.args.toTypedArray())
    }
}
