package ir.uciran.uctor

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("uctor", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var transport: String
        get() = str("transport", "auto")
        set(v) = put("transport", v)
    var customBridges: String
        get() = str("customBridges")
        set(v) = put("customBridges", v)
    var excludeNodes: String
        get() = str("excludeNodes")
        set(v) = put("excludeNodes", v)
    var excludeExitNodes: String
        get() = str("excludeExitNodes")
        set(v) = put("excludeExitNodes", v)
    var exitNodes: String
        get() = str("exitNodes")
        set(v) = put("exitNodes", v)
    /** کد کشور خروجی (مثلاً "de")؛ خالی یعنی خودکار. */
    var exitCountry: String
        get() = str("exitCountry")
        set(v) = put("exitCountry", v)
    var lastGood: String?
        get() = sp.getString("lastGood", null)
        set(v) = put("lastGood", v)
    var activeTransport: String?
        get() = sp.getString("activeTransport", null)
        set(v) = put("activeTransport", v)
    var socksPort: Int
        get() = sp.getInt("socksPort", 0)
        set(v) = sp.edit().putInt("socksPort", v).apply()
    var httpPort: Int
        get() = sp.getInt("httpPort", 0)
        set(v) = sp.edit().putInt("httpPort", v).apply()
    var vpnRunning: Boolean
        get() = sp.getBoolean("vpnRunning", false)
        set(v) = sp.edit().putBoolean("vpnRunning", v).apply()
    var vpnPaused: Boolean
        get() = sp.getBoolean("vpnPaused", false)
        set(v) = sp.edit().putBoolean("vpnPaused", v).apply()
    var tunnelMode: String
        get() = str("tunnelMode", "all")
        set(v) = put("tunnelMode", v)
    var selectedApps: Set<String>
        get() = sp.getStringSet("selectedApps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("selectedApps", v).apply()
}
