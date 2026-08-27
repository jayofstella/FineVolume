package com.finevolume

import android.content.AttributionSource
import android.content.ComponentName
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
        const val TX_SET_VOLUME = FIRST_CALL_TRANSACTION + 3

        private const val PROFILE_A2DP = 2
        private const val MANAGER_DESC = "android.bluetooth.IBluetoothManager"
        private const val PROFILE_DESC = "android.bluetooth.IBluetoothProfileServiceConnection"
        private const val A2DP_DESC = "android.bluetooth.IBluetoothA2dp"
        private const val A2DP_SERVICE_NAME = "android.bluetooth.IBluetoothA2dp"

        // AOSP Android 15 IBluetoothManager ordering.
        private const val MANAGER_TX_GET_STATE = IBinder.FIRST_CALL_TRANSACTION + 7 // 8
        private const val MANAGER_TX_BIND_PROFILE = IBinder.FIRST_CALL_TRANSACTION + 9 // 10

        // AOSP modular Bluetooth stack ordering.
        private const val A2DP_TX_SET_AVRCP_ABS_VOLUME = IBinder.FIRST_CALL_TRANSACTION + 15 // 16
    }

    @Volatile private var a2dpBinder: IBinder? = null
    @Volatile private var stateMessage = "UserService created; no Bluetooth transaction has run yet"
    @Volatile private var lastReport = "FineVolume v0.6.0 UserService ready"
    @Volatile private var bindResult = "not attempted"

    // IMPORTANT: constructor/init intentionally performs NO Bluetooth Binder transaction.
    // Previous v0.5.2 could die before ServiceConnection callback if the vendor Binder layout differed.

    private val profileCallback = object : Binder() {
        init { attachInterface(null, PROFILE_DESC) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(PROFILE_DESC)
                    true
                }
                FIRST_CALL_TRANSACTION -> {
                    try {
                        data.enforceInterface(PROFILE_DESC)
                        val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                        val service = try { data.readStrongBinder() } catch (_: Throwable) { null }
                        a2dpBinder = service
                        stateMessage = if (service != null) {
                            "A2DP callback READY: ${component?.flattenToShortString() ?: "unknown"}"
                        } else {
                            "A2DP callback arrived but binder=null"
                        }
                    } catch (e: Throwable) {
                        stateMessage = "A2DP callback parse failed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                    true
                }
                FIRST_CALL_TRANSACTION + 1 -> {
                    try {
                        data.enforceInterface(PROFILE_DESC)
                        val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                        a2dpBinder = null
                        stateMessage = "A2DP disconnected: ${component?.flattenToShortString() ?: "unknown"}"
                    } catch (_: Throwable) {
                        a2dpBinder = null
                    }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TX_STATUS -> {
                reply?.writeNoException()
                reply?.writeString(buildQuickStatus())
                true
            }
            TX_RUN_ALL -> {
                val doAudibleTest = try { data.readInt() != 0 } catch (_: Throwable) { false }
                val result = runCompleteTest(doAudibleTest)
                reply?.writeNoException()
                reply?.writeString(result)
                true
            }
            TX_SET_VOLUME -> {
                val value = data.readInt().coerceIn(0, 127)
                val result = sendVolumeIfReady(value)
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

    private fun permissionResult(permission: String): String {
        return try {
            val value = context.checkPermission(permission, Process.myPid(), Process.myUid())
            "$permission=${if (value == 0) "GRANTED" else "DENIED($value)"}"
        } catch (e: Throwable) {
            "$permission=ERROR ${e.javaClass.simpleName}:${e.message}"
        }
    }

    private fun readManagerState(manager: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MANAGER_DESC)
            val ok = manager.transact(MANAGER_TX_GET_STATE, data, reply, 0)
            if (!ok) return "FAILED transact=false tx=$MANAGER_TX_GET_STATE"
            reply.readException()
            "SUCCESS state=${reply.readInt()} tx=$MANAGER_TX_GET_STATE"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "FAILED ${root.javaClass.name}: ${root.message} tx=$MANAGER_TX_GET_STATE"
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun bindA2dpAndroid15(): String {
        a2dpBinder = null
        val manager = getService("bluetooth_manager") ?: return "FAILED bluetooth_manager not found"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            // Android 15: bindBluetoothProfileService(int profile, String serviceName,
            // IBluetoothProfileServiceConnection proxy)
            data.writeInterfaceToken(MANAGER_DESC)
            data.writeInt(PROFILE_A2DP)
            data.writeString(A2DP_SERVICE_NAME)
            data.writeStrongBinder(profileCallback)
            val ok = manager.transact(MANAGER_TX_BIND_PROFILE, data, reply, 0)
            if (!ok) return "FAILED transact=false tx=$MANAGER_TX_BIND_PROFILE"
            reply.readException()
            val accepted = try { reply.readBoolean() } catch (_: Throwable) {
                try { reply.readInt() != 0 } catch (_: Throwable) { true }
            }
            bindResult = "accepted=$accepted tx=$MANAGER_TX_BIND_PROFILE layout=3-arg Android15"
            if (!accepted) "FAILED manager rejected bind; $bindResult" else "SUCCESS $bindResult"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            bindResult = "FAILED ${root.javaClass.name}: ${root.message}"
            bindResult
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun waitForA2dp(timeoutMs: Long): IBinder? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            a2dpBinder?.let { return it }
            Thread.sleep(100)
        }
        return a2dpBinder
    }

    private fun makeShellAttribution(): AttributionSource = AttributionSource.Builder(Process.myUid())
        .setPid(Process.myPid())
        .setPackageName("com.android.shell")
        .build()

    private fun sendRawAvrcpVolume(binder: IBinder, value: Int): String {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(A2DP_DESC)
            data.writeInt(value.coerceIn(0, 127))
            data.writeTypedObject(makeShellAttribution(), 0)
            val ok = binder.transact(A2DP_TX_SET_AVRCP_ABS_VOLUME, data, null, IBinder.FLAG_ONEWAY)
            if (ok) "SUCCESS tx=$A2DP_TX_SET_AVRCP_ABS_VOLUME value=$value/127" else "FAILED transact=false tx=$A2DP_TX_SET_AVRCP_ABS_VOLUME"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "FAILED ${root.javaClass.name}: ${root.message} tx=$A2DP_TX_SET_AVRCP_ABS_VOLUME"
        } finally {
            data.recycle()
        }
    }

    private fun runShell(command: String, timeoutSec: Long = 4, maxChars: Int = 12000): String {
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

    private fun volumeEvidence(): String {
        val bt = runShell("dumpsys bluetooth_manager | grep -i -E 'avrcp|absolute|volume|a2dp' | head -n 120", 5, 9000)
        val audio = runShell("dumpsys audio | grep -i -E 'bluetooth|a2dp|volume|stream_music' | head -n 160", 5, 9000)
        return "--- bluetooth_manager volume evidence ---\n$bt\n--- audio volume evidence ---\n$audio"
    }

    private fun runCompleteTest(doAudibleTest: Boolean): String {
        val sb = StringBuilder()
        fun section(title: String) { sb.append("\n===== ").append(title).append(" =====\n") }

        sb.append("FineVolume-TestReport v0.6.0\n")
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
            "android.permission.WRITE_SECURE_SETTINGS"
        ).forEach { sb.append(permissionResult(it)).append('\n') }

        section("SYSTEM BINDERS")
        listOf("bluetooth_manager", "bluetooth", "bluetooth_a2dp", "bluetooth_avrcp", "audio", "media.audio_flinger", "media.audio_policy", "vivoaudiopolicy")
            .forEach { sb.append(binderInfo(it)).append('\n') }

        section("BLUETOOTH MANAGER")
        val manager = getService("bluetooth_manager")
        if (manager == null) {
            sb.append("FATAL: bluetooth_manager unavailable\n")
            lastReport = sb.toString(); stateMessage = "AUTO TEST STOPPED: bluetooth_manager unavailable"
            return lastReport
        }
        sb.append("getState: ").append(readManagerState(manager)).append('\n')

        section("A2DP PROFILE BIND")
        val bind = bindA2dpAndroid15()
        sb.append(bind).append('\n')
        val a2dp = waitForA2dp(5000)
        sb.append("callbackState=").append(stateMessage).append('\n')
        sb.append("A2DP binder=").append(if (a2dp != null) "READY" else "NULL").append('\n')
        if (a2dp != null) {
            sb.append("A2DP alive=").append(a2dp.isBinderAlive).append('\n')
            sb.append("A2DP descriptor=").append(try { a2dp.interfaceDescriptor } catch (e: Throwable) { "ERROR:${e.message}" }).append('\n')
        }

        section("SHELL CAPABILITIES")
        sb.append("cmd bluetooth_manager help:\n").append(runShell("cmd bluetooth_manager help", 4, 5000)).append('\n')
        sb.append("cmd media_session help:\n").append(runShell("cmd media_session help", 4, 5000)).append('\n')

        section("BEFORE VOLUME EVIDENCE")
        sb.append(volumeEvidence()).append('\n')

        if (a2dp != null) {
            section("AVRCP TRANSACTION DRY RUN")
            sb.append("A2DP tx target=$A2DP_TX_SET_AVRCP_ABS_VOLUME descriptor=$A2DP_DESC\n")
            sb.append("No volume change in dry-run stage.\n")

            if (doAudibleTest) {
                section("SAFE A/B VOLUME TEST")
                sb.append("send60=").append(sendRawAvrcpVolume(a2dp, 60)).append('\n')
                Thread.sleep(800)
                sb.append("evidence60:\n").append(volumeEvidence()).append('\n')
                sb.append("send40=").append(sendRawAvrcpVolume(a2dp, 40)).append('\n')
                Thread.sleep(1000)
                sb.append("evidence40:\n").append(volumeEvidence()).append('\n')
                sb.append("restore60=").append(sendRawAvrcpVolume(a2dp, 60)).append('\n')
                Thread.sleep(500)
                sb.append("evidenceRestore:\n").append(volumeEvidence()).append('\n')
            } else {
                sb.append("Audible A/B test skipped by caller.\n")
            }
        } else {
            section("AVRCP TEST")
            sb.append("SKIPPED because A2DP binder is NULL. No volume command sent.\n")
        }

        section("LOGCAT SNAPSHOT")
        sb.append(runShell("logcat -d -t 250 | grep -i -E 'bluetooth|a2dp|avrcp|finevolume|shizuku' | tail -n 180", 5, 12000)).append('\n')

        section("SUMMARY")
        sb.append("UserServiceAlive=true\n")
        sb.append("BluetoothManagerFound=true\n")
        sb.append("A2dpBinderReady=").append(a2dp != null).append('\n')
        sb.append("AudibleTestRequested=").append(doAudibleTest).append('\n')
        sb.append("NEXT: send this single report back for analysis.\n")

        lastReport = sb.toString()
        stateMessage = if (a2dp != null) "AUTO TEST COMPLETE: A2DP READY" else "AUTO TEST COMPLETE: A2DP NULL"
        return lastReport
    }

    private fun sendVolumeIfReady(value: Int): String {
        val b = a2dpBinder ?: return "FAILED: A2DP binder not ready. Run the complete test first."
        return sendRawAvrcpVolume(b, value)
    }

    private fun buildQuickStatus(): String = "FineVolume v0.6.0\n" +
        "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
        "state=$stateMessage\n" +
        "bindResult=$bindResult\n" +
        "A2DP binder=${if (a2dpBinder != null) "READY" else "NULL"}\n" +
        "lastReportChars=${lastReport.length}"

    @Suppress("unused")
    fun destroy() { System.exit(0) }
}
