package com.cseini.byd.karaoke.data

import android.content.Context
import android.media.MediaRecorder

/** 앱 설정(SharedPreferences). YouTube API 키·마이크 소스·AEC 등. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    var youtubeApiKey: String
        get() = prefs.getString("yt_api_key", "") ?: ""
        set(v) = prefs.edit().putString("yt_api_key", v.trim()).apply()

    /** 검색 방식: "keyless"(API 키 불필요, 결과 페이지 파싱) | "api"(Data API v3). 기본 키 없이. */
    var searchMode: String
        get() = prefs.getString("search_mode", "keyless") ?: "keyless"
        set(v) = prefs.edit().putString("search_mode", v).apply()

    val keylessSearch: Boolean get() = searchMode != "api"

    /**
     * 마이크 소스 이름. 반주(차 스피커)+목소리를 한 트랙에 담아야 하므로 기본은 MIC.
     * (VOICE_RECOGNITION 은 배경음 억제가 걸려 반주가 지워진다.) 실차에서 조정 가능.
     */
    var micSourceName: String
        get() = prefs.getString("mic_source", "MIC") ?: "MIC"
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
