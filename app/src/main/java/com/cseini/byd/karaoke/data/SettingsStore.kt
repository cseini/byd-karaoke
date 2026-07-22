package com.cseini.byd.karaoke.data

import android.content.Context
import android.media.MediaRecorder

/** 앱 설정(SharedPreferences). YouTube API 키·마이크 소스·AEC 등. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    var youtubeApiKey: String
        get() = prefs.getString("yt_api_key", "") ?: ""
        set(v) = prefs.edit().putString("yt_api_key", v.trim()).apply()

    /**
     * 검색 방식: "keyless"(결과 페이지 파싱) | "api"(Data API v3).
     * 사용자가 명시적으로 고르지 않았으면, 키가 있으면 API(임베드 가능만 필터되어 재생 안정적),
     * 키가 없으면 keyless 를 기본으로 한다.
     */
    var searchMode: String
        get() = prefs.getString("search_mode", null)
            ?: if (youtubeApiKey.isNotBlank()) "api" else "keyless"
        set(v) = prefs.edit().putString("search_mode", v).apply()

    val keylessSearch: Boolean get() = searchMode != "api"

    /**
     * 마이크 소스 이름. 다시듣기는 반주(유튜브)와 목소리를 따로 재생해 싱크를 맞추므로,
     * 녹음은 깨끗한 목소리만 담는 VOICE_RECOGNITION 이 기본(채점 정확도에도 유리).
     */
    var micSourceName: String
        get() = prefs.getString("mic_source", "VOICE_RECOGNITION") ?: "VOICE_RECOGNITION"
        set(v) = prefs.edit().putString("mic_source", v).apply()

    /** 다시듣기 싱크 보정(ms). 유튜브 반주 대비 내 목소리를 앞/뒤로 밀어 맞춘다. */
    var syncOffsetMs: Int
        get() = prefs.getInt("sync_offset_ms", 0)
        set(v) = prefs.edit().putInt("sync_offset_ms", v).apply()

    /** 녹음 저장 위치: "internal"(차 내부) | "sd"(SD카드). */
    var storageMode: String
        get() = prefs.getString("storage_mode", "internal") ?: "internal"
        set(v) = prefs.edit().putString("storage_mode", v).apply()

    /** 녹음 최대 사용 용량(MB). 초과 시 오래된 녹음부터 자동 삭제. */
    var maxStorageMb: Int
        get() = prefs.getInt("max_storage_mb", 500)
        set(v) = prefs.edit().putInt("max_storage_mb", v).apply()

    val maxStorageBytes: Long get() = maxStorageMb * 1_000_000L

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
