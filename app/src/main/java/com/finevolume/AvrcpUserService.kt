package com.finevolume

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

class AvrcpUserService(@Suppress("UNUSED_PARAMETER") private val context: Context) : Binder() {
    companion object {
        const val TX_PROBE = FIRST_CALL_TRANSACTION + 1
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TX_PROBE -> {
                reply?.writeNoException()
                reply?.writeString(probeBluetoothServices())
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun getService(name: String): IBinder? {
        return try {
            val clazz = Class.forName("android.os.ServiceManager")
            val method = clazz.getDeclaredMethod("getService", String::class.java)
            method.isAccessible = true
            method.invoke(null, name) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    private fun listServices(): List<String> {
        return try {
            val clazz = Class.forName("android.os.ServiceManager")
            val method = clazz.getDeclaredMethod("listServices")
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null) as? Array<String>)?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun binderInfo(name: String): String {
        val binder = getService(name)
            ?: return "$name: NOT FOUND"
        val descriptor = try {
            binder.interfaceDescriptor
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
        val alive = try { binder.pingBinder() } catch (_: Throwable) { false }
        return "$name: FOUND\n  binder=${binder.javaClass.name}\n  alive=$alive\n  descriptor=$descriptor"
    }

    private fun probeBluetoothServices(): String {
        val sb = StringBuilder()
        sb.append("===== FineVolume v0.4.2 Bluetooth Binder Probe =====\n")
        sb.append("uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}\n")
        sb.append("SELinux context: ")
        sb.append(readProcAttrCurrent()).append("\n\n")

        val names = listOf(
            "bluetooth_manager",
            "bluetooth",
            "bluetooth_a2dp",
            "bluetooth_avrcp",
            "audio",
            "media.audio_flinger",
            "media.audio_policy"
        )
        for (name in names) {
            sb.append(binderInfo(name)).append("\n\n")
        }

        val all = listServices()
        sb.append("===== ServiceManager names containing bluetooth / audio =====\n")
        if (all.isEmpty()) {
            sb.append("listServices unavailable or returned empty\n")
        } else {
            all.filter {
                it.contains("bluetooth", ignoreCase = true) ||
                    it.contains("audio", ignoreCase = true)
            }.sorted().forEach { sb.append(it).append('\n') }
        }

        sb.append("\nInterpretation:\n")
        sb.append("• bluetooth_manager FOUND + descriptor readable: next step can call manager Binder directly.\n")
        sb.append("• bluetooth FOUND: direct adapter Binder is exposed to this shell process.\n")
        sb.append("• bluetooth_a2dp / bluetooth_avrcp FOUND: profile service can potentially be called without BluetoothAdapter.\n")
        sb.append("• This build does not send any volume command.\n")
        return sb.toString()
    }

    private fun readProcAttrCurrent(): String {
        return try {
            java.io.File("/proc/self/attr/current").readText().trim()
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Suppress("unused")
    fun destroy() {
        System.exit(0)
    }
}
