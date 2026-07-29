package com.cseini.byd.karaoke

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * USB 마이크 버튼을 HID 인터페이스에서 직접 읽는다(KeyEvent 로 안 올라오는 유닛 대응).
 * HID(class 3) 인터페이스만 claim → 오디오 인터페이스(볼륨 등)는 안 건드림.
 * 대상 버튼(리포트[5]=0x3C)을 길게 누르면 음성검색. 그 외 리포트는 로그만 남긴다(다른 버튼 코드 수집용).
 */
class UsbMicButtons(
    private val activity: AppCompatActivity,
    private val onAction: (Action) -> Unit,
) {
    /** 마이크 버튼으로 실행할 앱 동작. */
    enum class Action { VOICE, NEXT, STOP }

    companion object {
        private const val TAG = "karaoke-usb"
        private const val ACTION_PERM = "com.cseini.byd.karaoke.USB_PERM"
        private const val READ_TIMEOUT_MS = 300
        private const val DEBOUNCE_MS = 800L
        private const val LONG_PRESS_MS = 600L
        // 리포트[5] 버튼 코드(실측). 마이크 음성=0x3C, 볼륨=0x3D/0x3E(업/다운 가정, 반대면 스왑).
        private const val CODE_INDEX = 5
        private const val CODE_MIC = 0x3C
        private const val CODE_VOL_UP = 0x3D
        private const val CODE_VOL_DOWN = 0x3E
        // BYD 노래방 시스템(com.byd.minikaraoke) micevent 로 넘길 KEY_EVENT 값(디컴파일로 확인).
        private const val KEY_MIC_TOGGLE = 133   // 패널 토글(KEY_AUTO_VOICE)
        private const val KEY_VOL_UP = 134       // 마이크 볼륨 업
        private const val KEY_VOL_DOWN = 135     // 마이크 볼륨 다운
        private const val KARAOKE_PKG = "com.byd.minikaraoke"
        private const val KARAOKE_RECEIVER = "com.byd.minikaraoke.main.KaraokeReceiver"
        private const val MICEVENT_ACTION = "byd.intent.minikaraoke_micevent"
    }

    private val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    @Volatile private var running = false
    @Volatile private var held = false
    private var reader: Thread? = null
    private var lastTrigger = 0L
    private var lastPermReq = 0L
    private var registered = false
    private var notified = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 진단 화면용: 설정하면 장치 정보·버튼 신호(hex)가 그대로 전달된다(메인 스레드). */
    @Volatile var onRaw: ((String) -> Unit)? = null
    private fun raw(msg: String) { onRaw?.let { cb -> handler.post { cb(msg) } } }

    /** 같은 안내가 반복되지 않도록 1회만 표시(멱등 start 가 자주 불리므로). */
    private fun notifyOnce(msg: String) {
        if (notified) return
        notified = true
        activity.runOnUiThread { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show() }
    }
    private var pendingLong: Runnable? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != ACTION_PERM) return
            val dev: UsbDevice = i.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openAndRead(dev)
            else { Log.i(TAG, "USB 권한 거부: ${dev.productName}"); raw("USB 권한이 거부되었습니다") }
        }
    }

    /** 여러 번 호출해도 안전(멱등). 아직 권한이 없으면 요청, 권한 있으면 읽기 시작. */
    fun start() {
        if (conn != null) return   // 이미 읽는 중
        if (!registered) runCatching {
            activity.registerReceiver(permReceiver, IntentFilter(ACTION_PERM)); registered = true
        }
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            Log.i(TAG, "USB 장치 없음")
            notifyOnce("USB 마이크가 연결돼 있지 않습니다")
            raw("USB 장치가 없습니다 — 마이크를 꽂아주세요")
            return
        }
        devices.forEach {
            logDevice(it)
            raw("장치: ${it.productName ?: it.deviceName} vid=${it.vendorId} pid=${it.productId} ifaces=${it.interfaceCount}")
        }
        val cand = devices.firstOrNull { findHidInterrupt(it) != null } ?: run {
            Log.i(TAG, "HID 버튼 인터페이스 없음")
            notifyOnce("이 마이크는 버튼 제어를 지원하지 않습니다 (설정에서 꺼주세요)")
            raw("버튼(HID) 인터페이스가 없습니다 — 이 마이크는 버튼 신호를 USB 로 보내지 않습니다")
            return
        }
        if (usb.hasPermission(cand)) { openAndRead(cand); return }
        raw("USB 권한 요청 중 — 허용을 눌러주세요")
        // 권한 없음 → 요청. 단 최근 요청했으면(8초) 다이얼로그 스팸 방지로 스킵.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPermReq < 8000L) return
        lastPermReq = now
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            activity, 0, Intent(ACTION_PERM).setPackage(activity.packageName), flags
        )
        usb.requestPermission(cand, pi)
    }

    fun stop() {
        running = false
        held = false
        pendingLong?.let { handler.removeCallbacks(it) }; pendingLong = null
        reader?.let { runCatching { it.join(500) } }; reader = null
        iface?.let { i -> conn?.releaseInterface(i) }; iface = null
        conn?.let { runCatching { it.close() } }; conn = null
        if (registered) { runCatching { activity.unregisterReceiver(permReceiver) }; registered = false }
    }

    private fun logDevice(d: UsbDevice) {
        Log.i(TAG, "device ${d.deviceName} vid=${d.vendorId} pid=${d.productId} name=${d.productName} ifaces=${d.interfaceCount}")
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            Log.i(TAG, "  iface#$i class=${f.interfaceClass} sub=${f.interfaceSubclass} eps=${f.endpointCount}")
        }
    }

    private fun findHidInterrupt(d: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            for (j in 0 until f.endpointCount) {
                val e = f.getEndpoint(j)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN)
                    return f to e
            }
        }
        return null
    }

    private fun openAndRead(dev: UsbDevice) {
        val (hid, ep) = findHidInterrupt(dev) ?: run { Log.i(TAG, "HID 인터럽트 엔드포인트 없음"); return }
        val c = usb.openDevice(dev) ?: run { Log.i(TAG, "USB 장치 열기 실패"); return }
        if (!c.claimInterface(hid, true)) { Log.i(TAG, "HID claim 실패"); c.close(); return }
        conn = c; iface = hid
        Log.i(TAG, "USB 버튼 읽기 시작: ${dev.productName ?: dev.deviceName}")
        raw("읽기 시작: ${dev.productName ?: dev.deviceName} — 버튼을 눌러보세요")
        running = true
        reader = thread(name = "usb-mic") {
            val buf = ByteArray(ep.maxPacketSize.coerceIn(1, 64))
            var lastCode = -1
            var longFired = false
            var lastHex = ""
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (running) {
                val n = runCatching { c.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS) }.getOrDefault(-1)
                if (n <= 0) continue
                // 버튼별 실제 리포트: 시각 + 전체 hex. 눌러서 잡히는지·계속 유지되는지·자동뗌 여부를 본다.
                // (같은 리포트 반복은 생략 — 일부 장치는 주기적으로 동일 리포트를 보내 화면을 도배한다)
                val hex = (0 until n).joinToString(" ") { "%02x".format(buf[it]) }
                if (hex != lastHex) {
                    lastHex = hex
                    val line = "t=+%.1fs hex=".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000.0) + hex
                    Log.i(TAG, line)
                    raw(line)
                }
                val code = if (n > CODE_INDEX) buf[CODE_INDEX].toInt() and 0xFF else 0
                if (code == lastCode) continue
                val prev = lastCode
                lastCode = code

                // 어떤 버튼이든 손을 떼면 대기 중인 '길게 누름'은 취소.
                if (prev != 0 && prev != code) {
                    held = false
                    pendingLong?.let { handler.removeCallbacks(it) }; pendingLong = null
                    // 마이크 버튼을 짧게 눌렀다 뗀 경우 → 노래방 패널 토글(길게가 이미 걸렸으면 제외)
                    if (prev == CODE_MIC && !longFired) sendMicEvent(KEY_MIC_TOGGLE)
                }

                // 누름: 볼륨은 즉시 네이티브 동작(마이크 볼륨±) + 길게 누르면 앱 제어.
                when (code) {
                    CODE_MIC -> armLongPress(Action.VOICE) { longFired = true }
                    CODE_VOL_UP -> {
                        sendMicEvent(KEY_VOL_UP)
                        armLongPress(Action.NEXT) { longFired = true }
                    }
                    CODE_VOL_DOWN -> {
                        sendMicEvent(KEY_VOL_DOWN)
                        armLongPress(Action.STOP) { longFired = true }
                    }
                }
                if (code != 0) longFired = false
            }
        }
    }

    /** BYD 노래방 시스템에 micevent 를 보내 네이티브 동작(패널/마이크 볼륨)을 그대로 실행. */
    private fun sendMicEvent(keyEvent: Int) {
        activity.runOnUiThread {
            runCatching {
                val i = Intent(MICEVENT_ACTION)
                i.setClassName(KARAOKE_PKG, KARAOKE_RECEIVER)
                i.putExtra("android.intent.extra.KEY_EVENT", keyEvent)
                activity.sendBroadcast(i)
            }
        }
    }

    /** 버튼을 누른 상태로 LONG_PRESS_MS 가 지나면 앱 동작 실행(누르고 있는 동안만 유효). */
    private fun armLongPress(action: Action, onFired: () -> Unit) {
        held = true
        val r = Runnable { if (held) { onFired(); fireAction(action) } }
        pendingLong?.let { handler.removeCallbacks(it) }
        pendingLong = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun fireAction(action: Action) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        activity.runOnUiThread { onAction(action) }
    }
}
