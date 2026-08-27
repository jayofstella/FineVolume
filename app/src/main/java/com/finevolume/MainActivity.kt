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
            .processNameSuffix("avrcpctl")
            .debuggable(true)
            .version(6)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("AVRCP 控制 UserService 已连接")
            queryStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            refresh("AVRCP 控制 UserService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.5.2 已启动")
        rootBind()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.5.2"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Bluetooth AVRCP Direct-AIDL Binder 实控版 · Shizuku shell"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "重新连接并自动建立 A2DP Binder"
            setOnClickListener { rootBind() }
        })

        root.addView(Button(this).apply {
            text = "读取当前控制链路状态"
            setOnClickListener { queryStatus() }
        })

        root.addView(TextView(this).apply {
            text = "\n中等音量实控测试（系统媒体音量建议 6～8/15）"
            textSize = 17f
        })
        addButtonRow(root, listOf(60, 50, 40, 30))

        root.addView(TextView(this).apply {
            text = "\n超低音量精细测试（中段有效后再测试）"
            textSize = 17f
        })
        addButtonRow(root, listOf(12, 8, 5, 3, 1))

        root.addView(Button(this).apply {
            text = "恢复到测试基准 60 / 127"
            setOnClickListener { setVolume(60) }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "正在自动连接控制链路……"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "v0.5.2 不再依赖 vivo ROM 暴露隐藏 Stub/Proxy 类，而是按 Android 15 AIDL 的 Binder 事务格式直接连接：\n" +
                "1. bluetooth_manager；\n" +
                "2. A2DP profile Binder；\n" +
                "3. AVRCP absolute-volume support；\n" +
                "4. setAvrcpAbsoluteVolume 0～127。\n\n" +
                "测试时连接蓝牙耳机并播放音乐，系统音量保持约 7/15。只有状态出现 A2DP binder=READY 后才测试 60、50、40、30；若有效，再测试 12、8、5、3、1。\n\n" +
                "界面版本号、APK versionName 与内部诊断版本从本版起统一维护。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addButtonRow(root: LinearLayout, values: List<Int>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { value ->
            row.addView(Button(this).apply {
                text = value.toString()
                setOnClickListener { setVolume(value) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun refresh(message: String) {
        val alive = safePing()
        val uid = if (alive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val permission = if (alive) try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERR" } else "N/A"
        status.text = "Shizuku Binder：${if (alive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku UID：$uid\n" +
            "FineVolume permission：$permission\n" +
            "AVRCP UserService：${if (remote?.pingBinder() == true) "CONNECTED" else "NOT CONNECTED"}\n\n" +
            "状态：$message\n"
    }

    private fun rootBind() {
        if (!safePing()) {
            refresh("Shizuku 未连接")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh("FineVolume 尚未获得 Shizuku 授权")
            return
        }
        try {
            detail.text = "正在建立 bluetooth_manager → A2DP Binder 控制链……"
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Throwable) {
            detail.text = "bindUserService ERROR: ${e.javaClass.name}: ${e.message}"
            refresh("UserService 启动失败")
        }
    }

    private fun queryStatus() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接。请点“重新连接”。"
            return
        }
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_STATUS, null)
            runOnUiThread {
                detail.text = result
                refresh("控制链路状态已读取")
            }
        }.start()
    }

    private fun setVolume(value: Int) {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接。请先重新连接。"
            return
        }
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_SET_VOLUME, value)
            runOnUiThread {
                detail.text = "请求 AVRCP=$value/127\n\n$result"
                refresh(if (result.startsWith("SUCCESS")) "AVRCP $value/127 调用成功" else "AVRCP $value/127 调用失败")
            }
        }.start()
    }

    private fun transactForString(binder: IBinder, code: Int, value: Int?): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (value != null) data.writeInt(value)
            val ok = binder.transact(code, data, reply, 0)
            if (!ok) return "FAILED: Binder transact returned false"
            reply.readException()
            reply.readString() ?: "FAILED: no result"
        } catch (e: Throwable) {
            "FAILED: ${e.javaClass.name}: ${e.message}"
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
