package com.multivpn.app.core

import android.net.Uri
import android.util.Base64
import com.multivpn.app.sub.VpnConfig
import org.json.JSONArray
import org.json.JSONObject

object CoreConfigGenerator {
    const val LOCAL_SOCKS_PORT = 10808
    const val LOCAL_HTTP_PORT = 10809

    fun buildXrayJson(config: VpnConfig): String {
        val root = JSONObject()

        // تنظیمات لاگ
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // ورودی‌های لوکال (SOCKS5 + HTTP) روی گوشی
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", LOCAL_SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
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
            })
        })
        root.put("inbounds", inbounds)

        // خروجی‌ها (سرور اصلی + TLS Fragment ضد فیلترینگ)
        val outbounds = JSONArray()
        val mainOutbound = buildMainOutbound(config)
        outbounds.put(mainOutbound)

        // خروجی Fragment برای دور زدن DPI مخابرات و اپراتورها
        outbounds.put(JSONObject().apply {
            put("tag", "fragment-out")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
                put("fragment", JSONObject().apply {
                    put("packets", "tlshello")
                    put("length", "10-20")
                    put("interval", "10-20")
                })
            })
        })

        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        root.put("outbounds", outbounds)

        return root.toString(2)
    }

    private fun buildMainOutbound(config: VpnConfig): JSONObject {
        val raw = config.rawConfig.trim()
        return try {
            when {
                raw.startsWith("vless://", ignoreCase = true) -> parseVlessOutbound(raw)
                raw.startsWith("vmess://", ignoreCase = true) -> parseVmessOutbound(raw)
                raw.startsWith("trojan://", ignoreCase = true) -> parseTrojanOutbound(raw)
                raw.startsWith("ss://", ignoreCase = true) -> parseShadowsocksOutbound(raw)
                else -> buildFragmentFreedomOutbound()
            }
        } catch (e: Exception) {
            buildFragmentFreedomOutbound()
        }
    }

    private fun parseVlessOutbound(raw: String): JSONObject {
        val uri = Uri.parse(raw)
        val uuid = uri.userInfo ?: raw.substringAfter("vless://").substringBefore("@")
        val host = uri.host ?: raw.substringAfter("@").substringBefore(":")
        val port = if (uri.port > 0) uri.port else 443

        val encryption = uri.getQueryParameter("encryption") ?: "none"
        val flow = uri.getQueryParameter("flow") ?: ""

        val userObj = JSONObject().apply {
            put("id", uuid)
            put("encryption", encryption)
            if (flow.isNotBlank()) put("flow", flow)
        }

        val vnextObj = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("users", JSONArray().put(userObj))
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(vnextObj))
            })
            put("streamSettings", buildStreamSettingsFromUri(uri, host))
        }
    }

    private fun parseVmessOutbound(raw: String): JSONObject {
        val b64 = raw.substring(8).trim()
        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        val j = JSONObject(jsonStr)

        val host = j.optString("add", "")
        val port = j.optString("port", "443").toIntOrNull() ?: j.optInt("port", 443)
        val id = j.optString("id", "")
        val aid = j.optString("aid", "0").toIntOrNull() ?: 0
        val scy = j.optString("scy", "auto").ifBlank { "auto" }
        val net = j.optString("net", "tcp").ifBlank { "tcp" }
        val tls = j.optString("tls", "none")
        val sni = j.optString("sni", host).ifBlank { host }
        val wsHost = j.optString("host", host).ifBlank { host }
        val path = j.optString("path", "/").ifBlank { "/" }

        val userObj = JSONObject().apply {
            put("id", id)
            put("alterId", aid)
            put("security", scy)
        }

        val vnextObj = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("users", JSONArray().put(userObj))
        }

        val stream = JSONObject().apply {
            put("network", net)
            put("security", if (tls.equals("tls", true)) "tls" else "none")
            if (tls.equals("tls", true)) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("allowInsecure", true)
                })
            }
            if (net.equals("ws", true)) {
                put("wsSettings", JSONObject().apply {
                    put("path", path)
                    put("headers", JSONObject().apply {
                        put("Host", wsHost)
                    })
                })
            } else if (net.equals("grpc", true)) {
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", path.removePrefix("/"))
                })
            }
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vmess")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(vnextObj))
            })
            put("streamSettings", stream)
        }
    }

    private fun parseTrojanOutbound(raw: String): JSONObject {
        val uri = Uri.parse(raw)
        val password = uri.userInfo ?: raw.substringAfter("trojan://").substringBefore("@")
        val host = uri.host ?: raw.substringAfter("@").substringBefore(":")
        val port = if (uri.port > 0) uri.port else 443

        val serverObj = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("password", password)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "trojan")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(serverObj))
            })
            put("streamSettings", buildStreamSettingsFromUri(uri, host, defaultSecurity = "tls"))
        }
    }

    private fun parseShadowsocksOutbound(raw: String): JSONObject {
        val afterScheme = raw.substringAfter("ss://").substringBefore("#").substringBefore("?")
        var method = "aes-256-gcm"
        var password = ""
        var host = ""
        var port = 443

        if (afterScheme.contains("@")) {
            val userB64 = afterScheme.substringBefore("@")
            val decodedUser = try {
                String(Base64.decode(userB64, Base64.URL_SAFE or Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                userB64
            }
            method = decodedUser.substringBefore(":")
            password = decodedUser.substringAfter(":")
            val hp = afterScheme.substringAfter("@")
            host = hp.substringBefore(":")
            port = hp.substringAfter(":").toIntOrNull() ?: 443
        } else {
            val decodedAll = String(Base64.decode(afterScheme, Base64.URL_SAFE or Base64.DEFAULT), Charsets.UTF_8)
            val userPart = decodedAll.substringBeforeLast("@")
            val hp = decodedAll.substringAfterLast("@")
            method = userPart.substringBefore(":")
            password = userPart.substringAfter(":")
            host = hp.substringBefore(":")
            port = hp.substringAfter(":").toIntOrNull() ?: 443
        }

        val serverObj = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("method", method)
            put("password", password)
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(serverObj))
            })
        }
    }

    private fun buildStreamSettingsFromUri(uri: Uri, host: String, defaultSecurity: String = "none"): JSONObject {
        val net = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: defaultSecurity
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
        val fp = uri.getQueryParameter("fp") ?: "chrome"
        val pbk = uri.getQueryParameter("pbk") ?: ""
        val sid = uri.getQueryParameter("sid") ?: ""
        val path = uri.getQueryParameter("path") ?: "/"
        val wsHost = uri.getQueryParameter("host") ?: host
        val serviceName = uri.getQueryParameter("serviceName") ?: ""

        return JSONObject().apply {
            put("network", net)
            put("security", security)

            if (security.equals("tls", true)) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("fingerprint", fp)
                    put("allowInsecure", true)
                })
            } else if (security.equals("reality", true)) {
                put("realitySettings", JSONObject().apply {
                    put("serverName", sni)
                    put("fingerprint", fp)
                    put("publicKey", pbk)
                    put("shortId", sid)
                })
            }

            if (net.equals("ws", true)) {
                put("wsSettings", JSONObject().apply {
                    put("path", path)
                    put("headers", JSONObject().apply {
                        put("Host", wsHost)
                    })
                })
            } else if (net.equals("grpc", true)) {
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", serviceName)
                })
            }
        }
    }

    private fun buildFragmentFreedomOutbound(): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
                put("fragment", JSONObject().apply {
                    put("packets", "tlshello")
                    put("length", "10-20")
                    put("interval", "10-20")
                })
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("tcpNoDelay", true)
                })
            })
        }
    }
}
