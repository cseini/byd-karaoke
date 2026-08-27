package com.cseini.byd.karaoke.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cseini.byd.karaoke.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 OTA.
 * 앱 시작 시 최신 릴리스 태그(vX.Y)를 현재 versionName 과 비교해, 새 버전이면
 * APK 에셋을 내려받아 시스템 설치 화면을 띄운다(사이드로드 앱은 설치 확인
 * 탭 한 번은 Android 정책상 생략 불가).
 * 저장소가 private 이거나 오프라인이면 확인이 실패하며 조용히 건너뛴다.
 */
object UpdateManager {

    // 플래버별 OTA 저장소(prod=본앱, lab=테스트앱). 서로 섞이지 않는다.
    private val LATEST_URL: String
        get() = "https://api.github.com/repos/${BuildConfig.OTA_REPO}/releases/latest"

    data class Asset(val name: String?, val browser_download_url: String?)
    data class Release(val tag_name: String?, val assets: List<Asset> = emptyList()) {
        // 버전명이 붙은 APK(byd-karaoke-vX.YZ.apk)를 우선, 없으면 아무 .apk.
        val apkUrl: String?
            get() = (assets.firstOrNull { it.name?.matches(Regex("byd-karaoke-v.*\\.apk")) == true }
                ?: assets.firstOrNull { it.name?.endsWith(".apk") == true })?.browser_download_url
        val version: String get() = tag_name.orEmpty().removePrefix("v")
    }

    /**
     * 긴급 업데이트 신호(랜딩의 min.json). 유튜브 재생이 깨져 필수 업데이트가 필요할 때,
     * 맥미니 자동배포가 이 값을 올린다. 현재 버전이 minVersion 보다 낮으면 강제 안내.
     */
    data class MinInfo(val minVersion: String?, val message: String?)

