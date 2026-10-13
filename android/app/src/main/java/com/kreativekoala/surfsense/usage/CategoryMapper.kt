package com.kreativekoala.surfsense.usage

import retrofit2.Response
import java.io.IOException

/** Looks up categories in batches the backend accepts (at most 100 per request). */
class CategoryMapper(
    private val fetch: suspend (List<String>) -> Response<Map<String, String>>,
) {
    sealed interface Outcome {
        data class Mapped(val categories: Map<String, String>) : Outcome
        data class Failed(val retryable: Boolean) : Outcome
    }

    suspend fun map(packages: List<String>): Outcome {
        val result = HashMap<String, String>()
        for (chunk in packages.chunked(BATCH_SIZE)) {
            val response = try {
                fetch(chunk)
            } catch (e: IOException) {
                return Outcome.Failed(retryable = true)
            }
            if (!response.isSuccessful) {
                val code = response.code()
                return Outcome.Failed(retryable = code == 429 || code >= 500)
            }
            val body = response.body() ?: return Outcome.Failed(retryable = true)
            result.putAll(body)
        }
        return Outcome.Mapped(result)
    }

    companion object {
        const val BATCH_SIZE = 100
    }
}
