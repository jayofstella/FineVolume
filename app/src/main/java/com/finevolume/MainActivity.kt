package com.finevolume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var summary: TextView
    private lateinit var details: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        requestBluetoothPermissionIfNeeded()
        refresh()
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) refresh()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 48)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.1.2"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "OriginOS 5 / Android 15 深度音频诊断版"
            textSize = 15f
            setPadding(0, 8, 0, 24)
        })

        summary = TextView(this).apply { textSize = 16f }
        root.addView(summary)

        root.addView(Button(this).apply {
            text = "重新扫描音频设备与 AudioEffect"
            setOnClickListener { refresh() }
        })

        root.addView(TextView(this).apply {
            text = "\n详细诊断结果"
            textSize = 20f
        })
        details = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(details)

        root.addView(TextView(this).apply {
            text = "\n说明：本版只做能力探测，不会修改或接管系统音频。请在蓝牙耳机已连接且正在播放音乐/视频时运行扫描，并把详细结果截图发回。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        else -> if (Build.VERSION.SDK_INT >= 31) {
            when (type) {
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
                else -> "TYPE_$type"
            }
        } else "TYPE_$type"
    }

    private fun refresh() {
        try {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val effects = AudioEffect.queryEffects() ?: emptyArray()
            val insertCount = effects.count { it.connectMode == AudioEffect.EFFECT_INSERT }
            val postCount = if (Build.VERSION.SDK_INT >= 30) {
                effects.count { it.connectMode == AudioEffect.EFFECT_POST_PROCESSING }
            } else 0
            val auxCount = effects.count { it.connectMode == AudioEffect.EFFECT_AUXILIARY }
            val musicNow = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            val btOutputs = outputs.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && (
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                            it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                        ))
            }

            val btText = if (btOutputs.isEmpty()) {
                "未检测到蓝牙输出"
            } else {
                btOutputs.joinToString("\n") {
                    "• ${it.productName}  ${deviceTypeName(it.type)} [type=${it.type}, id=${it.id}]"
                }
            }

            summary.text = "蓝牙输出：\n$btText\n\n" +
                "系统媒体音量：$musicNow / $musicMax\n" +
                "AudioEffect 总数：${effects.size}\n" +
                "INSERT：$insertCount   AUXILIARY：$auxCount   POST_PROCESSING：$postCount\n" +
                "Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "设备：${Build.MANUFACTURER} ${Build.MODEL}\n"

            val outText = buildString {
                append("===== ALL OUTPUT DEVICES (${outputs.size}) =====\n")
                outputs.forEachIndexed { index, d ->
                    append("[$index] ${d.productName}\n")
                    append("  type=${d.type} (${deviceTypeName(d.type)})  id=${d.id}\n")
                    append("  sink=${d.isSink}  source=${d.isSource}\n")
                    append("  sampleRates=${d.sampleRates.joinToString()}\n")
                    append("  channelCounts=${d.channelCounts.joinToString()}\n\n")
                }

                append("===== AUDIO EFFECTS (${effects.size}) =====\n")
                effects.forEachIndexed { index, e ->
                    val suspicious = listOf(
                        "gain", "volume", "loud", "dynamic", "compress", "limit",
                        "equal", "vivo", "qti", "qualcomm", "dirac", "dts", "dolby"
                    ).any { token ->
                        e.name.contains(token, ignoreCase = true) ||
                            e.implementor.contains(token, ignoreCase = true)
                    }
                    append(if (suspicious) "*** CANDIDATE ***\n" else "")
                    append("[$index] ${e.name}\n")
                    append("  implementor=${e.implementor}\n")
                    append("  mode=${e.connectMode}\n")
                    append("  type=${e.type}\n")
                    append("  uuid=${e.uuid}\n\n")
                }
            }
            details.text = outText
        } catch (e: Exception) {
            summary.text = "检测失败：${e.javaClass.simpleName}: ${e.message}"
            details.text = e.stackTraceToString()
        }
    }
}
