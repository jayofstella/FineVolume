package com.finevolume

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

class AvrcpUserService(private val context: Context) : Binder() {
    companion object {
        const val TX_STATUS = FIRST_CALL_TRANSACTION + 1
        const val TX_RUN_ALL = FIRST_CALL_TRANSACTION + 2
    }

    @Volatile private var stateMessage = "v0.7.0 UserService ready; no probe has run"
    @Volatile private var lastReport = "FineVolume v0.7.0 UserService ready"

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TX_STATUS -> {
                reply?.writeNoException()
                reply?.writeString(buildQuickStatus())
                true
            }
            TX_RUN_ALL -> {
                val result = runCompleteProbe()
                reply?.writeNoException()
                reply?.writeString(result)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun getService(name: String): IBinder? = try {
        val clazz = Class.forName("android.os.ServiceManager")
        val method = clazz.getDeclaredMethod("getService", String::class.java)
        method.isAccessible = true
        method.invoke(null, name) as? IBinder
    } catch (_: Throwable) { null }

    private fun binderInfo(name: String): String {
        val b = getService(name) ?: return "$name=NOT FOUND"
        val desc = try { b.interfaceDescriptor ?: "null" } catch (e: Throwable) { "ERROR:${e.message}" }
        return "$name=FOUND alive=${b.isBinderAlive} descriptor=$desc"
    }

    private fun permissionResult(permission: String): String = try {
        val value = context.checkPermission(permission, Process.myPid(), Process.myUid())
        "$permission=${if (value == 0) "GRANTED" else "DENIED($value)"}"
    } catch (e: Throwable) {
        "$permission=ERROR ${e.javaClass.simpleName}:${e.message}"
    }

    private fun runShell(command: String, timeoutSec: Long = 5, maxChars: Int = 18000): String {
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return "TIMEOUT after ${timeoutSec}s: $command"
            }
            val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            "exit=${p.exitValue()}\n" + if (text.length > maxChars) text.take(maxChars) + "\n...[TRUNCATED]" else text
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.name}: ${e.message}"
        }
    }

    private fun reflectClass(className: String, nameFilter: Regex? = null): String {
        return try {
            val c = Class.forName(className)
            val methods = c.declaredMethods
                .filter { m -> nameFilter == null || nameFilter.containsMatchIn(m.name) }
                .sortedWith(compareBy({ it.name }, { it.parameterCount }))
                .take(120)
                .joinToString("\n") { m ->
                    val mods = Modifier.toString(m.modifiers)
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    "$mods ${m.returnType.simpleName} ${m.name}($params)"
                }
            val txFields = c.declaredFields
                .filter { it.name.startsWith("TRANSACTION_") }
                .take(160)
                .mapNotNull { f ->
                    try {
                        f.isAccessible = true
                        "${f.name}=${f.getInt(null)}"
                    } catch (_: Throwable) { null }
                }
                .joinToString("\n")
            "CLASS FOUND: $className\nMETHODS:\n${if (methods.isBlank()) "(none matched)" else methods}\nTRANSACTION FIELDS:\n${if (txFields.isBlank()) "(none visible)" else txFields}"
        } catch (e: Throwable) {
            "CLASS UNAVAILABLE: $className -> ${e.javaClass.name}: ${e.message}"
        }
    }

    private fun probeServiceManagerNames(): String = runShell(
        "service list | grep -i -E 'audio|media|bluetooth|vivo' | head -n 220",
        5,
        16000
    )

    private fun probeAudioDumps(): String {
        val audio = runShell(
            "dumpsys audio | grep -i -E 'STREAM_MUSIC|bt_a2dp|absolute|safe|volume|prescale|device' | head -n 260",
            6,
            20000
        )
        val policy = runShell(
            "dumpsys media.audio_policy | grep -i -E 'a2dp|bluetooth|volume|curve|gain|device' | head -n 260",
            6,
            20000
        )
        val vivo = runShell(
            "dumpsys vivoaudiopolicy 2>&1 | head -n 260",
            6,
            20000
        )
        return "--- dumpsys audio ---\n$audio\n\n--- dumpsys media.audio_policy ---\n$policy\n\n--- dumpsys vivoaudiopolicy ---\n$vivo"
    }

    private fun probeShellAudioTools(): String {
        val commands = listOf(
            "cmd audio help",
            "cmd media_session help",
            "cmd media_session volume --stream 3 --get",
            "media volume --stream 3 --get",
            "settings get global bluetooth_disabled_profiles",
            "settings get global bluetooth_on",
            "settings list global | grep -i -E 'absolute|bluetooth|volume|audio' | head -n 160",
            "getprop | grep -i -E 'audio|a2dp|avrcp|bluetooth' | head -n 220"
        )
        return commands.joinToString("\n\n") { cmd -> "### $cmd\n${runShell(cmd, 5, 10000)}" }
    }

    private fun probeFrameworkClasses(): String {
        val filter = Regex("volume|gain|device|a2dp|bluetooth|stream|absolute|parameter|policy", RegexOption.IGNORE_CASE)
        return listOf(
            "android.media.IAudioService\$Stub",
            "android.media.IAudioService",
            "android.media.IAudioPolicyService\$Stub",
            "android.media.IAudioPolicyService",
            "android.media.IAudioFlingerService\$Stub",
            "android.media.IAudioFlingerService",
            "com.vivo.vivoaudiopolicy.IVivoAudioPolicyService\$Stub",
            "com.vivo.vivoaudiopolicy.IVivoAudioPolicyService"
        ).joinToString("\n\n") { reflectClass(it, filter) }
    }

    private fun runCompleteProbe(): String {
        stateMessage = "v0.7.0 probe running"
        val sb = StringBuilder()
        fun section(title: String) { sb.append("\n===== ").append(title).append(" =====\n") }

        sb.append("FineVolume-TestReport v0.7.0\n")
        sb.append("timestampMs=${System.currentTimeMillis()}\n")
        sb.append("uid=${Process.myUid()} pid=${Process.myPid()}\n")
        sb.append("selinux=").append(try { java.io.File("/proc/self/attr/current").readText().trim() } catch (e: Throwable) { "ERROR:${e.message}" }).append('\n')

        section("IDENTITY")
        sb.append(runShell("id", 2, 3000)).append('\n')

        section("REMOTE PERMISSIONS")
        listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_PRIVILEGED",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.DUMP",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.CAPTURE_AUDIO_OUTPUT"
        ).forEach { sb.append(permissionResult(it)).append('\n') }

        section("SYSTEM BINDERS")
        listOf(
            "audio",
            "media.audio_flinger",
            "media.audio_policy",
            "vivoaudiopolicy",
            "bluetooth_manager",
            "bluetooth",
            "bluetooth_a2dp",
            "bluetooth_avrcp"
        ).forEach { sb.append(binderInfo(it)).append('\n') }

        section("SERVICE MANAGER AUDIO/VIVO NAMES")
        sb.append(probeServiceManagerNames()).append('\n')

        section("FRAMEWORK / VIVO AIDL SURFACE")
        sb.append(probeFrameworkClasses()).append('\n')

        section("SHELL AUDIO TOOLS")
        sb.append(probeShellAudioTools()).append('\n')

        section("AUDIO / POLICY DUMPS")
        sb.append(probeAudioDumps()).append('\n')

        section("NON-DESTRUCTIVE CONCLUSION INPUT")
        sb.append("No volume-changing Binder transaction was sent in v0.7.0.\n")
        sb.append("No AudioPolicy or vivoaudiopolicy method was invoked; this report only maps callable surfaces.\n")
        sb.append("Goal: choose one concrete control path for the next build without repeating earlier Shizuku/A2DP diagnostics.\n")

        section("SUMMARY")
        sb.append("UserServiceAlive=true\n")
        sb.append("AudioServiceFound=${getService("audio") != null}\n")
        sb.append("AudioPolicyFound=${getService("media.audio_policy") != null}\n")
        sb.append("VivoAudioPolicyFound=${getService("vivoaudiopolicy") != null}\n")
        sb.append("BluetoothManagerFound=${getService("bluetooth_manager") != null}\n")
        sb.append("NEXT: send this single v0.7.0 report back for analysis.\n")

        lastReport = sb.toString()
        stateMessage = "v0.7.0 probe complete"
        return lastReport
    }

    private fun buildQuickStatus(): String = "FineVolume v0.7.0\n" +
        "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
        "state=$stateMessage\n" +
        "audio=${if (getService("audio") != null) "FOUND" else "NOT FOUND"}\n" +
        "media.audio_policy=${if (getService("media.audio_policy") != null) "FOUND" else "NOT FOUND"}\n" +
        "vivoaudiopolicy=${if (getService("vivoaudiopolicy") != null) "FOUND" else "NOT FOUND"}\n" +
        "lastReportChars=${lastReport.length}"

    @Suppress("unused")
    fun destroy() { System.exit(0) }
}
