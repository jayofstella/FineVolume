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
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.pow

class MainActivity : Activity() {
    private lateinit var audio: AudioManager
    private lateinit var status: TextView
    private var volumeEffect: AudioEffect? = null
    private var commandMethod: java.lang.reflect.Method? = null
    private var currentDb: Float = 0f

    companion object {
        private val VOLUME_IMPL_UUID: UUID = UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b")
        private const val EFFECT_CMD_SET_VOLUME = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        buildUi()
        requestBluetoothPermissionIfNeeded()
        refresh("等待测试")
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
        if (requestCode == 10) refresh("蓝牙权限已更新")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        root.addView(TextView(this).apply {
            text = "FineVolume v0.2.2 Experimental"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "AOSP / NXP Volume Command 实验 · 无 Root"
            textSize = 15f
            setPadding(0, 6, 0, 20)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "① 初始化 session 0 Volume Effect"
            setOnClickListener { initializeVolumeEffect() }
        })

        root.addView(TextView(this).apply {
            text = "\n实际衰减测试（建议系统音量 6～8/15）"
            textSize = 20f
        })

        listOf(-3f, -6f, -12f, -18f).forEach { db ->
            root.addView(Button(this).apply {
                text = "设置 ${db.toInt()} dB"
                setOnClickListener { sendVolumeCommand(db) }
            })
        }

        root.addView(Button(this).apply {
            text = "恢复 0 dB"
            setOnClickListener { sendVolumeCommand(0f) }
        })

        root.addView(Button(this).apply {
            text = "紧急关闭 / 释放 Effect"
            setOnClickListener {
                emergencyRelease()
                refresh("已关闭并释放 Volume Effect")
            }
        })

        root.addView(Button(this).apply {
            text = "刷新蓝牙与系统状态"
            setOnClickListener { refresh("状态已刷新") }
        })

        root.addView(TextView(this).apply {
            text = "\n测试步骤：\n" +
                "1. 连接蓝牙耳机并持续播放熟悉的音乐。\n" +
                "2. 系统音量建议使用 6～8/15，便于听出差异。\n" +
                "3. 先点击①初始化。只有状态显示 CREATE=SUCCESS、command API=AVAILABLE 后再继续。\n" +
                "4. 先点 -3 dB；若音量明显下降，再试 -6/-12 dB。\n" +
                "5. 若出现异常，立即点“紧急关闭”。\n\n" +
                "技术说明：该 NXP Volume 实际是 AOSP LVM Volume Effect。源码表明它接收 EFFECT_CMD_SET_VOLUME，而不是普通 setParameter。\n" +
                "本版通过 Android 隐藏的 AudioEffect.command() 发送标准 Volume 命令；不写任何未知私有参数。"
            textSize = 14f
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun initializeVolumeEffect() {
        emergencyRelease()
        val log = StringBuilder()
        try {
            val descriptor = (AudioEffect.queryEffects() ?: emptyArray()).firstOrNull {
                it.uuid == VOLUME_IMPL_UUID ||
                    (it.name.equals("Volume", true) && it.implementor.contains("NXP", true))
            }
            if (descriptor == null) {
                refresh("NXP/AOSP Volume descriptor NOT FOUND")
                return
            }

            log.append("descriptor=${descriptor.name} / ${descriptor.implementor}\n")
            log.append("type=${descriptor.type}\n")
            log.append("uuid=${descriptor.uuid}\n")

            val ctor = AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java,
                UUID::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { it.isAccessible = true }

            val fx = ctor.newInstance(descriptor.type, descriptor.uuid, 1000, 0) as AudioEffect
            volumeEffect = fx
            log.append("CREATE session0=SUCCESS id=${fx.id} hasControl=${fx.hasControl()}\n")

            val enabledResult = fx.setEnabled(true)
            log.append("setEnabled=$enabledResult enabled=${fx.enabled}\n")

            val method = AudioEffect::class.java.getMethod(
                "command",
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java
            )
            commandMethod = method
            log.append("hidden command API=AVAILABLE\n")
            currentDb = 0f
            refresh(log.toString())
        } catch (e: Throwable) {
            val x = unwrap(e)
            emergencyRelease()
            refresh("初始化失败：${x.javaClass.name}: ${x.message}\n$log")
        }
    }

    private fun sendVolumeCommand(db: Float) {
        val fx = volumeEffect
        val method = commandMethod
        if (fx == null || method == null) {
            refresh("尚未初始化。请先点击①。")
            return
        }

        try {
            val gain = 10.0.pow(db / 20.0).coerceIn(0.0, 1.0)
            val q824 = (gain * (1 shl 24)).toLong().coerceIn(0L, 0x1000000L).toInt()

            val cmd = ByteBuffer.allocate(8)
                .order(ByteOrder.nativeOrder())
                .putInt(q824)
                .putInt(q824)
                .array()
            val reply = ByteArray(8)

            val result = method.invoke(fx, EFFECT_CMD_SET_VOLUME, cmd, reply) as Int
            val rb = ByteBuffer.wrap(reply).order(ByteOrder.nativeOrder())
            val replyL = rb.int
            val replyR = rb.int
            currentDb = db

            refresh(
                "VOLUME COMMAND SENT\n" +
                    "target=%.1f dB\n".format(db) +
                    "linear=%.6f  Q8.24=0x%08X\n".format(gain, q824) +
                    "commandResult=$result\n" +
                    "replyL=0x%08X replyR=0x%08X\n".format(replyL, replyR) +
                    "effect hasControl=${fx.hasControl()} enabled=${fx.enabled}\n" +
                    "请以耳机实际听感为准。"
            )
        } catch (e: Throwable) {
            val x = unwrap(e)
            refresh("Volume command 失败：${x.javaClass.name}: ${x.message}")
        }
    }

    private fun refresh(message: String) {
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
        val fx = volumeEffect
        val fxState = if (fx == null) "OFF" else try {
            "READY id=${fx.id}, control=${fx.hasControl()}, enabled=${fx.enabled}, target=${currentDb} dB"
        } catch (_: Throwable) { "ERROR" }

        status.text = "蓝牙输出：\n$btText\n\n" +
            "系统媒体音量：$music / $max\n" +
            "Android：${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\n" +
            "targetSdk：${applicationInfo.targetSdkVersion}\n" +
            "Volume Effect：$fxState\n\n" +
            "状态：\n$message\n"
    }

    private fun emergencyRelease() {
        volumeEffect?.let { fx ->
            try {
                val method = commandMethod ?: AudioEffect::class.java.getMethod(
                    "command",
                    Int::class.javaPrimitiveType,
                    ByteArray::class.java,
                    ByteArray::class.java
                )
                val unity = 1 shl 24
                val cmd = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                    .putInt(unity).putInt(unity).array()
                try { method.invoke(fx, EFFECT_CMD_SET_VOLUME, cmd, ByteArray(8)) } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            try { fx.setEnabled(false) } catch (_: Throwable) {}
            try { fx.release() } catch (_: Throwable) {}
        }
        volumeEffect = null
        commandMethod = null
        currentDb = 0f
    }

    private fun unwrap(t: Throwable): Throwable {
        var x = t
        if (x is InvocationTargetException && x.targetException != null) x = x.targetException
        return x
    }

    override fun onDestroy() {
        emergencyRelease()
        super.onDestroy()
    }
}
