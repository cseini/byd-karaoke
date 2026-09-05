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
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
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
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
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


    /**
     * 무음 설치 디스패치(dadb/adb). true=설치 명령이 유닛에 전달됨 — 설치가 실제로 성공하면
     * 이 앱 프로세스가 죽고 새 버전이 자동 재시작되므로, 호출한 쪽 코드는 더 진행되지 않는다.
     * 일정 시간 뒤에도 앱이 살아 있으면 pm install 이 실패한 것 — [readSilentInstallResult] 로
     * 원인을 읽을 수 있다. false=ADB 연결/전송 자체가 실패(미개방·미인증 유닛) → 설치창 폴백.
     */
    suspend fun startSilentInstall(context: Context, apk: File): Boolean =
        withContext(Dispatchers.IO) { silentInstall(context, apk) }

    /** 무음 설치 결과(pm install 출력)를 ADB 로 읽는다 — 실패 원인 표시·제보용. */
    suspend fun readSilentInstallResult(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val kp = dadb.AdbKeyPair.read(File(context.filesDir, "adbkey"), File(context.filesDir, "adbkey.pub"))
            dadb.Dadb.create("127.0.0.1", 5555, kp, 5_000, 10_000).use { d ->
                d.shell("cat $RESULT_FILE 2>/dev/null").allOutput.trim()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun installViaSystem(context: Context, apk: File) {
        // Android 8+: 이 앱에 "이 출처의 앱 설치 허용" 권한이 없으면 업데이트 설치가 막힌다.
        // (다운로드는 되는데 설치가 안 되는 가장 흔한 원인) → 다이얼로그로 안내하고, 권한을 켜고
        // 돌아오면 자동으로 이어서 설치한다(Sealion7 등 Android 12 에서 흔함).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // SharedPreferences에 APK 경로 저장 — 권한 후 onResume에서 복원
            context.getSharedPreferences("karaoke_update", android.content.Context.MODE_PRIVATE).edit()
                .putString("pending_apk", apk.absolutePath)
                .commit()
            // 다이얼로그로 한 단계 거치지 않고 권한 화면을 바로 연다. 켜고 돌아오면 자동 설치.
            // 앱 이름을 하드코딩하면 테스트 앱(노래방테스트) 사용자가 목록에서 엉뚱한 '노래방'을 켠다.
            val label = runCatching {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            }.getOrDefault("노래방")
            Toast.makeText(
                context, "설치하려면 목록에서 '$label'을 켜고(허용) 뒤로 돌아오세요 — 자동으로 이어서 설치됩니다.",
                Toast.LENGTH_LONG,
            ).show()
            openUnknownSources(context)
            return
        }
        // SharedPreferences 삭제 (설치 시작)
        context.getSharedPreferences("karaoke_update", android.content.Context.MODE_PRIVATE).edit()
            .remove("pending_apk")
            .commit()
        installNow(context, apk)
    }

    /** 설정에서 권한을 켜고 돌아왔을 때(onResume) 미룬 설치를 자동 재개. */
    fun retryPendingInstall(context: Context) {
        val prefs = context.getSharedPreferences("karaoke_update", android.content.Context.MODE_PRIVATE)
        val apkPath = prefs.getString("pending_apk", null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // 아직 권한이 없으면 대기
            return
        }
        // 권한이 있거나 O 이전 버전 — 설치 진행
        val apk = java.io.File(apkPath)
        // 이미 현재 버전과 같은 파일이면 무시 (설치 완료된 상태)
        if (apk.absolutePath.endsWith("update-${BuildConfig.VERSION_NAME}.apk")) {
            prefs.edit().remove("pending_apk").commit()
            return
        }
        if (apk.exists()) {
            prefs.edit().remove("pending_apk").commit()
            installNow(context, apk)
        } else {
            // 파일이 없으면 pref만 정리
            prefs.edit().remove("pending_apk").commit()
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

    // 무음 설치 결과 파일 — 스크립트가 pm install 출력을 남기고, 실패 시 앱이 읽어 원인을 보여준다.
    private const val RESULT_FILE = "/data/local/tmp/karaoke_install_result.txt"

    // ★무음 설치 — dadb(로컬 ADB 5555)로 pm install -r + am start(자동실행). adb 미인증/미개방이면 false → 시스템 설치창 폴백.
    //   최초 1회 헤드유닛에 'USB 디버깅 허용?' 팝업 뜨면 허용해야 함(키 인증). 이후 무음.
    private fun silentInstall(context: Context, apk: File): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 120_000).use { d ->
                val remote = "/data/local/tmp/karaoke_update.apk"
                d.push(apk, remote, 420, System.currentTimeMillis())
                val pkg = context.packageName
                val script = "#!/system/bin/sh\nsleep 4\n" +
                    "pm install -r " + remote + " > " + RESULT_FILE + " 2>&1\nsleep 2\n" +
                    "am start -n " + pkg + "/com.cseini.byd.karaoke.MainActivity -f 0x10000000\n" +
                    "rm -f " + remote + "\n"
                val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                d.shell("rm -f " + RESULT_FILE + "; echo " + b64 + " | base64 -d > /data/local/tmp/karaoke_upd.sh && chmod 755 /data/local/tmp/karaoke_upd.sh")
                d.shell("setsid nohup sh /data/local/tmp/karaoke_upd.sh </dev/null >/dev/null 2>&1 &")
            }
            true
        } catch (t: Throwable) { android.util.Log.w("Karaoke", "silentInstall 실패", t); false }
    }

    /**
     * dadb(로컬 ADB 5555)로 접근성 서비스를 켠다 — 유닛이 접근성 설정 UI 를 막아 사용자가 직접
     * 못 켜는 경우용. ADB shell 은 WRITE_SECURE_SETTINGS 권한이 있어 앱이 못 하는 이 설정을 할 수 있다.
     * 키보드 마이크 자동 클릭(KeyCatcherService.clickKeyboardMic)에 필요. 실패해도(ADB 미개방) 무해.
     */
    fun enableAccessibilityViaAdb(context: Context): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 20_000).use { d ->
                val comp = context.packageName + "/com.cseini.byd.karaoke.KeyCatcherService"
                val cur = d.shell("settings get secure enabled_accessibility_services").allOutput.trim()
                val next = when {
                    cur.isBlank() || cur == "null" -> comp
                    cur.contains(comp) -> cur
                    else -> cur + ":" + comp
                }
                d.shell("settings put secure enabled_accessibility_services '" + next + "'")
                d.shell("settings put secure accessibility_enabled 1")
                // 씨라이언7 모드: 팝업 위 '말하세요' 띠(시스템 오버레이) 권한도 같이 부여(실패해도 무해, 소리 안내는 됨)
                d.shell("appops set " + context.packageName + " SYSTEM_ALERT_WINDOW allow")
                com.cseini.byd.karaoke.CrashLog.event(context, "a11y.adb ok comp=" + comp + " prev='" + cur + "'")
            }
            true
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "a11y.adb 실패 " + t.message)
            false
        }
    }

    /**
     * dadb(로컬 ADB 5555)로 오버레이 권한(SYSTEM_ALERT_WINDOW)을 부여한다 — 씨라이언7 모드의
     * '말하세요' 띠용. 접근성이 이미 켜진 유닛은 enableAccessibilityViaAdb 경로를 안 타므로 별도로 둔다.
     * 실패해도(ADB 미개방/미인증) 무해 — 그 경우 소리 안내만 나간다.
     */
    fun grantOverlayViaAdb(context: Context): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 20_000).use { d ->
                val out = d.shell("appops set " + context.packageName + " SYSTEM_ALERT_WINDOW allow").allOutput.trim()
                com.cseini.byd.karaoke.CrashLog.event(context, "overlay.adb ok '" + out.take(80) + "'")
            }
            true
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "overlay.adb 실패 " + t.message)
            false
        }
    }

    /**
     * dadb(로컬 ADB)로 USB 마이크(Loostone vid=0c76)를 소프트 재연결한다 — sysfs authorized 를
     * 0→1 토글하면 USB 가 재인식(detach→attach)돼, 매니페스트 <usb-device/> intent-filter 로
     * 앱이 연결 이벤트를 받아 권한이 자동 부여된다(물리 재연결과 동일 효과).
     * DiLink 처럼 권한 팝업도 안 뜨고 재연결 신호도 안 오는 유닛의 USB 버튼 문제 우회용.
     * sysfs 쓰기가 shell 권한으로 되는지 불확실 → 결과를 D1 로 남겨 확인한다.
     */
    /**
     * 차 안에서 BYD 관련 브로드캐스트 이력·수신자 목록을 읽어 로그로 남긴다.
     *
     * 우리는 액션 4개만 registerReceiver 로 듣고 있어서, 마이크의 '볼륨창 버튼'이 그 밖의 액션으로
     * 쏘면 볼 수가 없다. dumpsys 는 시스템이 기록한 이력이라 우리가 등록하지 않은 액션까지 보인다.
     * 읽기 전용 — 오디오·USB 를 건드리지 않는다.
     */
    fun dumpBydBroadcasts(context: Context): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 30_000).use { d ->
                // ① 최근 브로드캐스트 '액션만' 압축해서 — 필터를 걸면 byd 문자열이 없는 액션을 놓친다.
                //    (dumpsys 는 최근 것을 먼저 찍으므로 head 가 최신이다)
                val acts = d.shell(
                    "dumpsys activity broadcasts | grep -oE 'act=[^ ]+' | head -60",
                ).allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                com.cseini.byd.karaoke.CrashLog.event(context, "bcast.act ${acts.size}개")
                // 같은 액션이 연달아 오면 묶어서 남긴다(줄 수 절약).
                acts.distinct().take(40).forEachIndexed { i, a ->
                    com.cseini.byd.karaoke.CrashLog.event(context, "act[$i] " + a.take(200))
                }
                // ② 마이크 서비스가 어떤 액션을 듣고 있는지(등록된 수신자) — 버튼 신호의 후보를 역으로 찾는다.
                val recv = d.shell(
                    "dumpsys package com.byd.sing com.byd.minikaraoke 2>/dev/null | grep -iE 'action|Receiver' | head -40",
                ).allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                com.cseini.byd.karaoke.CrashLog.event(context, "bcast.recv ${recv.size}줄")
                recv.take(30).forEachIndexed { i, l ->
                    com.cseini.byd.karaoke.CrashLog.event(context, "recv[$i] " + l.take(200))
                }
                // ③ BYD 의 MicKeyService(접근성)가 키 필터(FLAG_REQUEST_FILTER_KEY_EVENTS=0x20)를 요청하는지 —
                //    요청한다면 마이크 버튼이 KeyEvent 로 흐른다는 뜻이고, 우리 접근성도 같은 키를 받을 수 있다.
                val a11y = d.shell(
                    "dumpsys accessibility 2>/dev/null | grep -iE 'MicKeyService|KeyCatcher' | cut -c1-300 | head -6",
                ).allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                a11y.forEachIndexed { i, l -> com.cseini.byd.karaoke.CrashLog.event(context, "a11y.svc[$i] $l") }
                // ④ 마이크가 커널 입력 장치로 잡혀 있는지(키 능력 포함) — 있으면 KeyEvent 경로가 구조적으로 가능.
                val inp = d.shell(
                    "getevent -p 2>/dev/null | grep -iE 'add device|name:|KEY' | cut -c1-160 | head -30",
                ).allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                com.cseini.byd.karaoke.CrashLog.event(context, "input.dev ${inp.size}줄")
                inp.forEachIndexed { i, l -> com.cseini.byd.karaoke.CrashLog.event(context, "inp[$i] $l") }
                // ⑤ 오디오 입력 프로필 — 씨라이언 순정마이크(BUS)가 정말 단일개방(maxOpenCount=1)이라
                //    우리 캡처가 BYD 를 밀어내는지 확증. 활성 입력 핸들·프로필 제약을 본다.
                val ap = d.shell(
                    "dumpsys media.audio_policy 2>/dev/null | grep -iE 'Input profile|maxOpenCount|maxActiveCount|Activ|devices|flags|AUDIO_DEVICE_IN' | cut -c1-160 | head -40",
                ).allOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
                com.cseini.byd.karaoke.CrashLog.event(context, "audiopolicy ${ap.size}줄")
                ap.forEachIndexed { i, l -> com.cseini.byd.karaoke.CrashLog.event(context, "ap[$i] $l") }
                acts.isNotEmpty() || recv.isNotEmpty()
            }
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "bcast.dump 실패 " + t.message)
            false
        }
    }

    fun reconnectMicViaAdb(context: Context): Boolean {
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 20_000).use { d ->
                // 0c76 = PureMic/JMTek 비순정 USB 마이크 권한 우회용. sysfs authorized 쓰기는 root 권한이
                // 필요한데 이 유닛 adbd 는 shell 권한이라 실차에서 토글된 적이 없다(D1 전수 확인, 씨라이언
                // 순정 vid 1235 는 이 경로로 못 살림 — 소프트 재삽입 폐기).
                val sh = "for dd in /sys/bus/usb/devices/*/; do " +
                    "v=\$(cat \${dd}idVendor 2>/dev/null); " +
                    "if [ \"\$v\" = \"0c76\" ]; then " +
                    "a=\$(cat \${dd}authorized 2>/dev/null); " +
                    "echo 0 > \${dd}authorized 2>&1; sleep 1; echo 1 > \${dd}authorized 2>&1; " +
                    "echo \"toggled was=\$a now=\$(cat \${dd}authorized 2>/dev/null)\"; " +
                    "fi; done"
                val out = d.shell(sh).allOutput.trim()
                com.cseini.byd.karaoke.CrashLog.event(context, "usb.reconnect '" + out.take(160) + "'")
                out.contains("toggled")
            }
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "usb.reconnect 실패 " + t.message)
            false
        }
    }

    /**
     * (실험) 사용자가 내려받아 둔 Gboard APK 를 로컬 ADB(pm install)로 설치하고 기본 키보드(IME)로 지정한다.
     * 설치 화면의 '알 수 없는 출처' 차단을 우회한다(무음 업데이트와 동일 경로, shell 권한이면 됨).
     * 목적: Gboard 음성 버튼이 우리 AudioRecord 와 다른(시스템 인식 서비스) 경로로 씨라이언 마이크를 잡는지
     * 실측. 결과는 D1 로그로 남긴다. Gboard APK 는 /sdcard/Download 등에 있어야 한다(단일 .apk, XAPK 아님).
     */
    // 우리 릴리스에 올린 정품 Gboard(구글 서명 검증 완료). 로컬 Download 에 없을 때만 받는다.
    private const val GBOARD_URL = "https://github.com/cseini/byd-karaoke-test/releases/download/gboard-stt/gboard-18.1.3.apk"
    private const val GBOARD_PKG = "com.google.android.inputmethod.latin"
    // Gboard 공식 서명 인증서 SHA-256(apksigner 검증). API29 는 minSdk24-32 블록(f0fd…) 사용, 33+ 는 7ce8….
    private val GBOARD_CERTS = setOf(
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
        "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053",
    )
    // 우리가 정품 검증해 올린 Gboard APK 의 정확한 바이트 수. 이 APK 는 v2/v3 서명만 있어(v1 없음) 구형
    // 안드로이드(GET_SIGNING_CERTIFICATES 미지원)에선 서명을 못 읽는다. 그 경우 '패키지명+이 크기 정확히
    // 일치'(=우리 서버가 준 그 파일)로 정품 인정한다. ★호스팅 APK 교체 시 이 값도 갱신할 것.
    private const val GBOARD_SIZE = 79910350L

    /** URL → 파일 다운로드. 회선 불안정 유닛(씰 등) 대비: 끊기면 받은 지점부터 이어받기(Range) + 최대 6회 재개.
     *  onProgress(0~100) 로 진행율 콜백. 부분 파일을 지우지 않고 두어 재개한다. */
    private fun downloadTo(context: Context, url: String, dest: java.io.File, onProgress: (Int) -> Unit): Boolean {
        repeat(12) { attempt ->
            // 지금까지 받은 만큼 진행율 반영(재시도 대기 중에도 UI 가 '90%에서 이어받는 중'을 보여주게).
            onProgress((dest.length() * 100 / GBOARD_SIZE).toInt().coerceIn(0, 99))
            when (downloadOnce(context, url, dest, onProgress)) {
                DlResult.DONE -> return true
                DlResult.FATAL -> return false   // HTTP 오류·리다이렉트 실패 등 이어받아도 소용없는 경우
                DlResult.RETRY -> {
                    com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl 이어받기 ${attempt + 1} (현재 ${dest.length()} bytes)")
                    // 회선이 끊겼을 때 즉시 재시도하면 또 실패한다(DNS 안 잡힘). 회복 시간을 준다(백오프 최대 8초).
                    if (attempt < 11) runCatching { Thread.sleep(3_000L + attempt * 1_000L) }
                }
            }
        }
        return false
    }

    private enum class DlResult { DONE, RETRY, FATAL }

    /** 이어받기 1회. dest 가 이미 있으면 그 크기부터 Range 로 요청. 완결(Content-Length 일치)이면 DONE. */
    private fun downloadOnce(context: Context, url: String, dest: java.io.File, onProgress: (Int) -> Unit): DlResult {
        var cur = url; var hops = 0
        val from = if (dest.exists()) dest.length() else 0L
        val t0 = System.currentTimeMillis()
        return try {
            while (hops < 5) {
                val conn = (java.net.URL(cur).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "byd-karaoke")
                    if (from > 0) setRequestProperty("Range", "bytes=$from-")   // 리다이렉트마다 다시 붙는다
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location"); conn.disconnect()
                    if (loc.isNullOrBlank()) { com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl 리다이렉트 Location 없음"); return DlResult.FATAL }
                    cur = loc; hops++; continue
                }
                if (code != 200 && code != 206) {
                    // 416(범위 벗어남) = 이미 다 받았을 수 있음 → 파일이 충분하면 완결로 본다.
                    conn.disconnect()
                    if (code == 416 && dest.length() >= 70_000_000) return DlResult.DONE
                    com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl HTTP $code"); return DlResult.FATAL
                }
                val remaining = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val append = code == 206
                val total = if (append && remaining > 0) from + remaining else remaining
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = if (append) from else 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                conn.disconnect()
                val sec = (System.currentTimeMillis() - t0) / 1000
                if (total > 0 && dest.length() < total) {
                    com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl 끊김 ${dest.length()}/$total bytes ${sec}s → 이어받기")
                    return DlResult.RETRY   // 부분 파일 유지, 다음 회차에 이어받는다
                }
                if (dest.length() < 70_000_000) { com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl 너무작음 ${dest.length()}"); return DlResult.RETRY }
                onProgress(100)
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl ok ${dest.length()} bytes ${sec}s")
                return DlResult.DONE
            }
            DlResult.FATAL
        } catch (t: Throwable) {
            val sec = (System.currentTimeMillis() - t0) / 1000
            com.cseini.byd.karaoke.CrashLog.event(context, "gboard dl 중단 ${sec}s ${t::class.java.simpleName}: ${t.message} (${dest.length()} bytes) → 이어받기")
            DlResult.RETRY   // SSL abort 등 — 받은 만큼 두고 이어받는다
        }
    }

    /** 다운로드한 APK 가 정품 Gboard 인지 온디바이스 검증. null=정품, 아니면 실패 사유. */
    private fun verifyGboardApk(context: Context, file: java.io.File): String? {
        val pm = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return "APK 파싱 실패"
        if (info.packageName != GBOARD_PKG) return "패키지 불일치(${info.packageName})"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digests = mutableListOf<String>()
        // API28+: v2/v3 서명 인증서
        info.signingInfo?.apkContentsSigners?.forEach { s -> digests += md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
        // 구형 API 폴백: v1(JAR) 서명 — 단 이 APK 는 v1 이 없어 이것도 비어있을 수 있음
        if (digests.isEmpty()) {
            val old = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
            }.getOrNull()
            @Suppress("DEPRECATION")
            old?.signatures?.forEach { s -> digests += md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
        }
        if (digests.any { it in GBOARD_CERTS }) return null
        // 서명을 아예 못 읽는 구형 유닛(GET_SIGNING_CERTIFICATES 미지원 + v1 서명 없음): 우리가 검증해 올린
        // 정품과 패키지명+바이트수가 정확히 일치하면 정품으로 인정한다(다운로드 완결성은 Content-Length 로 이미 검증).
        if (digests.isEmpty() && file.length() == GBOARD_SIZE) return null
        return "서명 불일치/미확인(${digests.joinToString().take(80)} size=${file.length()})"
    }

    /**
     * Gboard 가 이미 설치돼 있는데 기본키보드가 Gboard 가 아니면(차량이 부팅 때 되돌림) ADB 로 다시 지정.
     * 앱 시작 때 호출. Gboard 미설치면 아무 일 안 함. 이미 Gboard 기본이면 그대로 둔다.
     */
    fun ensureGboardDefault(context: Context): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(GBOARD_PKG, 0); true }.getOrDefault(false)
        if (!installed) return false
        return try {
            val priv = java.io.File(context.filesDir, "adbkey")
            val pub = java.io.File(context.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
            val kp = dadb.AdbKeyPair.read(priv, pub)
            dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 20_000).use { d ->
                val cur = d.shell("settings get secure default_input_method").allOutput.trim()
                if (cur.contains(GBOARD_PKG)) {
                    com.cseini.byd.karaoke.CrashLog.event(context, "gboard 기본키보드 유지=[$cur]")
                    return@use true
                }
                val ime = d.shell("ime list -a 2>/dev/null | grep -oE '$GBOARD_PKG/[A-Za-z0-9._]+' | head -1")
                    .allOutput.trim().lineSequence().firstOrNull().orEmpty()
                if (ime.isEmpty()) { com.cseini.byd.karaoke.CrashLog.event(context, "gboard 재지정: IME 목록에 없음(cur=$cur)"); return@use false }
                d.shell("ime enable '$ime'"); d.shell("ime set '$ime'")
                val now = d.shell("settings get secure default_input_method").allOutput.trim().take(120)
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard 기본키보드 재지정 [$cur]→[$now]")
                now.contains(GBOARD_PKG)
            }
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "gboard 재지정 실패 " + t.message); false
        }
    }

    fun setupGboard(context: Context, onProgress: (Int) -> Unit = {}): Boolean {
        val priv = java.io.File(context.filesDir, "adbkey")
        val pub = java.io.File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.generate(priv, pub)
        val kp = runCatching { dadb.AdbKeyPair.read(priv, pub) }.getOrNull() ?: return false
        fun open() = dadb.Dadb.create("127.0.0.1", 5555, kp, 10_000, 30_000)

        // ── 1단계: ADB 를 잠깐 열어 로컬 APK 있는지만 확인(빠름). 80MB 다운로드 동안 ADB 를 열어두면
        //    유휴 연결이 끊겨(Connection reset) 설치가 실패한다 — 다운로드는 ADB 밖에서 한다. ──
        var sdcardApk = ""
        runCatching {
            open().use { d ->
                val findSh = "find /sdcard/Download /sdcard/ApkPure /sdcard/Android/obb -maxdepth 3 -iname '*.apk' 2>/dev/null | " +
                    "grep -iE 'gboard|inputmethod.latin|inputmethod_latin' | head -1"
                sdcardApk = d.shell(findSh).allOutput.trim().lineSequence().firstOrNull().orEmpty()
            }
        }.onFailure { com.cseini.byd.karaoke.CrashLog.event(context, "gboard 1단계(find) 실패 ${it.message}"); return false }
        com.cseini.byd.karaoke.CrashLog.event(context, "gboard 로컬apk=[$sdcardApk]")

        // ── 2단계(로컬 없으면): 우리 릴리스에서 다운로드 + 온디바이스 서명검증 (ADB 미사용, 느림) ──
        var dlFile: java.io.File? = null
        if (sdcardApk.isEmpty()) {
            val dl = java.io.File(context.getExternalFilesDir(null), "gboard.apk")
            // 이미 받아둔 정품이 있으면 재다운로드 생략(누를 때마다 80MB 재다운로드 방지).
            if (dl.exists() && dl.length() > 1_000_000 && verifyGboardApk(context, dl) == null) {
                dlFile = dl
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard 기존 다운로드 재사용 ${dl.length() / 1024 / 1024}MB")
            } else {
                if (!downloadTo(context, GBOARD_URL, dl, onProgress)) return false
                val bad = verifyGboardApk(context, dl)
                if (bad != null) { com.cseini.byd.karaoke.CrashLog.event(context, "gboard 서명검증 실패: $bad"); dl.delete(); return false }
                dlFile = dl
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard 다운로드·정품검증 OK ${dl.length() / 1024 / 1024}MB")
            }
        }

        // ── 3단계: push 후 '설치+기본키보드'를 분리 스크립트로 백그라운드 실행(OTA 검증 방식) ──
        //    inline pm install 은 76MB 라 오래 걸려 ADB 연결을 붙잡다 끊긴다(Connection reset). 그래서
        //    push 만 하고, 설치는 detached 로 돌린 뒤 결과를 파일로 읽는다.
        try {
            open().use { d ->
                // push(소켓 76MB 전송)가 이 유닛에서 Connection reset 을 냈다(6.69 실측: push 시작만, 완료 없음).
                // → push 안 하고, 앱이 받아둔 /sdcard 경로에서 '기기 내부 cp' 로 /data/local/tmp 에 복사 후 설치.
                // Android10 adb shell(uid 2000)은 앱 external files(/sdcard/Android/data/<pkg>)를 읽을 수 있다.
                val src = if (dlFile != null) dlFile.absolutePath else sdcardApk
                val sh = "rm -f /data/local/tmp/gboard.apk\n" +
                    "cp '$src' /data/local/tmp/gboard.apk 2>>/data/local/tmp/gb_res.txt\n" +
                    "echo copied=\$(ls -l /data/local/tmp/gboard.apk 2>&1 | awk '{print \$5}') >> /data/local/tmp/gb_res.txt\n" +
                    "pm install -r -d /data/local/tmp/gboard.apk >> /data/local/tmp/gb_res.txt 2>&1\n" +
                    "IME=\$(ime list -a 2>/dev/null | grep -oE 'com.google.android.inputmethod.latin/[A-Za-z0-9._]+' | head -1)\n" +
                    // 설치 직후 IME 목록에 안 뜨면(등록 지연/필터) 알려진 Gboard IME id 로 정식 enable 시도 후 재확인.
                    // enable 은 '목록에 추가'라 기본키보드를 억지로 덮어써 먹통 만드는 위험이 없다.
                    "if [ -z \"\$IME\" ]; then\n" +
                    "  K=com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.libs.framework.core.LatinIME\n" +
                    "  ime enable \"\$K\" >> /data/local/tmp/gb_res.txt 2>&1\n" +
                    "  IME=\$(ime list -a 2>/dev/null | grep -oE 'com.google.android.inputmethod.latin/[A-Za-z0-9._]+' | head -1)\n" +
                    "  echo \"enable후 ime=[\$IME] en=\$(pm list packages -e | grep -c latin) dis=\$(pm list packages -d | grep -c latin)\" >> /data/local/tmp/gb_res.txt\n" +
                    "  echo \"imes_all=[\$(ime list -a | grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | tr '\\n' ',' | cut -c1-200)]\" >> /data/local/tmp/gb_res.txt\n" +
                    "fi\n" +
                    "if [ -n \"\$IME\" ]; then ime enable \"\$IME\"; ime set \"\$IME\"; fi\n" +
                    "echo \"ime=\$IME def=\$(settings get secure default_input_method)\" >> /data/local/tmp/gb_res.txt\n" +
                    "rm -f /data/local/tmp/gboard.apk\n"
                val b64 = android.util.Base64.encodeToString(sh.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                d.shell("rm -f /data/local/tmp/gb_res.txt; echo $b64 | base64 -d > /data/local/tmp/gb.sh && chmod 755 /data/local/tmp/gb.sh")
                d.shell("setsid nohup sh /data/local/tmp/gb.sh </dev/null >/dev/null 2>&1 &")
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard 설치 스크립트 실행(기기내 cp, push 없음) src=$src")
            }
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "gboard 3단계(실행) 실패 " + t.message)
            return false
        }
        // 백그라운드 설치가 끝날 시간을 준 뒤, 새 세션으로 결과 읽기(연결을 붙잡지 않음).
        Thread.sleep(35_000)
        return try {
            open().use { d ->
                val res = d.shell("cat /data/local/tmp/gb_res.txt 2>/dev/null").allOutput.trim().replace('\n', ' ').take(200)
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard 결과=[$res]")
                val recog = d.shell("dumpsys package r 2>/dev/null | grep -iE 'RecognitionService' -A1 | grep -oE '[a-zA-Z0-9._]+/[a-zA-Z0-9._]+' | sort -u | head -6")
                    .allOutput.trim().replace('\n', ',').ifEmpty { "none" }.take(200)
                com.cseini.byd.karaoke.CrashLog.event(context, "gboard recog후=[$recog]")
                res.contains("Success") || res.contains("ime=com.google")
            }
        } catch (t: Throwable) {
            com.cseini.byd.karaoke.CrashLog.event(context, "gboard 결과읽기 실패 " + t.message); false
        }
    }
}
