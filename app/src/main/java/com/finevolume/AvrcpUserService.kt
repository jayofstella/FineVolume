package com.finevolume

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AvrcpUserService(private val context: Context) : Binder() {
    companion object {
        const val TX_STATUS = FIRST_CALL_TRANSACTION + 1
        const val TX_RUN_ALL = FIRST_CALL_TRANSACTION + 2
    }

    @Volatile private var stateMessage = "v0.8.0 UserService ready"

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        TX_STATUS -> { reply?.writeNoException(); reply?.writeString(buildQuickStatus()); true }
        TX_RUN_ALL -> { val r = runControlledExperiment(); reply?.writeNoException(); reply?.writeString(r); true }
        else -> super.onTransact(code, data, reply, flags)
    }

    private fun getService(name: String): IBinder? = try {
        val c = Class.forName("android.os.ServiceManager")
        val m = c.getDeclaredMethod("getService", String::class.java)
        m.isAccessible = true
        m.invoke(null, name) as? IBinder
    } catch (_: Throwable) { null }

    private fun runShell(command: String, timeoutSec: Long = 5, maxChars: Int = 12000): String = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); "TIMEOUT: $command" } else {
            val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            "exit=${p.exitValue()}\n" + if (text.length > maxChars) text.take(maxChars) + "\n...[TRUNCATED]" else text
        }
    } catch (e: Throwable) { "ERROR ${e.javaClass.name}: ${e.message}" }

    private fun invokeAudio(method: String, vararg args: Any?): Any? {
        val binder = getService("audio") ?: throw IllegalStateException("audio binder missing")
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.isAccessible = true
        val service = asInterface.invoke(null, binder)
        val candidates = service.javaClass.methods.filter { it.name == method && it.parameterCount == args.size }
        var last: Throwable? = null
        for (m in candidates) {
            try { m.isAccessible = true; return m.invoke(service, *args) }
            catch (e: Throwable) { last = e }
        }
        throw IllegalStateException("$method/${args.size} unavailable", last)
    }

    private fun currentMusicIndex(): Int = (invokeAudio("getStreamVolume", 3) as Number).toInt()
    private fun minMusicIndex(): Int = (invokeAudio("getStreamMinVolume", 3) as Number).toInt()
    private fun maxMusicIndex(): Int = (invokeAudio("getStreamMaxVolume", 3) as Number).toInt()

    private fun setMusicIndex(index: Int) {
        // IAudioService.setStreamVolume(streamType,index,flags,callingPackage)
        invokeAudio("setStreamVolume", 3, index, 0, "com.finevolume")
    }

    private fun audioSnapshot(): String = runShell(
        "dumpsys audio | grep -i -E 'STREAM_MUSIC|bt_a2dp|absolute volume devices|pre-scale for bluetooth|streamVolume' | head -n 100",
        5, 9000
    )

    private fun policySnapshot(): String = runShell(
        "dumpsys media.audio_policy | grep -i -E 'AUDIO_DEVICE_OUT_BLUETOOTH_A2DP|Volume:|volume index|curve' | head -n 140",
        5, 9000
    )

    private fun runControlledExperiment(): String {
        stateMessage = "v0.8.0 controlled experiment running"
        val sb = StringBuilder()
        fun sec(s: String) { sb.append("\n===== ").append(s).append(" =====\n") }
        sb.append("FineVolume-TestReport v0.8.0\n")
        sb.append("timestampMs=${System.currentTimeMillis()} uid=${Process.myUid()} pid=${Process.myPid()}\n")
        sb.append("Purpose: controlled AudioService/AudioPolicy A2DP volume experiment\n")

        sec("PRECHECK")
        sb.append(runShell("id", 2, 2000)).append('\n')
        sb.append("audioBinder=${getService("audio")?.interfaceDescriptor ?: "NULL"}\n")
        sb.append("policyBinder=${getService("media.audio_policy")?.interfaceDescriptor ?: "NULL"}\n")
        val original = try { currentMusicIndex() } catch (e: Throwable) {
            sb.append("FATAL getStreamVolume: ${e.javaClass.name}:${e.message}\n")
            stateMessage = "v0.8.0 failed before experiment"
            return sb.toString()
        }
        val min = try { minMusicIndex() } catch (_: Throwable) { 0 }
        val max = try { maxMusicIndex() } catch (_: Throwable) { 15 }
        sb.append("STREAM_MUSIC original=$original range=[$min..$max]\n")
        sb.append("shellRead=\n${runShell("cmd media_session volume --stream 3 --get", 3, 2000)}\n")

        sec("BASELINE")
        sb.append(audioSnapshot()).append('\n')
        sb.append(policySnapshot()).append('\n')

        // Safe, audible experiment: use the current index and one neighbouring index only.
        // Never force minimum volume and always restore the exact original index.
        val alternate = when {
            original > min + 1 -> original - 1
            original < max -> original + 1
            else -> original
        }
        sec("CONTROLLED INDEX A/B")
        sb.append("Plan: original=$original -> alternate=$alternate -> original=$original\n")
        if (alternate == original) {
            sb.append("SKIPPED: no safe neighbouring index available\n")
        } else {
            try {
                setMusicIndex(alternate)
                Thread.sleep(900)
                sb.append("afterSetAlternate readback=${currentMusicIndex()}\n")
                sb.append(audioSnapshot()).append('\n')
                sb.append(policySnapshot()).append('\n')
            } catch (e: Throwable) {
                sb.append("alternate ERROR ${e.javaClass.name}:${e.message}\n")
            } finally {
                try {
                    setMusicIndex(original)
                    Thread.sleep(500)
                    sb.append("restore readback=${currentMusicIndex()} expected=$original\n")
                } catch (e: Throwable) {
                    sb.append("RESTORE ERROR ${e.javaClass.name}:${e.message}\n")
                }
            }
        }

        sec("FINE-GRAINED CAPABILITY CHECK")
        sb.append("IAudioService exposes integer STREAM_MUSIC indices; framework range observed=[$min..$max].\n")
        sb.append("This build intentionally does NOT guess undocumented fractional indices or raw AudioPolicy parcels.\n")
        sb.append("We compare policy dB snapshots before/after a known-safe integer change to determine whether the platform exposes another controllable layer.\n")

        sec("FINAL")
        sb.append("finalMusicIndex=").append(try { currentMusicIndex() } catch (_: Throwable) { -1 }).append(" original=$original\n")
        sb.append("Final audio snapshot:\n").append(audioSnapshot()).append('\n')
        stateMessage = "v0.8.0 experiment complete; original volume restored"
        return sb.toString()
    }

    private fun buildQuickStatus(): String = "FineVolume v0.8.0\nuid=${Process.myUid()} pid=${Process.myPid()}\nstate=$stateMessage\naudio=${if (getService("audio") != null) "FOUND" else "NOT FOUND"}\nmedia.audio_policy=${if (getService("media.audio_policy") != null) "FOUND" else "NOT FOUND"}"

    @Suppress("unused") fun destroy() { System.exit(0) }
}
