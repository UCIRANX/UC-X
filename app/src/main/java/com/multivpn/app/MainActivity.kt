package com.multivpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
  MaterialTheme {
      Surface(
modifier = Modifier.fillMaxSize(),
color = MaterialTheme.colorScheme.background
      ) {
SinglePageVpnScreen()
      }
  }
        }
    }
}

@Composable
fun SinglePageVpnScreen() {
    var selectedCore by remember { mutableStateOf("Auto (Smart)") }
    var isConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("آماده اتصال") }
    var expandCoreMenu by remember { mutableStateOf(false) }

    val cores = listOf(
        "Auto (Smart)",
        "Sing-box (Xray)",
        "AmneziaWG",
        "OpenVPN",
        "Psiphon",
        "Tor"
    )

    Column(
        modifier = Modifier
  .fillMaxSize()
  .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
  Column(modifier = Modifier.padding(16.dp)) {
      Text(text = "شناسه دستگاه: در حال آماده‌سازی (فاز ۲)")
      Spacer(modifier = Modifier.height(4.dp))
      Text(text = "اعتبار کد ۵ روزه: بررسی نشده")
  }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Button(
      onClick = {
isConnected = !isConnected
statusText = if (isConnected) "متصل به \$selectedCore" else "قطع شد"
      },
      modifier = Modifier.size(160.dp)
  ) {
      Text(if (isConnected) "قطع اتصال" else "اتصال")
  }
  Spacer(modifier = Modifier.height(16.dp))
  Text(text = statusText)
        }

        Column(
  modifier = Modifier.fillMaxWidth(),
  verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
  Box(modifier = Modifier.fillMaxWidth()) {
      OutlinedButton(
onClick = { expandCoreMenu = true },
modifier = Modifier.fillMaxWidth()
      ) {
Text("هسته فعال: \$selectedCore")
      }
      DropdownMenu(
expanded = expandCoreMenu,
onDismissRequest = { expandCoreMenu = false }
      ) {
cores.forEach { core ->
    DropdownMenuItem(
        text = { Text(core) },
        onClick = {
  selectedCore = core
  expandCoreMenu = false
        }
    )
}
      }
  }

  OutlinedButton(
      onClick = { statusText = "در حال تست پینگ کانفیگ‌ها..." },
      modifier = Modifier.fillMaxWidth()
  ) {
      Text("تست پینگ")
  }

  OutlinedButton(
      onClick = { statusText = "در حال دریافت کانفیگ‌ها از ساب‌لینک..." },
      modifier = Modifier.fillMaxWidth()
  ) {
      Text("بروزرسانی ساب‌لینک‌ها")
  }
        }
    }
}

class MultiCoreVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    override fun onRevoke() {
        super.onRevoke()
        stopSelf()
    }
}
