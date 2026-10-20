package com.multivpn.app

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** سرور L2TP/IPsec با کلید PSK، نام کاربری و رمز. */
data class L2tpEntry(
    val name: String,
    val server: String,
    val psk: String,
    val username: String,
    val password: String,
) {
    val key: String get() = "$server|$username"
    val label: String get() = if (name.isNotBlank()) name else server
}

/** سرور OpenConnect (فعلاً پروتکل Cisco AnyConnect). */
data class OcEntry(
    val name: String,
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val authGroup: String,
    val certPin: String,
) {
    val key: String get() = "$server|$port|$username"
    val label: String get() = if (name.isNotBlank()) name else server
}

/** ذخیرهٔ سرورهای L2TP و OpenConnect و انتخاب‌های هر بخش. */
object ProtocolStore {
    private const val K_L2TP = "L2TP_LIST"
    private const val K_L2TP_SEL = "L2TP_SELECTED"
    private const val K_OC = "OC_LIST"
    private const val K_OC_SEL = "OC_SELECTED"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun loadL2tp(ctx: Context): List<L2tpEntry> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_L2TP, "[]") ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            L2tpEntry(o.optString("n"), o.getString("s"), o.optString("k"), o.getString("u"), o.optString("p"))
        }
    }.getOrDefault(emptyList())

    fun saveL2tp(ctx: Context, list: List<L2tpEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("s", it.server).put("k", it.psk)
                .put("u", it.username).put("p", it.password))
        }
        prefs(ctx).edit().putString(K_L2TP, arr.toString()).apply()
    }

    fun selectedL2tp(ctx: Context): String? = prefs(ctx).getString(K_L2TP_SEL, null)

    fun setSelectedL2tp(ctx: Context, key: String?) {
        prefs(ctx).edit().putString(K_L2TP_SEL, key).apply()
    }

    fun loadOc(ctx: Context): List<OcEntry> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_OC, "[]") ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            OcEntry(
                name = o.optString("n"),
                server = o.getString("s"),
                port = o.optInt("o", 443),
                username = o.getString("u"),
                password = o.optString("p"),
                authGroup = o.optString("g"),
                certPin = o.optString("c"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveOc(ctx: Context, list: List<OcEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("s", it.server).put("o", it.port)
                .put("u", it.username).put("p", it.password).put("g", it.authGroup).put("c", it.certPin))
        }
        prefs(ctx).edit().putString(K_OC, arr.toString()).apply()
    }

    fun selectedOc(ctx: Context): String? = prefs(ctx).getString(K_OC_SEL, null)

    fun setSelectedOc(ctx: Context, key: String?) {
        prefs(ctx).edit().putString(K_OC_SEL, key).apply()
    }
}
