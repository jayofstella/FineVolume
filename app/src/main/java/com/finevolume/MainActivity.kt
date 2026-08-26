package com.finevolume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.InvocationTargetException
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private var probeTrack: AudioTrack? = null
    private var probeEffect: AudioEffect? = null
    private var probeEq: Equalizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        requestBluetoothPermissionIfNeeded()
        refreshStatus("等待探测")
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
            text = "FineVolume v0.2.1"
            textSize = 27f
        })
        root.addView(TextView(this).apply {
            text = "NXP Volume 安全探测版 · 无 Root"
            textSize = 16f
            setPadding(0, 6, 0, 22)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 运行 NXP Volume 安全探测"
            setOnClickListener { runNxpProbe() }
        })

        root.addView(Button(this).apply {
            text = "刷新蓝牙与系统状态"
            setOnClickListener { refreshStatus("状态已刷新") }
        })

        root.addView(Button(this).apply {
            text = "释放测试资源"
            setOnClickListener {
                releaseProbeResources()
                refreshStatus("测试资源已释放")
            }
        })

        root.addView(TextView(this).apply {
            text = "\n本版本不会修改 NXP Volume 的任何私有参数，也不会改变耳机音量。它只回答两个问题：\n\n" +
                "A. NXP Volume 能否挂到 session 0（全局输出）？\n" +
                "B. NXP Volume 能否挂到 FineVolume 自己拥有的 AudioTrack session？\n\n" +
                "同时会用标准 Equalizer 在自有 session 上做对照测试。\n\n" +
                "测试时建议连接 Ola Friend 并保持系统音量约 6～8/15；无需特意使用最低音量。点击①后，把完整结果截图发回。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun runNxpProbe() {
        releaseProbeResources()
        val log = StringBuilder()
        log.append("===== FineVolume NXP SAFE PROBE =====\n")
        log.append("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        log.append("Device ${Build.MANUFACTURER} ${Build.MODEL}\n\n")

        val effects = try { AudioEffect.queryEffects() ?: emptyArray() } catch (e: Throwable) {
            log.append("queryEffects FAILED: ${describeThrowable(e)}\n")
            status.text = buildHeader() + "\n\n" + log
            return
        }

        val nxp = effects.firstOrNull {
            it.name.equals("Volume", ignoreCase = true) && it.implementor.contains("NXP", ignoreCase = true)
        } ?: effects.firstOrNull {
            it.name.contains("volume", ignoreCase = true) && it.implementor.contains("NXP", ignoreCase = true)
        }

        if (nxp == null) {
            log.append("NXP Volume: NOT FOUND\n")
            status.text = buildHeader() + "\n\n" + log
            return
        }

        log.append("NXP Volume descriptor FOUND\n")
        log.append("name=${nxp.name}\n")
        log.append("implementor=${nxp.implementor}\n")
        log.append("mode=${nxp.connectMode}\n")
        log.append("type=${nxp.type}\n")
        log.append("uuid=${nxp.uuid}\n\n")

        log.append("--- TEST A: hidden constructor visibility ---\n")
        val ctor = try {
            AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java,
                UUID::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { it.isAccessible = true }
        } catch (e: Throwable) {
            log.append("Constructor access FAILED: ${describeThrowable(e)}\n")
            log.append("Conclusion: Android hidden-API policy blocks generic private-effect creation.\n")
            status.text = buildHeader() + "\n\n" + log
            return
        }
        log.append("Constructor reflection: AVAILABLE\n\n")

        log.append("--- TEST B: NXP Volume on session 0 ---\n")
        try {
            val fx = ctor.newInstance(nxp.type, nxp.uuid, 0, 0) as AudioEffect
            probeEffect = fx
            log.append("CREATE session0: SUCCESS\n")
            log.append("hasControl=${safeBool { fx.hasControl() }} enabled=${safeBool { fx.enabled }} id=${safeInt { fx.id }}\n")
            log.append("NOTE: no private parameters were read or written.\n")
            try { fx.release() } catch (_: Throwable) {}
            probeEffect = null
        } catch (e: Throwable) {
            log.append("CREATE session0: FAILED\n")
            log.append("${describeThrowable(e)}\n")
        }
        log.append("\n")

        log.append("--- TEST C: create FineVolume-owned AudioTrack session ---\n")
        val ownSession: Int
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            probeTrack = track
            ownSession = track.audioSessionId
            log.append("AudioTrack: SUCCESS, session=$ownSession, state=${track.state}\n")
        } catch (e: Throwable) {
            log.append("AudioTrack: FAILED ${describeThrowable(e)}\n")
            status.text = buildHeader() + "\n\n" + log
            return
        }

        log.append("\n--- CONTROL: standard Equalizer on own session ---\n")
        try {
            val eq = Equalizer(0, ownSession)
            probeEq = eq
            log.append("Equalizer own-session: SUCCESS\n")
            log.append("bands=${eq.numberOfBands}, hasControl=${safeBool { eq.hasControl() }}\n")
        } catch (e: Throwable) {
            log.append("Equalizer own-session: FAILED ${describeThrowable(e)}\n")
        }

        log.append("\n--- TEST D: NXP Volume on own session ---\n")
        try {
            val fx = ctor.newInstance(nxp.type, nxp.uuid, 0, ownSession) as AudioEffect
            probeEffect = fx
            log.append("NXP own-session: SUCCESS\n")
            log.append("hasControl=${safeBool { fx.hasControl() }} enabled=${safeBool { fx.enabled }} id=${safeInt { fx.id }}\n")
            log.append("No parameter writes performed.\n")
        } catch (e: Throwable) {
            log.append("NXP own-session: FAILED\n")
            log.append("${describeThrowable(e)}\n")
        }

        log.append("\n===== INTERPRETATION =====\n")
        log.append("如果 session0 失败但 own-session 成功：效果器存在且可用于 App 自己的音频，但不能接管全局输出。\n")
        log.append("如果两者都失败：NXP 私有效果器对普通第三方 App 基本不可用。\n")
        log.append("如果 session0 成功：才值得进入下一阶段研究参数协议。\n")

        status.text = buildHeader() + "\n\n" + log
    }

    private fun buildHeader(): String {
        val outputs = try { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { emptyArray() }
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
        val music = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { -1 }
        val max = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { -1 }
        return "蓝牙输出：\n$btText\n\n系统媒体音量：$music / $max"
    }

    private fun refreshStatus(message: String) {
        val effects = try { AudioEffect.queryEffects() ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        val nxp = effects.firstOrNull {
            it.name.contains("volume", true) && it.implementor.contains("NXP", true)
        }
        status.text = buildHeader() + "\n\n" +
            "NXP Volume：${if (nxp != null) "已发现" else "未发现"}\n" +
            "状态：$message"
    }

    private fun describeThrowable(t: Throwable): String {
        var x: Throwable = t
        if (x is InvocationTargetException && x.targetException != null) x = x.targetException
        return "${x.javaClass.name}: ${x.message ?: "(no message)"}"
    }

    private inline fun safeBool(block: () -> Boolean): String = try { block().toString() } catch (e: Throwable) { "ERR:${e.javaClass.simpleName}" }
    private inline fun safeInt(block: () -> Int): String = try { block().toString() } catch (e: Throwable) { "ERR:${e.javaClass.simpleName}" }

    private fun releaseProbeResources() {
        try { probeEffect?.release() } catch (_: Throwable) {}
        probeEffect = null
        try { probeEq?.release() } catch (_: Throwable) {}
        probeEq = null
        try { probeTrack?.release() } catch (_: Throwable) {}
        probeTrack = null
    }

    override fun onDestroy() {
        releaseProbeResources()
        super.onDestroy()
    }
}
