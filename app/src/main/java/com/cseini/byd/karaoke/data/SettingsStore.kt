package com.cseini.byd.karaoke.data

import android.content.Context
import android.media.MediaRecorder

/** 앱 설정(SharedPreferences). YouTube API 키·마이크 소스·AEC 등. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    var youtubeApiKey: String
        get() = prefs.getString("yt_api_key", "") ?: ""
        set(v) = prefs.edit().putString("yt_api_key", v.trim()).apply()

    /** 마이크 소스 이름. 실차 0-B 결과에 따라 사용자가 바꿀 수 있음. */
    var micSourceName: String
        get() = prefs.getString("mic_source", "VOICE_RECOGNITION") ?: "VOICE_RECOGNITION"
        set(v) = prefs.edit().putString("mic_source", v).apply()

    var aecEnabled: Boolean
        get() = prefs.getBoolean("aec", false)
        set(v) = prefs.edit().putBoolean("aec", v).apply()

    var preferUsbMic: Boolean
        get() = prefs.getBoolean("prefer_usb", true)
        set(v) = prefs.edit().putBoolean("prefer_usb", v).apply()

    fun micSourceConst(): Int = when (micSourceName) {
        "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
        "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else -> MediaRecorder.AudioSource.MIC
    }

    fun hasApiKey(): Boolean = youtubeApiKey.isNotBlank()
}
