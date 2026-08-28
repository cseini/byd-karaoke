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
    private val onEvent: (Event) -> Unit,
) {
    /** 마이크 버튼 제스처(기능은 설정에서 매핑). 한 번 클릭은 원래 기능(패널/볼륨) 유지. */
    enum class Event { MIC_LONG, MIC_DOUBLE, VOL_UP_DOUBLE, VOL_DOWN_DOUBLE }

    companion object {
        private const val TAG = "karaoke-usb"
        private const val ACTION_PERM = "com.cseini.byd.karaoke.USB_PERM"
        private const val READ_TIMEOUT_MS = 300
        private const val DEBOUNCE_MS = 800L
        private const val LONG_PRESS_MS = 600L
        private const val DOUBLE_MS = 400L   // 볼륨 버튼 더블클릭 판정창
        // 버튼 식별자(내부용). 실제 HID 코드는 설정(hid*Code, 학습 가능)에서 읽는다.
        private const val BTN_MIC = 1
        private const val BTN_UP = 2
        private const val BTN_DOWN = 3
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
    private val ifaces = ArrayList<UsbInterface>()
    @Volatile private var running = false
    @Volatile private var held = false
    private val readers = ArrayList<Thread>()
    private var lastTrigger = 0L
    private var lastPermReq = 0L
    private var usbReconnectTried = false   // dadb USB 소프트 재연결(권한 우회) 1회만 시도
    private var registered = false
    private var notified = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 진단 화면용: 설정하면 장치 정보·버튼 신호(hex)가 그대로 전달된다(메인 스레드). */
    @Volatile var onRaw: ((String) -> Unit)? = null
    private fun raw(msg: String) { onRaw?.let { cb -> handler.post { cb(msg) } } }

    /** USB 버튼 상태 변화를 D1 로그로 남긴다(진단 화면 안 열어도 원격 추적). 직전과 같은 메시지는 스킵. */
    private var lastDiag = ""
    private fun diag(msg: String) {
        if (msg == lastDiag) return
        lastDiag = msg
        runCatching { CrashLog.event(activity, msg) }
    }

    /**
     * 버튼 학습 모드: 설정하면 '눌렀다 뗌'에서 감지된 버튼 코드(바이트인덱스, 값)를 전달하고
     * 제스처·네이티브 전달은 잠시 멈춘다. (뗄 때 원래 값으로 되돌아가는 바이트가 그 버튼의 코드)
     */
    @Volatile var onCapture: ((Int, Int, String) -> Unit)? = null

    private data class BtnCode(val idx: Int, val v: Int)
    private val settings by lazy { com.cseini.byd.karaoke.data.SettingsStore(activity) }
    private var codeMic: BtnCode? = null
    private var codeVolUp: BtnCode? = null
    private var codeVolDown: BtnCode? = null

    private fun parseCode(s: String): BtnCode? {
        val p = s.split(':')
        val i = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val v = p.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (i in 0..63 && v in 1..255) BtnCode(i, v) else null
    }

    /** 리포트 → 버튼 판별(학습된 코드 기준). 어느 버튼도 아니면 0. */
    private fun classify(b: ByteArray, n: Int): Int {
        fun hit(c: BtnCode?) = c != null && n > c.idx && (b[c.idx].toInt() and 0xFF) == c.v
        return when {
            hit(codeMic) -> BTN_MIC
            hit(codeVolUp) -> BTN_UP
            hit(codeVolDown) -> BTN_DOWN
            else -> 0
        }
    }

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
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) { diag("mic.perm 허용됨 ${dev.productName ?: dev.deviceName}"); openAndRead(dev) }
            else { Log.i(TAG, "USB 권한 거부: ${dev.productName}"); raw("USB 권한이 거부되었습니다"); diag("mic.perm 거부됨 ${dev.productName ?: dev.deviceName}") }
        }
    }

    /** 여러 번 호출해도 안전(멱등). 아직 권한이 없으면 요청, 권한 있으면 읽기 시작. */
    fun start() {
        // 학습으로 바뀌었을 수 있으니 시작할 때마다 버튼 코드를 다시 읽는다.
        codeMic = parseCode(settings.hidMicCode) ?: BtnCode(5, 0x3C)
        codeVolUp = parseCode(settings.hidVolUpCode) ?: BtnCode(5, 0x3D)
        codeVolDown = parseCode(settings.hidVolDownCode) ?: BtnCode(5, 0x3E)
        if (conn != null) return   // 이미 읽는 중
        if (!registered) runCatching {
            activity.registerReceiver(permReceiver, IntentFilter(ACTION_PERM)); registered = true
        }
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            Log.i(TAG, "USB 장치 없음")
            notifyOnce("USB 마이크가 연결돼 있지 않습니다")
            raw("USB 장치가 없습니다 — 마이크를 꽂아주세요")
            diag("mic.start 장치없음")
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
            diag("mic.start HID없음 devs=${devices.joinToString { "${it.productName ?: it.deviceName}(if=${it.interfaceCount})" }}")
            return
        }
        if (usb.hasPermission(cand)) { diag("mic.start 권한OK ${cand.productName ?: cand.deviceName}"); openAndRead(cand); return }
        raw("USB 권한 요청 중 — 허용을 눌러주세요")
        diag("mic.start 권한없음 ${cand.productName ?: cand.deviceName} vid=${cand.vendorId} pid=${cand.productId}")
        // 권한 팝업이 안 뜨는 유닛(DiLink) 우회: dadb 로 USB 소프트 재연결 → 연결 이벤트로 자동 권한.
        // 프로세스당 1회만(성공하면 USB_ATTACHED 인텐트로 자동 재시작). dadb 안 되면 무해.
        if (!usbReconnectTried) {
            usbReconnectTried = true
            kotlin.concurrent.thread { com.cseini.byd.karaoke.update.UpdateManager.reconnectMicViaAdb(activity) }
        }
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
        pendingVol?.let { handler.removeCallbacks(it) }; pendingVol = null
        pendingMicTap?.let { handler.removeCallbacks(it) }; pendingMicTap = null
        readers.forEach { runCatching { it.join(500) } }; readers.clear()
        ifaces.forEach { i -> runCatching { conn?.releaseInterface(i) } }; ifaces.clear()
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

    /** 마이크의 HID 인터럽트-IN 엔드포인트를 **모두** 모은다(버튼/볼륨이 서로 다른 인터페이스일 수 있다). */
    private fun findAllHidInterrupts(d: UsbDevice): List<Pair<UsbInterface, UsbEndpoint>> {
        val out = ArrayList<Pair<UsbInterface, UsbEndpoint>>()
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            for (j in 0 until f.endpointCount) {
                val e = f.getEndpoint(j)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN) {
                    out.add(f to e); break
                }
            }
        }
        return out
    }

    private fun openAndRead(dev: UsbDevice) {
        val hids = findAllHidInterrupts(dev)
        if (hids.isEmpty()) { Log.i(TAG, "HID 인터럽트 엔드포인트 없음"); return }
        val c = usb.openDevice(dev) ?: run { Log.i(TAG, "USB 장치 열기 실패"); return }
        conn = c
        running = true
        var claimed = 0
        // 버튼이 어느 인터페이스에 있든 잡도록 모든 HID 인터럽트 인터페이스를 claim 하고 각각 읽는다.
        for ((hid, ep) in hids) {
            if (c.claimInterface(hid, true)) {
                ifaces.add(hid)
                readers.add(thread(name = "usb-mic-$claimed") { readLoop(c, ep) })
                claimed++
            }
        }
        if (claimed == 0) { Log.i(TAG, "HID claim 실패"); raw("HID claim 실패"); diag("mic.read claim실패 ${dev.productName ?: dev.deviceName}"); running = false; runCatching { c.close() }; conn = null; return }
        Log.i(TAG, "USB 버튼 읽기 시작: ${dev.productName ?: dev.deviceName} — ${claimed}개 인터페이스")
        raw("읽기 시작: ${dev.productName ?: dev.deviceName} (${claimed}개 인터페이스) — 버튼을 눌러보세요")
        diag("mic.read 시작 ${claimed}개iface ${dev.productName ?: dev.deviceName}")
    }

    private fun readLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        run {
            val buf = ByteArray(ep.maxPacketSize.coerceIn(1, 64))
            val prevBuf = ByteArray(buf.size)
            var lastCode = -1
            var longFired = false
            var lastHex = ""
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (running) {
                val n = runCatching { c.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS) }.getOrDefault(-1)
                if (n <= 0) continue
                // 버튼별 실제 리포트: 시각 + 전체 hex. 눌러서 잡히는지·계속 유지되는지·자동뗌 여부를 본다.
                // (같은 리포트 반복은 생략 — 일부 장치는 주기적으로 동일 리포트를 보내 화면을 도배한다)
                val needHex = onRaw != null || onCapture != null
                val hex = if (needHex) (0 until n).joinToString(" ") { "%02x".format(buf[it]) } else ""
                if (needHex && hex != lastHex) {
                    lastHex = hex
                    val line = "t=+%.1fs hex=".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000.0) + hex
                    Log.i(TAG, line)
                    raw(line)
                    // 학습 모드: '뗌'에서 0이 아니던 값이 바뀐(대개 0으로 되돌아간) 첫 바이트가 버튼 코드.
                    // 직전 리포트를 기준으로 비교하므로 상시 1인 프리픽스 바이트는 걸리지 않는다.
                    onCapture?.let { cb ->
                        for (i in 0 until n) {
                            val pv = prevBuf[i].toInt() and 0xFF
                            val nv = buf[i].toInt() and 0xFF
                            if (pv != 0 && nv != pv) {
                                val ci = i
                                handler.post { cb(ci, pv, hex) }
                                break
                            }
                        }
                    }
                    System.arraycopy(buf, 0, prevBuf, 0, n)
                }
                if (onCapture != null) continue   // 학습 중엔 제스처·네이티브 전달 정지

                val code = classify(buf, n)
                if (code == lastCode) continue
                val prev = lastCode
                lastCode = code

                // 어떤 버튼이든 손을 떼면 대기 중인 '길게 누름'은 취소.
                if (prev != 0 && prev != code) {
                    held = false
                    pendingLong?.let { handler.removeCallbacks(it) }; pendingLong = null
                    // 마이크 버튼 짧게 뗌: 더블탭 판정 — 단일이면 판정창 뒤 노래방 패널 토글
                    if (prev == BTN_MIC && !longFired) handleMicTap()
                }

                // 누름: 마이크는 유지형(길게 누름 가능), 볼륨은 이벤트형(누름+뗌이 즉시 옴)이라
                // 길게 누름이 불가 → 더블클릭으로 판정. 첫 클릭의 볼륨 이벤트는 더블 판정창만큼
                // 보류했다가 단일 클릭으로 확정되면 그때 보낸다(더블이면 취소 → 볼륨 변화 0).
                when (code) {
                    BTN_MIC -> armLongPress(Event.MIC_LONG) { longFired = true }
                    BTN_UP, BTN_DOWN -> handleVolClick(code)
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

    private var pendingVol: Runnable? = null
    private var pendingVolCode = 0
    private var pendingMicTap: Runnable? = null

    /**
     * 마이크 버튼 짧게 뗌: 빠르게 두 번이면 뒤로(BACK), 한 번이면 노래방 패널 토글.
     * 볼륨과 같은 보류 방식 — 더블탭 시 패널이 안 뜬다.
     */
    private fun handleMicTap() {
        handler.post {
            val p = pendingMicTap
            if (p != null) {
                handler.removeCallbacks(p); pendingMicTap = null
                fireEvent(Event.MIC_DOUBLE)
                return@post
            }
            val r = Runnable { pendingMicTap = null; sendMicEvent(KEY_MIC_TOGGLE) }
            pendingMicTap = r
            handler.postDelayed(r, DOUBLE_MS)
        }
    }

    /**
     * 볼륨 버튼 클릭(이벤트형): 더블클릭이면 앱 동작(▲=다음곡/▼=종료), 단일이면 원래 볼륨 조절.
     * 첫 클릭의 네이티브 볼륨 이벤트를 DOUBLE_MS 보류 → 더블이면 취소돼 볼륨이 안 움직인다.
     */
    private fun handleVolClick(code: Int) {
        handler.post {
            val p = pendingVol
            if (p != null && pendingVolCode == code) {
                handler.removeCallbacks(p); pendingVol = null
                fireEvent(if (code == BTN_UP) Event.VOL_UP_DOUBLE else Event.VOL_DOWN_DOUBLE)
                return@post
            }
            // 다른 버튼의 보류분이 있으면 그건 단일 클릭으로 확정해 즉시 실행
            if (p != null) { handler.removeCallbacks(p); pendingVol = null; p.run() }
            val key = if (code == BTN_UP) KEY_VOL_UP else KEY_VOL_DOWN
            val r = Runnable { pendingVol = null; sendMicEvent(key) }
            pendingVol = r; pendingVolCode = code
            handler.postDelayed(r, DOUBLE_MS)
        }
    }

    /** 버튼을 누른 상태로 LONG_PRESS_MS 가 지나면 앱 동작 실행(누르고 있는 동안만 유효). */
    private fun armLongPress(event: Event, onFired: () -> Unit) {
        held = true
        val r = Runnable { if (held) { onFired(); fireEvent(event) } }
        pendingLong?.let { handler.removeCallbacks(it) }
        pendingLong = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun fireEvent(event: Event) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        activity.runOnUiThread { onEvent(event) }
    }

    /** '노래방 패널' 기능 매핑용 — BYD 패널 토글을 그대로 실행. */
    fun sendPanelToggle() = sendMicEvent(KEY_MIC_TOGGLE)
}
