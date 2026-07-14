package top.cbug.adbx.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdbHelper {

    private const val TAG = "ADB_X_AdbHelper"

    /** Use settings global as primary check (works on Rust adbd too) */
    fun isAdbWifiEnabled(context: Context): Boolean {
        return getCurrentState(context)
    }

    /** Get current ADB port - prefers non-root method first */
    fun getCurrentPort(context: Context?): String {
        val nr = getCurrentPortNonRoot()
        if (nr.isNotEmpty()) return nr
        if (!ShellUtils.hasRoot()) return ""
        for (prop in arrayOf(
            "service.adb.tls.port",
            "service.adb.tcp.port"
        )) {
            val r = ShellUtils.executeSu("getprop " + prop, 500)
            if (r.isSuccess()) {
                val out = r.output.trim()
                if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                    return out.filter { it.isDigit() }.take(5)
                }
            }
        }
        return ""
    }

    /** Non-root: read service props (all processes can read) */
    fun getCurrentPortNonRoot(): String {
        for (prop in arrayOf(
            "service.adb.tls.port",
            "service.adb.tcp.port"
        )) {
            val out = ShellUtils.execute("getprop " + prop, 500).output.trim()
            if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                return out.filter { it.isDigit() }.take(5)
            }
        }
        return ""
    }

    /** Best-effort wireless ADB state detection.
     *  Primary: check getprop (fast, non-root), then settings global, then dumpsys */
    fun getCurrentState(context: Context): Boolean {
        // 1. Fastest: check service props first
        val port = getCurrentPortNonRoot()
        if (port.isNotEmpty()) {
            Log.d(TAG, "Active ADB port via non-root getprop: " + port)
            return true
        }

        // 2. Check settings global via root (authoritative on API 36)
        if (ShellUtils.hasRoot()) {
            val r = ShellUtils.executeSu("settings get global adb_wifi_enabled 2>/dev/null", 1000)
            if (r.isSuccess()) {
                val v = r.output.trim()
                if (v == "1") {
                    Log.d(TAG, "adb_wifi_enabled=1 via root")
                    return true
                }
                if (v == "0") {
                    Log.d(TAG, "adb_wifi_enabled=0 via root")
                    return false
                }
            }
            // Also check getprop via root
            for (prop in arrayOf("service.adb.tls.port", "service.adb.tcp.port")) {
                val rp = ShellUtils.executeSu("getprop " + prop, 500)
                if (rp.isSuccess()) {
                    val out = rp.output.trim()
                    if (out.isNotEmpty() && out != "0") {
                        Log.d(TAG, "Active port via su getprop: " + out)
                        return true
                    }
                }
            }
        }

        // 3. Try non-root via content resolver
        try {
            if (context != null) {
                val v = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
                if (v == 1) {
                    Log.d(TAG, "adb_wifi_enabled=1 via ContentResolver")
                    return true
                }
            }
        } catch (_: Exception) { }

        // 4. Last resort: dumpsys adb (slow)
        if (ShellUtils.hasRoot()) {
            val d = ShellUtils.executeSu("dumpsys adb 2>/dev/null", 2000)
            if (d.isSuccess()) {
                val output = d.output.lowercase()
                if (output.contains("adb wifi enabled") || output.contains("wireless debugging enabled")) {
                    Log.d(TAG, "dumpsys adb reports wireless enabled")
                    return true
                }
            }
        }
        return false
    }

    fun enableWirelessAdb(): Boolean {
        Log.d(TAG, "enableWirelessAdb")
        // On modern Android (14+, Rust adbd) the authoritative way is
        // settings put global.  NEVER restart adbd — that kills the
        // wireless connection we're running over.
        ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1500)
        // Also set service props as hints for the next adbd life-cycle.
        ShellUtils.executeSu("setprop service.adb.tls.port 5555", 500)
        ShellUtils.executeSu("setprop service.adb.tcp.port 5555", 500)
        return true
    }

    fun disableWirelessAdb(): Boolean {
        Log.d(TAG, "disableWirelessAdb")
        ShellUtils.executeSu("settings put global adb_wifi_enabled 0", 1500)
        ShellUtils.executeSu("setprop service.adb.tls.port 0", 500)
        ShellUtils.executeSu("setprop service.adb.tcp.port 0", 500)
        return true
    }

    fun setFixedPort(port: Int): Boolean {
        ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1000)
        ShellUtils.executeSu("setprop service.adb.tcp.port " + port, 500)
        val r = ShellUtils.executeSu(
            "setprop service.adb.tls.port " + port, 2000)
        Log.d(TAG, "setFixedPort(" + port + "): " + r.output.trim())
        return r.isSuccess()
    }

    fun setPairingCode(code: String): Boolean {
        if (code.length !in 6..8 || !code.all { it.isDigit() }) return false
        writePairingCodeFile(code)
        Log.d(TAG, "Pairing code stored: " + code)
        return true
    }

    private fun writePairingCodeFile(code: String) {
        // Remove old file first to avoid readPairingCode() picking up a stale value
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 500)
        ShellUtils.executeSu(
            "echo '" + code + "' > /data/local/tmp/adb_x_pairing_code && chmod 644 /data/local/tmp/adb_x_pairing_code",
            2000)
    }

    fun readPairingCode(): String {
        // 1. Direct file read
        try {
            val file = File("/data/local/tmp/adb_x_pairing_code")
            if (file.exists()) {
                val text = file.readText().trim()
                if (text.matches(Regex("\\d{6,8}"))) return text
            }
        } catch (_: Exception) { }
        // 2. Via su
        val r = ShellUtils.executeSu("cat /data/local/tmp/adb_x_pairing_code 2>/dev/null", 1500)
        if (r.isSuccess()) {
            val out = r.output.trim()
            if (out.matches(Regex("\\d{6,8}"))) return out
        }
        // 3. Parse dumpsys adb for pairing code
        val d = ShellUtils.executeSu("dumpsys adb 2>/dev/null", 2000)
        if (d.isSuccess()) {
            for (line in d.output.lines()) {
                val trimmed = line.trim()
                val m1 = Regex("[Pp]airing\\s*[Cc]ode[:\\s]*(\\d{6,8})").find(trimmed)
                if (m1 != null) return m1.groupValues[1]
                val m2 = Regex("code[=:]\\s*(\\d{6,8})").find(trimmed)
                if (m2 != null) return m2.groupValues[1]
                // Rust adbd output patterns
                val m3 = Regex("[Pp]airing\\s*code\\s*is\\s*(\\d{6,8})").find(trimmed)
                if (m3 != null) return m3.groupValues[1]
                val m4 = Regex("code:\\s*(\\d{6,8})").find(trimmed)
                if (m4 != null) return m4.groupValues[1]
            }
        }
        return ""
    }

    fun getPairingPort(): String {
        for (prop in arrayOf("service.adb.tls.port", "service.adb.tcp.port")) {
            val out = ShellUtils.execute("getprop " + prop, 500).output.trim()
            if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                return out
            }
        }
        if (ShellUtils.hasRoot()) {
            for (prop in arrayOf("service.adb.tls.port", "service.adb.tcp.port")) {
                val r = ShellUtils.executeSu("getprop " + prop, 500)
                if (r.isSuccess()) {
                    val out = r.output.trim()
                    if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                        return out
                    }
                }
            }
        }
        return ""
    }

    fun clearPairingCode() {
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 1000)
    }

    data class AdbStatus(
        val enabled: Boolean,
        val port: String,
        val pairingPort: String,
        val pairingCode: String,
        val hasRoot: Boolean
    )

    suspend fun getFullStatus(context: Context): AdbStatus = withContext(Dispatchers.IO) {
        val hasRoot = ShellUtils.hasRoot()
        val enabled = getCurrentState(context)
        val port = getCurrentPort(context)
        val pairingPort = getPairingPort()
        val pairingCode = readPairingCode()
        AdbStatus(enabled, port, pairingPort, pairingCode, hasRoot)
    }
}
