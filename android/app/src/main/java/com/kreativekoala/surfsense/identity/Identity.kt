package com.kreativekoala.surfsense.identity

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Process-wide wiring for the install identity. Call [init] from Application.onCreate. */
object Identity {
    @Volatile private var identity: DeviceIdentity? = null
    @Volatile private var registrar: DeviceRegistrar? = null

    fun init(context: Context) {
        synchronized(this) {
            if (identity != null) return
            val app = context.applicationContext
            val id = DeviceIdentity(AndroidSecretStore(app))
            identity = id
            registrar = DeviceRegistrar(id, { deviceName(app) }) { request ->
                com.kreativekoala.surfsense.ApiClient.create().registerClient(request)
            }
        }
    }

    fun identity(): DeviceIdentity = checkNotNull(identity) { "Identity.init() was not called" }
    fun registrar(): DeviceRegistrar = checkNotNull(registrar) { "Identity.init() was not called" }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
