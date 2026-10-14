package com.multivpn.app.sub

import android.content.Context
import android.net.Uri
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

    // آدرس‌های ساب‌لینک مخفی شما (کاربر در برنامه قادر به دیدن یا تغییر آن‌ها نیست)
    private val SUBSCRIPTION_URLS = listOf(
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt"
    )

    fun loadCachedOrSampleConfigs(context: Context): List<VpnConfig> {
        val cached = TimeGuard.getSecurePrefs(context).getString(KEY_CACHED_RAW_SUB, null)
        val parsed = if (!cached.isNullOrBlank()) parseRawSubscription(cached) else emptyList()
        return if (parsed.isNotEmpty()) parsed else getBuiltInStarterConfigs()
    }

    suspend fun fetchAndParseSub(context: Context): List<VpnConfig> {
        val combinedRaw = StringBuilder()
        for (url in SUBSCRIPTION_URLS) {
            try {
                val text = DohClient.fetchUrlText(url)
                combinedRaw.append(text).append("\n")
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
        return if (configs.isNotEmpty()) configs else getBuiltInStarterConfigs()
    }

    fun parseRawSubscription(rawText: String): List<VpnConfig> {
        val trimmed = rawText.trim()
        val decodedContent = if (!trimmed.contains("://") && !trimmed.contains("[Interface]")) {
            try {
                val cleanB64 = trimmed.filterNot { it.isWhitespace() }
                String(Base64.decode(cleanB64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                trimmed
            }
        } else {
            trimmed
        }

        val list = mutableListOf<VpnConfig>()
        val lines = decodedContent.lines()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            when {
                line.startsWith("vmess://", ignoreCase = true) -> {
                    parseVmess(line)?.let { list.add(it) }
                }
                line.startsWith("vless://", ignoreCase = true) ||
                line.startsWith("trojan://", ignoreCase = true) ||
                line.startsWith("ss://", ignoreCase = true) ||
                line.startsWith("hysteria2://", ignoreCase = true) ||
                line.startsWith("hy2://", ignoreCase = true) ||
                line.startsWith("tuic://", ignoreCase = true) -> {
                    parseStandardUri(line, "Sing-box (Xray)")?.let { list.add(it) }
                }
                line.startsWith("awg://", ignoreCase = true) ||
                line.startsWith("wg://", ignoreCase = true) ||
                line.startsWith("wireguard://", ignoreCase = true) -> {
                    parseStandardUri(line, "AmneziaWG")?.let { list.add(it) }
                }
                line.startsWith("ovpn://", ignoreCase = true) -> {
                    parseStandardUri(line, "OpenVPN")?.let { list.add(it) }
                }
                line.startsWith("Endpoint", ignoreCase = true) && line.contains("=") -> {
                    val endpoint = line.substringAfter("=").trim()
                    val host = endpoint.substringBeforeLast(":").trim()
                    val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820
                    if (host.isNotEmpty()) {
                        list.add(
                            VpnConfig(
                                name = "AmneziaWG ($host)",
                                coreType = "AmneziaWG",
                                protocol = "awg",
                                host = host,
                                port = port,
                                rawConfig = decodedContent
                            )
                        )
                    }
                }
                line.startsWith("remote ", ignoreCase = true) -> {
                    val parts = line.split(' ', '\t').filter { it.isNotBlank() }
                    if (parts.size >= 3) {
                        val host = parts[1]
                        val port = parts[2].toIntOrNull() ?: 1194
                        list.add(
                            VpnConfig(
                                name = "OpenVPN ($host)",
                                coreType = "OpenVPN",
                                protocol = "ovpn",
                                host = host,
                                port = port,
                                rawConfig = decodedContent
                            )
                        )
                    }
                }
            }
            if (list.size >= 40) break
        }

        list.addAll(getBuiltInFallbackNodes())
        return list
    }

    private fun parseVmess(uriStr: String): VpnConfig? {
        return try {
            val b64 = uriStr.substring(8).trim()
            val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val host = json.optString("add", "")
            val port = json.optString("port", "443").toIntOrNull() ?: json.optInt("port", 443)
            val name = json.optString("ps", "VMess-$host").ifBlank { "VMess-$host" }
            if (host.isBlank()) null else VpnConfig(
                name = name.take(36),
                coreType = "Sing-box (Xray)",
                protocol = "vmess",
                host = host,
                port = port,
                rawConfig = uriStr
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStandardUri(uriStr: String, coreType: String): VpnConfig? {
        return try {
            val protocol = uriStr.substringBefore("://").lowercase()
            val uri = Uri.parse(uriStr)
            var host = uri.host ?: ""
            var port = uri.port

            if (host.isBlank()) {
                val afterScheme = uriStr.substringAfter("://").substringBefore("#").substringBefore("?")
                val hostPort = if (afterScheme.contains("@")) afterScheme.substringAfterLast("@") else afterScheme
                host = hostPort.substringBefore(":")
                port = hostPort.substringAfter(":", "443").substringBefore("/").toIntOrNull() ?: 443
            }
            if (port <= 0) port = 443

            val rawFragment = uriStr.substringAfterLast("#", "")
            val remark = if (rawFragment.isNotBlank() && rawFragment != uriStr) {
                try { URLDecoder.decode(rawFragment, "UTF-8") } catch (_: Exception) { rawFragment }
            } else {
                "${protocol.uppercase()}-$host"
            }

            if (host.isBlank()) null else VpnConfig(
                name = remark.take(36),
                coreType = coreType,
                protocol = protocol,
                host = host,
                port = port,
                rawConfig = uriStr
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getBuiltInFallbackNodes(): List<VpnConfig> {
        return listOf(
            VpnConfig(
                name = "Psiphon Direct Tunnel (EU)",
                coreType = "Psiphon",
                protocol = "psiphon",
                host = "1.1.1.1",
                port = 443,
                rawConfig = "psiphon://auto"
            ),
            VpnConfig(
                name = "Tor Guard Relay (Obfs4)",
                coreType = "Tor",
                protocol = "tor",
                host = "8.8.8.8",
                port = 443,
                rawConfig = "tor://obfs4"
            )
        )
    }

    private fun getBuiltInStarterConfigs(): List<VpnConfig> {
        return listOf(
            VpnConfig(
                name = "Xray VLESS Reality (Cloudflare)",
                coreType = "Sing-box (Xray)",
                protocol = "vless",
                host = "1.1.1.1",
                port = 443,
                rawConfig = "vless://starter@1.1.1.1:443?security=reality#Xray-Starter"
            ),
            VpnConfig(
                name = "AmneziaWG Warp Endpoint",
                coreType = "AmneziaWG",
                protocol = "awg",
                host = "162.159.192.1",
                port = 443,
                rawConfig = "awg://162.159.192.1:443#Amnezia-Warp"
            ),
            VpnConfig(
                name = "OpenVPN TCP Relay",
                coreType = "OpenVPN",
                protocol = "ovpn",
                host = "8.8.4.4",
                port = 443,
                rawConfig = "ovpn://8.8.4.4:443#OpenVPN-Relay"
            )
        ) + getBuiltInFallbackNodes()
    }
}
