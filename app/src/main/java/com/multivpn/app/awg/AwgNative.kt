package com.multivpn.app.awg

/** پل JNI به کتابخانهٔ AmneziaWG (libawg.so) که از amneziawg-go ساخته می‌شود. */
object AwgNative {
    init {
        System.loadLibrary("awg")
    }

    /** برمی‌گرداند: شناسهٔ تونل (بزرگ‌تر از صفر) یا -1 در صورت خطا. */
    @JvmStatic
    external fun turnOn(iface: String, tunFd: Int, settings: String): Int

    @JvmStatic
    external fun turnOff(handle: Int)

    @JvmStatic
    external fun ipcGet(handle: Int): String
}

sealed class AwgState {
    object Idle : AwgState()
    object Connecting : AwgState()
    data class Up(val name: String) : AwgState()
    data class Failed(val message: String) : AwgState()
}
