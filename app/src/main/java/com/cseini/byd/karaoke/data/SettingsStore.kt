package com.cseini.byd.karaoke.data

import android.content.Context
import android.media.MediaRecorder

/** 앱 설정(SharedPreferences). YouTube API 키·마이크 소스·AEC 등. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke", Context.MODE_PRIVATE)

    var youtubeApiKey: String
        get() = prefs.getString("yt_api_key", "") ?: ""
        set(v) = prefs.edit().putString("yt_api_key", v.trim()).apply()

    /** Gemini API 키(무료 티어) — 온라인 음성검색(오디오→가수/곡명). 있으면 Vosk 대신 사용. */
    var openaiApiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(v) = prefs.edit().putString("openai_key", v.trim()).apply()

    /** 보조 Gemini 키 — 1번 키가 한도 초과(429)면 자동으로 다음 키로 넘어간다. */
    var openaiApiKey2: String
        get() = prefs.getString("openai_key2", "") ?: ""
        set(v) = prefs.edit().putString("openai_key2", v.trim()).apply()

    var openaiApiKey3: String
        get() = prefs.getString("openai_key3", "") ?: ""
        set(v) = prefs.edit().putString("openai_key3", v.trim()).apply()

    /** 입력된 Gemini 키 목록(빈 칸 제외, 1→2→3 순). 한도 초과 시 순서대로 재시도. */
    fun geminiApiKeys(): List<String> =
        listOf(openaiApiKey, openaiApiKey2, openaiApiKey3).map { it.trim() }.filter { it.isNotBlank() }

    /** Gemini 음성검색 모델: "flash"(정확, 250회/일) | "flash-lite"(빠름, 1000회/일). */
    var geminiModel: String
        get() = prefs.getString("gemini_model", "flash") ?: "flash"
        set(v) = prefs.edit().putString("gemini_model", v).apply()

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
     * 마이크 입력 방식. "AUTO"=USB 유무에 따라 자동, 나머지는 특정 소스 강제.
     * 유닛마다 지원 소스가 달라(예: 씨라 유닛은 VOICE_RECOGNITION 이 무음, MIC 만 됨) 사용자가 고를 수 있게 한다.
     */
    var micSourceName: String
        get() = prefs.getString("mic_source", "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString("mic_source", v).apply()

    /** 사용자가 특정 소스를 강제했으면 그 상수, "AUTO"면 null(라우팅이 자동 결정). */
    fun forcedMicSource(): Int? = when (micSourceName) {
        "MIC" -> MediaRecorder.AudioSource.MIC
        "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
        else -> null
    }

    /** 노래 녹음 사용. 끄면 부를 때 녹음·채점 없이 재생만 한다. */
    var recordingEnabled: Boolean
        get() = prefs.getBoolean("recording_enabled", true)
        set(v) = prefs.edit().putBoolean("recording_enabled", v).apply()

    /** 녹음 싱크 보정(ms). 반주 대비 내 목소리를 앞/뒤로 밀어 맞춘다. 기본 -140(경험값). */
    var syncOffsetMs: Int
        get() = prefs.getInt("sync_offset_ms", -140)
        set(v) = prefs.edit().putInt("sync_offset_ms", v).apply()

    /** USB 마이크 물리버튼으로 앱 제어(길게=음성검색, 볼륨/짧게=노래방 패널). 기본 꺼짐 —
     *  마이크 기종마다 HID 코드가 달라 켜면 그 기기 기본 버튼이 안 먹을 수 있어 옵트인. */
    var micButtonControl: Boolean
        get() = prefs.getBoolean("mic_button_control", false)
        set(v) = prefs.edit().putBoolean("mic_button_control", v).apply()

    /** 휠 무음 버튼 더블클릭으로 음성검색(접근성 기반). 기본 꺼짐. USB 점유 안 해 안전. */
    var wheelButtonControl: Boolean
        get() = prefs.getBoolean("wheel_button_control", false)
        set(v) = prefs.edit().putBoolean("wheel_button_control", v).apply()

    /** 음성 검색 후 첫 곡을 3초 뒤 자동 재생. */
    var autoPlayVoiceFirst: Boolean
        get() = prefs.getBoolean("auto_play_voice", false)
        set(v) = prefs.edit().putBoolean("auto_play_voice", v).apply()

    /** 녹음 품질(Hz). 낮을수록 용량↓·채점↑빠름. 22050(표준)/16000(절약)/11025(최소). */
    var recordRateHz: Int
        get() = prefs.getInt("record_rate", 22050)
        set(v) = prefs.edit().putInt("record_rate", v).apply()

    /** 채점 사용. 끄면 녹음만 즉시 저장(채점 계산 생략). */
    var scoringEnabled: Boolean
        get() = prefs.getBoolean("scoring", true)
        set(v) = prefs.edit().putBoolean("scoring", v).apply()

    /** 목소리 명료도(0~100). 고음 강조로 먹먹함을 줄인다. */
    var voiceClarity: Int
        get() = prefs.getInt("voice_clarity", 45)
        set(v) = prefs.edit().putInt("voice_clarity", v.coerceIn(0, 100)).apply()

    /** 목소리 에코(0~100). 노래방 리버브 느낌. */
    var voiceEcho: Int
        get() = prefs.getInt("voice_echo", 20)
        set(v) = prefs.edit().putInt("voice_echo", v.coerceIn(0, 100)).apply()

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
