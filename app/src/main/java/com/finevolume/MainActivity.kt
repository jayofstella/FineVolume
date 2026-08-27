package com.finevolume

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Parcel
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var runButton: Button
    private var remote: IBinder? = null
    @Volatile private var pendingAutoRun = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh("收到 Shizuku Binder") }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        refresh("Shizuku Binder 已断开")
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, AvrcpUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("autotest")
            .debuggable(true)
            .version(7)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("测试 UserService 已连接")
            if (pendingAutoRun) {
                pendingAutoRun = false
                runFullTest(true)
            } else {
                queryStatus()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            refresh("测试 UserService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.6.0 已启动；等待一键测试")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.6.0"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "一键全自动蓝牙音量链路验证版 · Shizuku shell"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        runButton = Button(this).apply {
            text = "一键完整测试并生成报告"
            setOnClickListener { startOneClickTest() }
        }
        root.addView(runButton)

        root.addView(Button(this).apply {
            text = "只读取当前状态（不改音量）"
            setOnClickListener { queryStatus() }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "测试前：连接蓝牙耳机、播放持续音乐，并把系统媒体音量放在约 7/15。\n\n然后只点一次“一键完整测试并生成报告”。"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "本版会自动完成：\n" +
                "1. 连接 Shizuku UserService；\n" +
                "2. 检查 shell UID、关键权限和系统 Binder；\n" +
                "3. 读取 bluetooth_manager 状态；\n" +
                "4. 按 Android 15 的 3 参数 AIDL 格式请求 A2DP profile Binder；\n" +
                "5. 收集 bluetooth_manager / audio / logcat 证据；\n" +
                "6. 仅在 A2DP Binder 成功后，自动做 60→40→60 的安全 A/B 音量测试；\n" +
                "7. 自动把完整报告保存到“下载/FineVolume”目录。\n\n" +
                "如果 A2DP Binder 获取失败，程序不会发送任何音量命令。你只需要把生成的 txt 报告发回来，不用再逐项截图测试。"
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
            "AutoTest UserService：${if (remote?.pingBinder() == true) "CONNECTED" else "NOT CONNECTED"}\n\n" +
            "状态：$message\n"
    }

    private fun startOneClickTest() {
        if (!safePing()) {
            detail.text = "Shizuku 当前未连接。请先确认 Shizuku 主界面显示“正在运行”。"
            refresh("无法开始：Shizuku 未连接")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            detail.text = "FineVolume 尚未获得 Shizuku 授权。"
            refresh("无法开始：未授权")
            return
        }

        runButton.isEnabled = false
        detail.text = "正在启动一次性自动测试……\n\n首次连接 UserService 后会自动继续，不需要再点其它按钮。"
        refresh("自动测试启动中")

        val b = remote
        if (b != null && b.pingBinder()) {
            runFullTest(true)
            return
        }

        pendingAutoRun = true
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Throwable) {
            pendingAutoRun = false
            runButton.isEnabled = true
            detail.text = "bindUserService ERROR: ${e.javaClass.name}: ${e.message}"
            refresh("UserService 启动失败")
        }
    }

    private fun runFullTest(audibleTest: Boolean) {
        val b = remote
        if (b == null || !b.pingBinder()) {
            runButton.isEnabled = true
            detail.text = "UserService 未连接，自动测试无法继续。"
            refresh("自动测试中止：UserService 未连接")
            return
        }

        detail.text = "自动测试正在运行。可能需要约 15～30 秒，请不要关闭 FineVolume。\n\n如果 A2DP Binder 成功，耳机音量可能短暂按 60→40→60 变化，然后恢复到测试基准。"
        refresh("自动测试运行中")

        Thread {
            val result = transactRunAll(b, audibleTest)
            val saved = saveReport(result)
            runOnUiThread {
                detail.text = result + "\n\n===== REPORT FILE =====\n" + saved
                runButton.isEnabled = true
                refresh("自动测试完成；报告已生成")
            }
        }.start()
    }

    private fun queryStatus() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接。若需要测试，请直接点“一键完整测试并生成报告”。"
            return
        }
        Thread {
            val result = transactForString(b, AvrcpUserService.TX_STATUS)
            runOnUiThread {
                detail.text = result
                refresh("状态已读取")
            }
        }.start()
    }

    private fun transactRunAll(binder: IBinder, audible: Boolean): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInt(if (audible) 1 else 0)
            val ok = binder.transact(AvrcpUserService.TX_RUN_ALL, data, reply, 0)
            if (!ok) return "FAILED: TX_RUN_ALL Binder transact returned false"
            reply.readException()
            reply.readString() ?: "FAILED: empty report"
        } catch (e: Throwable) {
            "FAILED: ${e.javaClass.name}: ${e.message}"
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun transactForString(binder: IBinder, code: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val ok = binder.transact(code, data, reply, 0)
            if (!ok) return "FAILED: Binder transact returned false"
            reply.readException()
            reply.readString() ?: "FAILED: no result"
        } catch (e: Throwable) {
            "FAILED: ${e.javaClass.name}: ${e.message}"
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun saveReport(report: String): String {
        return try {
            val fileName = "FineVolume-TestReport-v0.6.0-${System.currentTimeMillis()}.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FineVolume")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "报告生成失败：MediaStore insert 返回 null"
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return "报告生成失败：无法打开输出流"
            "已保存：下载/FineVolume/$fileName\n请直接把这个 txt 文件发给我。"
        } catch (e: Throwable) {
            "报告保存失败：${e.javaClass.name}: ${e.message}\n你仍可长按上方报告文本复制发送。"
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
