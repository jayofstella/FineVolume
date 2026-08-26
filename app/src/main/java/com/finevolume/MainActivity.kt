package com.finevolume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private lateinit var attenuationLabel: TextView
    private lateinit var attenuationBar: SeekBar

    private var dynamics: DynamicsProcessing? = null
    private var dynamicsOriginalGains: FloatArray? = null
    private var equalizer: Equalizer? = null
    private var equalizerOriginalLevels: ShortArray? = null
    private var selectedDb: Float = -6f
    private var activeEngine: String = "OFF"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        requestBluetoothPermissionIfNeeded()
        refreshStatus("等待测试")
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
        if (requestCode == 10) refreshStatus("蓝牙权限已更新")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.2.0 Experimental"
            textSize = 25f
        })
        root.addView(TextView(this).apply {
            text = "无 Root · 蓝牙超低音量实验版"
            textSize = 15f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "刷新蓝牙与引擎状态"
            setOnClickListener { refreshStatus("状态已刷新") }
        })

        root.addView(TextView(this).apply {
            text = "\n额外衰减"
            textSize = 20f
        })

        attenuationLabel = TextView(this).apply {
            text = "-6.0 dB"
            textSize = 28f
            setPadding(0, 8, 0, 2)
        }
        root.addView(attenuationLabel)

        attenuationBar = SeekBar(this).apply {
            max = 600
            progress = 60
        }
        attenuationBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedDb = -progress / 10f
                attenuationLabel.text = "%.1f dB".format(selectedDb)
                if (fromUser && activeEngine != "OFF") {
                    applyCurrentValue()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(attenuationBar)

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-3f, -6f, -12f, -18f, -24f, -30f).forEach { db ->
            quick.addView(Button(this).apply {
                text = "${db.toInt()}"
                setOnClickListener {
                    selectedDb = db
                    attenuationBar.progress = (-db * 10).roundToInt()
                    attenuationLabel.text = "%.1f dB".format(db)
                    if (activeEngine != "OFF") applyCurrentValue()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(quick)

        root.addView(TextView(this).apply {
            text = "\n实验引擎"
            textSize = 20f
        })

        root.addView(Button(this).apply {
            text = "① 启用 DynamicsProcessing（首选）"
            setOnClickListener { enableDynamics() }
        })

        root.addView(Button(this).apply {
            text = "② 启用 Equalizer 全频段衰减（备用）"
            setOnClickListener { enableEqualizer() }
        })

        root.addView(Button(this).apply {
            text = "紧急关闭 / 恢复原音量"
            setOnClickListener {
                disableAll(true)
                refreshStatus("已紧急关闭所有实验效果")
            }
        })

        root.addView(TextView(this).apply {
            text = "\n测试方法：连接蓝牙耳机并播放熟悉的音乐，将系统音量设为最低非静音档（建议 1/15），先选择 -6 dB，再启用 DynamicsProcessing。若耳机实际变小，再测试 -12/-18 dB。若无变化，紧急关闭后改试 Equalizer。\n\n重要：NXP Volume 私有效果器本版不写入任何未知参数。每次启动默认关闭实验效果。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun enableDynamics() {
        disableAll(false)
        try {
            val dp = DynamicsProcessing(0)
            val count = dp.channelCount
            dynamicsOriginalGains = FloatArray(count) { i ->
                try { dp.getInputGainByChannelIndex(i) } catch (_: Exception) { 0f }
            }
            dp.setInputGainAllChannelsTo(selectedDb)
            val result = dp.setEnabled(true)
            dynamics = dp
            activeEngine = "DynamicsProcessing"
            refreshStatus(
                "DynamicsProcessing 已创建：channels=$count, setEnabled=$result, hasControl=${dp.hasControl()}, enabled=${dp.enabled}"
            )
        } catch (e: Throwable) {
            dynamics?.release()
            dynamics = null
            activeEngine = "OFF"
            refreshStatus("DynamicsProcessing 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun enableEqualizer() {
        disableAll(false)
        try {
            val eq = Equalizer(1000, 0)
            val bands = eq.numberOfBands.toInt()
            equalizerOriginalLevels = ShortArray(bands) { i ->
                try { eq.getBandLevel(i.toShort()) } catch (_: Exception) { 0 }
            }
            equalizer = eq
            activeEngine = "Equalizer"
            applyEqualizerValue(eq)
            val result = eq.setEnabled(true)
            val range = eq.bandLevelRange
            refreshStatus(
                "Equalizer 已创建：bands=$bands, range=${range.getOrNull(0)}..${range.getOrNull(1)} mB, setEnabled=$result, hasControl=${eq.hasControl()}, enabled=${eq.enabled}"
            )
        } catch (e: Throwable) {
            equalizer?.release()
            equalizer = null
            activeEngine = "OFF"
            refreshStatus("Equalizer 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyCurrentValue() {
        try {
            when (activeEngine) {
                "DynamicsProcessing" -> {
                    dynamics?.setInputGainAllChannelsTo(selectedDb)
                    refreshStatus("DynamicsProcessing 已更新到 %.1f dB".format(selectedDb))
                }
                "Equalizer" -> {
                    equalizer?.let { applyEqualizerValue(it) }
                    refreshStatus("Equalizer 已更新到目标 %.1f dB（受其最小 band level 限制）".format(selectedDb))
                }
            }
        } catch (e: Throwable) {
            refreshStatus("更新衰减失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyEqualizerValue(eq: Equalizer) {
        val range = eq.bandLevelRange
        val minMb = range.getOrElse(0) { (-1500).toShort() }.toInt()
        val maxMb = range.getOrElse(1) { 1500.toShort() }.toInt()
        val targetMb = (selectedDb * 100f).roundToInt().coerceIn(minMb, maxMb).toShort()
        for (i in 0 until eq.numberOfBands.toInt()) {
            eq.setBandLevel(i.toShort(), targetMb)
        }
    }

    private fun disableAll(restore: Boolean) {
        dynamics?.let { dp ->
            try {
                if (restore) {
                    dynamicsOriginalGains?.forEachIndexed { i, gain ->
                        try { dp.setInputGainbyChannel(i, gain) } catch (_: Exception) {}
                    }
                }
                dp.setEnabled(false)
            } catch (_: Exception) {}
            try { dp.release() } catch (_: Exception) {}
        }
        dynamics = null
        dynamicsOriginalGains = null

        equalizer?.let { eq ->
            try {
                if (restore) {
                    equalizerOriginalLevels?.forEachIndexed { i, level ->
                        try { eq.setBandLevel(i.toShort(), level) } catch (_: Exception) {}
                    }
                }
                eq.setEnabled(false)
            } catch (_: Exception) {}
            try { eq.release() } catch (_: Exception) {}
        }
        equalizer = null
        equalizerOriginalLevels = null
        activeEngine = "OFF"
    }

    private fun refreshStatus(message: String) {
        try {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bt = outputs.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && (
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                            it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                        ))
            }
            val btText = if (bt.isEmpty()) "未检测到" else bt.joinToString("\n") {
                "• ${it.productName} [type=${it.type}, id=${it.id}]"
            }
            val effects = AudioEffect.queryEffects() ?: emptyArray()
            val nxpVolume = effects.firstOrNull {
                it.name.contains("volume", true) && it.implementor.contains("NXP", true)
            }
            val dpAvailable = effects.any { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING }
            val eqAvailable = effects.any { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER }
            val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            status.text = "蓝牙输出：\n$btText\n\n" +
                "系统媒体音量：$music / $max\n" +
                "当前实验引擎：$activeEngine\n" +
                "目标衰减：%.1f dB\n".format(selectedDb) +
                "DynamicsProcessing：${if (dpAvailable) "可枚举" else "未发现"}\n" +
                "Equalizer：${if (eqAvailable) "可枚举" else "未发现"}\n" +
                "NXP Volume：${if (nxpVolume != null) "已发现（仅探测，不写私有参数）" else "未发现"}\n\n" +
                "状态：$message\n"
        } catch (e: Throwable) {
            status.text = "状态读取失败：${e.javaClass.simpleName}: ${e.message}\n$message"
        }
    }

    override fun onDestroy() {
        disableAll(true)
        super.onDestroy()
    }
}
