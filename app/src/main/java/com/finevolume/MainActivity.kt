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

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh("收到 Shizuku Binder 回调")
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        refresh("Shizuku Binder 已断开")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.3.2 已启动")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.3.2"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Shizuku Provider / Binder 连接修复诊断版"
            textSize = 16f
            setPadding(0, 6, 0, 24)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 检查 Provider 与 Binder"
            setOnClickListener { refresh("手动刷新完成") }
        })

        root.addView(Button(this).apply {
            text = "② 读取 FineVolume 的 Shizuku 授权状态"
            setOnClickListener {
                if (!safePing()) {
                    refresh("Binder 尚未连接，暂不能读取授权状态")
                } else {
                    val p = try { Shizuku.checkSelfPermission() } catch (e: Throwable) { Int.MIN_VALUE }
                    refresh("授权状态读取结果：${permText(p)}")
                }
            }
        })

        root.addView(TextView(this).apply {
            text = "\n本版重点：\n" +
                "• 显式声明 rikka.shizuku.ShizukuProvider，与 GKD 的接入方式保持一致。\n" +
                "• 检查 Provider 是否真正安装、启用、导出。\n" +
                "• 监听 Shizuku Binder received/dead 回调。\n" +
                "• 暂不加入 AVRCP 与音量控制。\n\n" +
                "测试前提：Shizuku 13.5.4 显示正在运行，且应用管理中 FineVolume 已授权。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun providerState(): String {
        return try {
            val authority = "$packageName.shizuku"
            val info = packageManager.resolveContentProvider(authority, 0)
            if (info == null) {
                "NOT FOUND ($authority)"
            } else {
                "FOUND\n" +
                    "class=${info.name}\n" +
                    "authority=${info.authority}\n" +
                    "enabled=${info.enabled}\n" +
                    "exported=${info.exported}\n" +
                    "permission=${info.readPermission ?: info.writePermission ?: "none"}"
            }
        } catch (e: Throwable) {
            "ERROR ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun refresh(message: String) {
        val alive = safePing()
        val version = if (alive) try { Shizuku.getVersion().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val uid = if (alive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val permission = if (alive) {
            try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERR" }
        } else "N/A"

        status.text = "ShizukuProvider：\n${providerState()}\n\n" +
            "Shizuku Binder：${if (alive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku API version：$version\n" +
            "Shizuku UID：$uid\n" +
            "FineVolume Shizuku permission：$permission\n\n" +
            "状态：\n$message\n"
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
