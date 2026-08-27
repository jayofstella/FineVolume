package com.finevolume

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AvrcpUserService(private val context: Context) : Binder() {
    companion object { const val TX_STATUS=FIRST_CALL_TRANSACTION+1; const val TX_RUN_ALL=FIRST_CALL_TRANSACTION+2 }
    @Volatile private var stateMessage="v0.9.0 UserService ready"
    override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean=when(code){TX_STATUS->{reply?.writeNoException();reply?.writeString(buildQuickStatus());true};TX_RUN_ALL->{val r=runProbe();reply?.writeNoException();reply?.writeString(r);true};else->super.onTransact(code,data,reply,flags)}
    private fun getService(name:String):IBinder?=try{val c=Class.forName("android.os.ServiceManager");val m=c.getDeclaredMethod("getService",String::class.java);m.isAccessible=true;m.invoke(null,name) as? IBinder}catch(_:Throwable){null}
    private fun runShell(command:String,timeoutSec:Long=5,maxChars:Int=16000):String=try{val p=ProcessBuilder("sh","-c",command).redirectErrorStream(true).start();val done=p.waitFor(timeoutSec,TimeUnit.SECONDS);if(!done){p.destroyForcibly();"TIMEOUT: $command"}else{val t=BufferedReader(InputStreamReader(p.inputStream)).use{it.readText()};"exit=${p.exitValue()}\n"+if(t.length>maxChars)t.take(maxChars)+"\n...[TRUNCATED]" else t}}catch(e:Throwable){"ERROR ${e.javaClass.name}: ${e.message}"}
    private fun invokeAudio(method:String,vararg args:Any?):Any?{val binder=getService("audio")?:throw IllegalStateException("audio binder missing");val stub=Class.forName("android.media.IAudioService\$Stub");val ai=stub.getDeclaredMethod("asInterface",IBinder::class.java);ai.isAccessible=true;val svc=ai.invoke(null,binder);var last:Throwable?=null;for(m in svc.javaClass.methods.filter{it.name==method&&it.parameterCount==args.size})try{m.isAccessible=true;return m.invoke(svc,*args)}catch(e:Throwable){last=e};throw IllegalStateException("$method/${args.size} unavailable",last)}
    private fun music()=(invokeAudio("getStreamVolume",3) as Number).toInt()
    private fun methods():String=try{val b=getService("audio")?:return "audio binder missing";val stub=Class.forName("android.media.IAudioService\$Stub");val ai=stub.getDeclaredMethod("asInterface",IBinder::class.java);ai.isAccessible=true;val svc=ai.invoke(null,b);svc.javaClass.methods.map{m->m.name+"("+m.parameterTypes.joinToString(","){it.simpleName}+")"}.filter{it.contains("volume",true)||it.contains("atten",true)||it.contains("gain",true)||it.contains("device",true)}.distinct().sorted().joinToString("\n")}catch(e:Throwable){"ERROR ${e.javaClass.name}:${e.message}"}
    private fun policy():String=runShell("dumpsys media.audio_policy | grep -i -E 'AUDIO_DEVICE_OUT_BLUETOOTH_A2DP|Volume:|gain|atten|curve|volume index' | head -n 220",6,16000)
    private fun flinger():String=runShell("dumpsys media.audio_flinger | grep -i -E 'A2DP|Bluetooth|volume|gain|track|mixer' | head -n 220",6,16000)
    private fun commands():String=runShell("for x in media_session audio media.audio_policy media.audio_flinger; do echo ====CMD:$x====; cmd $x help 2>&1 | head -n 80; done",8,14000)
    private fun services():String=runShell("service list | grep -i -E 'audio|bluetooth|media'",4,6000)
    private fun runProbe():String{stateMessage="v0.9.0 fine attenuation probe running";val sb=StringBuilder();fun s(x:String){sb.append("\n===== $x =====\n")};sb.append("FineVolume-TestReport v0.9.0\nuid=${Process.myUid()} pid=${Process.myPid()} timestampMs=${System.currentTimeMillis()}\nPurpose: find a non-root controllable attenuation/gain layer below STREAM_MUSIC without changing audible volume during discovery.\n");s("PRECHECK");sb.append(runShell("id",2,2000)).append('\n');sb.append("musicIndex=${try{music()}catch(_:Throwable){-1}}\n");sb.append("audio=${getService("audio")?.interfaceDescriptor}\npolicy=${getService("media.audio_policy")?.interfaceDescriptor}\n");s("AUDIO BINDER METHODS");sb.append(methods()).append('\n');s("AVAILABLE COMMAND SURFACES");sb.append(commands()).append('\n');s("RELEVANT SERVICES");sb.append(services()).append('\n');s("AUDIO POLICY SNAPSHOT");sb.append(policy()).append('\n');s("AUDIO FLINGER SNAPSHOT");sb.append(flinger()).append('\n');s("SAFETY RESULT");sb.append("No undocumented raw Binder transaction was sent. No volume index was changed. This probe only inventories callable gain/attenuation surfaces before the next targeted control test.\n");stateMessage="v0.9.0 probe complete; volume unchanged";return sb.toString()}
    private fun buildQuickStatus()="FineVolume v0.9.0\nuid=${Process.myUid()} pid=${Process.myPid()}\nstate=$stateMessage\naudio=${if(getService("audio")!=null)"FOUND" else "NOT FOUND"}\nmedia.audio_policy=${if(getService("media.audio_policy")!=null)"FOUND" else "NOT FOUND"}"
    @Suppress("unused") fun destroy(){System.exit(0)}
}
