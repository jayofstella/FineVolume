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
        private const val A2DP_SERVICE_NAME = "android.bluetooth.IBluetoothA2dp"
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
                FIRST_CALL_TRANSACTION -> {
                    data.enforceInterface(PROFILE_DESC)
                    val component = try { data.readTypedObject(ComponentName.CREATOR) } catch (_: Throwable) { null }
                    val service = data.readStrongBinder()
                    a2dpBinder = service
                    stateMessage = if (service != null) {
                        "A2DP profile connected: ${component?.flattenToShortString() ?: "unknown"}"
                    } else {
                        "A2DP callback received but binder=null"
                    }
                    reply?.writeNoException()
                    true
                }
                FIRST_CALL_TRANSACTION + 1 -> {
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

    init { bindA2dpProfile() }

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
        if (a2dpBinder != null) return
        val managerBinder = getService("bluetooth_manager")
        if (managerBinder == null) {
            bindResult = "FAILED: bluetooth_manager not found"
            stateMessage = bindResult
            return
        }

        val errors = mutableListOf<String>()

        // Preferred path: use the real hidden IBluetoothManager proxy and discover the
        // device's actual bindBluetoothProfileService overload. Android 14/15 commonly
        // uses (int profile, String serviceName, IBluetoothProfileServiceConnection).
        try {
            val managerStub = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
            val managerAsInterface = managerStub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
            val managerProxy = managerAsInterface.invoke(null, managerBinder)

            val connectionStub = Class.forName("android.bluetooth.IBluetoothProfileServiceConnection\$Stub")
            val connectionAsInterface = connectionStub.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
            val connectionProxy = connectionAsInterface.invoke(null, profileCallback)

            val methods = managerProxy.javaClass.methods
                .filter { it.name == "bindBluetoothProfileService" }
                .sortedByDescending { it.parameterCount }

            for (m in methods) {
                try {
                    m.isAccessible = true
                    val result = when (m.parameterCount) {
                        3 -> m.invoke(managerProxy, PROFILE_A2DP, A2DP_SERVICE_NAME, connectionProxy)
                        2 -> m.invoke(managerProxy, PROFILE_A2DP, connectionProxy)
                        else -> continue
                    }
                    val accepted = result as? Boolean ?: true
                    bindResult = "REFLECTION ${m.parameterCount}-arg bind accepted=$accepted signature=${m.parameterTypes.joinToString { it.simpleName }}"
                    stateMessage = if (accepted) "A2DP bind accepted; waiting callback" else "A2DP bind rejected"
                    if (accepted) return
                } catch (e: Throwable) {
                    val root = e.cause ?: e
                    errors += "reflection ${m.parameterCount}-arg: ${root.javaClass.simpleName}: ${root.message}"
                }
            }
            if (methods.isEmpty()) errors += "reflection: no bindBluetoothProfileService method"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            errors += "reflection setup: ${root.javaClass.simpleName}: ${root.message}"
        }

        // Fallback path: raw Binder transaction. Try modern 3-arg layout first,
        // then legacy 2-arg layout. This also covers vendor framework variations.
        val tx = transactionCode("android.bluetooth.IBluetoothManager\$Stub", "TRANSACTION_bindBluetoothProfileService")
        if (tx != null) {
            for (modern in listOf(true, false)) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(managerBinder.interfaceDescriptor ?: "android.bluetooth.IBluetoothManager")
                    data.writeInt(PROFILE_A2DP)
                    if (modern) data.writeString(A2DP_SERVICE_NAME)
                    data.writeStrongBinder(profileCallback)
                    val ok = managerBinder.transact(tx, data, reply, 0)
                    if (!ok) {
                        errors += "raw ${if (modern) "3" else "2"}-arg: transact=false"
                        continue
                    }
                    reply.readException()
                    val accepted = try { reply.readBoolean() } catch (_: Throwable) { try { reply.readInt() != 0 } catch (_: Throwable) { true } }
                    bindResult = "RAW ${if (modern) "3" else "2"}-arg bind accepted=$accepted tx=$tx"
                    stateMessage = if (accepted) "A2DP bind accepted; waiting callback" else "A2DP bind rejected"
                    if (accepted) return
                } catch (e: Throwable) {
                    val root = e.cause ?: e
                    errors += "raw ${if (modern) "3" else "2"}-arg: ${root.javaClass.simpleName}: ${root.message}"
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } else {
            errors += "raw: transaction code unavailable"
        }

        bindResult = "FAILED all bind paths: ${errors.joinToString(" | ")}"
        stateMessage = bindResult
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
        return "FineVolume v0.5.1 Android15 profile-bind fix\n" +
            "uid=${Process.myUid()} pid=${Process.myPid()}\n" +
            "bluetooth_manager=${if (manager != null) "FOUND" else "NOT FOUND"}\n" +
            "bindResult=$bindResult\n" +
            "state=$stateMessage\n" +
            "A2DP binder=${if (a2dp != null) "READY" else "NULL"}\n" +
            "A2DP descriptor=$a2dpDesc\n" +
            "method=$methodInfo"
    }

    private fun waitForA2dp(timeoutMs: Long): IBinder? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            a2dpBinder?.let { return it }
            Thread.sleep(100)
        }
        return a2dpBinder
    }

    private fun setAbsoluteVolume(value: Int): String {
        if (a2dpBinder == null) bindA2dpProfile()
        val binder = waitForA2dp(2500) ?: return "FAILED: A2DP binder not ready after 2.5s\n${buildStatus()}"
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
                            stateMessage = "SUCCESS AVRCP $value/127"
                            return "SUCCESS: AVRCP=$value/127\npath=bluetooth_manager -> A2DP Binder -> setAvrcpAbsoluteVolume(Int)\n${buildStatus()}"
                        }
                        m.parameterCount == 2 && m.parameterTypes[0] == Int::class.javaPrimitiveType && AttributionSource::class.java.isAssignableFrom(m.parameterTypes[1]) -> {
                            m.invoke(proxy, value, source)
                            stateMessage = "SUCCESS AVRCP $value/127"
                            return "SUCCESS: AVRCP=$value/127\npath=bluetooth_manager -> A2DP Binder -> setAvrcpAbsoluteVolume(Int, AttributionSource)\n${buildStatus()}"
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e.cause ?: e
                }
            }
            val err = lastError
            "FAILED: no callable AVRCP overload${if (err != null) "\n${err.javaClass.name}: ${err.message}" else ""}\n${buildStatus()}"
        } catch (e: Throwable) {
            val root = e.cause ?: e
            stateMessage = "FAILED ${root.javaClass.simpleName}: ${root.message}"
            "FAILED: ${root.javaClass.name}: ${root.message}\n${buildStatus()}"
        }
    }

    @Suppress("unused")
    fun destroy() { System.exit(0) }
}
