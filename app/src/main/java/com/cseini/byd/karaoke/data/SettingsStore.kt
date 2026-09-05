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

    /**
     * 순정 마이크(HID·오디오가 한 장치에 얽힌 마이크, 아토3 BYD-micTS02 등). 켜면 버튼으로 음성검색할 때
     * USB HID 를 잠깐 놓아 마이크 오디오를 되찾은 뒤 음성인식하고, 끝나면 버튼 읽기를 재개한다.
     * (안 켜면 HID 를 계속 잡아 오디오가 막혀 음성검색에 목소리가 안 들어갈 수 있다.)
     */
    var nativeMicMode: Boolean
        get() = prefs.getBoolean("native_mic_mode", false)
        set(v) = prefs.edit().putBoolean("native_mic_mode", v).apply()

    /** 음성검색 STT 엔진 — "server"(Groq 서버, 기본) / "gemini"(직접 발급 키). */
    var sttEngine: String
        get() = prefs.getString("stt_engine", "server")!!
        set(v) = prefs.edit().putString("stt_engine", v).apply()

    /**
     * 학습된 HID 버튼 코드 — "바이트인덱스:값(10진)". 설정의 '버튼 학습'으로 채워지며,
     * 기본값은 최초 실측 마이크(리포트[5]=0x3C/0x3D/0x3E). 기종마다 달라 학습으로 덮어쓴다.
     */
    var hidMicCode: String
        get() = prefs.getString("hid_mic", "5:60")!!
        set(v) = prefs.edit().putString("hid_mic", v).apply()

    var hidVolUpCode: String
        get() = prefs.getString("hid_vol_up", "5:61")!!
        set(v) = prefs.edit().putString("hid_vol_up", v).apply()

    var hidVolDownCode: String
        get() = prefs.getString("hid_vol_down", "5:62")!!
        set(v) = prefs.edit().putString("hid_vol_down", v).apply()

    /**
     * 마이크 버튼 제스처 → 기능 매핑. 값: none/voice/back/mute/next/stop/pause/panel.
     * 기본값은 기존 동작과 동일(길게=음성검색, 마이크 두 번=뒤로, 볼륨▲▲=다음곡, 볼륨▼▼=종료).
     */
    var mapMicLong: String
        get() = prefs.getString("map_mic_long", "voice")!!
        set(v) = prefs.edit().putString("map_mic_long", v).apply()

    var mapMicDouble: String
        get() = prefs.getString("map_mic_double", "back")!!
        set(v) = prefs.edit().putString("map_mic_double", v).apply()

    var mapVolUpDouble: String
        get() = prefs.getString("map_vol_up2", "next")!!
        set(v) = prefs.edit().putString("map_vol_up2", v).apply()

    var mapVolDownDouble: String
        get() = prefs.getString("map_vol_down2", "stop")!!
        set(v) = prefs.edit().putString("map_vol_down2", v).apply()

    /** 휠 무음 버튼 더블클릭으로 음성검색(접근성 기반). 기본 꺼짐. USB 점유 안 해 안전. */
    var wheelButtonControl: Boolean
        get() = prefs.getBoolean("wheel_button_control", false)
        set(v) = prefs.edit().putBoolean("wheel_button_control", v).apply()

    /**
     * 씨라이언7 전용 모드 — USB 마이크 버튼을 앱이 직접 못 읽는 유닛 우회. BYD 노래방 팝업
     * (com.byd.minikaraoke) 등장을 접근성으로 감지해 음성검색을 띄운다. 버튼 종류 구분은
     * 안 되지만(아무 버튼이나 음성검색), USB 를 점유하지 않아 BYD 가 마이크를 잡을 수 있다.
     */
    var sealionMode: Boolean
        get() = prefs.getBoolean("sealion_mode", false)
        set(v) = prefs.edit().putBoolean("sealion_mode", v).apply()

    /**
     * BYD 시스템 브로드캐스트(byd.intent.minikaraoke_micevent)의 KEY_EVENT 코드에 음성검색을 붙일지 —
     * "off"(기본) / "131" / "132" / "both".
     *
     * ⚠️ 기본이 off 인 이유(2026-09-02 실차 확정): 이 브로드캐스트로 오는 코드는 **131·132 두 개뿐이고
     * 둘 다 마이크 '전원' 버튼**이다(로그 42건 전수 확인). 정작 쓰고 싶은 버튼(볼륨창을 띄우는 버튼)은
     * 신호가 오지 않는다. 켜면 마이크를 껐다 켤 때마다 음성검색이 뜬다 — 씨라이언에서 버튼으로
     * 음성검색을 하려면 [씨라이언7 전용 모드](팝업 감지)를 쓸 것.
     */
    var micEventTrigger: String
        get() = prefs.getString("micevent_trigger", "off")!!
        set(v) = prefs.edit().putString("micevent_trigger", v).apply()

    /**
     * 구글 키보드(Gboard) 음성입력을 Gemini 보다 우선한다. 인식률이 좋고 API 키가 필요 없다
     * (앱이 검색창에 키보드를 띄우고 접근성으로 음성 버튼을 눌러 구글 STT 로 진입). 씨라이언
     * 모드는 이 값과 무관하게 항상 키보드 우선. Gboard 가 없으면 설치를 유도한다.
     */
    var googleSttPreferred: Boolean
        get() = prefs.getBoolean("google_stt_preferred", false)
        set(v) = prefs.edit().putBoolean("google_stt_preferred", v).apply()

    /** 새 곡을 항상 전체화면으로 시작(화면 탭으로 해제). 기본 꺼짐. */
    var startFullscreen: Boolean
        get() = prefs.getBoolean("start_fullscreen", false)
        set(v) = prefs.edit().putBoolean("start_fullscreen", v).apply()

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

    /** 녹음 시 목소리 크기(%). 100=원음. 차량 마이크가 작아 반주에 묻힐 때 올린다. */
    var voiceGainPct: Int
        get() = prefs.getInt("voice_gain_pct", 100)
        set(v) = prefs.edit().putInt("voice_gain_pct", v.coerceIn(50, 500)).apply()

    /** 녹음 시 반주 크기(%). 기본 60. 낮추면 목소리가 상대적으로 커진다. */
    var accompGainPct: Int
        get() = prefs.getInt("accomp_gain_pct", 60)
        set(v) = prefs.edit().putInt("accomp_gain_pct", v.coerceIn(0, 150)).apply()

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

    fun hasApiKey(): Boolean = youtubeApiKey.isNotBlank()
}
