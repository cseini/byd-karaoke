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
     * AudioRecord 를 연다. USB 있으면 요청 소스(→MIC→DEFAULT) 순, 없으면
     * VOICE_COMMUNICATION(→MIC→DEFAULT) 순으로 시도해 처음 초기화되는 것을 반환.
     * 선호 입력장치도 USB/내장으로 지정. 실패 시 null.
     */
    @SuppressLint("MissingPermission")
    fun open(context: Context, rate: Int, minBufBytes: Int, requestedSource: Int, preferUsb: Boolean): Opened? {
        val usb = if (preferUsb) usbInput(context) else null
        val builtin = usb == null
        val sources = if (builtin)
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)
        else
            intArrayOf(requestedSource, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)
        for (src in sources) {
            val r = runCatching {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufBytes)
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                (usb ?: builtinInput(context))?.let { runCatching { r.setPreferredDevice(it) } }
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
