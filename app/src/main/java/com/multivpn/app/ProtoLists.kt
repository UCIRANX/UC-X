package com.multivpn.app

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** نوع پروتکل یک سرور در بخش «پروتکل‌ها». */
enum class VpnType(val label: String) {
    IKEV2("IKEv2"),
    L2TP("L2TP/IPsec"),
    OPENCONNECT("OpenConnect"),
}

/**
 * یک سرور در لیست‌های بخش پروتکل‌ها (دستی یا ساب‌لینک).
 * rawKey کلید ذخیرهٔ اصلی در فروشگاه همان پروتکل است (برای ویرایش و حذف).
 */
data class ProtoServer(
    val type: VpnType,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val psk: String,
    val remoteId: String,
    val authGroup: String,
    val certPin: String,
    val rawKey: String,
) {
    val key: String get() = "${type.name}|$host|$port|$user"
    val label: String get() = if (name.isNotBlank()) name else host
}

/** فروشگاه لیست‌های بخش پروتکل‌ها: دستی، ساب‌لینک، قلب‌ها و انتخاب. */
object ProtoLists {
    private const val K_SEL = "PROTO_SELECTED"
    private const val K_FAV_MAN = "PROTO_FAV_MAN"
    private const val K_FAV_SUB = "PROTO_FAV_SUB"
    private const val K_SUB = "PROTO_SUB"

    /**
     * لینک‌های ساب‌لینک بخش پروتکل‌ها. هنوز تنظیم نشده‌اند؛ پس از ارسال لینک و
     * نمونهٔ فرمت، اینجا اضافه می‌شوند.
     */
    val SUB_URLS: List<String> = emptyList()

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** کلید ذخیره‌سازی در فروشگاه پروتکل مربوط. */
    fun rawKeyFor(item: ProtoServer): String =
        if (item.type == VpnType.OPENCONNECT) "${item.host}|${item.port}|${item.user}" else "${item.host}|${item.user}"

    // ───── سرورهای دستی ─────

    fun manual(ctx: Context): List<ProtoServer> {
        val out = mutableListOf<ProtoServer>()
        Ikev2Store.load(ctx).forEach {
            out += ProtoServer(VpnType.IKEV2, it.name, it.server, 0, it.username, it.password, it.psk, it.remoteId, "", "", it.key)
        }
        ProtocolStore.loadL2tp(ctx).forEach {
            out += ProtoServer(VpnType.L2TP, it.name, it.server, 0, it.username, it.password, it.psk, "", "", "", it.key)
        }
        ProtocolStore.loadOc(ctx).forEach {
            out += ProtoServer(VpnType.OPENCONNECT, it.name, it.server, it.port, it.username, it.password, "", "", it.authGroup, it.certPin, it.key)
        }
        return out
    }

