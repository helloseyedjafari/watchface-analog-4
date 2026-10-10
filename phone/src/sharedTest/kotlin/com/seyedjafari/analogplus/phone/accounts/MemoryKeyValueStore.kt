package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore

/** In-memory [KeyValueStore] shared by the JVM and instrumented tests of the accounts code. */
class MemoryKeyValueStore : KeyValueStore {
    val map = HashMap<String, Any>()
    /** Like the real preferences wrapper: a value of another type reads as absent. */
    override fun getString(key: String) = map[key] as? String
    override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
}
