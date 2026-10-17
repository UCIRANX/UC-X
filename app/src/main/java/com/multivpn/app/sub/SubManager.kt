package com.multivpn.app.sub

import com.multivpn.app.awg.AwgConfig

import android.util.Base64

import android.content.Context
import com.multivpn.app.security.TimeGuard
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

data class VpnConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coreType: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val rawConfig: String,
    var pingMs: Long = -1L
)

object SubManager {
    private const val KEY_CACHED_RAW_SUB = "cached_raw_sub_content"

    // ساب‌لینک‌های اختصاصی؛ UCX.txt فهرستی از ساب‌لینک‌های دیگر است و در هر بروزرسانی بررسی می‌شود
    private val SUBSCRIPTION_URLS = listOf(
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UcSubot.txt",
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/Uciran.txt",
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/Uciranir.txt",
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UCX.txt",
        "https://uciran.app/23NscP",
        "https://zaya.io/UCX"
    )

    private const val MAX_NESTED_DEPTH = 2

    fun loadCachedConfigs(context: Context): List<VpnConfig> {
        val cached = TimeGuard.getSecurePrefs(context).getString(KEY_CACHED_RAW_SUB, null)
        return if (!cached.isNullOrBlank()) parseRawSubscription(cached) else emptyList()
    }

    fun loadCachedOrSampleConfigs(context: Context): List<VpnConfig> = loadCachedConfigs(context)

    suspend fun fetchAndParseSub(context: Context, extraUrls: List<String> = emptyList()): List<VpnConfig> {
        val out = StringBuilder()
        val visited = mutableSetOf<String>()
        for (url in SUBSCRIPTION_URLS + extraUrls) collectFromUrl(url, 0, visited, out)
        val finalRaw = out.toString()
        if (finalRaw.isBlank()) {
            throw Exception("عدم دسترسی به سرور ساب‌لینک")
        }
        val configs = parseRawSubscription(finalRaw)
        if (configs.isNotEmpty()) {
            TimeGuard.getSecurePrefs(context).edit().putString(KEY_CACHED_RAW_SUB, finalRaw).apply()
        }
        return configs
    }

