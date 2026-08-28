package com.finevolume

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity:Activity(){private lateinit var status:TextView;private lateinit var detail:TextView;private lateinit var button:Button;private var remote:IBinder?=null;@Volatile private var pending=false
private val recv=Shizuku.OnBinderReceivedListener{refresh("Shizuku 已连接")};private val dead=Shizuku.OnBinderDeadListener{remote=null;refresh("Shizuku 已断开")};private val args by lazy{Shizuku.UserServiceArgs(ComponentName(packageName,AvrcpUserService::class.java.name)).daemon(false).processNameSuffix("audiocontrol").debuggable(true).version(12)}
private val conn=object:ServiceConnection{override fun onServiceConnected(n:ComponentName,b:IBinder){remote=b;refresh("UserService 已连接");if(pending){pending=false;run()}};override fun onServiceDisconnected(n:ComponentName){remote=null;refresh("UserService 已断开")}}
override fun onCreate(b:Bundle?){super.onCreate(b);val sc=ScrollView(this);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(36,54,36,54)};root.addView(TextView(this).apply{text="FineVolume v0.11.0";textSize=28f});root.addView(TextView(this).apply{text="Bluetooth Device Volume 深度探测版";textSize=16f});status=TextView(this).apply{textSize=15f;setPadding(0,20,0,15)};root.addView(status);button=Button(this).apply{text="一键探测蓝牙设备级音量并生成报告";setOnClickListener{start()}};root.addView(button);detail=TextView(this).apply{textSize=13f;setTextIsSelectable(true);setPadding(0,20,0,20);text="本版集中分析 VolumeInfo、当前媒体设备和 getDeviceVolume。只有确认存在高于 15 档的独立设备音量范围，后续版本才会启用可恢复写入。当前为安全探测，不盲写未知参数。"};root.addView(detail);root.addView(TextView(this).apply{text="保持 Shizuku 运行并连接蓝牙耳机。无需手动设置特定音量；若当前为 0，程序自动只读。完成后发送 下载/FineVolume/ 中的 v0.11.0 报告。";textSize=14f});sc.addView(root);setContentView(sc);Shizuku.addBinderReceivedListenerSticky(recv);Shizuku.addBinderDeadListener(dead);refresh("v0.11.0 已启动")}
private fun refresh(m:String){val ok=try{Shizuku.pingBinder()}catch(_:Throwable){false};val p=if(ok)try{if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"}catch(_:Throwable){"ERR"}else"N/A";status.text="Shizuku：${if(ok)"CONNECTED" else "NOT CONNECTED"}\n权限：$p\nUserService：${if(remote?.pingBinder()==true)"CONNECTED" else "NOT CONNECTED"}\n状态：$m"}
private fun start(){if(!try{Shizuku.pingBinder()}catch(_:Throwable){false}){detail.text="Shizuku 未连接";return};if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){detail.text="FineVolume 未获得 Shizuku 授权";return};button.isEnabled=false;if(remote?.pingBinder()==true){run()}else{pending=true;try{Shizuku.bindUserService(args,conn)}catch(e:Throwable){pending=false;button.isEnabled=true;detail.text="启动失败：${e.message}"}}}
private fun run(){val b=remote?:return;Thread{val d=Parcel.obtain();val r=Parcel.obtain();val text=try{b.transact(AvrcpUserService.TX_RUN_ALL,d,r,0);r.readException();r.readString()?:"empty report"}catch(e:Throwable){"FAILED ${e.javaClass.name}: ${e.message}"}finally{d.recycle();r.recycle()};val saved=save(text);runOnUiThread{detail.text=text+"\n\n"+saved;button.isEnabled=true;refresh("v0.11.0 完成")}}.start()}
private fun save(t:String):String { return try{val n="FineVolume-TestReport-v0.11.0-${System.currentTimeMillis()}.txt";val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,n);put(MediaStore.Downloads.MIME_TYPE,"text/plain");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FineVolume")};val u=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:return "保存失败";val out=contentResolver.openOutputStream(u)?:return "保存失败";out.use{it.write(t.toByteArray())};"已保存：下载/FineVolume/$n"}catch(e:Throwable){"保存失败：${e.message}"} }
override fun onDestroy(){Shizuku.removeBinderReceivedListener(recv);Shizuku.removeBinderDeadListener(dead);super.onDestroy()}}
