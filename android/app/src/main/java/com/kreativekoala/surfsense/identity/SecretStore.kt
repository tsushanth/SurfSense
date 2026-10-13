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

/** Encrypted SharedPreferences; plain prefs for the current process if the keystore is temporarily unavailable. */
class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences = open(context.applicationContext)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val ENCRYPTED_NAME = "surfsense_identity"
        const val PLAIN_NAME = "surfsense_identity_plain"

        // Never delete anything here: a transient Keystore failure (right after boot or an OS update)
        // must not wipe the stored id and key. Fall back to plain prefs for this process only; the next
        // launch tries the encrypted store again.
        fun open(context: Context): SharedPreferences = try {
            encrypted(context)
        } catch (e: Exception) {
            Timber.e(e, "Encrypted prefs unavailable; using plain prefs for this process only")
            context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)
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