    /** یک آدرس را می‌خواند؛ خطوط http(s) داخل آن، ساب‌لینک‌های تودرتو هستند و دوباره بررسی می‌شوند. */
    private suspend fun collectFromUrl(url: String, depth: Int, visited: MutableSet<String>, out: StringBuilder) {
        if (depth > MAX_NESTED_DEPTH || !visited.add(url)) return
        val text = try {
            DohClient.fetchUrlText(url)
        } catch (_: Exception) {
            return
        }
        for (rawLine in decodeSubscriptionText(text).lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)) {
                collectFromUrl(line, depth + 1, visited, out)
            } else if (line.contains("://")) {
                out.append(line).append('\n')
            }
        }
    }

    /** کلید یکتا برای هر کانفیگ: بدون نام (بخش # ) و با پارامترهای مرتب‌شده. */
    private fun dedupKey(c: VpnConfig): String {
        val raw = c.rawConfig.substringBefore("#").trim()
        val scheme = raw.substringBefore("://").lowercase()
        val rest = raw.substringAfter("://")
        val authority = rest.substringBefore("?").trimEnd('/')
        val query = if (rest.contains("?")) rest.substringAfter("?") else ""
        val params = query.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
        return "$scheme://$authority?$params"
    }

    private fun decodeSubscriptionText(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.contains("://")) return trimmed
        val cleanB64 = trimmed.filterNot { it.isWhitespace() }
        val padded = cleanB64.padEnd((cleanB64.length + 3) / 4 * 4, '=')
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                trimmed
            }
        }
    }

    /** فقط همین لینک‌ها (بدون ساب‌لینک‌های ثابت)؛ برای ساب‌لینک‌های شخصی. */
    suspend fun fetchUrls(urls: List<String>): List<VpnConfig> {
        val out = StringBuilder()
        val visited = mutableSetOf<String>()
        for (url in urls) collectFromUrl(url, 0, visited, out)
        return parseRawSubscription(out.toString())
    }

    /** متن خام (از کلیپ‌برد یا هر منبع) را به کانفیگ تبدیل می‌کند؛ base64 یا خطوط ساده. */
    fun parseText(text: String): List<VpnConfig> = parseRawSubscription(decodeSubscriptionText(text))

    fun parseRawSubscription(rawText: String): List<VpnConfig> {
        val decodedContent = decodeSubscriptionText(rawText)
        val list = mutableListOf<VpnConfig>()

        for (rawLine in decodedContent.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            val parsed = when {
                line.startsWith("vless://", ignoreCase = true) -> parseGenericUri(line, "vless", "Sing-box (Xray)")
                line.startsWith("ss://", ignoreCase = true) -> parseShadowsocksUri(line)
                line.startsWith("hy2://", ignoreCase = true) -> parseGenericUri(line, "hy2", "Sing-box (Xray)")
                line.startsWith("hysteria2://", ignoreCase = true) -> parseGenericUri(line, "hy2", "Sing-box (Xray)")
                line.startsWith("vmess://", ignoreCase = true) -> parseVmess(line)
                line.startsWith("trojan://", ignoreCase = true) -> parseGenericUri(line, "trojan", "Sing-box (Xray)")
                line.startsWith("tuic://", ignoreCase = true) -> parseGenericUri(line, "tuic", "Sing-box (Xray)")
                line.startsWith("awg://", ignoreCase = true) -> parseAwgUri(line)
                line.startsWith("wg://", ignoreCase = true) ||
                line.startsWith("wireguard://", ignoreCase = true) -> parseAwgUri(line)
                line.startsWith("ovpn://", ignoreCase = true) -> parseGenericUri(line, "ovpn", "OpenVPN")
                else -> null
            }

            if (parsed != null) {
                list.add(parsed)
            }
        }
        val seen = HashSet<String>()
        return list.filter { seen.add(dedupKey(it)) }
    }

    /** هر فرمت AmneziaWG (awg/wg/wireguard، base64 یا URL، یک یا دو بار encode) به یک لینک استاندارد تبدیل می‌شود. */
    private fun parseAwgUri(line: String): VpnConfig? {
        return try {
            val canonical = AwgConfig.canonicalConf(line) ?: return null
            val parsed = AwgConfig.parse(canonical) ?: return null
            val remark = line.substringAfter("#", "").let {
                if (it.isNotBlank()) URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") else ""
            }
            VpnConfig(
                name = remark.ifBlank { "AmneziaWG ${parsed.endpointHost}" },
                coreType = "AmneziaWG",
                protocol = "awg",
                host = parsed.endpointHost,
                port = parsed.endpointPort,
                rawConfig = AwgConfig.encodeLink(canonical),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGenericUri(uriStr: String, protocol: String, coreType: String): VpnConfig? {
        return try {
            val withoutScheme = uriStr.substringAfter("://")
            val mainPart = withoutScheme.substringBefore("#")
            val rawFragment = if (withoutScheme.contains("#")) withoutScheme.substringAfterLast("#") else ""

            val remark = if (rawFragment.isNotBlank()) {
                try { URLDecoder.decode(rawFragment, "UTF-8") } catch (_: Exception) { rawFragment }
            } else ""

            val authorityAndPath = mainPart.substringBefore("?")
            val hostPortPart = if (authorityAndPath.contains("@")) {
                authorityAndPath.substringAfterLast("@")
            } else {
                authorityAndPath
            }.substringBefore("/")

            val host: String
            val port: Int

            if (hostPortPart.startsWith("[")) {
                host = hostPortPart.substringBefore("]") + "]"
                port = hostPortPart.substringAfter("]:", "443").toIntOrNull() ?: 443
            } else {
                host = hostPortPart.substringBefore(":").trim()
                port = hostPortPart.substringAfter(":", "443").toIntOrNull() ?: 443
            }

            if (host.isBlank()) return null

            val finalName = remark.ifBlank { "${protocol.uppercase()} - $host:$port" }.take(45)
            VpnConfig(
                name = finalName,
                coreType = coreType,
                protocol = protocol,
                host = host,
                port = port,
                rawConfig = uriStr
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseShadowsocksUri(uriStr: String): VpnConfig? {
        return try {
            val withoutScheme = uriStr.substringAfter("://")
            val mainPart = withoutScheme.substringBefore("#").substringBefore("?")
            val rawFragment = if (withoutScheme.contains("#")) withoutScheme.substringAfterLast("#") else ""
            val remark = if (rawFragment.isNotBlank()) {
                try { URLDecoder.decode(rawFragment, "UTF-8") } catch (_: Exception) { rawFragment }
            } else ""

            val hostPortPart = if (mainPart.contains("@")) {
                mainPart.substringAfterLast("@").substringBefore("/")
            } else {
                val b64 = try { URLDecoder.decode(mainPart.substringBefore("/").trim(), "UTF-8") } catch (_: Exception) { mainPart.substringBefore("/").trim() }
                val padded = b64.padEnd((b64.length + 3) / 4 * 4, '=')
                val decoded = try {
                    String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
                } catch (_: Exception) {
                    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                }
                decoded.substringAfterLast("@")
            }

            val host = hostPortPart.substringBefore(":").trim()
            val port = hostPortPart.substringAfter(":", "443").toIntOrNull() ?: 443
            if (host.isBlank()) return null

            val finalName = remark.ifBlank { "SS - $host:$port" }.take(45)
            VpnConfig(
                name = finalName,
                coreType = "Sing-box (Xray)",
                protocol = "ss",
                host = host,
                port = port,
                rawConfig = uriStr
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmess(uriStr: String): VpnConfig? {
        return try {
            val b64 = uriStr.substringAfter("://").trim()
            val padded = b64.padEnd((b64.length + 3) / 4 * 4, '=')
            val jsonStr = try {
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
            }
            val json = JSONObject(jsonStr)
            val host = json.optString("add", "").trim()
            val port = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
            val name = json.optString("ps", "VMess-$host").ifBlank { "VMess-$host" }
            if (host.isBlank()) null else VpnConfig(
                name = name.take(45),
                coreType = "Sing-box (Xray)",
                protocol = "vmess",
                host = host,
                port = port,
                rawConfig = uriStr
            )
        } catch (_: Exception) {
            null
        }
    }
}
