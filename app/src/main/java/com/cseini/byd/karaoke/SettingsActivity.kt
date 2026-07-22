package com.cseini.byd.karaoke

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
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
    private lateinit var keyless: CheckBox
    private lateinit var apiKey: EditText
    private lateinit var updateStatus: TextView
    private lateinit var engineGroup: RadioGroup
    private lateinit var storageGroup: RadioGroup
    private lateinit var storageInfo: TextView
    private lateinit var maxStorageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)

        keyless = findViewById(R.id.chk_keyless)
        apiKey = findViewById(R.id.api_key_input)
        updateStatus = findViewById(R.id.update_status)
        engineGroup = findViewById(R.id.engine_group)

        keyless.isChecked = settings.keylessSearch
        apiKey.setText(settings.youtubeApiKey)
        updateStatus.text = "현재 버전 v${BuildConfig.VERSION_NAME}"
        engineGroup.check(
            if (settings.playbackEngine == "iframe") R.id.eng_iframe else R.id.eng_stream
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
        findViewById<Button>(R.id.btn_yt_login).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        NavBar.wire(this, SettingsActivity::class.java)
    }

    private fun save() {
        settings.searchMode = if (keyless.isChecked) "keyless" else "api"
        settings.youtubeApiKey = apiKey.text.toString()
        settings.playbackEngine =
            if (engineGroup.checkedRadioButtonId == R.id.eng_iframe) "iframe" else "stream"
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
