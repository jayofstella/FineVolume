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
            .processNameSuffix("avrcp")
            .debuggable(true)
            .version(1)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("AVRCP shell UserService 已连接")
            queryServiceStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            refresh("AVRCP shell UserService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.4.1 已启动；先初始化 shell AVRCP service")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.4.1"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "AVRCP Absolute Volume 首次实控版 · Shizuku shell"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 初始化 / 连接 AVRCP shell service"
            setOnClickListener { bindAvrcpService() }
        })

        root.addView(Button(this).apply {
            text = "② 读取 AVRCP service 状态"
            setOnClickListener { queryServiceStatus() }
        })

        root.addView(TextView(this).apply {
            text = "\n中等音量 A/B 测试（建议系统先放在 6～8/15）"
            textSize = 17f
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(60, 50, 40, 30).forEach { value ->
            row.addView(Button(this).apply {
                text = "$value"
                setOnClickListener { setAbsoluteVolume(value) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "\n低音量精细测试（只有上面确认有效后再试）"
            textSize = 17f
        })

        val lowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(12, 8, 5, 3).forEach { value ->
            lowRow.addView(Button(this).apply {
                text = "$value"
                setOnClickListener { setAbsoluteVolume(value) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(lowRow)

        root.addView(Button(this).apply {
            text = "恢复到测试基准 60 / 127"
            setOnClickListener { setAbsoluteVolume(60) }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "尚未连接 AVRCP shell UserService。"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "测试方法：\n" +
                "1. 连接蓝牙耳机并播放持续音乐。\n" +
                "2. 系统媒体音量先放在约 6～8/15。\n" +
                "3. 点①，等待状态出现 UserService 已连接 / A2DP proxy ready。\n" +
                "4. 依次点 60、50、40、30，听耳机实际音量是否逐步变化。\n" +
                "5. 只有中段测试有效，才继续试 12、8、5、3。\n\n" +
                "注意：按钮数值是 AVRCP 绝对音量 0～127，不是 Android 的 0～15 音量档。" 
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
            "AVRCP UserService：${if (remote?.pingBinder() == true) "CONNECTED" else "NOT CONNECTED"}\n\n" +
            "状态：$message\n"
    }

    private fun bindAvrcpService() {
        if (!safePing()) {
            refresh("Shizuku 未连接")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh("FineVolume 尚未获得 Shizuku 授权")
            return
        }
        try {
            refresh("正在启动 shell UserService……")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Throwable) {
            detail.text = "bindUserService ERROR: ${e.javaClass.name}: ${e.message}"
            refresh("UserService 启动失败")
        }
    }

    private fun queryServiceStatus() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接，请先点①。"
            return
        }
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_STATUS, null)
            runOnUiThread { detail.text = result }
        }.start()
    }

    private fun setAbsoluteVolume(value: Int) {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接，请先点①。"
            return
        }
        val safeValue = value.coerceIn(0, 127)
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_SET_VOLUME, safeValue)
            runOnUiThread {
                detail.text = "请求 AVRCP=$safeValue/127\n$result"
                refresh("已发送 AVRCP 测试值 $safeValue / 127")
            }
        }.start()
    }

    private fun transactForString(binder: IBinder, code: Int, value: Int?): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (value != null) data.writeInt(value)
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
