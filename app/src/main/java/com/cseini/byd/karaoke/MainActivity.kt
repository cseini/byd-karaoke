package com.cseini.byd.karaoke

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import com.cseini.byd.karaoke.update.UpdateManager
import com.cseini.byd.karaoke.voice.VoiceSearch
import kotlinx.coroutines.launch

/** 검색 홈. 타이핑/음성 검색 → 예약 또는 바로 부르기. */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var queue: QueueStore
    private lateinit var repo: YouTubeRepository
    private lateinit var voice: VoiceSearch

    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var btnQueue: Button
    private val adapter = ResultAdapter(
        onReserve = { reserve(it) },
        onPlayNow = { playNow(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        queue = QueueStore(this)
        repo = YouTubeRepository()
        voice = VoiceSearch(this, settings)

        searchInput = findViewById(R.id.search_input)
        status = findViewById(R.id.status)
        btnQueue = findViewById(R.id.btn_queue)

        findViewById<RecyclerView>(R.id.results).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener { doSearch() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoice() }
        btnQueue.setOnClickListener { startActivity(Intent(this, QueueActivity::class.java)) }
        findViewById<Button>(R.id.btn_diag).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        ensureMicPermission()
        if (!settings.hasApiKey()) {
            status.text = "먼저 [설정]에서 YouTube API 키를 입력하세요."
        }
        checkOtaUpdate()
    }

    /** GitHub Releases 에 새 버전이 있으면 내려받아 설치 화면을 띄운다. */
    private fun checkOtaUpdate() {
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate() ?: return@launch
            toast("새 버전 v${release.version} 다운로드 중…")
            val apk = UpdateManager.download(this@MainActivity, release) { p ->
                runOnUiThread { status.text = "업데이트 다운로드 중… $p%" }
            }
            if (apk == null) {
                status.text = "업데이트 다운로드 실패 — 네트워크 확인 후 앱을 다시 시작하세요"
                return@launch
            }
            status.text = "v${release.version} 설치를 진행하세요"
            UpdateManager.install(this@MainActivity, apk)
        }
    }

    override fun onResume() {
        super.onResume()
        updateQueueCount()
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) { status.text = "검색어를 입력하세요."; return }
        status.text = "검색 중…"
        lifecycleScope.launch {
            when (val r = repo.search(q, settings.youtubeApiKey, System.currentTimeMillis())) {
                is YouTubeRepository.Result.Ok -> {
                    adapter.submit(r.items)
                    status.text = if (r.items.isEmpty()) "결과가 없습니다." else "결과 ${r.items.size}개"
                }
                is YouTubeRepository.Result.Error -> status.text = r.message
            }
        }
    }

    private fun startVoice() {
        if (!voice.isAvailable()) {
            toast("이 기기는 음성 인식을 지원하지 않습니다. 타이핑으로 검색하세요.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ensureMicPermission(); toast("마이크 권한이 필요합니다"); return
        }
        status.text = "🎙 음성인식 준비 중… (첫 실행은 몇 초 걸립니다)"
        voice.start(
            onReady = { status.text = "🎙 말씀하세요…" },
            onResult = { text ->
                searchInput.setText(text)
                doSearch()
            },
            onError = { status.text = it },
        )
    }

    private fun reserve(item: QueueItem) {
        queue.add(item)
        updateQueueCount()
        toast("예약: ${item.title}")
    }

    private fun playNow(item: QueueItem) {
        startActivity(PlaybackActivity.intent(this, item.videoId, item.title, fromQueue = false))
    }

    private fun updateQueueCount() {
        btnQueue.text = "대기열 (${queue.size()})"
    }

    private fun showSettingsDialog() {
        val input = EditText(this).apply {
            hint = "YouTube Data API v3 키"
            setText(settings.youtubeApiKey)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("설정 — API 키")
            .setMessage("Google Cloud Console에서 발급한 YouTube Data API v3 키를 입력하세요.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                settings.youtubeApiKey = input.text.toString()
                status.text = if (settings.hasApiKey()) "API 키 저장됨." else "키가 비어 있습니다."
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
