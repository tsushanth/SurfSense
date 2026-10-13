package com.kreativekoala.surfsense.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object RetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** [attempt] is the 1-based number of the attempt that just finished. */
    fun shouldRetry(method: String, path: String, status: Int?, ioError: Boolean, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        // A wrong code is a user error and the backend rate-limits this route (5 per 15 minutes).
        if (path.endsWith("/complete-linking")) return false
        if (ioError) return true
        if (status == null) return false
        return method.equals("GET", ignoreCase = true) && status in 500..599
    }

    fun delayMs(attempt: Int): Long = 500L shl (attempt - 1)
}

class RetryInterceptor(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 1
        while (true) {
            var failure: IOException? = null
            val response: Response? = try {
                chain.proceed(request)
            } catch (e: IOException) {
                failure = e
                null
            }
            if (failure != null && chain.call().isCanceled()) throw failure
            val retry = RetryPolicy.shouldRetry(
                request.method, request.url.encodedPath, response?.code, response == null, attempt
            )
            if (!retry) return response ?: throw failure!!
            response?.close()
            sleep(RetryPolicy.delayMs(attempt))
            attempt++
        }
    }
}
