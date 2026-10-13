package com.kreativekoala.surfsense.usage

import retrofit2.Response
import java.io.IOException

class UsageSyncer(
    private val collect: () -> Map<String, Int>,
    private val mapper: CategoryMapper,
    private val submit: suspend (day: String, summary: Map<String, Int>) -> Response<Unit>,
    private val ensureRegistered: suspend () -> Boolean,
    private val today: () -> String,
) {
    enum class Outcome { SUCCESS, RETRY, FAIL }

    suspend fun run(): Outcome {
        if (!ensureRegistered()) return Outcome.RETRY

        val usage = collect()
        if (usage.isEmpty()) return Outcome.SUCCESS

        val mapping = when (val m = mapper.map(usage.keys.toList())) {
            is CategoryMapper.Outcome.Mapped -> m.categories
            is CategoryMapper.Outcome.Failed -> return if (m.retryable) Outcome.RETRY else Outcome.FAIL
        }

        val summary = HashMap<String, Int>()
        for ((pkg, seconds) in usage) {
            val category = mapping[pkg] ?: "Other"
            summary[category] = (summary[category] ?: 0) + seconds
        }

        val response = try {
            submit(today(), summary)
        } catch (e: IOException) {
            return Outcome.RETRY
        }
        val code = response.code()
        return when {
            response.isSuccessful -> Outcome.SUCCESS
            code == 429 || code >= 500 -> Outcome.RETRY
            else -> Outcome.FAIL
        }
    }
}
