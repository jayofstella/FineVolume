package com.finevolume

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

class AvrcpUserService(@Suppress("UNUSED_PARAMETER") private val context: Context) : Binder() {
    companion object {
        const val TX_STATUS = FIRST_CALL_TRANSACTION + 1
        const val TX_SET_VOLUME = FIRST_CALL_TRANSACTION + 2

        private const val PROFILE_A2DP = 2
        private const val MANAGER_DESC = "android.bluetooth.IBluetoothManager"
        private const val PROFILE_DESC = "android.bluetooth.IBluetoothProfileServiceConnection"
        private const val A2DP_DESC = "android.bluetooth.IBluetoothA2dp"

        // Android 12-15 IBluetoothManager AIDL order:
        // registerAdapter(1), unregisterAdapter(2), registerStateChangeCallback(3),
        // unregisterStateChangeCallback(4), enable(5), enableNoAutoConnect(6),
        // disable(7), getState(8), getBluetoothGatt(9), bindBluetoothProfileService(10).
        private const val MANAGER_TX_GET_STATE = IBinder.FIRST_CALL_TRANSACTION + 7 // 8
        private const val MANAGER_TX_BIND_PROFILE = IBinder.FIRST_CALL_TRANSACTION + 9 // 10

        // Android 12-15 IBluetoothA2dp AIDL order used by the modular Bluetooth stack:
        // isAvrcpAbsoluteVolumeSupported = transaction 15
        // setAvrcpAbsoluteVolume(int, AttributionSource) = transaction 16 (oneway)
        private const val A2DP_TX_IS_AVRCP_ABS_SUPPORTED = IBinder.FIRST_CALL_TRANSACTION + 14 // 15
        private const val A2DP_TX_SET_AVRCP_ABS_VOLUME = IBinder.FIRST_CALL_TRANSACTION + 15 // 16
    }

    @Volatile private var a2dpBinder: IBinder? = null
    @Volatile private var stateMessage = "initializing"
    @Volatile private var bindResult = "not attempted"
    @Volatile private var managerStateResult = "not read"
    @Volatile private var avrcpSupportResult = "not read"

    private val profileCallback = object : Binder() {
        init { attachInterface(null, PROFILE_DESC) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(PROFILE_DESC)
                    true
                }
                FIRST_CALL_TRANSACTION -> {
                    data.enforceInterface(PROFILE_DESC)
                    val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                    val service = try { data.readStrongBinder() } catch (_: Throwable) { null }
                    a2dpBinder = service
                    stateMessage = if (service != null) {
                        "A2DP profile callback READY: ${component?.flattenToShortString() ?: "unknown"}"
                    } else {
                        "A2DP callback received with binder=null"
                    }
                    true
                }
                FIRST_CALL_TRANSACTION + 1 -> {
                    data.enforceInterface(PROFILE_DESC)
                    val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                    a2dpBinder = null
                    stateMessage = "A2DP profile disconnected: ${component?.flattenToShortString() ?: "unknown"}"
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    init {
        bindA2dpProfileDirect()
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TX_STATUS -> {
                reply?.writeNoException()
                reply?.writeString(buildStatus())
                true
            }
            TX_SET_VOLUME -> {
                val value = data.readInt().coerceIn(0, 127)
                reply?.writeNoException()
                reply?.writeString(setAbsoluteVolumeDirect(value))
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
    } catch (_: Throwable) {
        null
    }

    private fun readManagerState(manager: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MANAGER_DESC)
            val ok = manager.transact(MANAGER_TX_GET_STATE, data, reply, 0)
            if (!ok) return "FAILED: transact=false"
            reply.readException()
            "state=${reply.readInt()} tx=$MANAGER_TX_GET_STATE"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "FAILED ${root.javaClass.simpleName}: ${root.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun bindA2dpProfileDirect() {
        if (a2dpBinder != null) return

        val manager = getService("bluetooth_manager")
        if (manager == null) {
            bindResult = "FAILED: bluetooth_manager not found"
            stateMessage = bindResult
            return
        }

        managerStateResult = readManagerState(manager)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MANAGER_DESC)
            data.writeInt(PROFILE_A2DP)
            data.writeStrongBinder(profileCallback)

            val ok = manager.transact(MANAGER_TX_BIND_PROFILE, data, reply, 0)
            if (!ok) {
                bindResult = "FAILED: bind transaction returned false (tx=$MANAGER_TX_BIND_PROFILE)"
                stateMessage = bindResult
                return
            }

            reply.readException()
            val accepted = try {
                reply.readBoolean()
            } catch (_: Throwable) {
                try { reply.readInt() != 0 } catch (_: Throwable) { true }
            }

            bindResult = "DIRECT AIDL-compatible Binder bind accepted=$accepted tx=$MANAGER_TX_BIND_PROFILE"
            stateMessage = if (accepted) {
                "A2DP bind accepted; waiting profile callback"
            } else {
                "A2DP bind rejected by bluetooth_manager"
            }
        } catch (e: Throwable) {
            val root = e.cause ?: e
            bindResult = "FAILED direct manager Binder: ${root.javaClass.name}: ${root.message}"
            stateMessage = bindResult
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun waitForA2dp(timeoutMs: Long): IBinder? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            a2dpBinder?.let { return it }
            Thread.sleep(100)
        }
        return a2dpBinder
    }

