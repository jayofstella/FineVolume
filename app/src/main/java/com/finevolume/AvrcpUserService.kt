package com.finevolume

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

class AvrcpUserService(private val context: Context) : Binder() {
    companion object { const val TX_STATUS=FIRST_CALL_TRANSACTION+1; const val TX_RUN_ALL=FIRST_CALL_TRANSACTION+2 }
    @Volatile private var stateMessage="v0.10.0 UserService ready"
    override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean=when(code){TX_STATUS->{reply?.writeNoException();reply?.writeString(status());true};TX_RUN_ALL->{val r=runTest();reply?.writeNoException();reply?.writeString(r);true};else->super.onTransact(code,data,reply,flags)}
    private fun getService(name:String):IBinder?=try{val c=Class.forName("android.os.ServiceManager");val m=c.getDeclaredMethod("getService",String::class.java);m.isAccessible=true;m.invoke(null,name) as? IBinder}catch(_:Throwable){null}
    private fun audioObj():Any{val b=getService("audio")?:error("audio binder missing");val s=Class.forName("android.media.IAudioService\$Stub");val m=s.getDeclaredMethod("asInterface",IBinder::class.java);m.isAccessible=true;return m.invoke(null,b)}
    private fun call(name:String,vararg args:Any?):Any?{val a=audioObj();var last:Throwable?=null;for(m in a.javaClass.methods.filter{it.name==name&&it.parameterCount==args.size})try{m.isAccessible=true;return m.invoke(a,*args)}catch(e:Throwable){last=e};throw IllegalStateException("$name/${args.size} failed",last)}
    private fun safe(name:String,vararg args:Any?)=try{"OK: "+(call(name,*args)?.toString()?:"null")}catch(e:Throwable){"ERROR ${e.javaClass.simpleName}: ${e.cause?.message?:e.message}"}
    private fun music()=(call("getStreamVolume",3) as Number).toInt()
    private fun real()=safe("getStreamRealVolume",3)
    private fun app(pkg:String)=safe("getAppMediaVolume",pkg)
    private fun runTest():String{stateMessage="v0.10.0 controlled capability test running";val original=try{music()}catch(_:Throwable){-1};val sb=StringBuilder();fun sec(n:String){sb.append("\n===== ").append(n).append(" =====\n")};sb.append("FineVolume-TestReport v0.10.0\nuid=${Process.myUid()} pid=${Process.myPid()} timestampMs=${System.currentTimeMillis()}\nPurpose: validate vendor real/app media volume surfaces while keeping STREAM_MUSIC index unchanged.\n")
        sec("BASELINE");sb.append("musicIndex=$original\ngetStreamRealVolume(3)=${real()}\ngetAppMediaVolume(com.finevolume)=${app("com.finevolume")}\n")
        sec("SIGNATURE DISCOVERY");val a=audioObj();a.javaClass.methods.filter{it.name.contains("AppMediaVolume",true)||it.name.contains("StreamRealVolume",true)}.sortedBy{it.name}.forEach{m->sb.append(m.name).append('(').append(m.parameterTypes.joinToString(","){it.name}).append(") -> ").append(m.returnType.name).append('\n')}
        sec("CONTROL SURFACE SEARCH");a.javaClass.methods.filter{(it.name.contains("volume",true)||it.name.contains("gain",true)||it.name.contains("atten",true))&&(it.name.startsWith("set")||it.name.startsWith("adjust"))}.sortedBy{it.name}.forEach{m->sb.append(m.name).append('(').append(m.parameterTypes.joinToString(","){it.name}).append(")\n")}
        sec("SAFE READBACK A/B");sb.append("Before index=$original real=${real()} app=${app("com.finevolume")}\n");Thread.sleep(1200);sb.append("After 1.2s index=${try{music()}catch(_:Throwable){-1}} real=${real()} app=${app("com.finevolume")}\n")
        sec("RESULT");val finalIndex=try{music()}catch(_:Throwable){-1};sb.append("originalIndex=$original finalIndex=$finalIndex unchanged=${original==finalIndex}\nNo undocumented write was issued in v0.10.0. This run resolves exact vendor method signatures and numeric domains first; a write test will only be enabled when a matching setter is actually exposed.\n");stateMessage="v0.10.0 complete; stream index unchanged";return sb.toString()}
    private fun status()="FineVolume v0.10.0\nuid=${Process.myUid()} pid=${Process.myPid()}\nstate=$stateMessage\nSTREAM_MUSIC=${try{music()}catch(_:Throwable){-1}}\nreal=${real()}"
    @Suppress("unused") fun destroy(){System.exit(0)}
}
