package com.cseini.byd.karaoke

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.byd.minikaraoke.IConnectionStateListener
import com.byd.minikaraoke.IKaraokeModeListener
import com.byd.minikaraoke.IMicrophoneService
import com.byd.minikaraoke.ISettingListener

/**
 * BYD 내장 노래방(com.byd.minikaraoke)의 exported 마이크 서비스(byd.intent.action.MICROPHONE_SERVICE)에
 * bind 해서, USB 권한을 가진 BYD(시스템 앱)가 대신 읽은 마이크 버튼 이벤트를 콜백으로 받는다.
 * 씨라이언7 등 앱이 USB HID 를 직접 못 여는 유닛에서 마이크 버튼을 쓰는 유일한 경로.
 *
 * 현재는 조사 단계 — 어떤 콜백이 어떤 버튼(마이크/볼륨업/볼륨다운)에, 어떤 패턴(단발/연속/토글)으로
 * 오는지 D1 로 계측한다. 마이크 버튼 신호를 확정한 뒤 onMicButton 트리거를 붙인다.
 */
class BydMicBridge(private val activity: Activity, private val onMicButton: () -> Unit) {

    private var service: IMicrophoneService? = null
    @Volatile private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IMicrophoneService.Stub.asInterface(binder)
            service = svc
            CrashLog.event(activity, "bydmic 연결됨 $name")
            runCatching { svc.registerKaraokeModeListener(modeListener) }
                .onFailure { CrashLog.event(activity, "bydmic mode등록실패 ${it.message}") }
            runCatching { svc.registerSettingListener(settingListener) }
                .onFailure { CrashLog.event(activity, "bydmic setting등록실패 ${it.message}") }
            runCatching { svc.registerMicrophoneConnectionStateListener(connListener) }
            runCatching { CrashLog.event(activity, "bydmic support=" + svc.isBuiltInMicKaraokeModeSupport + " connState=" + svc.micConnectionState) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            CrashLog.event(activity, "bydmic 끊김")
        }
    }

    private val modeListener = object : IKaraokeModeListener.Stub() {
        override fun onKaraokeModeChange(i: Int) { CrashLog.event(activity, "bydmic MODE=$i") }
        override fun onKaraokeModeStartFailed(i: Int, s: String?) { CrashLog.event(activity, "bydmic MODEFAIL=$i $s") }
    }

    private val settingListener = object : ISettingListener.Stub() {
        override fun onMicVolumeChanged(i: Int) { CrashLog.event(activity, "bydmic VOL=$i") }
        override fun onEffectChanged(i: Int) { CrashLog.event(activity, "bydmic EFFECT=$i") }
        override fun onReverberationChanged(i: Int) { CrashLog.event(activity, "bydmic REVERB=$i") }
    }

    private val connListener = object : IConnectionStateListener.Stub() {
        override fun onMicrophoneConnectionStateChanged(z: Boolean) { CrashLog.event(activity, "bydmic CONN=$z") }
    }

    /** com.byd.minikaraoke → 안 되면 com.byd.sing 순으로 bind 시도. */
    fun bind() {
        if (bound) return
        for (pkg in listOf("com.byd.minikaraoke", "com.byd.sing")) {
            val intent = Intent("byd.intent.action.MICROPHONE_SERVICE").setPackage(pkg)
            val ok = runCatching { activity.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            CrashLog.event(activity, "bydmic bind $pkg = $ok")
            if (ok) { bound = true; return }   // 콜백 미도착이어도 unbind() 가 unbindService 와 짝을 이루도록 여기서 세운다
        }
        CrashLog.event(activity, "bydmic bind실패 — 서비스 없음")
    }

    fun unbind() {
        if (!bound) return
        bound = false
        // AIDL 리스너들을 명시적으로 등록 해제 — onServiceDisconnected는 unbindService 호출 시 발화하지 않으므로
        // 이곳에서 원격 서비스의 리스너 목록에서 제거해야 함.
        service?.let { svc ->
            runCatching { svc.unregisterKaraokeModeListener(modeListener) }
                .onFailure { CrashLog.event(activity, "bydmic mode등록해제실패 ${it.message}") }
            runCatching { svc.unregisterSettingListener(settingListener) }
                .onFailure { CrashLog.event(activity, "bydmic setting등록해제실패 ${it.message}") }
            runCatching { svc.unregisterMicrophoneConnectionStateListener(connListener) }
                .onFailure { CrashLog.event(activity, "bydmic conn등록해제실패 ${it.message}") }
        }
        service = null
        runCatching { activity.unbindService(conn) }
    }
}
