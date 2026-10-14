package com.multivpn.app.core

import android.util.Base64
import com.multivpn.app.sub.VpnConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object CoreConfigGenerator {
    const val LOCAL_SOCKS_PORT = 10808
    const val LOCAL_HTTP_PORT = 10809

    fun buildHysteria2Json(config: VpnConfig, resolvedHostIp: String): String {
        val raw = config.rawConfig.trim()
        val withoutScheme = raw.substringAfter("://").substringBefore("#")
        val userPass = withoutScheme.substringBefore("@")
        val authPassword = urlDecode(userPass.removeSuffix(":"))
        val params = parseQueryParams(withoutScheme.substringAfter("?", ""))

        val sni = params["sni"]?.ifBlank { null } ?: config.host
        val insecure = params["insecure"] == "1" || params["insecure"] == "true" || params["allowInsecure"] == "1"
        val obfsType = params["obfs"] ?: ""
        val obfsPassword = params["obfs-password"] ?: ""

        val serverAddress = if (resolvedHostIp.contains(":")) "[$resolvedHostIp]:${config.port}" else "$resolvedHostIp:${config.port}"

        return JSONObject().apply {
            put("server", serverAddress)
            put("auth", authPassword)
            put("tls", JSONObject().apply {
                put("sni", sni)
                put("insecure", insecure)
            })
            if (obfsType.equals("salamander", ignoreCase = true) && obfsPassword.isNotBlank()) {
                put("obfs", JSONObject().apply {
                    put("type", "salamander")
                    put("salamander", JSONObject().apply {
                        put("password", obfsPassword)
                    })
                })
            }
            put("socks5", JSONObject().apply {
                put("listen", "127.0.0.1:$LOCAL_SOCKS_PORT")
            })
            put("http", JSONObject().apply {
                put("listen", "127.0.0.1:$LOCAL_HTTP_PORT")
            })
        }.toString(2)
    }

    fun buildXrayJson(config: VpnConfig, resolvedHostIp: String): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "warning") })
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
        })

        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", LOCAL_SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
                put("routeOnly", true)
            })
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "http-in")
            put("port", LOCAL_HTTP_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
                put("routeOnly", true)
            })
        })
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(buildMainOutbound(config, resolvedHostIp))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        root.put("outbounds", outbounds)
        return root.toString(2)
    }

    private fun buildMainOutbound(config: VpnConfig, resolvedHostIp: String): JSONObject {
        val raw = config.rawConfig.trim()
        return when {
            raw.startsWith("vless://", ignoreCase = true) -> parseVlessOutbound(raw, config.host, config.port, resolvedHostIp)
            raw.startsWith("ss://", ignoreCase = true) -> parseShadowsocksOutbound(raw, resolvedHostIp)
            raw.startsWith("vmess://", ignoreCase = true) -> parseVmessOutbound(raw, resolvedHostIp)
            raw.startsWith("trojan://", ignoreCase = true) -> parseTrojanOutbound(raw, config.host, config.port, resolvedHostIp)
            else -> throw Exception("پروتکل ${config.protocol} پشتیبانی نمی‌شود")
        }
    }

    private fun parseVlessOutbound(raw: String, originalHost: String, port: Int, resolvedHostIp: String): JSONObject {
        val withoutScheme = raw.substringAfter("://").substringBefore("#")
        val uuid = urlDecode(withoutScheme.substringBefore("@").trim())
        val params = parseQueryParams(withoutScheme.substringAfter("?", ""))
        val encryption = params["encryption"]?.ifBlank { "none" } ?: "none"
        val net = (params["type"] ?: "tcp").lowercase()
        val flow = params["flow"] ?: ""

        val userObj = JSONObject().apply {
            put("id", uuid)
            put("encryption", encryption)
            if (flow.isNotBlank() && (net == "tcp" || net == "raw" || net == "xhttp")) {
                put("flow", flow)
            }
        }
        val vnextObj = JSONObject().apply {
            put("address", resolvedHostIp)
            put("port", port)
            put("users", JSONArray().put(userObj))
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply { put("vnext", JSONArray().put(vnextObj)) })
            put("streamSettings", buildStreamSettings(params, originalHost))
        }
    }

    private fun parseTrojanOutbound(raw: String, originalHost: String, port: Int, resolvedHostIp: String): JSONObject {
        val withoutScheme = raw.substringAfter("://").substringBefore("#")
        val password = urlDecode(withoutScheme.substringBefore("@").trim())
        val params = parseQueryParams(withoutScheme.substringAfter("?", ""))
        val serverObj = JSONObject().apply {
            put("address", resolvedHostIp)
            put("port", port)
            put("password", password)
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply { put("servers", JSONArray().put(serverObj)) })
            put("streamSettings", buildStreamSettings(params, originalHost, defaultSecurity = "tls"))
        }
    }

    private fun parseShadowsocksOutbound(raw: String, resolvedHostIp: String): JSONObject {
        val withoutScheme = raw.substringAfter("://").substringBefore("#").substringBefore("?")
        val method: String
        val password: String
        val port: Int

        if (withoutScheme.contains("@")) {
            val rawUser = urlDecode(withoutScheme.substringBeforeLast("@").trim())
            val hostPort = withoutScheme.substringAfterLast("@").substringBefore("/")
            port = hostPort.substringAfterLast(":", "443").toIntOrNull() ?: 443
            val decodedUser = if (rawUser.contains(":")) rawUser else decodeBase64Safe(rawUser)
            method = decodedUser.substringBefore(":").trim()
            password = decodedUser.substringAfter(":").trim()
        } else {
            val decodedAll = decodeBase64Safe(urlDecode(withoutScheme.substringBefore("/").trim()))
            val userPart = decodedAll.substringBeforeLast("@")
            val hostPort = decodedAll.substringAfterLast("@")
            method = userPart.substringBefore(":").trim()
            password = userPart.substringAfter(":").trim()
            port = hostPort.substringAfterLast(":", "443").toIntOrNull() ?: 443
        }

        val serverObj = JSONObject().apply {
            put("address", resolvedHostIp)
            put("port", port)
            put("method", method)
            put("password", password)
        }
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply { put("servers", JSONArray().put(serverObj)) })
        }
    }

    private fun parseVmessOutbound(raw: String, resolvedHostIp: String): JSONObject {
        val b64 = raw.substringAfter("://").trim()
        val j = JSONObject(decodeBase64Safe(b64))
        val originalHost = j.optString("add", "").trim()
        val port = j.optString("port", "443").toIntOrNull() ?: j.optInt("port", 443)
        val id = j.optString("id", "").trim()
        val aid = j.optString("aid", "0").toIntOrNull() ?: 0
        val scy = j.optString("scy", "auto").ifBlank { "auto" }
        val net = j.optString("net", "tcp").ifBlank { "tcp" }.lowercase()
        val tls = j.optString("tls", "none").lowercase()
        val sni = j.optString("sni", originalHost).ifBlank { originalHost }
        val wsHost = j.optString("host", originalHost).ifBlank { originalHost }
        val path = j.optString("path", "/").ifBlank { "/" }

        val userObj = JSONObject().apply {
            put("id", id)
            put("alterId", aid)
            put("security", scy)
        }
        val vnextObj = JSONObject().apply {
            put("address", resolvedHostIp)
            put("port", port)
            put("users", JSONArray().put(userObj))
        }
        val params = mapOf(
            "type" to net,
            "security" to if (tls == "tls") "tls" else "none",
            "sni" to sni,
            "host" to wsHost,
            "path" to path,
            "serviceName" to path.removePrefix("/")
        )
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply { put("vnext", JSONArray().put(vnextObj)) })
            put("streamSettings", buildStreamSettings(params, originalHost))
        }
    }

    private fun buildStreamSettings(params: Map<String, String>, originalHost: String, defaultSecurity: String = "none"): JSONObject {
        val rawNet = (params["type"] ?: "tcp").lowercase()
        val net = when (rawNet) {
            "httpupgrade" -> "httpupgrade"
            "splithttp", "xhttp" -> "splithttp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            else -> "tcp"
        }
        val security = (params["security"] ?: defaultSecurity).lowercase()
        val sni = params["sni"]?.ifBlank { null } ?: params["host"]?.ifBlank { null } ?: originalHost
        val fp = params["fp"]?.ifBlank { "chrome" } ?: "chrome"
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val spx = params["spx"] ?: "/"
        val path = params["path"]?.ifBlank { "/" } ?: "/"
        val wsHost = params["host"]?.ifBlank { originalHost } ?: originalHost
        val serviceName = params["serviceName"] ?: ""
        val headerType = params["headerType"] ?: "none"

        return JSONObject().apply {
            put("network", net)
            put("security", security)
            if (security == "tls") {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("fingerprint", fp)
                    put("allowInsecure", true)
                })
            } else if (security == "reality") {
                put("realitySettings", JSONObject().apply {
                    put("serverName", sni)
                    put("fingerprint", fp)
                    put("publicKey", pbk)
                    put("shortId", sid)
                    put("spiderX", spx)
                })
            }
            when (net) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", path)
                    put("headers", JSONObject().apply { put("Host", wsHost) })
                })
                "httpupgrade" -> put("httpupgradeSettings", JSONObject().apply {
                    put("path", path)
                    put("host", wsHost)
                })
                "splithttp" -> put("splithttpSettings", JSONObject().apply {
                    put("path", path)
                    put("host", wsHost)
                })
                "grpc" -> put("grpcSettings", JSONObject().apply { put("serviceName", serviceName) })
                "tcp" -> if (headerType.equals("http", ignoreCase = true)) {
                    put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("path", JSONArray().put(path))
                                put("headers", JSONObject().apply { put("Host", JSONArray().put(wsHost)) })
                            })
                        })
                    })
                }
            }
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            map[urlDecode(pair.substringBefore("="))] = urlDecode(pair.substringAfter("=", ""))
        }
        return map
    }

    private fun urlDecode(str: String): String = try { URLDecoder.decode(str, "UTF-8") } catch (_: Exception) { str }

    private fun decodeBase64Safe(input: String): String {
        val clean = input.filterNot { it.isWhitespace() }
        val padded = clean.padEnd((clean.length + 3) / 4 * 4, '=')
        return try {
            String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }
    }
}
