package com.kreativekoala.surfsense.usage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** User-chosen category per package; overrides always win over the server mapping. */
class CategoryOverrides(private val store: Store) {
    interface Store {
        fun load(): Map<String, String>
        fun save(map: Map<String, String>)
    }

    fun all(): Map<String, String> = store.load()

    /** A blank [category] removes the override. */
    fun set(pkg: String, category: String) {
        if (category.isBlank()) return remove(pkg)
        store.save(store.load() + (pkg to category.trim()))
    }

    fun remove(pkg: String) {
        val current = store.load()
        if (pkg in current) store.save(current - pkg)
    }

    /** Overrides win; packages in neither [mapping] nor overrides are omitted. Stale overrides are ignored. */
    fun apply(mapping: Map<String, String>, packages: Collection<String>): Map<String, String> {
        val overrides = store.load()
        val out = HashMap<String, String>()
        for (p in packages) {
            val c = overrides[p] ?: mapping[p] ?: continue
            out[p] = c
        }
        return out
    }

    class SharedPrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val gson = Gson()

        override fun load(): Map<String, String> {
            val json = prefs.getString(KEY, null) ?: return emptyMap()
            return try {
                gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
                    ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

        override fun save(map: Map<String, String>) {
            prefs.edit().putString(KEY, gson.toJson(map)).apply()
        }

        private companion object {
            const val PREFS = "surfsense_category_overrides"
            const val KEY = "overrides"
        }
    }
}
