package com.multivpn.app

import android.content.Context
import com.multivpn.app.security.TimeGuard
import org.json.JSONArray
import org.json.JSONObject

/** کانفیگ افزوده‌شده از کلیپ‌برد؛ تا وقتی تست پینگ یا برداشتن قلب نشود، بالای لیست می‌ماند. */
data class ClipItem(val raw: String, var pending: Boolean)

/** ساب‌لینک شخصی با نام دلخواه؛ تیک فعال یعنی در بروزرسانی خوانده شود. */
data class PersonalSub(val name: String, val url: String, val enabled: Boolean)

/** ذخیرهٔ قلب‌ها، کلیپ‌برد و ساب‌لینک‌های شخصی. */
object SingBoxStore {
    private const val K_FAVS = "sb_favs"
    private const val K_CLIPS = "sb_clips"
    private const val K_PERSONAL = "sb_personal"
    private const val K_PCACHE = "sb_personal_cache"

    private fun prefs(ctx: Context) = TimeGuard.getSecurePrefs(ctx)

    fun loadFavs(ctx: Context): MutableList<String> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_FAVS, "[]") ?: "[]")
        MutableList(arr.length()) { arr.getString(it) }
    }.getOrDefault(mutableListOf())

    fun saveFavs(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(K_FAVS, arr.toString()).apply()
    }

    fun loadClips(ctx: Context): MutableList<ClipItem> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_CLIPS, "[]") ?: "[]")
        MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            ClipItem(o.getString("raw"), o.optBoolean("pending", false))
        }
    }.getOrDefault(mutableListOf())

    fun saveClips(ctx: Context, list: List<ClipItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("raw", it.raw).put("pending", it.pending)) }
        prefs(ctx).edit().putString(K_CLIPS, arr.toString()).apply()
    }

    fun loadPersonal(ctx: Context): MutableList<PersonalSub> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_PERSONAL, "[]") ?: "[]")
        MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            PersonalSub(o.getString("name"), o.getString("url"), o.optBoolean("enabled", true))
        }
    }.getOrDefault(mutableListOf())

    fun savePersonal(ctx: Context, list: List<PersonalSub>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled))
        }
        prefs(ctx).edit().putString(K_PERSONAL, arr.toString()).apply()
    }

    /** کش هر ساب‌لینک شخصی (آدرس → کانفیگ‌های خام). */
    fun loadPersonalCache(ctx: Context): MutableMap<String, List<String>> = runCatching {
        val o = JSONObject(prefs(ctx).getString(K_PCACHE, "{}") ?: "{}")
        val out = mutableMapOf<String, List<String>>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            out[k] = List(a.length()) { a.getString(it) }
        }
        out
    }.getOrDefault(mutableMapOf())

    fun savePersonalCache(ctx: Context, map: Map<String, List<String>>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs(ctx).edit().putString(K_PCACHE, o.toString()).apply()
    }
}
