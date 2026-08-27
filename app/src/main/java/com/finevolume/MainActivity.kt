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

class MainActivity:Activity(){private lateinit var status:TextView;private lateinit var detail:TextView;private lateinit var runButton:Button;private var remote:IBinder?=null;@Volatile private var pending=false
private val received=Shizuku.OnBinderReceivedListener{refresh("收到 Shizuku Binder")};private val dead=Shizuku.OnBinderDeadListener{remote=null;refresh("Shizuku Binder 已断开")};private val args by lazy{Shizuku.UserServiceArgs(ComponentName(packageName,AvrcpUserService::class.java.name)).daemon(false).processNameSuffix("audiocontrol").debuggable(true).version(10)}
private val conn=object:ServiceConnection{override fun onServiceConnected(n:ComponentName,b:IBinder){remote=b;refresh("Audio Control UserService 已连接");if(pending){pending=false;runProbe()}else query()};override fun onServiceDisconnected(n:ComponentName){remote=null;refresh("Audio Control UserService 已断开")}}
override fun onCreate(b:Bundle?){super.onCreate(b);buildUi();Shizuku.addBinderReceivedListenerSticky(received);Shizuku.addBinderDeadListener(dead);refresh("v0.9.0 已启动")}
private fun buildUi(){val s=ScrollView(this);val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(36,54,36,54)};r.addView(TextView(this).apply{text="FineVolume v0.9.0";textSize=28f});r.addView(TextView(this).apply{text="精细衰减能力探测版 · 本次不改变系统音量";textSize=16f;setPadding(0,6,0,20)});status=TextView(this).apply{textSize=15f;setTextIsSelectable(true)};r.addView(status);runButton=Button(this).apply{text="一键探测精细音量控制能力并生成报告";setOnClickListener{start()}};r.addView(runButton);r.addView(Button(this).apply{text="只读取当前服务状态";setOnClickListener{query()}});detail=TextView(this).apply{textSize=13f;setTextIsSelectable(true);setPadding(0,18,0,20);text="v0.8.0 已证明系统 0～15 A2DP 音量控制链可用。本版只寻找 0～15 之外的增益/衰减控制接口，不执行未知 Binder 写操作。"};r.addView(detail);r.addView(TextView(this).apply{text="测试：保持 Shizuku 运行并连接蓝牙耳机即可。点击一次探测按钮。本版不会执行 3→2→3，也不会改变当前媒体音量。完成后把 FineVolume-TestReport-v0.9.0 txt 发回来。";textSize=14f});s.addView(r);setContentView(s)}
private fun ping()=try{Shizuku.pingBinder()}catch(_:Throwable){false};private fun refresh(m:String){val a=ping();val uid=if(a)try{Shizuku.getUid().toString()}catch(_:Throwable){"ERR"}else"N/A";val p=if(a)try{if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"}catch(_:Throwable){"ERR"}else"N/A";status.text="Shizuku Binder：${if(a)"CONNECTED" else "NOT CONNECTED"}\nShizuku UID：$uid\nFineVolume permission：$p\nAudio Control UserService：${if(remote?.pingBinder()==true)"CONNECTED" else "NOT CONNECTED"}\n\n状态：$m\n"}
private fun start(){if(!ping()){detail.text="Shizuku 当前未连接。";return};if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){detail.text="FineVolume 尚未获得 Shizuku 授权。";return};runButton.isEnabled=false;val b=remote;if(b!=null&&b.pingBinder()){runProbe();return};pending=true;try{Shizuku.bindUserService(args,conn)}catch(e:Throwable){pending=false;runButton.isEnabled=true;detail.text="启动失败：${e.javaClass.name}: ${e.message}"}}
private fun runProbe(){val b=remote;if(b==null||!b.pingBinder()){runButton.isEnabled=true;return};detail.text="正在读取 AudioService / AudioPolicy / AudioFlinger 可控接口……";Thread{val result=tx(b,AvrcpUserService.TX_RUN_ALL);val saved=save(result);runOnUiThread{detail.text=result+"\n\n"+saved;runButton.isEnabled=true;refresh("v0.9.0 探测完成；系统音量未改变")}}.start()}
private fun query(){val b=remote?:run{detail.text="UserService 尚未连接；点一键探测即可自动启动。";return};Thread{val x=tx(b,AvrcpUserService.TX_STATUS);runOnUiThread{detail.text=x}}.start()}
private fun tx(b:IBinder,c:Int):String{val d=Parcel.obtain();val r=Parcel.obtain();return try{if(!b.transact(c,d,r,0))return "FAILED transact";r.readException();r.readString()?:"FAILED empty"}catch(e:Throwable){"FAILED ${e.javaClass.name}: ${e.message}"}finally{d.recycle();r.recycle()}}
private fun save(report:String):String{try{val n="FineVolume-TestReport-v0.9.0-${System.currentTimeMillis()}.txt";val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,n);put(MediaStore.Downloads.MIME_TYPE,"text/plain");put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/FineVolume")};val u=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:return"报告保存失败";contentResolver.openOutputStream(u)?.use{it.write(report.toByteArray(Charsets.UTF_8))}?:return"报告保存失败";return"已保存：下载/FineVolume/$n"}catch(e:Throwable){return"报告保存失败：${e.message}"}}
override fun onDestroy(){Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);super.onDestroy()}}