    private fun readAvrcpSupport(binder: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(A2DP_DESC)
            val ok = binder.transact(A2DP_TX_IS_AVRCP_ABS_SUPPORTED, data, reply, 0)
            if (!ok) return "FAILED: transact=false tx=$A2DP_TX_IS_AVRCP_ABS_SUPPORTED"
            reply.readException()
            val supported = try { reply.readBoolean() } catch (_: Throwable) { reply.readInt() != 0 }
            "supported=$supported tx=$A2DP_TX_IS_AVRCP_ABS_SUPPORTED"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "FAILED ${root.javaClass.simpleName}: ${root.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun makeShellAttribution(): AttributionSource {
        return AttributionSource.Builder(Process.myUid())
            .setPid(Process.myPid())
            .setPackageName("com.android.shell")
            .build()
    }

    private fun sendAvrcpVolume(binder: IBinder, value: Int): String {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(A2DP_DESC)
            data.writeInt(value)
            data.writeTypedObject(makeShellAttribution(), 0)

            val ok = binder.transact(
                A2DP_TX_SET_AVRCP_ABS_VOLUME,
                data,
                null,
                IBinder.FLAG_ONEWAY
            )
            if (!ok) {
                "FAILED: A2DP one-way transact returned false tx=$A2DP_TX_SET_AVRCP_ABS_VOLUME"
            } else {
                "SUCCESS: raw AIDL-compatible A2DP Binder transaction accepted; tx=$A2DP_TX_SET_AVRCP_ABS_VOLUME"
            }
        } catch (e: Throwable) {
            val root = e.cause ?: e
            "FAILED ${root.javaClass.name}: ${root.message}"
        } finally {
            data.recycle()
        }
    }

    private fun setAbsoluteVolumeDirect(value: Int): String {
        if (a2dpBinder == null) bindA2dpProfileDirect()
        val binder = waitForA2dp(3000)
            ?: return "FAILED: A2DP binder not ready after 3s\n${buildStatus()}"

        avrcpSupportResult = readAvrcpSupport(binder)
        val sendResult = sendAvrcpVolume(binder, value)
        stateMessage = if (sendResult.startsWith("SUCCESS")) {
            "AVRCP $value/127 transaction sent"
        } else {
            sendResult
        }

        return "$sendResult\nrequested=$value/127\npath=bluetooth_manager(tx10) -> A2DP profile Binder -> IBluetoothA2dp(tx16)\n\n${buildStatus()}"
    }

    private fun buildStatus(): String {
        val manager = getService("bluetooth_manager")
        val a2dp = a2dpBinder
        val managerDesc = try { manager?.interfaceDescriptor ?: "null" } catch (e: Throwable) { "ERROR ${e.message}" }
        val a2dpDesc = try { a2dp?.interfaceDescriptor ?: "null" } catch (e: Throwable) { "ERROR ${e.message}" }

        if (a2dp != null && avrcpSupportResult == "not read") {
            avrcpSupportResult = readAvrcpSupport(a2dp)
        }

        return "FineVolume v0.5.2 direct-AIDL Binder controller\n" +
            "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
            "bluetooth_manager=${if (manager != null) "FOUND" else "NOT FOUND"}\n" +
            "manager descriptor=$managerDesc\n" +
            "manager getState=$managerStateResult\n" +
            "bindResult=$bindResult\n" +
            "state=$stateMessage\n" +
            "A2DP binder=${if (a2dp != null) "READY" else "NULL"}\n" +
            "A2DP descriptor=$a2dpDesc\n" +
            "AVRCP absolute-volume support=$avrcpSupportResult\n" +
            "tx map: manager bind=10, A2DP support=15, A2DP set volume=16"
    }

    @Suppress("unused")
    fun destroy() {
        System.exit(0)
    }
}