    suspend fun fetchMinVersion(): MinInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("https://karaoke.usenu.kr/min.json").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "byd-karaoke")
                connectTimeout = 5_000; readTimeout = 5_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Gson().fromJson(body, MinInfo::class.java)
        }.getOrNull()
    }

    /** 현재 버전이 min 보다 낮은가(=긴급 업데이트 필요). */
    fun isBelow(minVersion: String?): Boolean =
        !minVersion.isNullOrBlank() && isNewer(minVersion, BuildConfig.VERSION_NAME)

    /** 새 버전이 있으면 Release, 없거나 확인 실패면 null. */
    suspend fun checkForUpdate(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "byd-karaoke")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val release = Gson().fromJson(body, Release::class.java)
            if (release.apkUrl != null && isNewer(release.version, BuildConfig.VERSION_NAME)) release else null
        }.getOrNull()
    }

    // "0.10" vs "0.9" 같은 경우 때문에 문자열이 아니라 숫자 단위로 비교한다.
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** APK 를 앱 전용 외부 저장소로 내려받는다. 실패 시 null. */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "ota").apply { mkdirs() }
                val out = File(dir, "update-${release.version}.apk")
                // 지난 버전 APK(개당 6~7MB)가 계속 쌓이므로 이번에 받을 것만 남기고 정리한다.
                dir.listFiles()?.forEach { if (it.name != out.name) runCatching { it.delete() } }
                val conn = (URL(release.apkUrl!!).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "byd-karaoke")
                    instanceFollowRedirects = true
                    connectTimeout = 10_000
                    readTimeout = 60_000
                }
                val total = conn.contentLength.toLong()
                if (out.exists()) out.delete()   // 이전에 남은(불완전) 파일 제거 후 새로 받기
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                // 무결성 검증: 다운로드가 끊겨 불완전하면 설치가 "앱이 설치되지 않았습니다"로 실패한다.
                // 크기(알 수 있으면)와 APK(zip) 헤더(PK)를 확인해, 불완전하면 지우고 실패 처리.
                val sizeOk = total <= 0 || out.length() == total
                val head = runCatching {
                    out.inputStream().use { val b = ByteArray(2); it.read(b); b }
                }.getOrDefault(ByteArray(2))
                val isApk = head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                if (sizeOk && isApk) out else { out.delete(); null }
            }.getOrNull()
        }

    // 권한이 없어 미룬 설치. 설정에서 허용하고 돌아오면 onResume 에서 자동 재개.
    @Volatile private var pendingApk: File? = null

    fun install(context: Context, apk: File) {
        // ★무음 설치 우선(dadb/adb, 블로킹이라 백그라운드) — 되면 진행률→자동설치→자동재시작 원스톱. 실패 시 시스템 설치창 폴백.
        // 무음이라 아무 안내가 없으면 사용자는 앱이 갑자기 꺼졌다 켜지는 걸로만 본다 → 단계마다 토스트로 알린다.
        val ui = android.os.Handler(context.mainLooper)
        ui.post { Toast.makeText(context, "✅ 다운로드 완료 — 설치를 시작합니다", Toast.LENGTH_SHORT).show() }
        Thread {
            if (silentInstall(context, apk)) {
                ui.post {
                    Toast.makeText(
                        context,
                        "⚙ 설치 중입니다. 끝나면 앱이 자동으로 다시 시작돼요 — 잠시만 기다려 주세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@Thread
            }
            ui.post { installViaSystem(context, apk) }
        }.start()
    }

    private fun installViaSystem(context: Context, apk: File) {
        // Android 8+: 이 앱에 "이 출처의 앱 설치 허용" 권한이 없으면 업데이트 설치가 막힌다.
        // (다운로드는 되는데 설치가 안 되는 가장 흔한 원인) → 다이얼로그로 안내하고, 권한을 켜고
        // 돌아오면 자동으로 이어서 설치한다(Sealion7 등 Android 12 에서 흔함).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            // 다이얼로그로 한 단계 거치지 않고 권한 화면을 바로 연다. 켜고 돌아오면 자동 설치.
            Toast.makeText(
                context, "설치하려면 '노래방'을 켜고(허용) 뒤로 돌아오세요 — 자동으로 이어서 설치됩니다.",
                Toast.LENGTH_LONG,
            ).show()
            openUnknownSources(context)
            return
        }
        pendingApk = null
        installNow(context, apk)
    }

    /** 설정에서 권한을 켜고 돌아왔을 때(onResume) 미룬 설치를 자동 재개. */
    fun retryPendingInstall(context: Context) {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            if (apk.exists()) installNow(context, apk)
        }
    }

    private fun openUnknownSources(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun installNow(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // ★무음 설치 — dadb(로컬 ADB 5555)로 pm install -r + am start(자동실행). adb 미인증/미개방이면 false → 시스템 설치창 폴백.
    //   최초 1회 헤드유닛에 'USB 디버깅 허용?' 팝업 뜨면 허용해야 함(키 인증). 이후 무음.
    private fun silentInstall(context: Context, apk: File): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            val d = dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 120_000)
            val remote = "/data/local/tmp/karaoke_update.apk"
            d.push(apk, remote, 420, System.currentTimeMillis())
            val pkg = context.packageName
            val script = "#!/system/bin/sh\nsleep 4\n" +
                "pm install -r " + remote + "\nsleep 2\n" +
                "am start -n " + pkg + "/com.cseini.byd.karaoke.MainActivity -f 0x10000000\n" +
                "rm -f " + remote + "\n"
            val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            d.shell("echo " + b64 + " | base64 -d > /data/local/tmp/karaoke_upd.sh && chmod 755 /data/local/tmp/karaoke_upd.sh")
            d.shell("setsid nohup sh /data/local/tmp/karaoke_upd.sh </dev/null >/dev/null 2>&1 &")
            d.close()
            true
        } catch (t: Throwable) { android.util.Log.w("Karaoke", "silentInstall 실패", t); false }
    }
}
