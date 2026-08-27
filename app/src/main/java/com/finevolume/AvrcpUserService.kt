package com.finevolume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Binder
import android.os.Parcel

class AvrcpUserService(private val context: Context) : Binder() {
    companion object {
        const val TX_STATUS = FIRST_CALL_TRANSACTION + 1
        const val TX_SET_VOLUME = FIRST_CALL_TRANSACTION + 2
    }

    @Volatile
    private var a2dpProxy: BluetoothProfile? = null

    @Volatile
    private var stateMessage: String = "initializing"

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy
                stateMessage = "A2DP proxy ready"
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                stateMessage = "A2DP proxy disconnected"
            }
        }
    }

    init {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                stateMessage = "BluetoothAdapter=null"
            } else {
                val requested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
                stateMessage = if (requested) "A2DP proxy requested; waiting callback" else "getProfileProxy returned false"
            }
        } catch (e: Throwable) {
            stateMessage = "init ERROR ${e.javaClass.name}: ${e.message}"
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel, flags: Int): Boolean {
        return when (code) {
            TX_STATUS -> {
                reply.writeNoException()
                reply.writeString(buildStatus())
                true
            }
            TX_SET_VOLUME -> {
                val value = data.readInt().coerceIn(0, 127)
                val result = setAbsoluteVolume(value)
                reply.writeNoException()
                reply.writeString(result)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun buildStatus(): String {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null }
        val proxy = a2dpProxy
        val devices = try {
            proxy?.connectedDevices?.joinToString { d ->
                try { d.name ?: d.address } catch (_: Throwable) { d.address }
            } ?: "(proxy not ready)"
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
        val methodState = try {
            proxy?.javaClass?.getDeclaredMethod("setAvrcpAbsoluteVolume", Int::class.javaPrimitiveType)
            "AVAILABLE"
        } catch (e: Throwable) {
            "NOT FOUND (${e.javaClass.simpleName})"
        }
        return "UserService uid=${android.os.Process.myUid()}\n" +
            "Bluetooth enabled=${adapter?.isEnabled}\n" +
            "state=$stateMessage\n" +
            "A2DP proxy=${proxy?.javaClass?.name ?: "null"}\n" +
            "connectedDevices=$devices\n" +
            "hidden setAvrcpAbsoluteVolume=$methodState"
    }

    private fun setAbsoluteVolume(value: Int): String {
        val proxy = a2dpProxy ?: return "FAILED: A2DP proxy not ready\n${buildStatus()}"
        return try {
            val method = proxy.javaClass.getDeclaredMethod("setAvrcpAbsoluteVolume", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(proxy, value)
            stateMessage = "setAvrcpAbsoluteVolume($value) invoked"
            "SUCCESS: setAvrcpAbsoluteVolume($value) invoked as uid=${android.os.Process.myUid()}\n${buildStatus()}"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            stateMessage = "set volume failed: ${root.javaClass.simpleName}: ${root.message}"
            "FAILED: ${root.javaClass.name}: ${root.message}\n${buildStatus()}"
        }
    }

    @Suppress("unused")
    fun destroy() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val proxy = a2dpProxy
            if (adapter != null && proxy != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        } catch (_: Throwable) {
        }
        a2dpProxy = null
        System.exit(0)
    }
}
