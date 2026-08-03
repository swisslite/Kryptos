package com.kryptos.android.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import java.security.MessageDigest

object DeviceIntegrity {
    data class Report(
        val tampered: Boolean,
        val foreignAccessibility: List<String>,
    )

    fun check(context: Context): Report = Report(
        tampered = !signatureMatches(context),
        foreignAccessibility = foreignAccessibilityServices(context),
    )

    private const val EXPECTED_CERT_SHA256 =
        "6470246992edc4a44478d40cb84fcb2bb08138162794c4ae951f5d4b8faf8e54"

    private fun signatureMatches(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: return false
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        signatures.any { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } == EXPECTED_CERT_SHA256
        }
    }.getOrDefault(false)

    private fun foreignAccessibilityServices(context: Context): List<String> = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
    }.getOrDefault(emptyList())
}
