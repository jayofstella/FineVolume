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
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private lateinit var fineValue: TextView

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "FineVolume v0.1.1"
            textSize = 28f
        }
        val subtitle = TextView(this).apply {
            text = "vivo / Android 15 蓝牙音频技术验证版"
            textSize = 15f
            setPadding(0, 8, 0, 28)
        }
        status = TextView(this).apply { textSize = 16f }

        val systemLabel = TextView(this).apply {
            textSize = 17f
            setPadding(0, 30, 0, 4)
        }
        val systemBar = SeekBar(this)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        systemBar.max = max
        systemBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        systemLabel.text = "系统媒体音量：${systemBar.progress} / $max"
        systemBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                systemLabel.text = "系统媒体音量：$progress / $max"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        fineValue = TextView(this).apply {
            text = "目标软件衰减：-18.0 dB"
            textSize = 20f
            setPadding(0, 30, 0, 4)
        }
        val fineBar = SeekBar(this).apply {
            max = 400
            progress = 180
        }
        fineBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fineValue.text = "目标软件衰减：%.1f dB".format(-progress / 10.0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val refreshButton = Button(this).apply {
            text = "重新检测蓝牙 / AudioEffect"
            setOnClickListener { refresh() }
        }
        val note = TextView(this).apply {
            text = "\n重要：本版首先检测 OriginOS 5 暴露的音频能力。上面的 dB 滑块是目标值，尚不宣称已经对全局蓝牙音频实施衰减。请把检测结果截图发回。"
            textSize = 14f
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(systemLabel)
        root.addView(systemBar)
        root.addView(fineValue)
        root.addView(fineBar)
        root.addView(refreshButton)
        root.addView(note)
        setContentView(root)
    }

    private fun refresh() {
        try {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bluetooth = outputs.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
            val effects = AudioEffect.queryEffects() ?: emptyArray()
            val insertCount = effects.count { it.connectMode == AudioEffect.EFFECT_INSERT }
            val postCount = if (Build.VERSION.SDK_INT >= 30) effects.count { it.connectMode == AudioEffect.EFFECT_POST_PROCESSING } else 0
            val deviceText = if (bluetooth.isEmpty()) {
                "未检测到蓝牙音频输出（请先连接耳机）"
            } else {
                bluetooth.joinToString("\n") { "• ${it.productName}  [type=${it.type}]" }
            }
            status.text = "蓝牙输出：\n$deviceText\n\nAudioEffect 总数：${effects.size}\nINSERT：$insertCount\nPOST_PROCESSING：$postCount\nAndroid：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n设备：${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            status.text = "检测失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
