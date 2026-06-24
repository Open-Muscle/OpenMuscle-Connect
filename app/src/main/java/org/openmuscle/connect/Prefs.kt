package org.openmuscle.connect

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for the few things the app remembers between
 * runs. Kept deliberately small; move to DataStore if this grows.
 */
object Prefs {
    private const val FILE = "om_connect_prefs"
    private const val KEY_DEVICE = "selected_device_id"
    private const val KEY_NICK_PREFIX = "nick_"
    private const val KEY_DEVICE_CACHE = "device_cache_json"

    fun selectedDeviceId(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE, null)

    fun setSelectedDeviceId(ctx: Context, id: String?) {
        val editor = prefs(ctx).edit()
        if (id == null) editor.remove(KEY_DEVICE) else editor.putString(KEY_DEVICE, id)
        editor.apply()
    }

    /** User-set friendly name for a device id, or null. */
    fun nickname(ctx: Context, id: String): String? =
        prefs(ctx).getString(KEY_NICK_PREFIX + id, null)

    fun setNickname(ctx: Context, id: String, name: String?) {
        val editor = prefs(ctx).edit()
        if (name.isNullOrBlank()) editor.remove(KEY_NICK_PREFIX + id) else editor.putString(KEY_NICK_PREFIX + id, name)
        editor.apply()
    }

    /** Persisted device address cache (JSON; see [org.openmuscle.connect.discovery.DeviceCache]). */
    fun deviceCacheJson(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_CACHE, null)

    fun setDeviceCacheJson(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_DEVICE_CACHE, json).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
