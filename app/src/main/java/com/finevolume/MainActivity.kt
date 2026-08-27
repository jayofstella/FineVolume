package com.finevolume

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private var remote: IBinder? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh("收到 Shizuku Binder") }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        refresh("Shizuku Binder 已断开")
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, AvrcpUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("btprobe")
            .debuggable(true)
            .version(2)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("Bluetooth Binder Probe UserService 已连接")
            runProbe()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            refresh("Bluetooth Binder Probe UserService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.4.2 已启动；本版不会修改蓝牙音量")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.4.2"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Bluetooth System Service 直连诊断版 · Shizuku shell"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 启动 / 连接 Bluetooth Binder Probe Service"
            setOnClickListener { bindProbeService() }
        })

        root.addView(Button(this).apply {
            text = "② 读取系统 Bluetooth / Audio Binder 服务"
            setOnClickListener { runProbe() }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "尚未运行系统 Binder 探测。"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "本版目的：\n" +
                "• 不再使用 BluetoothAdapter.getDefaultAdapter()。\n" +
                "• 直接从 ServiceManager 查询 bluetooth_manager / bluetooth / bluetooth_a2dp / bluetooth_avrcp。\n" +
                "• 同时读取 audio / AudioFlinger / AudioPolicy Binder，确认 shell UserService 能看到哪些系统服务。\n" +
                "• 本版只读取，不发送 AVRCP 数值，也不会改变媒体音量。\n\n" +
                "测试方法：保持 Shizuku 13.5.4 正在运行且 FineVolume 已授权，连接 Ola Friend 后点①。将完整结果截图发回。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun refresh(message: String) {
        val alive = safePing()
        val uid = if (alive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val permission = if (alive) try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERR" } else "N/A"
        status.text = "Shizuku Binder：${if (alive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku UID：$uid\n" +
            "FineVolume permission：$permission\n" +
            "Probe UserService：${if (remote?.pingBinder() == true) "CONNECTED" else "NOT CONNECTED"}\n\n" +
            "状态：$message\n"
    }

    private fun bindProbeService() {
        if (!safePing()) {
            refresh("Shizuku 未连接")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh("FineVolume 尚未获得 Shizuku 授权")
            return
        }
        try {
            detail.text = "正在启动 shell UserService……"
            refresh("正在启动 Bluetooth Binder Probe UserService")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Throwable) {
            detail.text = "bindUserService ERROR: ${e.javaClass.name}: ${e.message}"
            refresh("Probe UserService 启动失败")
        }
    }

    private fun runProbe() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "Probe UserService 尚未连接，请先点①。"
            return
        }
        detail.text = "正在读取系统 Binder 服务……"
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_PROBE)
            runOnUiThread {
                detail.text = result
                refresh("Bluetooth / Audio Binder 探测完成")
            }
        }.start()
    }

    private fun transactForString(binder: IBinder, code: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val ok = binder.transact(code, data, reply, 0)
            if (!ok) return "Binder transact returned false"
            reply.readException()
            reply.readString() ?: "(no result)"
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.name}: ${e.message}"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun permText(v: Int): String = when (v) {
        PackageManager.PERMISSION_GRANTED -> "GRANTED"
        PackageManager.PERMISSION_DENIED -> "DENIED"
        else -> "UNKNOWN($v)"
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }
}
