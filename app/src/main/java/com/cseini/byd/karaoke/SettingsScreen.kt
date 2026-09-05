package com.cseini.byd.karaoke

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.Storage
import com.cseini.byd.karaoke.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 마이크 버튼 제스처에 매핑 가능한 기능 목록(코드 ↔ 표시 이름).
 * 파일 최상위 상수 — 클래스 프로퍼티로 두면 init 보다 늦게 초기화돼 NPE(v4.16~v4.18 크래시).
 */
private val MAP_FUNCTIONS = listOf(
    "none" to "사용 안 함",
    "voice" to "🎤 음성 검색",
    "back" to "◀ 뒤로",
    "mute" to "🔇 반주 음소거",
    "next" to "⏭ 다음 예약곡",
    "stop" to "⏹ 노래 종료(채점)",
    "pause" to "⏯ 일시정지/재생",
    "panel" to "🎛 노래방 패널",
)

/** 설정 화면(임베드 오버레이): 검색 방식·API 키·싱크·마이크·저장·수동 업데이트. */
class SettingsScreen(private val root: View, private val host: ScreenHost) {

    private val activity = root.context as AppCompatActivity
    private val settings = SettingsStore(activity)

    private val sttEngineGroup: RadioGroup = root.findViewById(R.id.stt_engine_group)
    private val geminiKeyArea: View = root.findViewById(R.id.gemini_key_area)
    private val gboardArea: View = root.findViewById(R.id.gboard_area)
    private val carTypeText: TextView = root.findViewById(R.id.txt_car_type)
    private val openaiKey: EditText = root.findViewById(R.id.openai_key_input)
    private val geminiModelGroup: RadioGroup = root.findViewById(R.id.gemini_model_group)
    private val syncSeek: SeekBar = root.findViewById(R.id.sync_seek)
    private val syncLabel: TextView = root.findViewById(R.id.sync_label)
    private val rateGroup: RadioGroup = root.findViewById(R.id.rate_group)
    private val scoringCheck: CheckBox = root.findViewById(R.id.chk_scoring)
    private val recordingCheck: CheckBox = root.findViewById(R.id.chk_recording)
    private val micSourceGroup: RadioGroup = root.findViewById(R.id.mic_source_group)
    private val voiceGainSeek: SeekBar = root.findViewById(R.id.voice_gain_seek)
    private val voiceGainLabel: TextView = root.findViewById(R.id.voice_gain_label)
    private val accompGainSeek: SeekBar = root.findViewById(R.id.accomp_gain_seek)
    private val accompGainLabel: TextView = root.findViewById(R.id.accomp_gain_label)
    private val micButtonCheck: CheckBox = root.findViewById(R.id.chk_mic_button)
    private val nativeMicCheck: CheckBox = root.findViewById(R.id.chk_native_mic)
    private val recordOptions: View = root.findViewById(R.id.record_options)
    private val startFullscreenCheck: CheckBox = root.findViewById(R.id.chk_start_fullscreen)
    private val autoplayCheck: CheckBox = root.findViewById(R.id.chk_autoplay)
    private val wheelButtonCheck: CheckBox = root.findViewById(R.id.chk_wheel_button)
    private val sealionCheck: CheckBox = root.findViewById(R.id.chk_sealion)
    private val sealionDesc: TextView = root.findViewById(R.id.txt_sealion_desc)
    private val googleSttCheck: CheckBox = root.findViewById(R.id.chk_google_stt)
    private val googleSttDesc: TextView = root.findViewById(R.id.txt_google_stt_desc)
    private val updateStatus: TextView = root.findViewById(R.id.update_status)
    private val storageGroup: RadioGroup = root.findViewById(R.id.storage_group)
    private val storageInfo: TextView = root.findViewById(R.id.storage_info)
    private val maxStorageInput: EditText = root.findViewById(R.id.max_storage_input)
    private val clearInternalBtn: Button = root.findViewById(R.id.btn_clear_internal)
    private val clearSdBtn: Button = root.findViewById(R.id.btn_clear_sd)