    /** افزودن یا ویرایش؛ replaceRaw کلید سرور قبلی است (در ویرایش). */
    fun saveManual(ctx: Context, item: ProtoServer, replaceRaw: String?) {
        val newRaw = rawKeyFor(item)
        when (item.type) {
            VpnType.IKEV2 -> {
                val rest = Ikev2Store.load(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                Ikev2Store.save(ctx, rest + Ikev2Server(item.name, item.host, item.user, item.password, item.remoteId, item.psk))
            }
            VpnType.L2TP -> {
                val rest = ProtocolStore.loadL2tp(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                ProtocolStore.saveL2tp(ctx, rest + L2tpEntry(item.name, item.host, item.psk, item.user, item.password))
            }
            VpnType.OPENCONNECT -> {
                val rest = ProtocolStore.loadOc(ctx).filter { it.key != replaceRaw && it.key != newRaw }
                ProtocolStore.saveOc(
                    ctx,
                    rest + OcEntry(item.name, item.host, item.port, item.user, item.password, item.authGroup, item.certPin),
                )
            }
        }
    }

    fun deleteManual(ctx: Context, item: ProtoServer) {
        when (item.type) {
            VpnType.IKEV2 -> Ikev2Store.save(ctx, Ikev2Store.load(ctx).filter { it.key != item.rawKey })
            VpnType.L2TP -> ProtocolStore.saveL2tp(ctx, ProtocolStore.loadL2tp(ctx).filter { it.key != item.rawKey })
            VpnType.OPENCONNECT -> ProtocolStore.saveOc(ctx, ProtocolStore.loadOc(ctx).filter { it.key != item.rawKey })
        }
    }

    // ───── قلب‌ها ─────

    fun favManKeys(ctx: Context): List<String> = readStrings(prefs(ctx).getString(K_FAV_MAN, "[]"))

    /** برمی‌گرداند: آیا الان قلب‌دار است. */
    fun toggleManFav(ctx: Context, key: String): Boolean {
        val cur = favManKeys(ctx).toMutableList()
        val nowFav = if (cur.contains(key)) {
            cur.remove(key)
            false
        } else {
            cur.add(0, key)
            true
        }
        prefs(ctx).edit().putString(K_FAV_MAN, JSONArray(cur).toString()).apply()
        return nowFav
    }

    /**
     * قلب سرورهای ساب‌لینک: نسخهٔ کامل سرور ذخیره می‌شود تا با بروزرسانی حذف نشود.
     * فقط وقتی قلب برداشته شود و در ساب‌لینک جدید نباشد، از لیست حذف می‌شود.
     */
    fun favSub(ctx: Context): List<ProtoServer> = readServers(prefs(ctx).getString(K_FAV_SUB, "[]"))

    fun toggleSubFav(ctx: Context, item: ProtoServer): Boolean {
        val cur = favSub(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.key == item.key }
        val nowFav = if (idx >= 0) {
            cur.removeAt(idx)
            false
        } else {
            cur.add(0, item)
            true
        }
        prefs(ctx).edit().putString(K_FAV_SUB, writeServers(cur)).apply()
        return nowFav
    }

    // ───── ساب‌لینک ─────

    fun loadSub(ctx: Context): List<ProtoServer> = readServers(prefs(ctx).getString(K_SUB, "[]"))

    fun saveSub(ctx: Context, list: List<ProtoServer>) {
        prefs(ctx).edit().putString(K_SUB, writeServers(list)).apply()
    }

    /** بروزرسانی ساب‌لینک‌های این بخش؛ پیام نتیجه را برمی‌گرداند. */
    fun refreshSub(ctx: Context): String {
        if (SUB_URLS.isEmpty()) return "لینک ساب‌لینک این بخش هنوز تنظیم نشده است"
        return "فرمت ساب‌لینک این بخش هنوز تعریف نشده است"
    }

    // ───── انتخاب ─────

    fun selected(ctx: Context): String? = prefs(ctx).getString(K_SEL, null)

    fun setSelected(ctx: Context, key: String?) {
        prefs(ctx).edit().putString(K_SEL, key).apply()
    }

    // ───── JSON ─────

    private fun readStrings(s: String?): List<String> = runCatching {
        val arr = JSONArray(s ?: "[]")
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeServers(list: List<ProtoServer>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("t", it.type.name).put("n", it.name).put("h", it.host).put("o", it.port)
                    .put("u", it.user).put("p", it.password).put("k", it.psk).put("r", it.remoteId)
                    .put("g", it.authGroup).put("c", it.certPin).put("raw", it.rawKey)
            )
        }
        return arr.toString()
    }

    private fun readServers(s: String?): List<ProtoServer> = runCatching {
        val arr = JSONArray(s ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            ProtoServer(
                type = VpnType.valueOf(o.getString("t")),
                name = o.optString("n"),
                host = o.getString("h"),
                port = o.optInt("o", 0),
                user = o.optString("u"),
                password = o.optString("p"),
                psk = o.optString("k"),
                remoteId = o.optString("r"),
                authGroup = o.optString("g"),
                certPin = o.optString("c"),
                rawKey = o.optString("raw"),
            )
        }
    }.getOrDefault(emptyList())
}
