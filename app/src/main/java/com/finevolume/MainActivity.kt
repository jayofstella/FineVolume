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
            .processNameSuffix("audioprobe")
            .debuggable(true)
            .version(8)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("Audio Probe UserService 已连接")
            if (pendingAutoRun) {
                pendingAutoRun = false
                runFullTest()
            } else {
                queryStatus()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            refresh("Audio Probe UserService 已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.7.0 已启动；等待一键扫描")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.7.0"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Audio / Vivo AudioPolicy 一键控制路径扫描版 · Shizuku shell"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        runButton = Button(this).apply {
            text = "一键扫描全部剩余音量控制路径并生成报告"
            setOnClickListener { startOneClickTest() }
        }
        root.addView(runButton)

        root.addView(Button(this).apply {
            text = "只读取当前服务状态"
            setOnClickListener { queryStatus() }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "本版不再测试已经确认失败的 A2DP Profile Binder 回调。\n\n直接扫描 AudioService、AudioPolicy、vivoaudiopolicy 以及系统 shell 音频入口。"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "本次只需要一次测试：\n" +
                "1. 保持 Shizuku 正在运行并已授权 FineVolume；\n" +
                "2. 蓝牙耳机可以保持连接；\n" +
                "3. 点上方“一键扫描”；\n" +
                "4. 等待约 15～30 秒；\n" +
                "5. 把自动生成的 FineVolume-TestReport-v0.7.0 txt 文件发回来。\n\n" +
                "v0.7.0 不会发送任何改变耳机音量的 Binder 事务，只负责把剩余可行接口一次映射完整。"
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
            "Audio Probe UserService：${if (remote?.pingBinder() == true) "CONNECTED" else "NOT CONNECTED"}\n\n" +
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
        detail.text = "正在启动 v0.7.0 一键扫描……\n\n不需要再点其它按钮。"
        refresh("自动扫描启动中")

        val b = remote
        if (b != null && b.pingBinder()) {
            runFullTest()
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

    private fun runFullTest() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            runButton.isEnabled = true
            detail.text = "UserService 未连接，自动扫描无法继续。"
            refresh("自动扫描中止：UserService 未连接")
            return
        }

        detail.text = "正在扫描 AudioService / AudioPolicy / vivoaudiopolicy / shell 音频接口……\n\n预计 15～30 秒，请不要关闭 FineVolume。"
        refresh("自动扫描运行中")

        Thread {
            val result = transactRunAll(b)
            val saved = saveReport(result)
            runOnUiThread {
                detail.text = result + "\n\n===== REPORT FILE =====\n" + saved
                runButton.isEnabled = true
                refresh("v0.7.0 扫描完成；报告已生成")
            }
        }.start()
    }

    private fun queryStatus() {
        val b = remote
        if (b == null || !b.pingBinder()) {
            detail.text = "UserService 尚未连接。若需要扫描，请直接点“一键扫描全部剩余音量控制路径并生成报告”。"
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

    private fun transactRunAll(binder: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
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
            val fileName = "FineVolume-TestReport-v0.7.0-${System.currentTimeMillis()}.txt"
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
