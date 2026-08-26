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
        refresh("Binder 已连接")
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        refresh("Binder 已断开")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 3101) {
            refresh(
                if (grantResult == PackageManager.PERMISSION_GRANTED)
                    "授权回调：GRANTED"
                else "授权回调：DENIED"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh("最小诊断版已启动；尚未请求任何远程权限")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.3.1"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Shizuku 最小兼容性诊断版"
            textSize = 16f
            setPadding(0, 6, 0, 24)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 只检查 Shizuku Binder"
            setOnClickListener { refresh("仅刷新 Binder 状态") }
        })

        root.addView(Button(this).apply {
            text = "② 读取当前授权状态（不发起授权）"
            setOnClickListener {
                if (!safePing()) {
                    refresh("Shizuku 未运行 / Binder 未连接")
                } else {
                    val p = try { Shizuku.checkSelfPermission() } catch (e: Throwable) { Int.MIN_VALUE }
                    refresh("当前授权状态：${permText(p)}；本操作没有发起授权请求")
                }
            }
        })

        root.addView(Button(this).apply {
            text = "③ 最小化请求 Shizuku 授权"
            setOnClickListener { requestShizukuPermissionMinimal() }
        })

        root.addView(TextView(this).apply {
            text = "\n本版已经移除：AVRCP、蓝牙隐藏接口、checkRemotePermission、UserService 和所有音频控制代码。\n\n" +
                "测试顺序：\n" +
                "1. 在 Shizuku 中通过无线调试启动服务，确认显示“正在运行”。\n" +
                "2. 打开 FineVolume，先点①。\n" +
                "3. 点②，只读取现有授权状态。这个动作不应该停止 Shizuku。\n" +
                "4. 最后再点③。如果仅点击③就导致 Shizuku 从“正在运行”变为“未运行”，就可以确认问题发生在 OriginOS/Shizuku 的授权事务本身，而不是 AVRCP 或 FineVolume 音频代码。\n\n" +
                "如果 Shizuku 已在自己的应用列表里给 FineVolume 授权，请先不要反复切换授权，先用①②观察状态。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun requestShizukuPermissionMinimal() {
        try {
            if (!safePing()) {
                refresh("无法请求：Shizuku 未运行 / Binder 未连接")
                return
            }

            val current = Shizuku.checkSelfPermission()
            if (current == PackageManager.PERMISSION_GRANTED) {
                refresh("FineVolume 已经获得 Shizuku 授权，无需再次请求")
                return
            }

            if (Shizuku.shouldShowRequestPermissionRationale()) {
                refresh("Shizuku 报告应显示权限说明；为避免反复触发，本版暂不强制请求")
                return
            }

            status.text = buildState("即将调用唯一一次 Shizuku.requestPermission(3101)…")
            Shizuku.requestPermission(3101)
        } catch (e: Throwable) {
            refresh("最小授权请求异常：${e.javaClass.name}: ${e.message}")
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun buildState(message: String): String {
        val alive = safePing()
        val version = if (alive) try { Shizuku.getVersion().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val uid = if (alive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERR" } else "N/A"
        val permission = if (alive) {
            try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERR" }
        } else "N/A"

        return "Shizuku Binder：${if (alive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku API version：$version\n" +
            "Shizuku UID：$uid\n" +
            "FineVolume Shizuku permission：$permission\n\n" +
            "状态：\n$message"
    }

    private fun refresh(message: String) {
        status.text = buildState(message)
    }

    private fun permText(v: Int): String = when (v) {
        PackageManager.PERMISSION_GRANTED -> "GRANTED"
        PackageManager.PERMISSION_DENIED -> "DENIED"
        else -> "UNKNOWN($v)"
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }
}
