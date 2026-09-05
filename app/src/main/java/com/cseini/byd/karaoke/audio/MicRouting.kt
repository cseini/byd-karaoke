package com.cseini.byd.karaoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 마이크 라우팅. USB 마이크(입 가까운 깨끗한 소리)가 꽂혀 있으면 그걸 쓰고,
 * 없으면 차량 내장 통화 마이크로 폴백한다(VOICE_COMMUNICATION + 에코/잡음 제거).
 * 소스가 지원되지 않는 유닛을 대비해 여러 소스를 순서대로 시도한다.
 */
object MicRouting {

    /** open() 결과: 초기화된 레코더 + 내장(통화) 마이크로 열렸는지 여부(에코제거 필요 판단용) + 실제 시도한 소스. */
    class Opened(val record: AudioRecord, val builtin: Boolean, val src: Int, val bydOwnsMic: Boolean = false)

    /**
     * 시도할 AudioSource 순서 — 부수효과 없는 순수 함수라 단위 테스트로 검증한다.
     * 실차(씨라이언7·순정마이크): 소리가 실제로 들어온 경우(hadSpeech=true)는 전부 VOICE_COMMUNICATION(src=7)
     * 이었다. 사용자 선택(forced)이 최우선, 그 다음 USB 없으면 통화 소스, → MIC → DEFAULT 폴백.
     */
    fun sourceOrder(forced: Int?, autoRequested: Int, hasUsb: Boolean): List<Int> {
        val primary = forced ?: if (!hasUsb) MediaRecorder.AudioSource.VOICE_COMMUNICATION else autoRequested
        return linkedSetOf(primary, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT).toList()
    }

    /** 장치 타입 → 짧은 이름(진단 로그용). */
    fun typeName(type: Int): String = deviceTypeName(type)

    /** AudioSource 상수 → 이름(진단 로그용). */
    fun sourceName(src: Int): String = when (src) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VR"
        MediaRecorder.AudioSource.UNPROCESSED -> "RAW"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> "src$src"
    }

    fun usbInput(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    fun builtinInput(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    // 씨라이언7 등 순정 마이크가 TYPE_BUS(t=21)로만 노출되는 유닛 대응 — 이 장치를 명시 지정해야 소리가 들어온다.
    fun busInput(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUS }

    fun hasUsbMic(context: Context): Boolean = usbInput(context) != null

    /**
     * AudioRecord 를 연다.
     * - forced != null: 그 소스를 강제(→MIC→DEFAULT 폴백). 유닛 호환용 사용자 지정.
     * - forced == null(AUTO): USB 있으면 autoRequested, 없으면 VOICE_COMMUNICATION. (→MIC→DEFAULT)
     * 항상 MIC/DEFAULT 로 폴백해 어떤 유닛에서도 소리가 들어오게 한다(씨라 등 VOICE_RECOGNITION 무음 대비).
     * 실패 시 null.
     */
    @SuppressLint("MissingPermission")
    fun open(context: Context, rate: Int, minBufBytes: Int, forced: Int?, autoRequested: Int, preferUsb: Boolean): Opened? {
        // 씨라이언7(DiLink5·com.byd.sing)에서는 USB 마이크를 BYD 시스템이 점유한다. 우리가 그 오디오를
        // 열면 마이크가 통째로 끊긴다(실측: 마이크 빨간불 + hadSpeech=false). 그 유닛에선 USB 를 건드리지
        // 않고 차량 내장 마이크(BUS/BUILTIN)로 간다. 돌핀 등 minikaraoke 유닛은 종전대로 USB 를 쓴다.
        val bydOwnsUsb = runCatching {
            context.packageManager.getPackageInfo("com.byd.sing", 0); true
        }.getOrDefault(false)
        val usb = if (preferUsb && !bydOwnsUsb) usbInput(context) else null
        val noUsb = usb == null
        // 씨라이언7(BYD 가 마이크 소유): 마이크가 죽어 있으면 통화 소스가 ~6초 블록 후 실패하는데, 여기서
        // MIC/DEFAULT 로 폴백하면 죽은 BUS 를 22초간 붙잡고도 무음이라 UI 가 28초 멈춘다(v6.54 실측).
        // 그 유닛에선 폴백하지 말고 첫 소스만 시도 — 실패면 빠르게 null 반환해 '마이크 죽음'을 바로 알린다.
        val order = sourceOrder(forced, autoRequested, hasUsb = !noUsb)
            .let { if (bydOwnsUsb && forced == null) it.take(1) else it }
        // 진단: 어떤 입력 장치가 잡히는지(내장 마이크가 앱에 안 열리는 유닛 파악용).
        android.util.Log.d("KaraokeMic", "inputs=[${inputSummary(context)}] usb=${usb != null} try=${order.toList()}")
        for ((idx, src) in order.withIndex()) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = runCatching {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufBytes)
            }.getOrNull()
            val ok = r?.state == AudioRecord.STATE_INITIALIZED
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            // 소스별 소요시간 — 씨라이언7 MIC vs VOICE_COMMUNICATION 선택이 성능·호환성에 미치는 영향 측정.
            // v6.52-test에서 src=7(VOICE_COMMUNICATION)이 BYD 통과음을 차단했음 → MIC(src=1) 우선 시도로 개선.
            val status = if (ok) "ok" else "실패"
            val msg = if (idx < order.size - 1 && !ok) {
                "mic try src=$src ${elapsed}ms $status → ${sourceName(order[idx + 1])} 폴백"
            } else {
                "mic try src=$src ${elapsed}ms $status"
            }
            com.cseini.byd.karaoke.CrashLog.event(context, msg)
            if (r == null) continue
            if (ok) {
                // 선호 장치: USB > BUS(씨라이언 순정) > 내장. BUS 를 지정 안 하면 씨라이언은 무음이 된다.
                (usb ?: busInput(context) ?: builtinInput(context))?.let { dev ->
                    // 반환값(false=라우팅 거부)을 남긴다 — 순정 마이크 무음이 라우팅 실패인지 소스 문제인지 가르는 단서.
                    val ok = runCatching { r.setPreferredDevice(dev) }.getOrDefault(false)
                    com.cseini.byd.karaoke.CrashLog.event(
                        context, "mic pref=$ok type=${deviceTypeName(dev.type)} src=$src bydUsb=$bydOwnsUsb",
                    )
                }
                android.util.Log.d("KaraokeMic", "opened src=$src builtinDev=${builtinInput(context) != null}")
                // 내장 마이크로 열렸고(=USB 없음), 통화 소스 계열이면 에코/잡음 제거가 유용.
                val builtin = noUsb && src == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                return Opened(r, builtin, src, bydOwnsMic = bydOwnsUsb)
            }
            runCatching { r.release() }
        }
        return null
    }

    /** 입력 장치 타입 목록 문자열(진단용). */
    private fun inputSummary(context: Context): String =
        inputs(context).joinToString(",") { deviceTypeName(it.type) }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "type$type"
    }

    private fun inputs(context: Context) =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
}
