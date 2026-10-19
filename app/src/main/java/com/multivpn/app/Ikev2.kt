package com.multivpn.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnManager
import android.net.eap.EapSessionConfig
import android.net.ipsec.ike.ChildSaProposal
import android.net.ipsec.ike.IkeFqdnIdentification
import android.net.ipsec.ike.IkeSaProposal
import android.net.ipsec.ike.IkeSessionParams
import android.net.ipsec.ike.IkeTunnelConnectionParams
import android.net.ipsec.ike.SaProposal
import android.net.ipsec.ike.TunnelModeChildSessionParams
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import ir.uciran.uctor.Phase
import ir.uciran.uctor.TorController
import org.json.JSONArray
import org.json.JSONObject

/**
 * یک سرور IKEv2.
 * remoteId = Server identity (اختیاری)؛ psk اگر پر باشد احراز هویت PSK است، وگرنه EAP با نام کاربری و رمز.
 */
data class Ikev2Server(
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val remoteId: String = "",
    val psk: String = "",
) {
    val key: String get() = "$server|$username"
    val label: String get() = if (name.isNotBlank()) name else server
}

/** ذخیرهٔ سرورهای IKEv2 دستی و سرور انتخاب‌شده. */
object Ikev2Store {
    private const val K_LIST = "IKEV2_LIST"
    private const val K_SELECTED = "IKEV2_SELECTED"
    private const val K_ACTIVE = "IKEV2_ACTIVE"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun load(ctx: Context): List<Ikev2Server> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(K_LIST, "[]") ?: "[]")
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Ikev2Server(
                name = o.optString("n"),
                server = o.getString("s"),
                username = o.getString("u"),
                password = o.optString("p"),
                remoteId = o.optString("r"),
                psk = o.optString("k"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Ikev2Server>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("n", it.name).put("s", it.server).put("u", it.username)
                    .put("p", it.password).put("r", it.remoteId).put("k", it.psk)
            )
        }
        prefs(ctx).edit().putString(K_LIST, arr.toString()).apply()
    }

    fun selected(ctx: Context): String? = prefs(ctx).getString(K_SELECTED, null)

    fun setSelected(ctx: Context, key: String?) {
        prefs(ctx).edit().putString(K_SELECTED, key).apply()
    }

    /** آیا پروفایل IKEv2 این برنامه فعال شده است (برای قطع هنگام اتصال بخش‌های دیگر). */
    fun isActive(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ACTIVE, false)

    fun setActive(ctx: Context, active: Boolean) {
        prefs(ctx).edit().putBoolean(K_ACTIVE, active).apply()
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun vpnManager(ctx: Context): VpnManager = ctx.getSystemService(VpnManager::class.java)

/**
 * پروفایل IKEv2 را با IkeSessionParams می‌سازد و در سیستم ثبت می‌کند.
 * Server identity → remoteIdentification؛ PSK یا EAP (نام کاربری و رمز).
 * اگر کاربر هنوز اجازه نداده باشد، Intent تأیید سیستم برمی‌گرداند؛ وگرنه null.
 */
@RequiresApi(Build.VERSION_CODES.S)
fun ikev2ConsentIntent(ctx: Context, s: Ikev2Server): Intent? {
    // پیشنهادهای رمزنگاری (بدون آن‌ها سرور IKE SA proposal را قبول نمی‌کند)
    val ike = IkeSessionParams.Builder()
        .setServerHostname(s.server)
        .addSaProposal(
            IkeSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA2_256)
                .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
                .build()
        )
    if (s.remoteId.isNotBlank()) {
        ike.setRemoteIdentification(IkeFqdnIdentification(s.remoteId.trim()))
    }
    if (s.psk.isNotBlank()) {
        ike.setAuthPsk(s.psk.toByteArray(Charsets.UTF_8))
    } else {
        // گواهی CA خالی است تا سیستم خودکار انتخاب کند (Select automatically)
        val eap = EapSessionConfig.Builder().setEapMsChapV2Config(s.username, s.password).build()
        ike.setAuthEap(null, eap)
    }
    val child = TunnelModeChildSessionParams.Builder()
        .addSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_NONE)
                .build()
        )
        .addSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .build()
        )
        .build()
    val params = IkeTunnelConnectionParams(ike.build(), child)
    val profile = Ikev2VpnProfile.Builder(params).build()
    return vpnManager(ctx).provisionVpnProfile(profile)
}

/** راه‌اندازی پروفایل ثبت‌شده؛ خطا را به‌صورت متن برمی‌گرداند. */
fun startIkev2(ctx: Context, s: Ikev2Server?): String? {
    if (s == null) return "ابتدا یک سرور انتخاب کنید"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "IKEv2 فقط روی اندروید ۱۲ به بالا کار می‌کند"
    return try {
        stopOthersForIkev2(ctx)
        vpnManager(ctx).startProvisionedVpnProfile()
        Ikev2Store.setActive(ctx, true)
        null
    } catch (e: Exception) {
        "اتصال IKEv2 شروع نشد: ${e.message}"
    }
}

/** قطع: حذف پروفایل ثبت‌شده (سیستم اتصال را می‌بندد). */
fun stopIkev2(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { vpnManager(ctx).deleteProvisionedVpnProfile() }
    }
    Ikev2Store.setActive(ctx, false)
}

/** اگر IKEv2 فعال است، قطعش می‌کند (هنگام اتصال بخش‌های دیگر). */
fun stopIkev2IfActive(ctx: Context) {
    if (Ikev2Store.isActive(ctx)) stopIkev2(ctx)
}

/** هنگام اتصال IKEv2، همه VPNهای دیگر قطع می‌شوند. */
fun stopOthersForIkev2(ctx: Context) {
    SingBoxController.stop(ctx, "سینگ باکس برای اتصال IKEv2 قطع شد")
    stopGuardIfActive(ctx)
    stopSstpIfActive(ctx)
    if (TorController.state.value.phase != Phase.IDLE) TorController.disconnect()
}

/** ثبت شنونده برای وجود شبکهٔ VPN؛ هنگام توقف باید با unwatchVpn لغو شود. */
fun watchVpn(ctx: Context, onChange: (Boolean) -> Unit): ConnectivityManager.NetworkCallback {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onChange(true)
        }

        override fun onLost(network: Network) {
            onChange(false)
        }
    }
    cm.registerNetworkCallback(request, cb)
    return cb
}

fun unwatchVpn(ctx: Context, cb: ConnectivityManager.NetworkCallback) {
    runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
}
