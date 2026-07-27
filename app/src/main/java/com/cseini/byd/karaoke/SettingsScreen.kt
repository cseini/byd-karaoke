package com.cseini.byd.karaoke

import android.app.Activity
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

/** 설정 화면(Activity/임베드 공용): 검색 방식·API 키·싱크·마이크·저장·수동 업데이트. */
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
    private val recordingCheck: CheckBox = root.findViewById(R.id.chk_recording)
    private val micSourceGroup: RadioGroup = root.findViewById(R.id.mic_source_group)
    private val micButtonCheck: CheckBox = root.findViewById(R.id.chk_mic_button)
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
        recordingCheck.isChecked = settings.recordingEnabled

        micSourceGroup.check(
            when (settings.micSourceName) {
                "MIC" -> R.id.ms_mic
                "VOICE_RECOGNITION" -> R.id.ms_recognition
                "VOICE_COMMUNICATION" -> R.id.ms_comm
                else -> R.id.ms_auto
            }
        )
        micButtonCheck.isChecked = settings.micButtonControl
        wheelButtonCheck.isChecked = settings.wheelButtonControl

        storageGroup.check(if (settings.storageMode == "sd") R.id.st_sd else R.id.st_internal)
        maxStorageInput.setText(settings.maxStorageMb.toString())
        storageGroup.setOnCheckedChangeListener { _, _ -> refreshStorageInfo() }
        refreshStorageInfo()

        root.findViewById<Button>(R.id.btn_back).setOnClickListener { host.onScreenBack() }
        root.findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        root.findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }
        root.findViewById<Button>(R.id.btn_key_qr).setOnClickListener { showKeyQr() }

        root.findViewById<View>(R.id.navbar)?.visibility = if (host.embedded) View.GONE else View.VISIBLE
        if (!host.embedded) (activity as? Activity)?.let { NavBar.wire(it, SettingsActivity::class.java) }
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
        settings.recordingEnabled = recordingCheck.isChecked
        settings.micSourceName = when (micSourceGroup.checkedRadioButtonId) {
            R.id.ms_mic -> "MIC"
            R.id.ms_recognition -> "VOICE_RECOGNITION"
            R.id.ms_comm -> "VOICE_COMMUNICATION"
            else -> "AUTO"
        }
        settings.micButtonControl = micButtonCheck.isChecked
        settings.wheelButtonControl = wheelButtonCheck.isChecked
        settings.storageMode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        maxStorageInput.text.toString().toIntOrNull()?.let { if (it > 0) settings.maxStorageMb = it }
        refreshStorageInfo()
        Toast.makeText(activity, "저장되었습니다", Toast.LENGTH_SHORT).show()
    }

    /** 차 내부·SD카드의 여유/사용 용량과 현재 저장 경로를 표시. */
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
        view.findViewById<android.widget.ImageView>(R.id.key_qr)
            .setImageBitmap(com.cseini.byd.karaoke.share.qrBitmap(url, 480))
        view.findViewById<TextView>(R.id.key_url).text = url
        val status = view.findViewById<TextView>(R.id.key_status)
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

    private fun checkUpdate() {
        updateStatus.text = "업데이트 확인 중…"
        activity.lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate()
            if (release == null) {
                updateStatus.text = "최신 버전입니다 (v${BuildConfig.VERSION_NAME})"
                return@launch
            }
            updateStatus.text = "새 버전 v${release.version} 다운로드 중…"
            val apk = UpdateManager.download(activity, release) { p ->
                activity.runOnUiThread { updateStatus.text = "다운로드 중… $p%" }
            }
            if (apk == null) {
                updateStatus.text = "다운로드 실패 — 네트워크를 확인하세요"
                return@launch
            }
            updateStatus.text = "v${release.version} 설치를 진행하세요"
            UpdateManager.install(activity, apk)
        }
    }
}
