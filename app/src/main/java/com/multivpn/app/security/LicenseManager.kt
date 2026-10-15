package com.multivpn.app.security

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

sealed class LicenseStatus {
    data class Valid(val remainingSeconds: Long, val expiryEpochSec: Long) : LicenseStatus()
    data class Expired(val message: String) : LicenseStatus()
    data class Invalid(val reason: String) : LicenseStatus()
    object Missing : LicenseStatus()
}

object LicenseManager {
    private const val KEY_SAVED_TOKEN = "saved_license_token"
    private const val TOKEN_PREFIX = "MV3-"
    private const val PAYLOAD_SIZE = 16     // ۸ بایت شناسه + ۸ بایت انقضا
    private const val RAW_SIG_SIZE = 64    // امضای ECDSA P-256 به فرمت r||s
    private const val TOKEN_SIZE = PAYLOAD_SIZE + RAW_SIG_SIZE

    // کلید عمومی در زمان build توسط GitHub Actions جایگزین می‌شود (فقط کلید عمومی است)
    private const val PUBLIC_KEY_B64 = "PASTE_PUBLIC_KEY_HERE"

    private val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT))
        )
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

        // حذف فاصله و پیشوند (بدون حساسیت به حروف بزرگ و کوچک)
        val trimmed = rawInput.trim().replace(" ", "")
        val stripped = if (trimmed.startsWith(TOKEN_PREFIX, ignoreCase = true)) {
            trimmed.substring(TOKEN_PREFIX.length)
        } else {
            trimmed
        }

        val decoded: ByteArray = try {
            Base64.decode(stripped, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: IllegalArgumentException) {
            return LicenseStatus.Invalid("فرمت کد وارد شده نامعتبر است.")
        }

        if (decoded.size != TOKEN_SIZE) {
            return LicenseStatus.Invalid("طول کد لایسنس صحیح نیست.")
        }

        val payload = decoded.copyOfRange(0, PAYLOAD_SIZE)
        val rawSig = decoded.copyOfRange(PAYLOAD_SIZE, TOKEN_SIZE)

        val isSignatureValid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload)
                verify(rawToDer(rawSig))
            }
        } catch (e: Exception) {
            false
        }

        if (!isSignatureValid) {
            return LicenseStatus.Invalid("امضای دیجیتال کد نامعتبر است یا دستکاری شده است.")
        }

        val tokenDeviceId = String(payload, 0, 8, Charsets.US_ASCII).trim()
        val myDeviceId = DeviceIdProvider.getCleanDeviceId(context)

        if (!tokenDeviceId.equals(myDeviceId, ignoreCase = true)) {
            return LicenseStatus.Invalid("این کد متعلق به این گوشی نیست! (قفل تک‌دستگاهی)")
        }

        val expiryEpoch = ByteBuffer.wrap(payload, 8, 8).long
        val nowSec = TimeGuard.getCurrentVerifiedTimeSec(context)
        val remaining = expiryEpoch - nowSec

        return if (remaining <= 0L) {
            LicenseStatus.Expired("مهلت استفاده ۵ روزه این کد تمام شده است. از ربات تلگرام کد جدید بگیرید.")
        } else {
            LicenseStatus.Valid(remainingSeconds = remaining, expiryEpochSec = expiryEpoch)
        }
    }

    // تبدیل امضای خام r||s (۶۴ بایت) به DER که JCA انتظار دارد
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun derInteger(x: ByteArray): ByteArray {
            var start = 0
            while (start < x.size - 1 && x[start] == 0.toByte()) start++
            var v = x.copyOfRange(start, x.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = derInteger(raw.copyOfRange(0, 32)) + derInteger(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
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
