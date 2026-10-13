package com.kreativekoala.surfsense

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.kreativekoala.surfsense.identity.Identity
import com.kreativekoala.surfsense.net.ReauthAuthenticator
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ApiClient {
    @Volatile
    private var instance: ApiService? = null

    @Volatile
    private var registrationInstance: ApiService? = null

    /**
     * Dedicated client for /register-client: own dispatcher (so a blocked 401 handler on the main client
     * can never starve registration), no authenticator and no Authorization header.
     */
    fun createForRegistration(): ApiService {
        return registrationInstance ?: synchronized(this) {
            registrationInstance ?: buildRegistrationService().also { registrationInstance = it }
        }
    }

    private fun buildRegistrationService(): ApiService {
        val clientBuilder = OkHttpClient.Builder()
            .dispatcher(Dispatcher())
            .connectTimeout(Config.CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Config.READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Config.WRITE_TIMEOUT, TimeUnit.SECONDS)
        if (Config.ENABLE_NETWORK_LOGGING) {
            clientBuilder.addInterceptor(loggingInterceptor())
        }
        return retrofit(clientBuilder.build())
    }

    private fun loggingInterceptor() = HttpLoggingInterceptor { message ->
        Timber.tag("API").d(message)
    }.apply {
        redactHeader("Authorization")
        setLevel(HttpLoggingInterceptor.Level.BODY)
    }

    private fun retrofit(client: OkHttpClient): ApiService {
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
        clientBuilder.authenticator(ReauthAuthenticator(Identity.identity(), Identity.registrar()))

        // Only enable logging in debug builds
        if (Config.ENABLE_NETWORK_LOGGING) {
            clientBuilder.addInterceptor(loggingInterceptor())
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

        return retrofit(clientBuilder.build())
    }
}
