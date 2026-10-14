package com.multivpn.app.sub

import android.content.Context
import android.util.Base64
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

    // آدرس ساب‌لینک خودت را اینجا بگذار
    private val SUBSCRIPTION_URLS = listOf(
        "https://raw.githubusercontent.com/UCIRANX/Vpn/refs/heads/main/UcSubot.txt"
    )

    fun loadCachedConfigs(context: Context): List<VpnConfig> {
        val cached = TimeGuard.getSecurePrefs(context).getString(KEY_CACHED_RAW_SUB, null)
        return if (!cached.isNullOrBlank()) parseRawSubscription(cached) else emptyList()
    }

    suspend fun fetchAndParseSub(context: Context): List<VpnConfig> {
        val combinedRaw = StringBuilder()
        for (url in SUBSCRIPTION_URLS) {
            try {
                val text = DohClient.fetchUrlText(url)
                combinedRaw.append(decodeSubscriptionText(text)).append("\n")
            } catch (_: Exception) {
            }
        }
        val finalRaw = combinedRaw.toString()
        if (finalRaw.isBlank()) {
            throw Exception("عدم دسترسی به سرور ساب‌لینک")
        }
        val configs = parseRawSubscription(finalRaw)
        if (configs.isNotEmpty()) {
            TimeGuard.getSecurePrefs(context).edit().putString(KEY_CACHED_RAW_SUB, finalRaw).apply()
        }
        return configs
    }

    private fun decodeSubscriptionText(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.contains("://")) return trimmed
        val cleanB64 = trimmed.filterNot { it.isWhitespace() }
        return try {
            String(Base64.decode(cleanB64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(Base64.decode(cleanB64, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                trimmed
            }
        }
    }

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
                line.startsWith("awg://", ignoreCase = true) ||
                line.startsWith("wg://", ignoreCase = true) ||
                line.startsWith("wireguard://", ignoreCase = true) -> parseGenericUri(line, "awg", "AmneziaWG")
                line.startsWith("ovpn://", ignoreCase = true) -> parseGenericUri(line, "ovpn", "OpenVPN")
                else -> null
            }

            if (parsed != null) {
                list.add(parsed)
            }
        }
        return list
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
                // IPv6
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
                // فرمت کاملاً Base64 شده: ss://BASE64(method:pass@host:port)#remark
                val b64 = mainPart.substringBefore("/").trim()
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
            val jsonStr = try {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                String(Base64.decode(b64, Base64.URL_SAFE), Charsets.UTF_8)
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
