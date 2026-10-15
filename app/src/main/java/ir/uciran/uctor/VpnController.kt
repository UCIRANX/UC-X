package ir.uciran.uctor

import android.app.Activity
import android.content.Intent
import android.net.VpnService

object VpnController {
    const val REQUEST = 4281
    fun permissionIntent(activity: Activity): Intent? = VpnService.prepare(activity)
    fun start(activity: Activity) {
        val i = permissionIntent(activity)
        if (i != null) activity.startActivityForResult(i, REQUEST) else FullVpnService.start(activity)
    }
    fun startAfterPermission(activity: Activity, requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST && resultCode == Activity.RESULT_OK) FullVpnService.start(activity)
    }
    fun stop(activity: Activity) = FullVpnService.stop(activity)
}
