package com.finevolume

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh("收到 Shizuku Binder") }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh("Shizuku Binder 已断开") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.4.0 已启动；本版不会修改耳机音量")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.4.0"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Shizuku / Bluetooth 音量控制能力实测版"
            textSize = 16f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 检查 Shizuku 基础状态"
            setOnClickListener { refresh("基础状态刷新完成") }
        })

        root.addView(Button(this).apply {
            text = "② 检测 shell 身份的蓝牙 / 音频系统权限"
            setOnClickListener { probeRemotePermissions() }
        })

        root.addView(Button(this).apply {
            text = "③ 探测 OriginOS 的 audio / bluetooth shell 命令"
            setOnClickListener { probeShellCommands() }
        })

        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 18, 0, 20)
            text = "详细结果将在这里显示。"
        }
        root.addView(detail)

        root.addView(TextView(this).apply {
            text = "本版只做读取与能力探测，不会发送 AVRCP 音量值，也不会改变系统媒体音量。\n\n" +
                "关键判断：\n" +
                "• BLUETOOTH_PRIVILEGED=GRANTED：下一版可直接尝试 Android 15 的 AVRCP 绝对音量接口。\n" +
                "• BLUETOOTH_PRIVILEGED=DENIED：继续查看 cmd audio / cmd bluetooth_manager 是否提供厂商 shell 控制入口。\n" +
                "• Shizuku UID=2000 表示当前是无 Root 的 ADB/shell 模式。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun refresh(message: String) {
        val alive = safePing()
        val version = if (alive) try { Shizuku.getVersion().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val uid = if (alive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val permission = if (alive) try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERR" } else "N/A"
        status.text = "Shizuku Binder：${if (alive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku API version：$version\n" +
            "Shizuku UID：$uid\n" +
            "FineVolume permission：$permission\n\n状态：$message\n"
    }

    private fun probeRemotePermissions() {
        if (!safePing()) {
            refresh("无法检测：Shizuku Binder 未连接")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh("无法检测：FineVolume 尚未获得 Shizuku 授权")
            return
        }

        val permissions = listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_PRIVILEGED",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.DUMP",
            "android.permission.WRITE_SECURE_SETTINGS"
        )
        val sb = StringBuilder("===== REMOTE PERMISSIONS (Shizuku UID=${try { Shizuku.getUid() } catch (_: Throwable) { -1 }}) =====\n")
        for (p in permissions) {
            val r = try { Shizuku.checkRemotePermission(p) } catch (e: Throwable) { Int.MIN_VALUE }
            sb.append(p.substringAfterLast('.')).append(" = ").append(permText(r)).append('\n')
        }
        detail.text = sb.toString()
        refresh("远程权限检测完成")
    }

    private fun probeShellCommands() {
        if (!safePing() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            refresh("无法执行 shell 探测：Shizuku 未连接或未授权")
            return
        }
        detail.text = "正在以 Shizuku shell 身份读取系统命令能力……"
        Thread {
            val commands = listOf(
                "id",
                "cmd audio help",
                "cmd bluetooth_manager help",
                "cmd media_session help"
            )
            val sb = StringBuilder()
            for (cmd in commands) {
                sb.append("\n===== $cmd =====\n")
                val result = runShizukuShell(cmd)
                sb.append(result.take(7000)).append('\n')
            }
            runOnUiThread {
                detail.text = sb.toString()
                refresh("shell 命令能力探测完成")
            }
        }.start()
    }

    private fun runShizukuShell(command: String): String {
        return try {
            // Shizuku API 13.1.5 已将 newProcess 设为 private/deprecated；此诊断版通过反射调用，
            // 仅用于读取 shell 命令帮助文本。后续正式版改为 UserService。
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            "exit=$code\n${out.trim()}${if (err.isNotBlank()) "\n[stderr]\n${err.trim()}" else ""}"
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.name}: ${e.cause?.message ?: e.message}"
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
