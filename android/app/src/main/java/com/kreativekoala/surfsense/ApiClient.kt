package com.kreativekoala.surfsense

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.kreativekoala.surfsense.identity.Identity
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile
    private var instance: ApiService? = null

    private const val RETRIED_AUTH_HEADER = "X-SurfSense-Auth-Retried"

    fun create(): ApiService {
        return instance ?: synchronized(this) {
            instance ?: buildApiService().also { instance = it }
        }
    }

    private fun buildApiService(): ApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(Config.CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Config.READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Config.WRITE_TIMEOUT, TimeUnit.SECONDS)

        // Auth interceptor: attach the current API key (read per request, so a fresh key is picked up)
        clientBuilder.addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            Identity.identity().apiKey?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }

        // 401 = the server does not recognise our key (e.g. its database was reset): register again once.
        clientBuilder.authenticator(Authenticator { _, response ->
            val request = response.request
            if (request.url.encodedPath.endsWith("/register-client") || request.header(RETRIED_AUTH_HEADER) != null) {
                return@Authenticator null
            }
            val recovered = runBlocking { Identity.registrar().onUnauthorized() }
            if (!recovered) return@Authenticator null
            request.newBuilder()
                .header("Authorization", "Bearer ${Identity.identity().apiKey}")
                .header(RETRIED_AUTH_HEADER, "1")
                .build()
        })

        // Only enable logging in debug builds
        if (Config.ENABLE_NETWORK_LOGGING) {
            val logging = HttpLoggingInterceptor { message ->
                Timber.tag("API").d(message)
            }
            logging.setLevel(HttpLoggingInterceptor.Level.BODY)
            clientBuilder.addInterceptor(logging)
        }

        // Retry interceptor
        clientBuilder.addInterceptor { chain ->
            var response = chain.proceed(chain.request())
            var tryCount = 0

            while (!response.isSuccessful && tryCount < 3) {
                tryCount++
                response.close()
                response = chain.proceed(chain.request())
            }

            response
        }

        val client = clientBuilder.build()

        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
            .create()

        return Retrofit.Builder()
            .baseUrl(Config.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }
}
