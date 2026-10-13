package com.kreativekoala.surfsense.usage

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class CategoryMapperTest {
    private fun pkgs(n: Int) = (1..n).map { "com.app$it" }

    @Test fun sendsAtMost100PerRequestAndMergesTheAnswers() = runBlocking {
        val sizes = ArrayList<Int>()
        val mapper = CategoryMapper { chunk ->
            sizes.add(chunk.size)
            Response.success(chunk.associateWith { "News" })
        }
        val out = mapper.map(pkgs(250))
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(250, (out as CategoryMapper.Outcome.Mapped).categories.size)
    }

    @Test fun noPackagesMeansNoRequests() = runBlocking {
        var calls = 0
        val mapper = CategoryMapper { calls++; Response.success(emptyMap()) }
        assertEquals(CategoryMapper.Outcome.Mapped(emptyMap()), mapper.map(emptyList()))
        assertEquals(0, calls)
    }

    @Test fun retryableFailuresAre429Server5xxAndIo() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            val m = CategoryMapper { Response.error(code, "".toResponseBody(null)) }
            assertEquals(CategoryMapper.Outcome.Failed(retryable = true), m.map(pkgs(1)))
        }
        val io = CategoryMapper { throw IOException("offline") }
        assertEquals(CategoryMapper.Outcome.Failed(retryable = true), io.map(pkgs(1)))
    }

    @Test fun clientErrorsAreNotRetryable() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            val m = CategoryMapper { Response.error(code, "".toResponseBody(null)) }
            assertEquals(CategoryMapper.Outcome.Failed(retryable = false), m.map(pkgs(1)))
        }
    }

    @Test fun aFailureInALaterChunkFailsTheWholeCall() = runBlocking {
        var n = 0
        val m = CategoryMapper { chunk ->
            if (++n == 2) Response.error(503, "".toResponseBody(null)) else Response.success(chunk.associateWith { "Other" })
        }
        assertTrue(m.map(pkgs(150)) is CategoryMapper.Outcome.Failed)
    }
}
