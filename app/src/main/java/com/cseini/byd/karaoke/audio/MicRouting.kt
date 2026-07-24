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

    /** open() 결과: 초기화된 레코더 + 내장(통화) 마이크로 열렸는지 여부(에코제거 필요 판단용). */
    class Opened(val record: AudioRecord, val builtin: Boolean)

    fun usbInput(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    fun builtinInput(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

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
        val usb = if (preferUsb) usbInput(context) else null
        val noUsb = usb == null
        val primary = forced ?: if (noUsb) MediaRecorder.AudioSource.VOICE_COMMUNICATION else autoRequested
        // 중복 제거한 시도 순서: primary → MIC → DEFAULT
        val order = linkedSetOf(primary, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)
        for (src in order) {
            val r = runCatching {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufBytes)
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                (usb ?: builtinInput(context))?.let { runCatching { r.setPreferredDevice(it) } }
                // 내장 마이크로 열렸고(=USB 없음), 통화 소스 계열이면 에코/잡음 제거가 유용.
                val builtin = noUsb && src == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                return Opened(r, builtin)
            }
            runCatching { r.release() }
        }
        return null
    }

    private fun inputs(context: Context) =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
}
