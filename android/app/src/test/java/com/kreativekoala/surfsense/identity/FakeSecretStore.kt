package com.kreativekoala.surfsense.identity

class FakeSecretStore : SecretStore {
    val data = HashMap<String, String>()
    override fun getString(key: String): String? = data[key]
    override fun putString(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}
