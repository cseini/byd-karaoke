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

    private val openaiKey: EditText = root.findViewById(R.id.openai_key_input)
    private val openaiKey2: EditText = root.findViewById(R.id.openai_key_input2)
    private val openaiKey3: EditText = root.findViewById(R.id.openai_key_input3)
    private val geminiModelGroup: RadioGroup = root.findViewById(R.id.gemini_model_group)
    private val syncSeek: SeekBar = root.findViewById(R.id.sync_seek)
    private val syncLabel: TextView = root.findViewById(R.id.sync_label)
    private val rateGroup: RadioGroup = root.findViewById(R.id.rate_group)
    private val scoringCheck: CheckBox = root.findViewById(R.id.chk_scoring)
    private val scoreDebugCheck: CheckBox = root.findViewById(R.id.chk_score_debug_dump)
    private val logUploadCheck: CheckBox = root.findViewById(R.id.chk_log_upload)
    private val recordingCheck: CheckBox = root.findViewById(R.id.chk_recording)
    private val micSourceGroup: RadioGroup = root.findViewById(R.id.mic_source_group)
    private val voiceGainSeek: SeekBar = root.findViewById(R.id.voice_gain_seek)
    private val voiceGainLabel: TextView = root.findViewById(R.id.voice_gain_label)
    private val accompGainSeek: SeekBar = root.findViewById(R.id.accomp_gain_seek)
    private val accompGainLabel: TextView = root.findViewById(R.id.accomp_gain_label)
    private val micButtonCheck: CheckBox = root.findViewById(R.id.chk_mic_button)
    private val startFullscreenCheck: CheckBox = root.findViewById(R.id.chk_start_fullscreen)
    private val wheelButtonCheck: CheckBox = root.findViewById(R.id.chk_wheel_button)
    private val updateStatus: TextView = root.findViewById(R.id.update_status)
    private val storageGroup: RadioGroup = root.findViewById(R.id.storage_group)
    private val storageInfo: TextView = root.findViewById(R.id.storage_info)
    private val maxStorageInput: EditText = root.findViewById(R.id.max_storage_input)

    init {
        openaiKey.setText(settings.openaiApiKey)
        openaiKey2.setText(settings.openaiApiKey2)
        openaiKey3.setText(settings.openaiApiKey3)
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
        scoreDebugCheck.isChecked = settings.scoreDebugDump
        logUploadCheck.isChecked = settings.logUpload
        recordingCheck.isChecked = settings.recordingEnabled

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
        startFullscreenCheck.isChecked = settings.startFullscreen
        setupMapSpinners()
        wheelButtonCheck.isChecked = settings.wheelButtonControl

        storageGroup.check(if (settings.storageMode == "sd") R.id.st_sd else R.id.st_internal)
        maxStorageInput.setText(settings.maxStorageMb.toString())
        storageGroup.setOnCheckedChangeListener { _, _ -> refreshStorageInfo() }
        refreshStorageInfo()

        root.findViewById<Button>(R.id.btn_back).setOnClickListener { host.onScreenBack() }
        root.findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        root.findViewById<Button>(R.id.btn_mic_diag).setOnClickListener { showMicDiag() }
        root.findViewById<Button>(R.id.btn_mic_learn).setOnClickListener { showMicLearn() }
        root.findViewById<Button>(R.id.btn_score_debug).setOnClickListener { shareScoreDebug() }

        // 고급 설정 접기/펼치기 — 자주 안 쓰는 항목은 숨겨 화면을 단순하게
        val advanced = root.findViewById<View>(R.id.advanced_section)
        val advBtn = root.findViewById<Button>(R.id.btn_advanced)
        val scroll = root.findViewById<android.widget.ScrollView>(R.id.settings_scroll)
        advBtn.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advBtn.text = if (show) "⚙ 고급 설정 접기 ▾" else "⚙ 고급 설정 펼치기 ▸"
            // 펼칠 때 새로 나타난 내용이 보이도록 버튼 위치로 스크롤(반응 없어 보이는 문제 해결)
            if (show) scroll.post { scroll.smoothScrollTo(0, advBtn.top) }
        }
        root.findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
        root.findViewById<Button>(R.id.btn_event_log).setOnClickListener { showEventLog() }
        root.findViewById<Button>(R.id.btn_key_qr).setOnClickListener { showKeyQr() }

        NavBar.wireEmbedded(root, "settings") { host.onNavigate(it) }
    }

    private fun updateGainLabels() {
        voiceGainLabel.text = "목소리 ${voiceGainSeek.progress + 50}%"
        accompGainLabel.text = "반주 ${accompGainSeek.progress}%"
    }

    private fun updateSyncLabel() {
        val v = syncSeek.progress - 300
        syncLabel.text = "${if (v > 0) "+" else ""}$v ms"
    }

    private fun save() {
        settings.openaiApiKey = openaiKey.text.toString()
        settings.openaiApiKey2 = openaiKey2.text.toString()
        settings.openaiApiKey3 = openaiKey3.text.toString()
        settings.geminiModel = if (geminiModelGroup.checkedRadioButtonId == R.id.gm_lite) "flash-lite" else "flash"
        settings.syncOffsetMs = syncSeek.progress - 300
        settings.recordRateHz = when (rateGroup.checkedRadioButtonId) {
            R.id.rate_save -> 16000
            R.id.rate_min -> 11025
            else -> 22050
        }
        settings.scoringEnabled = scoringCheck.isChecked
        settings.scoreDebugDump = scoreDebugCheck.isChecked
        settings.logUpload = logUploadCheck.isChecked
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
        settings.startFullscreen = startFullscreenCheck.isChecked
        settings.mapMicLong = selectedMap(R.id.map_mic_long)
        settings.mapMicDouble = selectedMap(R.id.map_mic_double)
        settings.mapVolUpDouble = selectedMap(R.id.map_vol_up2)
        settings.mapVolDownDouble = selectedMap(R.id.map_vol_down2)
        settings.wheelButtonControl = wheelButtonCheck.isChecked
        settings.storageMode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        maxStorageInput.text.toString().toIntOrNull()?.let { if (it > 0) settings.maxStorageMb = it }
        refreshStorageInfo()
        host.onSettingsSaved()   // 저장 즉시 반영(음성 버튼 노출·물리버튼 등)
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
                micButtonCheck.isChecked = true
            }
            render()
        }
    }

    /**
     * 마이크 버튼 진단: 버튼 신호(HID hex)를 화면에 그대로 보여주고 복사하게 한다.
     * adb 없이도 사용자가 채보 결과를 카페에 붙여넣을 수 있게 하는 용도(기종별 지원 확대).
     */
    private fun showMicDiag() {
        val tv = TextView(activity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(28, 20, 28, 20)
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(tv) }
        val lines = StringBuilder()
            .append("앱 v${BuildConfig.VERSION_NAME} 마이크 버튼 진단\n")
            .append("마이크 버튼을 하나씩 눌러보세요.\n")
            .append("(볼륨▲ 2초 꾹 → 볼륨▼ 2초 꾹 → 마이크 2초 꾹, 사이에 1초 쉬고)\n\n")
        tv.text = lines
        val dlg = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("🔍 마이크 버튼 진단")
            .setView(scroll)
            .setPositiveButton("결과 복사", null)
            .setNegativeButton("닫기", null)
            .create()
        dlg.setOnShowListener {
            // 기본 리스너는 자동으로 닫히므로 복사 버튼만 직접 연결(닫지 않고 계속 채보 가능)
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("mic-diag", lines.toString()))
                Toast.makeText(activity, "복사되었습니다 — 카페 댓글에 붙여넣어 주세요", Toast.LENGTH_LONG).show()
            }
        }
        dlg.setOnDismissListener { host.onMicDiagStop() }
        dlg.show()
        host.onMicDiagStart { line ->
            lines.append(line).append('\n')
            tv.text = lines
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 마지막 채점의 디버그 zip(목소리·반주 11kHz + 지표)을 폰으로 — 채점 문제 제보용. */
    private var debugShare: com.cseini.byd.karaoke.share.FileShareServer? = null
    private fun shareScoreDebug() {
        val zip = java.io.File(activity.filesDir, "scoredebug/score-debug.zip")
        if (!zip.exists()) {
            val msg = if (settings.scoreDebugDump)
                "아직 채점 기록이 없습니다. 한 곡 부른 뒤 다시 시도하세요."
            else
                "'채점 디버그 파일 저장'을 켜고 저장한 뒤, 한 곡 부르고 다시 시도하세요."
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { debugShare?.stop() }
        val server = com.cseini.byd.karaoke.share.FileShareServer(zip)
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        debugShare = server
        val view = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_share, null)
        com.cseini.byd.karaoke.share.QrSwitcher.bind(
            view.findViewById(R.id.share_qr),
            view.findViewById(R.id.share_url),
            view.findViewById(R.id.share_hint),
            server.listeningPort,
        )
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("🧪 채점 디버그 공유")
            .setView(view)
            .setPositiveButton("닫기", null)
            .setOnDismissListener { runCatching { debugShare?.stop() }; debugShare = null }
            .show()
    }

    private fun refreshStorageInfo() {
        val mode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        val intBase = Storage.internalBase(activity)
        val intUsed = Storage.usedBytes(Storage.recordingsDir(activity, "internal"))
        val sb = StringBuilder()
        sb.append("차 내부: 여유 ${Storage.formatSize(Storage.freeBytes(intBase))} · 녹음 ${Storage.formatSize(intUsed)}\n")
        val sd = Storage.sdBase(activity)
        if (sd != null) {
            val sdUsed = Storage.usedBytes(Storage.recordingsDir(activity, "sd"))
            sb.append("SD카드: 여유 ${Storage.formatSize(Storage.freeBytes(sd))} · 녹음 ${Storage.formatSize(sdUsed)}\n")
        } else {
            sb.append("SD카드: 없음\n")
        }
        sb.append("저장 경로: ${Storage.recordingsDir(activity, mode).absolutePath}")
        storageInfo.text = sb.toString()
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
        com.cseini.byd.karaoke.share.KeyEntryServer.onSaved = {
            // 폰이 키를 전송 → 차 화면 입력칸 갱신 + 상태 표시(서버가 이미 설정에 저장함).
            openaiKey.setText(settings.openaiApiKey)
            openaiKey2.setText(settings.openaiApiKey2)
            openaiKey3.setText(settings.openaiApiKey3)
            status.text = "✅ 폰에서 키를 받았어요. 저장 완료."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.tj_green))
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("닫기", null)
            .setOnDismissListener { com.cseini.byd.karaoke.share.KeyEntryServer.stop() }
            .show()
    }

    /** 노래 중 튕김처럼 재현이 어려운 문제를 그 자리에서 확인·복사해 제보하기 위한 로그 뷰어. */
    private fun showEventLog() {
        val body = CrashLog.recent(activity) ?: "기록된 이벤트가 없습니다."
        val tv = TextView(activity).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            text = body
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("이벤트 로그 — 복사해 제보해주세요")
            .setView(android.widget.ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("events", body))
                android.widget.Toast.makeText(activity, "복사되었습니다", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
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
