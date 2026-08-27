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
        private const val PROFILE_DESC = "android.bluetooth.IBluetoothProfileServiceConnection"
    }

    @Volatile private var a2dpBinder: IBinder? = null
    @Volatile private var stateMessage = "initializing"
    @Volatile private var bindResult = "not attempted"

    private val profileCallback = object : Binder() {
        init { attachInterface(null, PROFILE_DESC) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(PROFILE_DESC)
                    true
                }
                FIRST_CALL_TRANSACTION -> { // onServiceConnected(ComponentName, IBinder)
                    data.enforceInterface(PROFILE_DESC)
                    val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                    val service = data.readStrongBinder()
                    a2dpBinder = service
                    stateMessage = "A2DP profile connected: ${component?.flattenToShortString() ?: "unknown"}"
                    reply?.writeNoException()
                    true
                }
                FIRST_CALL_TRANSACTION + 1 -> { // onServiceDisconnected(ComponentName)
                    data.enforceInterface(PROFILE_DESC)
                    val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                    a2dpBinder = null
                    stateMessage = "A2DP profile disconnected: ${component?.flattenToShortString() ?: "unknown"}"
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    init {
        bindA2dpProfile()
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
                reply?.writeString(setAbsoluteVolume(value))
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

    private fun transactionCode(stubClassName: String, fieldName: String): Int? = try {
        val cls = Class.forName(stubClassName)
        val f = cls.getDeclaredField(fieldName)
        f.isAccessible = true
        f.getInt(null)
    } catch (_: Throwable) { null }

    private fun bindA2dpProfile() {
        val manager = getService("bluetooth_manager")
        if (manager == null) {
            bindResult = "FAILED: bluetooth_manager not found"
            stateMessage = bindResult
            return
        }
        val tx = transactionCode(
            "android.bluetooth.IBluetoothManager\$Stub",
            "TRANSACTION_bindBluetoothProfileService"
        )
        if (tx == null) {
            bindResult = "FAILED: bindBluetoothProfileService transaction code unavailable"
            stateMessage = bindResult
            return
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(manager.interfaceDescriptor)
            data.writeInt(PROFILE_A2DP)
            data.writeStrongBinder(profileCallback)
            val ok = manager.transact(tx, data, reply, 0)
            if (!ok) {
                bindResult = "FAILED: manager transact returned false (tx=$tx)"
                stateMessage = bindResult
                return
            }
            reply.readException()
            val accepted = try { reply.readBoolean() } catch (_: Throwable) { reply.readInt() != 0 }
            bindResult = "bindBluetoothProfileService(A2DP) accepted=$accepted tx=$tx"
            stateMessage = if (accepted) "A2DP bind accepted; waiting profile callback" else "A2DP bind rejected"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            bindResult = "FAILED ${root.javaClass.name}: ${root.message}"
            stateMessage = bindResult
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun buildStatus(): String {
        val manager = getService("bluetooth_manager")
        val a2dp = a2dpBinder
        val a2dpDesc = try { a2dp?.interfaceDescriptor ?: "null" } catch (e: Throwable) { "ERROR ${e.message}" }
        val methodInfo = if (a2dp == null) {
            "A2DP binder not ready"
        } else {
            try {
                val stub = Class.forName("android.bluetooth.IBluetoothA2dp\$Stub")
                val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
                val proxy = asInterface.invoke(null, a2dp)
                val methods = proxy.javaClass.methods.filter { it.name == "setAvrcpAbsoluteVolume" }
                if (methods.isEmpty()) "setAvrcpAbsoluteVolume NOT FOUND"
                else methods.joinToString(" | ") { m ->
                    "setAvrcpAbsoluteVolume(${m.parameterTypes.joinToString { it.simpleName }})"
                }
            } catch (e: Throwable) {
                "A2DP reflection ERROR ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return "FineVolume v0.5.0 multi-path controller\n" +
            "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
            "bluetooth_manager=${if (manager != null) "FOUND" else "NOT FOUND"}\n" +
            "bindResult=$bindResult\n" +
            "state=$stateMessage\n" +
            "A2DP binder=${if (a2dp != null) "READY" else "NULL"}\n" +
            "A2DP descriptor=$a2dpDesc\n" +
            "method=$methodInfo"
    }

    private fun setAbsoluteVolume(value: Int): String {
        if (a2dpBinder == null) {
            bindA2dpProfile()
            Thread.sleep(250)
        }
        val binder = a2dpBinder ?: return "FAILED: A2DP binder not ready\n${buildStatus()}"
        return try {
            val stub = Class.forName("android.bluetooth.IBluetoothA2dp\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
            val proxy = asInterface.invoke(null, binder)
            val candidates = proxy.javaClass.methods.filter { it.name == "setAvrcpAbsoluteVolume" }
            if (candidates.isEmpty()) return "FAILED: setAvrcpAbsoluteVolume method not found\n${buildStatus()}"

            val source = AttributionSource.Builder(Process.myUid())
                .setPid(Process.myPid())
                .setPackageName("com.android.shell")
                .build()

            var lastError: Throwable? = null
            for (m in candidates.sortedBy { it.parameterCount }) {
                try {
                    m.isAccessible = true
                    when {
                        m.parameterCount == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType -> {
                            m.invoke(proxy, value)
                            stateMessage = "SUCCESS via reflected IBluetoothA2dp: $value/127"
                            return "SUCCESS: AVRCP=$value/127\npath=bluetooth_manager -> A2DP profile Binder -> IBluetoothA2dp.setAvrcpAbsoluteVolume(Int)\n${buildStatus()}"
                        }
                        m.parameterCount == 2 && m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            AttributionSource::class.java.isAssignableFrom(m.parameterTypes[1]) -> {
                            m.invoke(proxy, value, source)
                            stateMessage = "SUCCESS via reflected IBluetoothA2dp + AttributionSource: $value/127"
                            return "SUCCESS: AVRCP=$value/127\npath=bluetooth_manager -> A2DP profile Binder -> IBluetoothA2dp.setAvrcpAbsoluteVolume(Int, AttributionSource)\nsourceUid=${Process.myUid()} package=com.android.shell\n${buildStatus()}"
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e.cause ?: e
                }
            }
            val err = lastError
            "FAILED: no callable overload${if (err != null) "\n${err.javaClass.name}: ${err.message}" else ""}\n${buildStatus()}"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            stateMessage = "FAILED ${root.javaClass.simpleName}: ${root.message}"
            "FAILED: ${root.javaClass.name}: ${root.message}\n${buildStatus()}"
        }
    }

    @Suppress("unused")
    fun destroy() {
        System.exit(0)
    }
}
