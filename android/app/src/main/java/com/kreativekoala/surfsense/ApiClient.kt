package com.kreativekoala.surfsense

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
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
    private var apiKey: String? = null

    fun create(): ApiService {
        return instance ?: synchronized(this) {
            instance ?: buildApiService().also { instance = it }
        }
    }

    fun setApiKey(key: String?) {
        apiKey = key
        synchronized(this) {
            instance = null
        }
    }

    fun initialize(context: Context) {
        val prefs = getSecurePrefs(context)
        apiKey = prefs.getString("api_key", null)
    }

    fun saveApiKey(context: Context, key: String) {
        apiKey = key
        getSecurePrefs(context)
            .edit()
            .putString("api_key", key)
            .apply()
        synchronized(this) {
            instance = null
        }
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "surfsense_secure",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create encrypted prefs, falling back to standard")
            context.getSharedPreferences("surfsense_secure", Context.MODE_PRIVATE)
        }
    }

    private fun buildApiService(): ApiService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(Config.CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Config.READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Config.WRITE_TIMEOUT, TimeUnit.SECONDS)

        // Auth interceptor
        clientBuilder.addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()

            apiKey?.let { key ->
                requestBuilder.header("Authorization", "Bearer $key")
            }

            chain.proceed(requestBuilder.build())
        }

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
