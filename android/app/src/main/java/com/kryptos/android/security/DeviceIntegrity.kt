package com.kryptos.android.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.security.MessageDigest

object DeviceIntegrity {
    data class Report(
        val rooted: Boolean,
        val debugged: Boolean,
        val emulator: Boolean,
        val hooked: Boolean,
        val tampered: Boolean,
        val foreignAccessibility: List<String>,
    )

    fun check(context: Context): Report = Report(
        rooted = isRooted(context),
        debugged = isDebugged(context),
        emulator = isEmulator(),
        hooked = isHooked(),
        tampered = !signatureMatches(context),
        foreignAccessibility = foreignAccessibilityServices(context),
    )

    private val suBinaries = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/system/xbin/daemonsu",
        "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
        "/system/bin/magisk", "/sbin/magisk", "/cache/magisk.log",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap",
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
        "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
        "com.thirdparty.superuser", "com.yellowes.su", "com.kingroot.kinguser",
        "de.robv.android.xposed.installer", "org.lsposed.manager", "io.va.exposed",
        "com.saurik.substrate",
    )

    private fun isRooted(context: Context): Boolean {
        if (suBinaries.any { runCatching { File(it).exists() }.getOrDefault(false) }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (rootPackages.any { isPackageInstalled(context, it) }) return true
        return System.getenv("PATH")?.split(":")?.any { File(it, "su").exists() } == true
    }

    private fun isPackageInstalled(context: Context, name: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(name, 0)
        true
    }.getOrDefault(false)

    private fun isDebugged(context: Context): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        if (tracerPid() != 0) return true
        val flags = context.applicationInfo.flags
        return (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun tracerPid(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun isHooked(): Boolean = xposedPresent() || suspiciousLibraryLoaded()

    private fun xposedPresent(): Boolean = runCatching {
        Class.forName("de.robv.android.xposed.XposedBridge")
        true
    }.getOrDefault(false)

    private fun suspiciousLibraryLoaded(): Boolean = runCatching {
        val markers = listOf("frida", "xposed", "substrate", "libriru", "zygisk")
        File("/proc/self/maps").bufferedReader().use { reader ->
            reader.lineSequence().take(8192).any { line ->
                val lower = line.lowercase()
                markers.any { lower.contains(it) }
            }
        }
    }.getOrDefault(false)

    private const val EXPECTED_CERT_SHA256 =
        "6470246992edc4a44478d40cb84fcb2bb08138162794c4ae951f5d4b8faf8e54"

    private fun signatureMatches(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: return true
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: return true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        signatures.any { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } == EXPECTED_CERT_SHA256
        }
    }.getOrDefault(true)

    private fun foreignAccessibilityServices(context: Context): List<String> = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    private fun isEmulator(): Boolean {
        val f = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return f.startsWith("generic") || f.contains("emulator") || f.contains("sdk_gphone") ||
            model.contains("emulator") || model.contains("android sdk") ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.lowercase().let { it.contains("sdk") || it.contains("emulator") }
    }
}
