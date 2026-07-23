package com.cseini.byd.karaoke

import android.content.Intent
import android.os.Bundle
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

/** 설정: 검색 방식(API/키없이) + API 키 + 수동 업데이트 확인. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var openaiKey: EditText
    private lateinit var geminiModelGroup: RadioGroup
    private lateinit var syncSeek: SeekBar
    private lateinit var syncLabel: TextView
    private lateinit var rateGroup: RadioGroup
    private lateinit var updateStatus: TextView
    private lateinit var storageGroup: RadioGroup
    private lateinit var storageInfo: TextView
    private lateinit var maxStorageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)

        openaiKey = findViewById(R.id.openai_key_input)
        geminiModelGroup = findViewById(R.id.gemini_model_group)
        updateStatus = findViewById(R.id.update_status)

        openaiKey.setText(settings.openaiApiKey)
        geminiModelGroup.check(if (settings.geminiModel == "flash-lite") R.id.gm_lite else R.id.gm_flash)
        updateStatus.text = "현재 버전 v${BuildConfig.VERSION_NAME}"

        syncSeek = findViewById(R.id.sync_seek)
        syncLabel = findViewById(R.id.sync_label)
        syncSeek.progress = (settings.syncOffsetMs + 300).coerceIn(0, 600)
        updateSyncLabel()
        syncSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateSyncLabel()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        rateGroup = findViewById(R.id.rate_group)
        rateGroup.check(
            when (settings.recordRateHz) {
                16000 -> R.id.rate_save
                11025 -> R.id.rate_min
                else -> R.id.rate_standard
            }
        )

        storageGroup = findViewById(R.id.storage_group)
        storageInfo = findViewById(R.id.storage_info)
        maxStorageInput = findViewById(R.id.max_storage_input)
        storageGroup.check(if (settings.storageMode == "sd") R.id.st_sd else R.id.st_internal)
        maxStorageInput.setText(settings.maxStorageMb.toString())
        storageGroup.setOnCheckedChangeListener { _, _ -> refreshStorageInfo() }
        refreshStorageInfo()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }

        NavBar.wire(this, SettingsActivity::class.java)
    }

    private fun updateSyncLabel() {
        val v = syncSeek.progress - 300
        syncLabel.text = "${if (v > 0) "+" else ""}$v ms"
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
        settings.storageMode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        maxStorageInput.text.toString().toIntOrNull()?.let { if (it > 0) settings.maxStorageMb = it }
        refreshStorageInfo()
        Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
    }

    /** 차 내부·SD카드의 여유/사용 용량과 현재 저장 경로를 표시. */
    private fun refreshStorageInfo() {
        val mode = if (storageGroup.checkedRadioButtonId == R.id.st_sd) "sd" else "internal"
        val intBase = Storage.internalBase(this)
        val intUsed = Storage.usedBytes(Storage.recordingsDir(this, "internal"))
        val sb = StringBuilder()
        sb.append("차 내부: 여유 ${Storage.formatSize(Storage.freeBytes(intBase))} · 녹음 ${Storage.formatSize(intUsed)}\n")
        val sd = Storage.sdBase(this)
        if (sd != null) {
            val sdUsed = Storage.usedBytes(Storage.recordingsDir(this, "sd"))
            sb.append("SD카드: 여유 ${Storage.formatSize(Storage.freeBytes(sd))} · 녹음 ${Storage.formatSize(sdUsed)}\n")
        } else {
            sb.append("SD카드: 없음\n")
        }
        sb.append("저장 경로: ${Storage.recordingsDir(this, mode).absolutePath}")
        storageInfo.text = sb.toString()
    }

    private fun checkUpdate() {
        updateStatus.text = "업데이트 확인 중…"
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate()
            if (release == null) {
                updateStatus.text = "최신 버전입니다 (v${BuildConfig.VERSION_NAME})"
                return@launch
            }
            updateStatus.text = "새 버전 v${release.version} 다운로드 중…"
            val apk = UpdateManager.download(this@SettingsActivity, release) { p ->
                runOnUiThread { updateStatus.text = "다운로드 중… $p%" }
            }
            if (apk == null) {
                updateStatus.text = "다운로드 실패 — 네트워크를 확인하세요"
                return@launch
            }
            updateStatus.text = "v${release.version} 설치를 진행하세요"
            UpdateManager.install(this@SettingsActivity, apk)
        }
    }
}
