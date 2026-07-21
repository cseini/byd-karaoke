package com.cseini.byd.karaoke

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cseini.byd.karaoke.audio.WavIo
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Phase 0 스파이크. 실차(BYD 돌핀 DiLink)에서 두 가지 make-or-break 게이트를 눈으로 검증.
 *  0-A: android-youtube-player 로 유튜브 IFrame 이 실제로 렌더되는가 (블랙스크린/오디오온리 여부).
 *  0-B: 반주 재생 중 USB 마이크로 채점 가능한 보컬 PCM 을 뜰 수 있는가 (WAV 로 저장 → 오프라인 YIN).
 * 채점·검색·예약은 이 게이트 통과 후 Phase 1/2 에서 구현. 여기엔 없음.
 */
class DiagnosticsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "karaoke-diag"
        private const val SAMPLE_RATE = 44100
        private const val REQ_MIC = 1001
    }

    private lateinit var playerView: YouTubePlayerView
    private var youTubePlayer: YouTubePlayer? = null

    private lateinit var playerStatus: TextView
    private lateinit var recStatus: TextView
    private lateinit var envInfo: TextView
    private lateinit var videoIdInput: EditText
    private lateinit var sourceGroup: RadioGroup
    private lateinit var chkAec: CheckBox
    private lateinit var chkPreferUsb: CheckBox
    private lateinit var btnRecord: Button

    @Volatile private var recording = false
    private var recThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        playerStatus = findViewById(R.id.player_status)
        recStatus = findViewById(R.id.rec_status)
        envInfo = findViewById(R.id.env_info)
        videoIdInput = findViewById(R.id.video_id_input)
        sourceGroup = findViewById(R.id.source_group)
        chkAec = findViewById(R.id.chk_aec)
        chkPreferUsb = findViewById(R.id.chk_prefer_usb)
        btnRecord = findViewById(R.id.btn_record)

        // ---- 0-A: 유튜브 플레이어 ----
        playerView = findViewById(R.id.youtube_player_view)
        lifecycle.addObserver(playerView)
        playerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                this@DiagnosticsActivity.youTubePlayer = youTubePlayer
                setPlayerStatus("onReady — cueVideo 준비됨. '영상 로드'를 누르세요.")
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                setPlayerStatus("상태: $state  (PLAYING 이면서 화면이 검으면 하드웨어가속/WebView 문제)")
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                setPlayerStatus("onError: $error  (NOT_EMBEDDABLE 등)")
            }
        })

        findViewById<Button>(R.id.btn_load_video).setOnClickListener { loadVideo() }
        findViewById<Button>(R.id.btn_refresh_env).setOnClickListener { refreshEnv() }
        btnRecord.setOnClickListener { toggleRecord() }

        refreshEnv()
        ensureMicPermission()
    }

    // ---------- 0-A ----------

    private fun loadVideo() {
        val raw = videoIdInput.text.toString().trim()
        val id = extractVideoId(raw)
        if (id.isEmpty()) {
            toast("videoId 를 확인하세요")
            return
        }
        val player = youTubePlayer
        if (player == null) {
            toast("플레이어가 아직 준비되지 않았습니다")
            return
        }
        setPlayerStatus("loadVideo($id) 호출 — 렌더 확인 중…")
        player.loadVideo(id, 0f)
    }

    /** 전체 URL 이든 11자리 id 든 videoId 만 뽑아낸다. */
    private fun extractVideoId(input: String): String {
        if (input.length == 11 && !input.contains("/") && !input.contains("?")) return input
        Regex("(?:v=|/embed/|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})")
            .find(input)?.let { return it.groupValues[1] }
        return input
    }

    // ---------- 0-B ----------

    private fun toggleRecord() {
        if (recording) stopRecord() else startRecord()
    }

    private fun startRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensureMicPermission()
            toast("마이크 권한이 필요합니다")
            return
        }

        val source = selectedSource()
        val sourceName = selectedSourceName()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            toast("AudioRecord 버퍼 계산 실패 ($minBuf)")
            return
        }

        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) {
            toast("AudioRecord 생성 실패: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            toast("AudioRecord 초기화 실패 (소스=$sourceName 미지원 가능)")
            record.release()
            return
        }

        // USB 마이크 강제 라우팅
        var routedTo = "기본"
        if (chkPreferUsb.isChecked) {
            findUsbInputDevice()?.let {
                val ok = record.setPreferredDevice(it)
                routedTo = if (ok) "USB(id=${it.id})" else "USB 라우팅 거부됨→기본"
            } ?: run { routedTo = "USB 장치 없음→기본" }
        }

        // AEC (스피커 반주 취소 실험)
        var aecInfo = "off"
        if (chkAec.isChecked) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    val aec = AcousticEchoCanceler.create(record.audioSessionId)
                    if (aec != null) {
                        aec.enabled = true
                        aecInfo = "on(enabled=${aec.enabled})"
                    } else aecInfo = "create=null"
                } catch (e: Exception) {
                    aecInfo = "예외:${e.message}"
                }
            } else {
                aecInfo = "미지원(isAvailable=false)"
            }
        }

        val ts = System.currentTimeMillis()
        val fileName = "spike_${sourceName}_aec-${if (chkAec.isChecked) "on" else "off"}_$ts.wav"
        val outFile = File(getExternalFilesDir(null), fileName)
        val writer = WavIo.Writer(outFile, SAMPLE_RATE)

        record.startRecording()
        recording = true
        btnRecord.text = "■ 녹음 중지"
        setRecStatus("녹음 시작\n소스=$sourceName\n라우팅=$routedTo\nAEC=$aecInfo\n파일=${outFile.name}")

        recThread = thread(name = "mic-capture") {
            val buf = ShortArray(minBuf)
            var peakRms = 0.0
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        writer.write(buf, n)
                        val rms = rms(buf, n)
                        if (rms > peakRms) peakRms = rms
                        runOnUiThread { updateRms(rms, peakRms) }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read 오류: $n")
                        break
                    }
                }
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                writer.close()
                runOnUiThread {
                    setRecStatus("저장 완료: ${outFile.absolutePath}\npeakRMS=${"%.1f".format(peakRms)} dBFS\n(adb pull 로 가져가 tools/analyze_pitch.py 로 분석)")
                }
            }
        }
    }

    private fun stopRecord() {
        recording = false
        btnRecord.text = "● 녹음 시작"
        recThread?.join(1500)
        recThread = null
    }

    private fun selectedSource(): Int = when (sourceGroup.checkedRadioButtonId) {
        R.id.src_unprocessed ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        R.id.src_voice_recognition -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        R.id.src_voice_communication -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else -> MediaRecorder.AudioSource.MIC
    }

    private fun selectedSourceName(): String = when (sourceGroup.checkedRadioButtonId) {
        R.id.src_unprocessed -> "UNPROCESSED"
        R.id.src_voice_recognition -> "VOICE_RECOGNITION"
        R.id.src_voice_communication -> "VOICE_COMMUNICATION"
        else -> "MIC"
    }

    private fun findUsbInputDevice() =
        (getSystemService(AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = buf[i] / 32768.0
            sum += s * s
        }
        val r = sqrt(sum / n)
        return 20.0 * Math.log10(r + 1e-9)
    }

    private fun updateRms(rms: Double, peak: Double) {
        if (recording) setRecStatus("녹음 중…  RMS=${"%.1f".format(rms)} dBFS  peak=${"%.1f".format(peak)} dBFS")
    }

    // ---------- 환경 정보 ----------

    private fun refreshEnv() {
        val sb = StringBuilder()
        sb.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("기기: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("WebView: ${webViewVersion()}\n")

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        sb.append("UNPROCESSED 지원: ${unprocessed ?: "미보고(null)"}\n")
        sb.append("AEC 지원: ${AcousticEchoCanceler.isAvailable()}\n\n")

        sb.append("입력 장치 목록:\n")
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) sb.append("  (없음)\n")
        for (d in inputs) {
            sb.append("  - type=${typeName(d.type)} id=${d.id} \"${d.productName}\"\n")
        }
        envInfo.text = sb.toString()
        Log.i(TAG, sb.toString())
    }

    private fun webViewVersion(): String = try {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage()
        if (pkg != null) "${pkg.packageName} ${pkg.versionName}" else "알 수 없음"
    } catch (e: Exception) {
        "조회 실패: ${e.message}"
    }

    private fun typeName(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "type#$type"
    }

    // ---------- 공통 ----------

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            toast(if (granted) "마이크 권한 허용됨" else "마이크 권한 거부됨")
            refreshEnv()
        }
    }

    override fun onDestroy() {
        recording = false
        recThread?.join(1000)
        super.onDestroy()
    }

    private fun setPlayerStatus(s: String) = runOnUiThread { playerStatus.text = "플레이어 상태: $s" }
    private fun setRecStatus(s: String) = runOnUiThread { recStatus.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
