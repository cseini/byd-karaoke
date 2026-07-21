package com.cseini.byd.karaoke

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.update.UpdateManager
import kotlinx.coroutines.launch

/** 설정: 검색 방식(API/키없이) + API 키 + 수동 업데이트 확인. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var keyless: CheckBox
    private lateinit var apiKey: EditText
    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)

        keyless = findViewById(R.id.chk_keyless)
        apiKey = findViewById(R.id.api_key_input)
        updateStatus = findViewById(R.id.update_status)

        keyless.isChecked = settings.keylessSearch
        apiKey.setText(settings.youtubeApiKey)
        updateStatus.text = "현재 버전 v${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }

        NavBar.wire(this, SettingsActivity::class.java)
    }

    private fun save() {
        settings.searchMode = if (keyless.isChecked) "keyless" else "api"
        settings.youtubeApiKey = apiKey.text.toString()
        Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
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
