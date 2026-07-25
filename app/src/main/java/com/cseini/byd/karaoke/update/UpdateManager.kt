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

    fun install(context: Context, apk: File) {
        // Android 8+: 이 앱에 "이 출처의 앱 설치 허용" 권한이 없으면 업데이트 설치가 막힌다.
        // (다운로드는 되는데 설치가 안 되는 가장 흔한 원인) → 설정 화면으로 안내하고 켠 뒤 다시 시도하게 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                context, "업데이트 설치 권한이 필요합니다. '이 출처 허용'을 켠 뒤 업데이트를 다시 확인하세요.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(
            context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
