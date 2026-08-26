package com.finevolume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh("Shizuku Binder 已连接") }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh("Shizuku Binder 已断开") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            refresh(if (grantResult == PackageManager.PERMISSION_GRANTED) "FineVolume 已获得 Shizuku 授权" else "Shizuku 授权被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        requestBluetoothPermissionIfNeeded()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh("等待 Shizuku 检测")
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.3.0"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Shizuku / AVRCP Absolute Volume 能力探测版"
            textSize = 15f
            setPadding(0, 6, 0, 22)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 检查 Shizuku 状态"
            setOnClickListener { refresh("状态已刷新") }
        })

        root.addView(Button(this).apply {
            text = "② 请求 FineVolume 的 Shizuku 授权"
            setOnClickListener {
                try {
                    if (!Shizuku.pingBinder()) {
                        refresh("Shizuku 尚未运行或 Binder 未连接")
                    } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        refresh("FineVolume 已经拥有 Shizuku 授权")
                    } else {
                        Shizuku.requestPermission(1001)
                        refresh("已发起 Shizuku 授权请求，请在弹窗中允许")
                    }
                } catch (e: Throwable) {
                    refresh("请求 Shizuku 授权失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
        })

        root.addView(Button(this).apply {
            text = "③ 检测 AVRCP 所需系统权限"
            setOnClickListener { probeRemotePermissions() }
        })

        root.addView(TextView(this).apply {
            text = "\n本版不会改变蓝牙耳机音量。它只判断无 Root 的 Shizuku/ADB 路线是否具备调用 Android 15 隐藏 AVRCP 绝对音量接口的权限。\n\n" +
                "使用方法：\n" +
                "1. 先安装并启动 Shizuku。\n" +
                "2. Android 11+ 可在 Shizuku 中通过“无线调试”启动，不需要电脑，也不需要 Root。\n" +
                "3. 回到 FineVolume，依次点击①、②、③。\n" +
                "4. 把完整结果截图发回。\n\n" +
                "关键判断：Android 15 的 BluetoothA2dp.setAvrcpAbsoluteVolume() 需要 BLUETOOTH_CONNECT 与 BLUETOOTH_PRIVILEGED。若 Shizuku 的 shell 身份没有 BLUETOOTH_PRIVILEGED，则普通 Shizuku/ADB 路线不能直接调用该接口。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun probeRemotePermissions() {
        val log = StringBuilder()
        try {
            if (!Shizuku.pingBinder()) {
                refresh("无法探测：Shizuku 未运行")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                refresh("无法探测：FineVolume 尚未获得 Shizuku 授权")
                return
            }

            val connect = Shizuku.checkRemotePermission(Manifest.permission.BLUETOOTH_CONNECT)
            val privileged = Shizuku.checkRemotePermission("android.permission.BLUETOOTH_PRIVILEGED")
            val modifyAudio = Shizuku.checkRemotePermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)

            log.append("===== REMOTE PERMISSION PROBE =====\n")
            log.append("Shizuku UID=${Shizuku.getUid()}\n")
            log.append("SELinux=${Shizuku.getSELinuxContext()}\n")
            log.append("BLUETOOTH_CONNECT=${permText(connect)}\n")
            log.append("BLUETOOTH_PRIVILEGED=${permText(privileged)}\n")
            log.append("MODIFY_AUDIO_SETTINGS=${permText(modifyAudio)}\n\n")

            if (connect == PackageManager.PERMISSION_GRANTED && privileged == PackageManager.PERMISSION_GRANTED) {
                log.append("RESULT: PASS\n下一版可以尝试通过 Shizuku UserService 调用 setAvrcpAbsoluteVolume(0..127)。")
            } else {
                log.append("RESULT: BLOCKED\nShizuku 当前身份缺少 AVRCP 隐藏接口所需权限；下一步需改走其他无 Root 系统接口/ADB service 路线，而不是直接调用 BluetoothA2dp hidden API。")
            }
            refresh(log.toString())
        } catch (e: Throwable) {
            refresh("远程权限探测失败：${e.javaClass.name}: ${e.message}")
        }
    }

    private fun permText(v: Int): String = if (v == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"

    private fun refresh(message: String) {
        val outputs = try { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { emptyArray() }
        val bt = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST))
        }
        val btText = if (bt.isEmpty()) "未检测到" else bt.joinToString("\n") { "• ${it.productName} [type=${it.type}, id=${it.id}]" }
        val music = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { -1 }
        val max = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { -1 }

        val shizukuAlive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val selfPermission = if (shizukuAlive) try { permText(Shizuku.checkSelfPermission()) } catch (_: Throwable) { "ERROR" } else "N/A"
        val uid = if (shizukuAlive) try { Shizuku.getUid().toString() } catch (_: Throwable) { "ERROR" } else "N/A"
        val version = if (shizukuAlive) try { Shizuku.getVersion().toString() } catch (_: Throwable) { "ERROR" } else "N/A"
        val context = if (shizukuAlive) try { Shizuku.getSELinuxContext() ?: "unknown" } catch (_: Throwable) { "ERROR" } else "N/A"

        status.text = "蓝牙输出：\n$btText\n\n" +
            "系统媒体音量：$music / $max\n" +
            "Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" +
            "Shizuku Binder：${if (shizukuAlive) "CONNECTED" else "NOT CONNECTED"}\n" +
            "Shizuku API version：$version\n" +
            "Shizuku UID：$uid\n" +
            "Shizuku SELinux：$context\n" +
            "FineVolume Shizuku permission：$selfPermission\n\n" +
            "状态：\n$message\n"
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }
}
