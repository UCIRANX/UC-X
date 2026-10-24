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
import com.multivpn.app.log.AppLogger
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
    AppLogger.log(
        "IKEv2",
        "شروع: سرور=${s.server} کاربر=${s.username.ifBlank { "-" }} روش=${if (s.psk.isNotBlank()) "PSK" else "EAP"} شناسه‌سرور=${s.remoteId.ifBlank { "-" }}",
    )
    return try {
        ikev2ConsentIntentInner(ctx, s).also {
            AppLogger.log("IKEv2", if (it == null) "پروفایل آماده است (بدون نیاز به تأیید)" else "پروفایل ساخته شد؛ تأیید سیستم لازم است")
        }
    } catch (e: Throwable) {
        AppLogger.log("IKEv2", "خطا در ساخت پروفایل: ${errorChain(e)}")
        throw e
    }
}

/** زنجیرهٔ علت خطا (تا سه سطح) برای لاگ. */
fun errorChain(t: Throwable): String =
    generateSequence(t) { it.cause }.take(3).joinToString(" ← ") {
        "${it.javaClass.name}${it.message?.let { m -> ": $m" } ?: ""}"
    }

private fun ikev2ConsentIntentInner(ctx: Context, s: Ikev2Server): Intent? {
    val ike = IkeSessionParams.Builder()
        .setServerHostname(s.server)
        // اضافه کردن طیف کامل پروپوزال‌های سازگار با سرورهای تجاری (StrongSwan / serverlocation)
        .addIkeSaProposal(
            IkeSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1)
                .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
                .addDhGroup(SaProposal.DH_GROUP_1024_BIT_MODP)
                .build()
        )
        .addIkeSaProposal(
            IkeSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_128)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_NONE)
                .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
                .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
                .build()
        )

    // رفع مشکل ناسازگاری شناسه سرور (در صورت بروز خطا، از هاست‌نیم سرور به‌عنوان fallback استفاده می‌شود)
    val targetRemoteId = if (s.remoteId.isNotBlank()) s.remoteId.trim() else s.server.trim()
    ike.setRemoteIdentification(IkeFqdnIdentification(targetRemoteId))

    if (s.psk.isNotBlank()) {
        ike.setAuthPsk(s.psk.toByteArray(Charsets.UTF_8))
    } else {
        val eap = EapSessionConfig.Builder().setEapMsChapV2Config(s.username, s.password).build()
        ike.setAuthEap(null, eap)
    }

    // اضافه کردن Child SA Proposals کامل با پشتیبانی از الگوریتم‌های مختلف
    val child = TunnelModeChildSessionParams.Builder()
        .addChildSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_128)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_256)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_NONE)
                .build()
        )
        .addChildSaProposal(
            ChildSaProposal.Builder()
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
                .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
                .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
                .build()
        )
        // تنظیم Traffic Selectors جهت عبور کامل تمام ترافیک شبکه (0.0.0.0/0)
        .addInnerAddressRequest(android.net.InetAddresses.parseNumericAddress("0.0.0.0"))
        .addOutboundTrafficSelector(android.net.ipsec.ike.TrafficSelector(0, 65535, android.net.InetAddresses.parseNumericAddress("0.0.0.0"), android.net.InetAddresses.parseNumericAddress("255.255.255.255")))
        .addInboundTrafficSelector(android.net.ipsec.ike.TrafficSelector(0, 65535, android.net.InetAddresses.parseNumericAddress("0.0.0.0"), android.net.InetAddresses.parseNumericAddress("255.255.255.255")))
        .build()

    val localId = s.username.trim()
    if (localId.isNotEmpty()) {
        ike.setLocalIdentification(IkeFqdnIdentification(localId))
    }

    val params = IkeTunnelConnectionParams(ike.build(), child)
    val profile = Ikev2VpnProfile.Builder(params)
        .setBypassable(false)
        .setBlockedApplications(emptyList())
        .build()

    return vpnManager(ctx).provisionVpnProfile(profile)
}

/** راه‌اندازی پروفایل ثبت‌شده؛ خطا را به‌صورت متن برمی‌گرداند. */
fun startIkev2(ctx: Context, s: Ikev2Server?): String? {
    if (s == null) return "ابتدا یک سرور انتخاب کنید"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "IKEv2 فقط روی اندروید ۱۲ به بالا کار می‌کند"
    AppLogger.log("IKEv2", "درخواست شروع اتصال پروفایل ثبت‌شده")
    return try {
        // ثبت Event Receiver پیش از اجرای اتصال تا هیچ خطایی از دست نرود
        Ikev2Events.register(ctx)
        stopOthersForIkev2(ctx)
        vpnManager(ctx).startProvisionedVpnProfile()
        Ikev2Store.setActive(ctx, true)
        AppLogger.log("IKEv2", "startProvisionedVpnProfile فراخوانی شد")
        Ikev2Events.onStarted()
        runCatching { AppLogger.log("IKEv2", "وضعیت پروفایل سیستم: ${vpnManager(ctx).getProvisionedVpnProfileState()}") }
        null
    } catch (e: Exception) {
        AppLogger.log("IKEv2", "شروع اتصال ناموفق: ${errorChain(e)}")
        "اتصال IKEv2 شروع نشد: ${e.message}"
    }
}

/** قطع: توقف پروفایل ثبت‌شده بدون پاک کردن کامل اطلاعات در صورت عدم نیاز (جلوگیری از پرش شبکه) */
fun stopIkev2(ctx: Context) {
    AppLogger.log("IKEv2", "قطع اتصال IKEv2")
    Ikev2Events.onStopped()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { vpnManager(ctx).stopProvisionedVpnProfile() }
    }
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
            AppLogger.log("IKEv2", "شبکهٔ VPN فعال شد")
            Ikev2Events.onNetworkUp()
            onChange(true)
        }

        override fun onLost(network: Network) {
            AppLogger.log("IKEv2", "شبکهٔ VPN قطع شد")
            Ikev2Events.onNetworkDown()
            onChange(false)
        }
    }
    cm.registerNetworkCallback(request, cb)
    return cb
}

fun unwatchVpn(ctx: Context, cb: ConnectivityManager.NetworkCallback) {
    runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
}
