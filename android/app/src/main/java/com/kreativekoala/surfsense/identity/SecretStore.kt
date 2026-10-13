package com.kreativekoala.surfsense.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import timber.log.Timber

/** Minimal key-value store so identity logic can be unit-tested off-device. */
interface SecretStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Encrypted SharedPreferences, rebuilt if the keystore master key is lost, plain prefs as a last resort. */
class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences = open(context.applicationContext)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val ENCRYPTED_NAME = "surfsense_identity"
        const val PLAIN_NAME = "surfsense_identity_plain"

        fun open(context: Context): SharedPreferences = try {
            encrypted(context)
        } catch (e: Exception) {
            Timber.e(e, "Encrypted prefs unavailable; recreating")
            try {
                context.deleteSharedPreferences(ENCRYPTED_NAME)
                encrypted(context)
            } catch (e2: Exception) {
                Timber.e(e2, "Encrypted prefs still unavailable; using app-private plain prefs")
                context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)
            }
        }

        fun encrypted(context: Context): SharedPreferences {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                ENCRYPTED_NAME,
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
