package com.cseini.byd.karaoke.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cseini.byd.karaoke.data.SettingsStore
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * 목소리로 검색어 입력(한국어).
 * 시스템 SpeechRecognizer(GMS)가 있으면 그걸 쓰고, 없으면(DiLink 등 중국계 AOSP)
 * APK 에 내장한 Vosk 한국어 모델로 완전 오프라인 인식한다.
 */
class VoiceSearch(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val ASSET_DIR = "model-ko"
        private const val SAMPLE_RATE = 16000
        private const val LISTEN_TIMEOUT_MS = 10_000L

        // 모델 로드가 수 초 걸리므로 프로세스당 1회만 로드해 재사용.
        @Volatile private var voskModel: Model? = null
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var voskListening = false
    private val main = Handler(Looper.getMainLooper())

    private fun systemSttAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun voskAssetsPresent(): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    fun isAvailable(): Boolean = systemSttAvailable() || voskAssetsPresent()

    /** onResult(인식문자열) 또는 onError(사유). onReady 는 말하기 시작해도 좋다는 신호. */
    fun start(
        onReady: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (systemSttAvailable()) startSystem(onReady, onResult, onError)
        else if (voskAssetsPresent()) startVosk(onReady, onResult, onError)
        else onError("이 기기에서 음성 인식을 사용할 수 없습니다 (타이핑으로 검색하세요)")
    }

    fun stop() {
        voskListening = false
        recognizer?.run { stopListening(); destroy() }
        recognizer = null
    }

    // ── 경로 1: 시스템 SpeechRecognizer ──────────────────────────────

    private fun startSystem(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReady()
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) onError("인식된 내용이 없습니다") else onResult(text)
            }
            override fun onError(error: Int) = onError(errorText(error))
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    // ── 경로 2: Vosk 오프라인 인식 ───────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startVosk(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (voskListening) return
        voskListening = true
        thread(name = "vosk-stt") {
            var record: AudioRecord? = null
            var rz: Recognizer? = null
            try {
                val model = voskModel ?: Model(unpackModel().absolutePath).also { voskModel = it }
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, SAMPLE_RATE)
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    main.post { onError("마이크를 열 수 없습니다") }
                    return@thread
                }
                if (settings.preferUsbMic) findUsbInput()?.let { record.setPreferredDevice(it) }

                rz = Recognizer(model, SAMPLE_RATE.toFloat())
                record.startRecording()
                main.post(onReady)

                val buf = ShortArray(2048)
                val deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS
                var text = ""
                while (voskListening && System.currentTimeMillis() < deadline) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // acceptWaveForm 이 true = 무음으로 발화 종료를 감지한 시점
                    if (rz.acceptWaveForm(buf, n)) {
                        text = JSONObject(rz.result).optString("text").trim()
                        if (text.isNotEmpty()) break
                    }
                }
                if (text.isEmpty()) {
                    text = JSONObject(rz.finalResult).optString("text").trim()
                }
                main.post {
                    if (text.isEmpty()) onError("말소리를 인식하지 못했습니다 (다시 시도해주세요)")
                    else onResult(text)
                }
            } catch (e: Exception) {
                main.post { onError("음성 인식 오류: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                voskListening = false
                runCatching { record?.stop() }
                record?.release()
                runCatching { rz?.close() }
            }
        }
    }

    /** assets/model-ko 를 filesDir 로 1회 복사(버전 파일이 같으면 재사용). */
    private fun unpackModel(): File {
        val dst = File(context.filesDir, ASSET_DIR)
        val assetVersion = runCatching {
            context.assets.open("$ASSET_DIR/version").bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("v1")
        val marker = File(dst, "version")
        if (marker.exists() && marker.readText().trim() == assetVersion) return dst
        dst.deleteRecursively()
        copyAssetDir(ASSET_DIR, dst)
        return dst
    }

    private fun copyAssetDir(path: String, dst: File) {
        val children = context.assets.list(path) ?: return
        if (children.isEmpty()) { // 파일
            dst.parentFile?.mkdirs()
            context.assets.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        } else { // 디렉터리
            dst.mkdirs()
            children.forEach { copyAssetDir("$path/$it", File(dst, it)) }
        }
    }

    private fun findUsbInput(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 오류"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한 필요"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
        SpeechRecognizer.ERROR_NO_MATCH -> "인식 실패(다시 말해주세요)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_SERVER -> "서버 오류"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 없습니다"
        else -> "오류($error)"
    }
}
