package com.multivpn.app.security

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer

sealed class LicenseStatus {
    data class Valid(val remainingSeconds: Long, val expiryEpochSec: Long) : LicenseStatus()
    data class Expired(val message: String) : LicenseStatus()
    data class Invalid(val reason: String) : LicenseStatus()
    object Missing : LicenseStatus()
}

object LicenseManager {
    private const val KEY_SAVED_TOKEN = "saved_license_token"
    private const val PUBLIC_KEY_HEX = "1e11705bc52f183c8ea574ff74a44010501b63846ce11e54d1a2bf0928fcc472"

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun verifyAndSaveToken(context: Context, rawInput: String): LicenseStatus {
        val status = verifyTokenInternal(context, rawInput)
        if (status is LicenseStatus.Valid) {
            TimeGuard.getSecurePrefs(context)
                .edit()
                .putString(KEY_SAVED_TOKEN, rawInput.trim())
                .apply()
        }
        return status
    }

    fun getCurrentLicenseStatus(context: Context): LicenseStatus {
        val saved = TimeGuard.getSecurePrefs(context).getString(KEY_SAVED_TOKEN, null)
        if (saved.isNullOrBlank()) return LicenseStatus.Missing
        return verifyTokenInternal(context, saved)
    }

    private fun verifyTokenInternal(context: Context, rawInput: String): LicenseStatus {
        if (TimeGuard.isClockTampered(context)) {
            return LicenseStatus.Invalid("ساعت گوشی به عقب کشیده شده است! لطفاً تاریخ و ساعت گوشی را به حالت خودکار برگردانید.")
        }

        val cleaned = rawInput.trim().removePrefix("MV1-").removePrefix("mv1-")
        val decoded: ByteArray = try {
            Base64.decode(cleaned, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            return LicenseStatus.Invalid("فرمت کد وارد شده نامعتبر است.")
        }

        // ساختار باینری: 8 بایت شناسه دستگاه + 8 بایت زمان انقضا + 64 بایت امضای Ed25519 = 80 بایت
        if (decoded.size != 80) {
            return LicenseStatus.Invalid("طول کد لایسنس صحیح نیست.")
        }

        val payload = decoded.copyOfRange(0, 16)
        val signature = decoded.copyOfRange(16, 80)

        val isSignatureValid = try {
            val pubKeyParams = Ed25519PublicKeyParameters(hexToBytes(PUBLIC_KEY_HEX), 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKeyParams)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }

        if (!isSignatureValid) {
            return LicenseStatus.Invalid("امضای دیجیتال کد نامعتبر است یا دستکاری شده است.")
        }

        val tokenDeviceId = String(payload, 0, 8, Charsets.US_ASCII)
        val myDeviceId = DeviceIdProvider.getCleanDeviceId(context)

        if (!tokenDeviceId.equals(myDeviceId, ignoreCase = true)) {
            return LicenseStatus.Invalid("این کد متعلق به این گوشی نیست! (قفل تک‌دستگاهی)")
        }

        val expiryEpochSec = ByteBuffer.wrap(payload, 8, 8).long
        val nowSec = TimeGuard.getCurrentVerifiedTimeSec(context)

        val remaining = expiryEpochSec - nowSec
        return if (remaining <= 0L) {
            LicenseStatus.Expired("مهلت استفاده ۵ روزه این کد تمام شده است. لطفاً از ربات تلگرام کد جدید بگیرید.")
        } else {
            LicenseStatus.Valid(remainingSeconds = remaining, expiryEpochSec = expiryEpochSec)
        }
    }

    fun formatRemainingTime(seconds: Long): String {
        if (seconds <= 0) return "منقضی شده"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return "${days} روز و ${hours} ساعت و ${mins} دقیقه و ${secs} ثانیه"
    }
}
    
