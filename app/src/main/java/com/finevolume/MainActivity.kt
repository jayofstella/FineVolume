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
    private val binderDead = Shizuku.OnBinderDeadListener { remote = null; refresh("Shizuku Binder 已断开") }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, AvrcpUserService::class.java.name))
            .daemon(false).processNameSuffix("audiocontrol").debuggable(true).version(9)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            refresh("Audio Control UserService 已连接")
            if (pendingAutoRun) { pendingAutoRun = false; runFullTest() } else queryStatus()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; refresh("Audio Control UserService 已断开") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refresh("v0.8.0 已启动；等待一次控制实验")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36,54,36,54) }
        root.addView(TextView(this).apply { text="FineVolume v0.8.0"; textSize=28f })
        root.addView(TextView(this).apply { text="AudioService / AudioPolicy 实际控制实验版 · 自动恢复原音量"; textSize=16f; setPadding(0,6,0,20) })
        status = TextView(this).apply { textSize=15f; setTextIsSelectable(true) }; root.addView(status)
        runButton = Button(this).apply { text="一键执行安全 A/B 控制实验并生成报告"; setOnClickListener { startOneClickTest() } }; root.addView(runButton)
        root.addView(Button(this).apply { text="只读取当前服务状态"; setOnClickListener { queryStatus() } })
        detail = TextView(this).apply {
            textSize=13f; setTextIsSelectable(true); setPadding(0,18,0,20)
            text="本版不再做大范围扫描。只验证已经由 v0.7.0 找到的 AudioService / AudioPolicy 控制链。"
        }; root.addView(detail)
        root.addView(TextView(this).apply {
            text="测试方式：\n1. 保持 Shizuku 正在运行；\n2. 连接蓝牙耳机并播放持续音乐；\n3. 建议系统媒体音量放在 5～8/15，便于听出 A/B 差异；\n4. 点一次上方按钮；\n5. 软件只在当前档位与相邻一档之间短暂切换，然后自动恢复原档位；\n6. 把 FineVolume-TestReport-v0.8.0 txt 发回来。\n\n本版不会测试最低音量，也不会猜测未经确认的 raw Binder transaction。"
            textSize=14f
        })
        scroll.addView(root); setContentView(scroll)
    }

    private fun safePing() = try { Shizuku.pingBinder() } catch (_:Throwable){false}
    private fun refresh(message:String) {
        val alive=safePing(); val uid=if(alive) try{Shizuku.getUid().toString()}catch(_:Throwable){"ERR"}else"N/A"
        val permission=if(alive) try{if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"}catch(_:Throwable){"ERR"}else"N/A"
        status.text="Shizuku Binder：${if(alive)"CONNECTED" else "NOT CONNECTED"}\nShizuku UID：$uid\nFineVolume permission：$permission\nAudio Control UserService：${if(remote?.pingBinder()==true)"CONNECTED" else "NOT CONNECTED"}\n\n状态：$message\n"
    }

    private fun startOneClickTest() {
        if(!safePing()){detail.text="Shizuku 当前未连接。";refresh("无法开始：Shizuku 未连接");return}
        if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){detail.text="FineVolume 尚未获得 Shizuku 授权。";refresh("无法开始：未授权");return}
        runButton.isEnabled=false; detail.text="正在启动 v0.8.0 控制实验……完成后会自动恢复原音量。"; refresh("控制实验启动中")
        val b=remote
        if(b!=null&&b.pingBinder()){runFullTest();return}
        pendingAutoRun=true
        try{Shizuku.bindUserService(userServiceArgs,userServiceConnection)}catch(e:Throwable){pendingAutoRun=false;runButton.isEnabled=true;detail.text="bindUserService ERROR: ${e.javaClass.name}: ${e.message}";refresh("UserService 启动失败")}
    }

    private fun runFullTest() {
        val b=remote
        if(b==null||!b.pingBinder()){runButton.isEnabled=true;detail.text="UserService 未连接。";refresh("实验中止");return}
        detail.text="正在执行当前音量 → 相邻一档 → 恢复当前音量，并记录 AudioPolicy 状态……";refresh("A/B 控制实验运行中")
        Thread {
            val result=transactForString(b,AvrcpUserService.TX_RUN_ALL); val saved=saveReport(result)
            runOnUiThread{detail.text=result+"\n\n===== REPORT FILE =====\n"+saved;runButton.isEnabled=true;refresh("v0.8.0 实验完成；原音量已尝试恢复")}
        }.start()
    }

    private fun queryStatus() {
        val b=remote ?: run { detail.text="UserService 尚未连接。直接点一键实验即可自动启动。";return }
        Thread{val r=transactForString(b,AvrcpUserService.TX_STATUS);runOnUiThread{detail.text=r;refresh("状态已读取")}}.start()
    }

    private fun transactForString(binder:IBinder,code:Int):String {
        val data=Parcel.obtain();val reply=Parcel.obtain()
        return try{val ok=binder.transact(code,data,reply,0);if(!ok)return "FAILED: Binder transact returned false";reply.readException();reply.readString()?:"FAILED: empty result"}catch(e:Throwable){"FAILED: ${e.javaClass.name}: ${e.message}"}finally{data.recycle();reply.recycle()}
    }

    private fun saveReport(report:String):String = try {
        val fileName="FineVolume-TestReport-v0.8.0-${System.currentTimeMillis()}.txt"
        val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,fileName);put(MediaStore.Downloads.MIME_TYPE,"text/plain");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FineVolume")}
        val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:return "报告生成失败：MediaStore insert 返回 null"
        contentResolver.openOutputStream(uri)?.use{it.write(report.toByteArray(Charsets.UTF_8));it.flush()}?:return "报告生成失败：无法打开输出流"
        "已保存：下载/FineVolume/$fileName\n请把这个 txt 文件发回来。"
    } catch(e:Throwable){"报告保存失败：${e.javaClass.name}: ${e.message}"}

    override fun onDestroy(){Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);super.onDestroy()}
}
