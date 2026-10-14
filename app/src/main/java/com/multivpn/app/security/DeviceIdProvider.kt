package com.multivpn.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdProvider {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    @SuppressLint("HardwareIds")
    fun getCleanDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_id"

        val rawFingerprint = "$androidId-${Build.BOARD}-${Build.BRAND}-${Build.DEVICE}-${Build.HARDWARE}"
        val digest = MessageDigest.getInstance("SHA-256").digest(rawFingerprint.toByteArray(Charsets.UTF_8))

        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val index = (digest[i].toInt() and 0xFF) % ALPHABET.length
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    fun getFormattedDeviceId(context: Context): String {
        val clean = getCleanDeviceId(context)
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
    }
}
