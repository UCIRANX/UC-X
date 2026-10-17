package com.multivpn.app.awg

import android.util.Base64

/** تجزیهٔ کانفیگ AmneziaWG (awg://<base64 از متن .conf>) و ساخت تنظیمات UAPI برای هسته. */
object AwgConfig {

    class Parsed(
        val iface: Map<String, String>,
        val peer: Map<String, String>,
        val allowed: List<String>,
        val endpointHost: String,
        val endpointPort: Int,
        val addresses: List<Pair<String, Int>>,
        val dns: List<String>,
        val mtu: Int,
    )

    /** فقط فرمت awg:// پشتیبانی می‌شود. wireguard:// با پارامترهای نویز هنوز رمزگشایی نمی‌شود. */
    fun decodeConf(raw: String): String? {
        val t = raw.trim()
        if (!t.startsWith("awg://", ignoreCase = true)) return null
        val payload = t.substringAfter("://").substringBefore("#").trim()
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (_: Exception) {
            Base64.decode(payload, Base64.URL_SAFE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun parse(conf: String): Parsed? {
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()
        val allowed = mutableListOf<String>()
        var section = ""
        for (raw in conf.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.equals("[Interface]", ignoreCase = true)) {
                section = "i"
                continue
            }
            if (line.equals("[Peer]", ignoreCase = true)) {
                section = "p"
                continue
            }
            if (!line.contains("=")) continue
            val key = line.substringBefore("=").trim().lowercase()
            val value = line.substringAfter("=").trim()
            when (section) {
                "i" -> {
                    iface[key] = value
                }
                "p" -> {
                    if (key == "allowedips") {
                        allowed.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    } else {
                        peer[key] = value
                    }
                }
            }
        }
        if (iface["privatekey"] == null || peer["publickey"] == null) return null
        val (host, port) = splitEndpoint(peer["endpoint"] ?: return null) ?: return null
        val addresses = (iface["address"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { a ->
            val ip = a.substringBefore("/")
            val prefix = a.substringAfter("/", if (ip.contains(":")) "128" else "32").toIntOrNull() ?: 32
            ip to prefix
        }
        val dns = (iface["dns"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val mtu = iface["mtu"]?.toIntOrNull() ?: 1280
        return Parsed(iface, peer, allowed, host, port, addresses, dns, mtu)
    }

    private fun splitEndpoint(e: String): Pair<String, Int>? {
        return if (e.startsWith("[")) {
            val host = e.substringBefore("]").removePrefix("[")
            val port = e.substringAfter("]:", "").toIntOrNull()
            if (port != null && host.isNotEmpty()) host to port else null
        } else {
            val host = e.substringBeforeLast(":")
            val port = e.substringAfterLast(":").toIntOrNull()
            if (port != null && host.isNotEmpty()) host to port else null
        }
    }

    private fun hex(b64: String): String =
        Base64.decode(b64, Base64.DEFAULT).joinToString("") { "%02x".format(it) }

    /** تنظیمات UAPI؛ آدرس endpoint باید IP باشد، پس قبلاً از بیرون resolve شده است. */
    fun toUapi(p: Parsed, endpointIp: String): String {
        val sb = StringBuilder()
        sb.append("set=1\n")
        sb.append("private_key=").append(hex(p.iface.getValue("privatekey"))).append('\n')
        for (k in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")) {
            val v = p.iface[k] ?: continue
            sb.append(k).append('=').append(v).append('\n')
        }
        sb.append("public_key=").append(hex(p.peer.getValue("publickey"))).append('\n')
        p.peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let {
            sb.append("preshared_key=").append(hex(it)).append('\n')
        }
        val ep = if (endpointIp.contains(":")) "[$endpointIp]" else endpointIp
        sb.append("endpoint=").append(ep).append(':').append(p.endpointPort).append('\n')
        p.peer["persistentkeepalive"]?.toIntOrNull()?.let {
            sb.append("persistent_keepalive_interval=").append(it).append('\n')
        }
        for (a in p.allowed) sb.append("allowed_ip=").append(a).append('\n')
        return sb.toString()
    }
}
