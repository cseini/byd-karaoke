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
    private val onButton: () -> Unit,
) {
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
            else Log.i(TAG, "USB 권한 거부: ${dev.productName}")
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
            return
        }
        devices.forEach { logDevice(it) }
        val cand = devices.firstOrNull { findHidInterrupt(it) != null } ?: run {
            Log.i(TAG, "HID 버튼 인터페이스 없음")
            notifyOnce("이 마이크는 버튼 제어를 지원하지 않습니다 (설정에서 꺼주세요)")
            return
        }
        if (usb.hasPermission(cand)) { openAndRead(cand); return }
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
        running = true
        reader = thread(name = "usb-mic") {
            val buf = ByteArray(ep.maxPacketSize.coerceIn(1, 64))
            var lastCode = -1
            var longFired = false
            while (running) {
                val n = runCatching { c.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS) }.getOrDefault(-1)
                if (n <= 0) continue
                val code = if (n > CODE_INDEX) buf[CODE_INDEX].toInt() and 0xFF else 0
                if (code == lastCode) continue
                val prev = lastCode
                lastCode = code
                // 마이크 버튼에서 손 뗌: 길게(음성검색)가 아직 안 걸렸으면 짧게 누름 → 노래방 패널 토글.
                if (prev == CODE_MIC) {
                    held = false
                    pendingLong?.let { handler.removeCallbacks(it) }; pendingLong = null
                    if (!longFired) sendMicEvent(KEY_MIC_TOGGLE)
                }
                when (code) {
                    CODE_MIC -> {                       // 누름: 길게=음성검색 / 짧게=패널(뗄 때 처리)
                        held = true; longFired = false
                        val r = Runnable { if (held) { longFired = true; fireButton() } }
                        pendingLong = r
                        handler.postDelayed(r, LONG_PRESS_MS)
                    }
                    CODE_VOL_UP -> sendMicEvent(KEY_VOL_UP)       // 마이크 볼륨↑ + 패널(네이티브, 자동 닫힘)
                    CODE_VOL_DOWN -> sendMicEvent(KEY_VOL_DOWN)   // 마이크 볼륨↓ + 패널
                }
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

    private fun fireButton() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        activity.runOnUiThread { onButton() }
    }
}
