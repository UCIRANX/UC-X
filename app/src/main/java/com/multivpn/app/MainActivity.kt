package com.multivpn.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import ir.uciran.uctor.FullVpnService
import ir.uciran.uctor.Phase
import ir.uciran.uctor.Prefs
import ir.uciran.uctor.TorController
import ir.uciran.uctor.VpnController
import ir.uciran.uctor.ui.UcTorTheme

class MainActivity : ComponentActivity() {
    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            TorController.setVpnStatus(
                intent.getBooleanExtra(FullVpnService.EXTRA_READY, false),
                intent.getStringExtra(FullVpnService.EXTRA_ERROR).orEmpty()
            )
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // بعد از هر پاسخ به مجوز VPN، مجوز نوتیفیکیشن پرسیده می‌شود
    private val vpnPermission =
        registerForActivityResult(StartActivityForResult()) { result ->
            requestNotificationPermission()
            if (result.resultCode == RESULT_OK &&
                TorController.state.value.phase == Phase.CONNECTED && !Prefs(this).vpnRunning
            ) {
                FullVpnService.start(this)
            }
        }

    /** ترتیب درست: اول مجوز VPN، سپس مجوز نوتیفیکیشن. */
    private fun requestVpnThenNotification() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** وقتی Tor وصل شد، VPN سراسری را همین‌جا (با مجوز لازم) راه می‌اندازد. */
    fun startFullVpnIfReady() {
        if (TorController.state.value.phase != Phase.CONNECTED || Prefs(this).vpnRunning) return
        val intent = VpnController.permissionIntent(this)
        if (intent != null) vpnPermission.launch(intent) else FullVpnService.start(this)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(vpnReceiver) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // لبه‌تا‌لبه؛ فاصله‌ی نوار سیستم در AppRoot با WindowInsets رعایت می‌شود
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        TorController.init(applicationContext)
        SubUpdate.schedule(applicationContext)
        ContextCompat.registerReceiver(
            this, vpnReceiver, IntentFilter(FullVpnService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent { UcTorTheme { AppRoot() } }
        window.decorView.post { requestVpnThenNotification() }
    }
}
