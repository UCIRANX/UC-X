package ir.uciran.uctor

import org.json.JSONObject

object TorrcBuilder {
    private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    fun build(cfg: JSONObject, lines: List<String>, ptPorts: Map<String, Int>): String = buildString {
        // IPv6Traffic: در حالت VPN مقصدهای IPv6 هم از SOCKS می‌آیند؛ بدون این گزینه Tor آن‌ها را رد می‌کند.
        appendLine("SocksPort 127.0.0.1:${cfg.getInt("socks")} IPv6Traffic")
        appendLine("HTTPTunnelPort 127.0.0.1:${cfg.getInt("http")}")
        appendLine("DNSPort 127.0.0.1:9053")
        appendLine("Log notice file ${cfg.getString("log")}")
        appendLine("DisableNetwork 0")
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
        appendLine("SafeLogging 1")
        // بدون GeoIP، Tor کد کشور (مثل {de}) را نمی‌شناسد و انتخاب کشور هیچ‌وقت کار نمی‌کند.
        cfg.optString("geoip").takeIf { it.isNotBlank() }?.let { appendLine("GeoIPFile ${clean(it)}") }
        cfg.optString("geoip6").takeIf { it.isNotBlank() }?.let { appendLine("GeoIPv6File ${clean(it)}") }
        if (lines.isEmpty()) {
            appendLine("UseBridges 0")
        } else {
            appendLine("UseBridges 1")
            ptPorts.forEach { (t, p) -> appendLine("ClientTransportPlugin $t socks5 127.0.0.1:$p") }
            lines.forEach { appendLine("Bridge ${clean(Planner.normalize(it))}") }
        }
        clean(cfg.optString("excludeNodes")).takeIf { it.isNotEmpty() }?.let { appendLine("ExcludeNodes $it") }
        clean(cfg.optString("excludeExitNodes")).takeIf { it.isNotEmpty() }?.let { appendLine("ExcludeExitNodes $it") }
        // انتخاب کشور: فقط خروجی‌های همان کشور (StrictNodes 1)؛ اگر پیدا نشد Tor سراغ کشور دیگر نمی‌رود.
        val country = ExitCountries.find(cfg.optString("exitCountry"))?.code
        val manual = clean(cfg.optString("exitNodes"))
        if (country != null) {
            appendLine("ExitNodes {$country}")
            appendLine("StrictNodes 1")
            // لاگ جداگانه‌ی مدارها، فقط برای تشخیص دلیل شکست وقتی خروجی آن کشور پیدا نمی‌شود.
            appendLine("Log [circ]info file ${cfg.getString("log")}.circ")
        } else if (manual.isNotEmpty()) {
            appendLine("ExitNodes $manual")
            appendLine("StrictNodes 0")
        }
    }
}