    // 미저장 변경 감지용 — init 끝에서 스냅샷, 저장 시 갱신. 나갈 때 현재값과 비교.
    private var savedSignature = ""

    init {
        // 음성 검색 엔진 — Groq(server) / Gemini(키) / Gboard(키보드 음성=googleSttPreferred).
        sttEngineGroup.check(
            when {
                settings.googleSttPreferred -> R.id.eng_gboard
                settings.sttEngine == "gemini" -> R.id.eng_gemini
                else -> R.id.eng_groq
            },
        )
        updateEngineVisibility()
        sttEngineGroup.setOnCheckedChangeListener { _, _ -> updateEngineVisibility() }
        showCarTypeHint()
        openaiKey.setText(settings.openaiApiKey)
        geminiModelGroup.check(if (settings.geminiModel == "flash-lite") R.id.gm_lite else R.id.gm_flash)
        updateStatus.text = "현재 버전 v${BuildConfig.VERSION_NAME}"

        syncSeek.progress = (settings.syncOffsetMs + 300).coerceIn(0, 600)
        updateSyncLabel()
        syncSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateSyncLabel()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        rateGroup.check(
            when (settings.recordRateHz) {
                16000 -> R.id.rate_save
                11025 -> R.id.rate_min
                else -> R.id.rate_standard
            }
        )
        scoringCheck.isChecked = settings.scoringEnabled
        recordingCheck.isChecked = settings.recordingEnabled
        // 녹음 꺼지면 하위 옵션(채점·품질·음량·싱크·저장) 숨김.
        recordOptions.visibility = if (recordingCheck.isChecked) View.VISIBLE else View.GONE
        recordingCheck.setOnCheckedChangeListener { _, checked ->
            recordOptions.visibility = if (checked) View.VISIBLE else View.GONE
        }

        micSourceGroup.check(
            when (settings.micSourceName) {
                "MIC" -> R.id.ms_mic
                "VOICE_RECOGNITION" -> R.id.ms_recognition
                "VOICE_COMMUNICATION" -> R.id.ms_comm
                else -> R.id.ms_auto
            }
        )
        // 녹음 음량(목소리 50~500%, 반주 0~150%)
        voiceGainSeek.progress = (settings.voiceGainPct - 50).coerceIn(0, 450)
        accompGainSeek.progress = settings.accompGainPct.coerceIn(0, 150)
        updateGainLabels()
        val gainListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateGainLabels()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        voiceGainSeek.setOnSeekBarChangeListener(gainListener)
        accompGainSeek.setOnSeekBarChangeListener(gainListener)

        micButtonCheck.isChecked = settings.micButtonControl
        nativeMicCheck.isChecked = settings.nativeMicMode
        startFullscreenCheck.isChecked = settings.startFullscreen
        autoplayCheck.isChecked = settings.autoPlayVoiceFirst
        setupMapSpinners()
        wheelButtonCheck.isChecked = settings.wheelButtonControl

        // 씨라이언7 전용 모드 — 이제 prod 도 노출(차종별 옵션). 씨라이언은 USB HID 제어와 상호배타.
        sealionCheck.isChecked = settings.sealionMode
        sealionCheck.visibility = View.VISIBLE
        sealionDesc.visibility = View.VISIBLE
        // 구 '구글 키보드 음성 우선' 체크박스는 엔진 라디오(Gboard)로 대체 — 숨긴다.
        googleSttCheck.visibility = View.GONE
        googleSttDesc.visibility = View.GONE
        sealionCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) micButtonCheck.isChecked = false
        }
        micButtonCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) sealionCheck.isChecked = false
        }

        storageGroup.check(if (settings.storageMode == "sd") R.id.st_sd else R.id.st_internal)
        maxStorageInput.setText(settings.maxStorageMb.toString())
        storageGroup.setOnCheckedChangeListener { _, _ -> refreshStorageInfo() }
        clearInternalBtn.setOnClickListener { clearRecordings("internal", "차 내부") }
        clearSdBtn.setOnClickListener { clearRecordings("sd", "SD카드") }
        clearSdBtn.isEnabled = Storage.sdBase(activity) != null
        refreshStorageInfo()

        root.findViewById<Button>(R.id.btn_back).setOnClickListener { requestClose { host.onScreenBack() } }
        root.findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        root.findViewById<Button>(R.id.btn_mic_learn).setOnClickListener { showMicLearn() }
        root.findViewById<Button>(R.id.btn_mic_diag).setOnClickListener { showMicDiag() }
        root.findViewById<Button>(R.id.btn_source_check).setOnClickListener { showMicSourceCheck() }
        // 마이크의 어느 버튼이 어떤 신호를 쏘는지 차 안에서 직접 조사한다(lab 전용, 읽기 전용).
        root.findViewById<Button>(R.id.btn_bcast_diag).apply {
            if (BuildConfig.FLAVOR == "lab") visibility = View.VISIBLE
            setOnClickListener { dumpBroadcasts(this) }
        }
        // Gboard 설치·기본키보드 — 엔진에서 Gboard 고르면 노출되는 gboard_area 안의 버튼(전 플레이버).
        root.findViewById<Button>(R.id.btn_gboard_install).setOnClickListener { setupGboard(it as Button) }
        // 구 lab 전용 Gboard 설치 버튼은 숨긴다(엔진 라디오 경로로 통합).
        root.findViewById<Button>(R.id.btn_gboard_setup).visibility = View.GONE

        root.findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
        root.findViewById<Button>(R.id.btn_log_send).setOnClickListener { sendLog(it as Button) }
        root.findViewById<Button>(R.id.btn_key_qr).setOnClickListener { showKeyQr() }

        NavBar.wireEmbedded(root, "settings") { host.onNavigate(it) }
        savedSignature = currentSignature()
    }

    /** 모든 설정 컨트롤의 현재 값을 하나의 문자열로 — 저장 스냅샷과 비교해 미저장 변경을 감지. */
    private fun currentSignature(): String = listOf(
        openaiKey.text, sttEngineGroup.checkedRadioButtonId, geminiModelGroup.checkedRadioButtonId,
        syncSeek.progress, rateGroup.checkedRadioButtonId, scoringCheck.isChecked,
        recordingCheck.isChecked, micSourceGroup.checkedRadioButtonId, voiceGainSeek.progress,
        accompGainSeek.progress, micButtonCheck.isChecked, nativeMicCheck.isChecked,
        startFullscreenCheck.isChecked, autoplayCheck.isChecked, wheelButtonCheck.isChecked,
        sealionCheck.isChecked, storageGroup.checkedRadioButtonId,
        maxStorageInput.text, selectedMap(R.id.map_mic_long), selectedMap(R.id.map_mic_double),
        selectedMap(R.id.map_vol_up2), selectedMap(R.id.map_vol_down2),
        settings.hidMicCode, settings.hidVolUpCode, settings.hidVolDownCode,
    ).joinToString("|")

    /** 나가기 요청 — 미저장 변경이 있으면 저장 여부를 묻고, 없으면 바로 닫는다. */
    fun requestClose(onClose: () -> Unit) {
        if (currentSignature() == savedSignature) { onClose(); return }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("변경사항 저장")
            .setMessage("저장하지 않은 설정 변경이 있습니다. 저장할까요?")
            .setPositiveButton("저장하고 나가기") { _, _ -> save(); onClose() }
            .setNegativeButton("저장 안 함") { _, _ -> onClose() }
            .setNeutralButton("계속 편집", null)
            .show()
    }

    private fun updateGainLabels() {
        voiceGainLabel.text = "목소리 ${voiceGainSeek.progress + 50}%"
        accompGainLabel.text = "반주 ${accompGainSeek.progress}%"
    }

    private fun updateSyncLabel() {
        val v = syncSeek.progress - 300
        syncLabel.text = "${if (v > 0) "+" else ""}$v ms"
    }

    // Gemini 선택 시만 키·모델, Gboard 선택 시만 설치 버튼 노출.
    private fun updateEngineVisibility() {
        val id = sttEngineGroup.checkedRadioButtonId
        geminiKeyArea.visibility = if (id == R.id.eng_gemini) View.VISIBLE else View.GONE
        gboardArea.visibility = if (id == R.id.eng_gboard) View.VISIBLE else View.GONE
    }

    /** 차량 세대를 감지(com.byd.sing=DiLink5=씨라이언)해 권장 조합을 안내(자동 적용 아님 — 사용자가 켠다). */
    private fun showCarTypeHint() {
        val bydOwns = runCatching { activity.packageManager.getPackageInfo("com.byd.sing", 0); true }.getOrDefault(false)
        carTypeText.text = if (bydOwns) {
            "🚗 씨라이언7(DiLink5) 감지 — 순정마이크는 Gboard 권장(+아래 '씨라이언7 전용 모드' 켜기). 비순정 마이크면 Groq도 시도해보세요."
        } else {
            "🚗 DiLink3(돌핀·아토3·씰) 감지 — Groq 권장. 순정마이크면 아래 '순정 마이크 사용'도 켜세요(자동으로 소리 잡습니다)."
        }
        carTypeText.visibility = View.VISIBLE
    }

    private fun save() {
        settings.openaiApiKey = openaiKey.text.toString()
        settings.geminiModel = if (geminiModelGroup.checkedRadioButtonId == R.id.gm_lite) "flash-lite" else "flash"
        settings.syncOffsetMs = syncSeek.progress - 300
        settings.recordRateHz = when (rateGroup.checkedRadioButtonId) {
            R.id.rate_save -> 16000
            R.id.rate_min -> 11025
            else -> 22050
        }
        settings.scoringEnabled = scoringCheck.isChecked
        settings.recordingEnabled = recordingCheck.isChecked
        settings.micSourceName = when (micSourceGroup.checkedRadioButtonId) {
            R.id.ms_mic -> "MIC"
            R.id.ms_recognition -> "VOICE_RECOGNITION"
            R.id.ms_comm -> "VOICE_COMMUNICATION"
            else -> "AUTO"
        }
        settings.voiceGainPct = voiceGainSeek.progress + 50
        settings.accompGainPct = accompGainSeek.progress
        settings.micButtonControl = micButtonCheck.isChecked
        settings.nativeMicMode = nativeMicCheck.isChecked
        settings.startFullscreen = startFullscreenCheck.isChecked
        settings.autoPlayVoiceFirst = autoplayCheck.isChecked
        settings.mapMicLong = selectedMap(R.id.map_mic_long)
        settings.mapMicDouble = selectedMap(R.id.map_mic_double)
        settings.mapVolUpDouble = selectedMap(R.id.map_vol_up2)
        settings.mapVolDownDouble = selectedMap(R.id.map_vol_down2)
        settings.wheelButtonControl = wheelButtonCheck.isChecked
        settings.sealionMode = sealionCheck.isChecked
        // 엔진 라디오가 곧 googleSttPreferred(Gboard) + sttEngine(Groq/Gemini) 을 결정한다.
        settings.googleSttPreferred = sttEngineGroup.checkedRadioButtonId == R.id.eng_gboard
        settings.sttEngine = if (sttEngineGroup.checkedRadioButtonId == R.id.eng_gemini) "gemini" else "server"
        settings.storageMode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        maxStorageInput.text.toString().toIntOrNull()?.let { if (it > 0) settings.maxStorageMb = it }
        // 유효하지 않은 입력이면 UI를 저장된 값으로 복원
        if (maxStorageInput.text.toString().toIntOrNull() == null || maxStorageInput.text.toString().toInt() <= 0) {
            maxStorageInput.setText(settings.maxStorageMb.toString())
        }
        refreshStorageInfo()
        host.onSettingsSaved()   // 저장 즉시 반영(음성 버튼 노출·물리버튼 등)
        savedSignature = currentSignature()
        Toast.makeText(activity, "저장되었습니다", Toast.LENGTH_SHORT).show()
    }


    /** 차 내부·SD카드의 여유/사용 용량과 현재 저장 경로를 표시. */
    private fun setupMapSpinners() {
        val adapter = android.widget.ArrayAdapter(
            activity, android.R.layout.simple_spinner_item, MAP_FUNCTIONS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        fun bind(id: Int, code: String) {
            root.findViewById<android.widget.Spinner>(id).apply {
                this.adapter = adapter
                setSelection(MAP_FUNCTIONS.indexOfFirst { it.first == code }.coerceAtLeast(0))
            }
        }
        bind(R.id.map_mic_long, settings.mapMicLong)
        bind(R.id.map_mic_double, settings.mapMicDouble)
        bind(R.id.map_vol_up2, settings.mapVolUpDouble)
        bind(R.id.map_vol_down2, settings.mapVolDownDouble)
    }

    private fun selectedMap(id: Int): String =
        MAP_FUNCTIONS[root.findViewById<android.widget.Spinner>(id).selectedItemPosition].first

    /** 마이크 버튼 진단 — HID raw hex 를 화면에 보여주고, '로그 전송'으로 D1(운영자)에 보낸다. */
    /**
     * 마이크 소스 진단 — 각 소스로 실제 열어 실시간 레벨(dB)을 보여준다. 말했을 때 막대가 크게
     * 흔들리는 소스가 그 차에 맞는 소스. 찾은 소스를 바로 저장할 수 있다(유닛마다 되는 소스가 다름).
     */
    /** 마이크 버튼을 누른 직후 탭하면, 차가 기록한 BYD 브로드캐스트 이력을 로그로 남긴다. */
    private fun dumpBroadcasts(btn: Button) {
        btn.isEnabled = false
        btn.text = "📡 조사 중…"
        kotlin.concurrent.thread {
            val ok = com.cseini.byd.karaoke.update.UpdateManager.dumpBydBroadcasts(activity)
            activity.runOnUiThread {
                btn.isEnabled = true
                btn.text = "📡 버튼 신호 조사 (누른 뒤 여기를 탭)"
                Toast.makeText(
                    activity,
                    if (ok) "기록했어요 — 이제 '로그 전송'을 눌러주세요"
                    else "조사 실패 — USB 디버깅이 꺼져 있을 수 있어요",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setupGboard(btn: Button) {
        btn.isEnabled = false
        btn.text = "⌨️ 설치 중…"
        // 약 80MB 다운로드 진행율 팝업. 회선 불안정 유닛은 이어받기라 오래 걸릴 수 있어 상태를 보여준다.
        val dlg = android.app.AlertDialog.Builder(activity)
            .setTitle("Gboard 설치 (약 80MB)")
            .setMessage("다운로드 준비 중… (끊겨도 이어받아요)")
            .setCancelable(false)
            .create()
        dlg.show()
        kotlin.concurrent.thread {
            val ok = com.cseini.byd.karaoke.update.UpdateManager.setupGboard(activity) { pct ->
                activity.runOnUiThread {
                    dlg.setMessage(if (pct >= 100) "설치 중… (잠시만요)" else "다운로드 중… $pct% (약 80MB · 끊기면 이어받기)")
                }
            }
            activity.runOnUiThread {
                runCatching { dlg.dismiss() }
                btn.isEnabled = true
                btn.text = "⌨️ Gboard 설치·기본키보드로 설정 (약 80MB)"
                if (ok) {
                    // 설치·enable 성공. 기본키보드 지정이 ADB 로 됐는지와 무관하게, 그 자리에서 Gboard 를
                    // 고를 수 있게 키보드 선택창을 띄운다(기본키보드로 안 바꿔도 이 검색 세션에 Gboard 사용).
                    Toast.makeText(activity, "설치 완료 — 키보드 선택창에서 Gboard 를 고른 뒤 검색창에 대고 마이크 버튼을 눌러보세요", Toast.LENGTH_LONG).show()
                    runCatching {
                        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
                    }
                } else {
                    // dadb(ADB 5555) 미개방이면 자동 설치·기본지정이 실패한다(태블릿·폰 등). Gboard 가 이미
                    // 있으면 설치가 필요 없으니, '실패'가 아니라 키보드 선택창으로 그 자리에서 고르게 한다.
                    val hasGboard = runCatching {
                        activity.packageManager.getPackageInfo("com.google.android.inputmethod.latin", 0); true
                    }.getOrDefault(false)
                    if (hasGboard) {
                        Toast.makeText(activity, "Gboard 가 이미 있어요 — 키보드 선택창에서 Gboard 를 고른 뒤 검색창에 대고 마이크를 눌러보세요", Toast.LENGTH_LONG).show()
                        runCatching {
                            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
                        }
                    } else {
                        Toast.makeText(activity, "다운로드가 끊겼어요(약 80MB, 회선 문제). 버튼을 다시 누르면 받은 지점부터 이어받습니다 — 몇 번 눌러 채우면 됩니다. 계속 안 되면 '로그 전송'.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showMicSourceCheck() {
        val ctx = activity
        val sources = listOf(
            Triple("자동(원음 UNPROCESSED)", android.media.MediaRecorder.AudioSource.UNPROCESSED, "AUTO"),
            Triple("일반 MIC", android.media.MediaRecorder.AudioSource.MIC, "MIC"),
            Triple("음성인식(VOICE_RECOGNITION)", android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
            Triple("통화(VOICE_COMMUNICATION)", android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
        )
        val primary = androidx.core.content.ContextCompat.getColor(ctx, R.color.tj_text_primary)
        val group = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        sources.forEachIndexed { i, (name, _, _) ->
            group.addView(android.widget.RadioButton(ctx).apply { id = i; text = name; textSize = 15f; setTextColor(primary) })
        }
        val level = TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 15f; setTextColor(primary); setPadding(0, 16, 0, 8)
            text = "레벨 [······························] --dB"
        }
        val info = TextView(ctx).apply { textSize = 13f; setTextColor(primary) }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(40, 24, 40, 12)
            addView(TextView(ctx).apply {
                text = "소스를 하나씩 골라 마이크에 대고 말해보세요.\n막대가 크게 흔들리는 소스가 이 차에 맞는 소스입니다."
                textSize = 13f; setTextColor(primary); setPadding(0, 0, 0, 12)
            })
            addView(group); addView(level); addView(info)
        }

        var rec: com.cseini.byd.karaoke.audio.AudioRecorder? = null
        val tmp = java.io.File(ctx.cacheDir, "src_probe.wav")
        fun stop() { rec?.let { runCatching { it.stop() } }; rec = null }
        fun start(src: Int, name: String) {
            stop()
            val r = com.cseini.byd.karaoke.audio.AudioRecorder(ctx, settings, 16000, sourceOverride = src, forceEffects = false)
            val err = r.start(tmp) { db ->
                activity.runOnUiThread {
                    val bars = (((db + 60f) / 60f) * 30f).toInt().coerceIn(0, 30)
                    level.text = "레벨 [" + "█".repeat(bars) + "·".repeat(30 - bars) + "] ${db.toInt()}dB"
                }
            }
            rec = r
            info.text = if (err == null) "▶ $name — 지금 말해보세요" else "✕ 이 소스는 열 수 없음: $err"
        }
        group.setOnCheckedChangeListener { _, id -> if (id in sources.indices) start(sources[id].second, sources[id].first) }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🎚 마이크 소스 진단")
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("이 소스로 저장") { _, _ ->
                val id = group.checkedRadioButtonId
                if (id in sources.indices) {
                    val wasClean = currentSignature() == savedSignature
                    settings.micSourceName = sources[id].third
                    micSourceGroup.check(
                        when (sources[id].third) {
                            "MIC" -> R.id.ms_mic
                            "VOICE_RECOGNITION" -> R.id.ms_recognition
                            "VOICE_COMMUNICATION" -> R.id.ms_comm
                            else -> R.id.ms_auto
                        },
                    )
                    if (wasClean) savedSignature = currentSignature()
                    Toast.makeText(ctx, "${sources[id].first} 소스로 저장했어요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .setOnDismissListener { stop() }
            .show()
        group.check(0)   // 첫 소스로 바로 시작
    }

    private fun showMicDiag() {
        val tv = TextView(activity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(28, 20, 28, 20)
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(tv) }
        val lines = StringBuilder("마이크 버튼을 하나씩 눌러보세요 (마이크 / 볼륨▲ / 볼륨▼)\n\n")
        tv.text = lines
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("🔍 마이크 진단")
            .setView(scroll)
            .setPositiveButton("닫고 로그 전송") { _, _ ->
                LogUploader.uploadNow(activity) { ok ->
                    Toast.makeText(activity, if (ok) "진단 로그를 보냈어요. 감사합니다!" else "전송 실패", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("닫기", null)
            .setOnDismissListener { host.onMicDiagStop() }
            .show()
        host.onMicDiagStart { line ->
            lines.append(line).append('\n')
            tv.text = lines
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            CrashLog.event(activity, "micdiag $line")   // 로그 전송 시 D1 로 감
        }
    }

    /**
     * 마이크 버튼 학습: 버튼을 하나씩 눌러보게 해 이 마이크의 HID 코드를 자동으로 찾아 저장.
     * 기종마다 코드가 달라도 학습 한 번이면 버튼 제어를 쓸 수 있다.
     */
    private fun showMicLearn() {
        val steps = listOf("🔊 볼륨▲", "🔉 볼륨▼", "🎙 마이크(음성)")
        val results = arrayOfNulls<Pair<Int, Int>>(3)
        var step = 0
        val tv = TextView(activity).apply { textSize = 15f; setPadding(36, 24, 36, 24) }
        fun render() {
            val sb = StringBuilder()
            for (i in steps.indices) {
                val r = results[i]
                sb.append(
                    when {
                        r != null -> "✅ ${steps[i]}  (코드 [${r.first}]=0x%02X)\n\n".format(r.second)
                        i == step -> "👉 ${steps[i]} 버튼을 짧게 눌렀다 떼세요\n\n"
                        else -> "· ${steps[i]}\n\n"
                    }
                )
            }
            if (step >= steps.size) sb.append("🎉 완료! 저장했습니다. 이제 이 마이크로 제어할 수 있어요.")
            tv.text = sb.toString()
        }
        render()
        val dlg = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("🎯 마이크 버튼 학습")
            .setView(android.widget.ScrollView(activity).apply { addView(tv) })
            .setNegativeButton("닫기", null)
            .create()
        dlg.setOnDismissListener { host.onMicLearnStop() }
        dlg.show()
        var lastCap = 0L
        val wasClean = currentSignature() == savedSignature
        host.onMicLearnStart { idx, v, _ ->
            val now = android.os.SystemClock.elapsedRealtime()
            // 한 번의 누름에서 여러 변화가 오거나 연타해도 한 단계씩만 진행
            if (step >= steps.size || now - lastCap < 700) return@onMicLearnStart
            lastCap = now
            results[step] = idx to v
            step++
            if (step >= steps.size) {
                settings.hidVolUpCode = "${results[0]!!.first}:${results[0]!!.second}"
                settings.hidVolDownCode = "${results[1]!!.first}:${results[1]!!.second}"
                settings.hidMicCode = "${results[2]!!.first}:${results[2]!!.second}"
                // 버튼 제어 옵션도 같이 켜준다(학습했다는 건 쓰겠다는 뜻)
                settings.micButtonControl = true
                // USB HID와 씨라이언은 상호배타(라인 132 주석 참고)
                settings.sealionMode = false
                micButtonCheck.isChecked = true
                if (wasClean) savedSignature = currentSignature()
            }
            render()
        }
    }


    private fun recordingCount(mode: String): Int =
        Storage.recordingsDir(activity, mode).listFiles()?.count { it.isFile } ?: 0

    private fun refreshStorageInfo() {
        val mode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        activity.lifecycleScope.launch {
            val intBase = Storage.internalBase(activity)
            val intUsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Storage.usedBytes(Storage.recordingsDir(activity, "internal"))
            }
            val intCnt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recordingCount("internal")
            }
            val sb = StringBuilder()
            sb.append("차 내부: 여유 ${Storage.formatSize(Storage.freeBytes(intBase))} · 녹음 ${intCnt}개 ${Storage.formatSize(intUsed)}\n")
            val sd = Storage.sdBase(activity)
            if (sd != null) {
                val sdUsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Storage.usedBytes(Storage.recordingsDir(activity, "sd"))
                }
                val sdCnt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    recordingCount("sd")
                }
                sb.append("SD카드: 여유 ${Storage.formatSize(Storage.freeBytes(sd))} · 녹음 ${sdCnt}개 ${Storage.formatSize(sdUsed)}\n")
            } else {
                sb.append("SD카드: 없음\n")
            }
            sb.append("저장 경로: ${Storage.recordingsDir(activity, mode).absolutePath}")
            storageInfo.text = sb.toString()
        }
    }

    /** 해당 위치(내부/SD)의 녹음 파일과 목록 항목을 전부 삭제(확인 후). */
    private fun clearRecordings(mode: String, label: String) {
        val dir = Storage.recordingsDir(activity, mode)
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(activity, "$label 에 지울 녹음이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = files.sumOf { it.length() }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("$label 녹음 전체 삭제")
            .setMessage("${files.size}개 (${Storage.formatSize(bytes)})를 모두 지웁니다.\n되돌릴 수 없습니다.")
            .setPositiveButton("전체 삭제") { _, _ ->
                files.forEach { runCatching { it.delete() } }
                com.cseini.byd.karaoke.data.RecordingStore(activity).removeByPaths(files.map { it.absolutePath })
                host.onRecordingsChanged()
                refreshStorageInfo()
                Toast.makeText(activity, "$label 녹음 ${files.size}개 삭제됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 폰으로 키 입력: 로컬 서버+QR을 띄워 폰에서 키(최대 3개)를 붙여넣어 전송받는다. */
    private fun showKeyQr() {
        val url = com.cseini.byd.karaoke.share.KeyEntryServer.start(activity)
        if (url == null) {
            Toast.makeText(activity, "네트워크에 연결돼 있지 않습니다. 차 핫스팟/WiFi를 확인하세요.", Toast.LENGTH_LONG).show()
            return
        }
        val view = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_key, null)
        val status = view.findViewById<TextView>(R.id.key_status)
        // 주소를 눌러 다른 후보 IP로 전환 가능(유닛마다 인터페이스가 달라 자동 선택이 틀릴 수 있음).
        com.cseini.byd.karaoke.share.QrSwitcher.bind(
            view.findViewById(R.id.key_qr),
            view.findViewById(R.id.key_url),
            status,
            com.cseini.byd.karaoke.share.QrSwitcher.portOf(url, 8095),
        )
        val wasClean = currentSignature() == savedSignature
        com.cseini.byd.karaoke.share.KeyEntryServer.onSaved = {
            // 폰이 키를 전송 → 차 화면 입력칸 갱신 + 상태 표시(서버가 이미 설정에 저장함).
            openaiKey.setText(settings.openaiApiKey)
            status.text = "✅ 폰에서 키를 받았어요. 저장 완료."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.tj_green))
            if (wasClean) savedSignature = currentSignature()
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("닫기", null)
            .setOnDismissListener { com.cseini.byd.karaoke.share.KeyEntryServer.stop() }
            .show()
    }

    /** 노래 중 튕김처럼 재현이 어려운 문제의 이벤트 로그를 지금 서버로 보낸다(운영자 제보용). */
    private fun sendLog(btn: Button) {
        btn.isEnabled = false
        btn.text = "전송 중…"
        LogUploader.uploadNow(activity) { ok ->
            btn.isEnabled = true
            btn.text = "로그 전송"
            android.widget.Toast.makeText(
                activity,
                if (ok) "로그를 보냈어요. 감사합니다!" else "보낼 로그가 없거나 네트워크 오류예요.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun checkUpdate() {
        updateStatus.text = "업데이트 확인 중…"
        activity.lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate()
            if (release == null) {
                updateStatus.text = "최신 버전입니다 (v${BuildConfig.VERSION_NAME})"
                return@launch
            }
            updateStatus.text = "새 버전 v${release.version}"
            com.cseini.byd.karaoke.update.UpdateFlow.start(activity, release)
        }
    }
}
