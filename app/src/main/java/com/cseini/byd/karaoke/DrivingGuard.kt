package com.cseini.byd.karaoke

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import android.os.SystemClock
import com.cseini.byd.karaoke.player.StreamPlayer
import com.cseini.byd.karaoke.share.ReserveServer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 운행 중 영상 시청 차단 — 기어가 P(주차)·N(중립)일 때만 영상을 보여 준다(운영자 결정).
 * 기어를 알 수 없으면 가린다. 기어는 BYD SDK 로 읽는다(릴레이와 같은 게터).
 * 기어 게터가 얼어붙는 사례(릴레이 v80 교훈)에 대비해, 차속이 읽히고 5km/h 이상이면 기어와 무관하게 차단한다.
 * 씨라7(D5)은 SDK 가 시스템 앱 안에 있어 그 APK 에서 읽어 온다(릴레이 deviceFromSystemApp 방식). 기어를 끝내 못 읽으면 차속(SDK→GPS)으로 판단한다.
 * 운행 중이면 재생을 멈추거나 화면을 닫지 않고, 영상 화면만 검게 가린다(소리는 계속 나고, 뒷좌석 태블릿은 계속 재생한다).
 * 폰·태블릿용 지인 빌드에는 적용하지 않는다.
 */
@UnstableApi
object DrivingGuard {
    /** D3 체계로 정규화한 기어: 0=N 1=R 2=D 3=P. */
    const val GEAR_N = 0
    const val GEAR_R = 1
    const val GEAR_D = 2
    const val GEAR_P = 3
    const val MOVING_KMH = 5.0
    const val REQ_LOCATION = 7101

    private const val POLL_MS = 1_000L
    private const val GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    private const val SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private val SDK_PERMS = listOf(
        "android.permission.BYDAUTO_GEARBOX_COMMON", "android.permission.BYDAUTO_GEARBOX_GET",
        "android.permission.BYDAUTO_SPEED_COMMON", "android.permission.BYDAUTO_SPEED_GET",
    )

    enum class Verdict { OK, DRIVING, UNKNOWN }

    /**
     * 기어(정규화 값, 모르면 null)와 차속(모르면 null)만으로 판정 — 시험하기 쉽게 분리한 순수 함수.
     * 기어를 알면 P·N 만 허용(차속이 5km/h 이상이면 기어와 무관하게 차단), 기어를 모르면 차속으로, 둘 다 모르면 차단.
     */
    internal fun decide(gear: Int?, speedKmh: Double?): Verdict = when {
        gear == null && speedKmh == null -> Verdict.UNKNOWN
        gear == null -> if (speedKmh!! >= MOVING_KMH) Verdict.DRIVING else Verdict.OK
        gear != GEAR_P && gear != GEAR_N -> Verdict.DRIVING
        speedKmh != null && speedKmh >= MOVING_KMH -> Verdict.DRIVING
        else -> Verdict.OK
    }

    /** 씨라7(D5)의 getGear 원값 → D3 체계. 1=P 2=R 3=N 4=D(실차 2회 확정, 릴레이 09-23). 모르는 값은 null — 절대 P 로 만들지 않는다. */
    internal fun mapD5Gear(raw: Int): Int? = when (raw) { 1 -> GEAR_P; 2 -> GEAR_R; 3 -> GEAR_N; 4 -> GEAR_D; else -> null }

    private val handler = Handler(Looper.getMainLooper())
    private val attached = CopyOnWriteArrayList<StreamPlayer>()
    @Volatile private var appCtx: Context? = null

    private var gearDev: Any? = null
    private var speedDev: Any? = null
    @Volatile private var sdkLogged = false

    private val poll = object : Runnable {
        override fun run() {
            if (attached.isEmpty()) return
            val ctx = appCtx ?: return
            val v = check(ctx)
            attached.forEach { runCatching { it.setHostHidden(v != Verdict.OK, coverText(ctx, v)) } }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** 영상 플레이어가 생기면 등록 — 운행 중이면 처음부터 화면을 가린 채 시작한다. */
    fun attach(ctx: Context, p: StreamPlayer) {
        appCtx = ctx.applicationContext
        if (attached.addIfAbsent(p)) {
            applyTo(p)
            if (attached.size == 1) handler.post(poll)
        }
    }

    fun detach(p: StreamPlayer) {
        attached.remove(p)
        if (attached.isEmpty()) handler.removeCallbacks(poll)
    }

    /** 지금 상태대로 이 플레이어의 화면 가림을 맞춘다(새 영상을 불러오기 직전에도 부른다). */
    fun applyTo(p: StreamPlayer) {
        val ctx = appCtx ?: return
        val v = check(ctx)
        p.setHostHidden(v != Verdict.OK, coverText(ctx, v))
    }

    /** 앱 시작 때 한 번 — SDK 읽기 권한을 self-adb 로 부여(버전마다 1회)하고 기어가 읽히는지 로그로 남긴다. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (VehicleProbe.vehicleType().isEmpty()) return
        Thread {
            val prefs = app.getSharedPreferences("driving_guard", Context.MODE_PRIVATE)
            val ver = BuildConfig.VERSION_NAME
            if (prefs.getString("granted_ver", null) != ver) {
                val ok = runCatching { grantSelfPermissions(app, SDK_PERMS) }.getOrDefault(false)
                if (ok) prefs.edit().putString("granted_ver", ver).apply()
            }
            val g = readGear(app)
            CrashLog.event(app, "운행 감지 기어 읽기: ${if (g == null) "불가(영상 차단)" else "가능 ${gearName(g)}"} 차종=${VehicleProbe.vehicleType()}")
        }.start()
    }

    // ── 개발자 모드(숨김): 설정 화면의 업데이트 버튼을 10번 연속 누르면 켜진다(끄기는 버전 옆 버튼). 켜면 운행 중 영상 가림을 하지 않는다. ──
    private const val DEV_TAPS = 10
    private const val DEV_TAP_GAP_MS = 3_000L
    private var devTapCount = 0
    private var devLastTapAt = 0L

    fun devMode(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences("driving_guard", Context.MODE_PRIVATE).getBoolean("dev_mode", false)

    fun setDevMode(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences("driving_guard", Context.MODE_PRIVATE).edit().putBoolean("dev_mode", on).apply()
    }

    /** 업데이트 버튼을 눌렀을 때 — 누른 횟수 문구를 돌려준다(10번째에 개발자 모드 켜짐 안내). 이미 켜져 있으면 null. */
    fun onUpdateTapped(ctx: Context): String? {
        if (devMode(ctx)) return null
        val now = SystemClock.elapsedRealtime()
        devTapCount = if (now - devLastTapAt > DEV_TAP_GAP_MS) 1 else devTapCount + 1
        devLastTapAt = now
        if (devTapCount < DEV_TAPS) return "$devTapCount 회"
        devTapCount = 0
        setDevMode(ctx, true)
        return "개발자 모드 활성화"
    }

    /** 지금 영상을 재생해도 되는지 판정한다(캐시가 아니라 이 순간의 기어). */
    @Synchronized
    fun check(ctx: Context): Verdict {
        if (devMode(ctx)) return Verdict.OK
        val app = ctx.applicationContext
        appCtx = app
        val gear = readGear(app)
        val speed = readSpeed(app) ?: if (gear == null) gpsSpeed(app) else null   // GPS 는 기어를 못 읽을 때만 쓴다
        return decide(gear, speed)
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun gearName(g: Int) = when (g) { GEAR_P -> "P"; GEAR_R -> "R"; GEAR_N -> "N"; GEAR_D -> "D"; else -> "?" }

    /** 뒷좌석 태블릿이 /now 를 최근 8초 안에 물어봤는가. */
    fun tabletActive(): Boolean { val t = ReserveServer.lastScreenAt; return t != 0L && System.currentTimeMillis() - t < 8_000L }

    /** 영상을 가린 화면에 띄우는 문구. 뒷좌석 태블릿이 붙어 있으면 그쪽에서 계속 재생 중임을 알린다. */
    fun coverText(ctx: Context, v: Verdict): String {
        val tablet = tabletActive()
        return when (v) {
            Verdict.UNKNOWN ->
                "차량 기어를 확인할 수 없어 노래방 영상을 가렸어요\n안전을 위해 운행 중 영상 시청은 불가능합니다" +
                    (if (tablet) "\n뒷좌석 화면에서는 계속 재생 중이에요" else "") +
                    (if (!hasLocationPermission(ctx)) "\n\n(화면을 누르면 위치 권한으로 속도를 확인할 수 있어요)" else "")
            else ->
                "운행 중에는 영상 시청이 불가능합니다\n반주 소리만 재생됩니다" +
                    (if (tablet) "\n뒷좌석 화면에서는 계속 재생 중이에요" else "")
        }
    }

    /** 가림 화면을 눌렀을 때 — 기어를 못 읽고 위치 권한도 없으면 권한을 요청한다. */
    fun onCoverTapped(ctx: Context) {
        val act = ctx as? android.app.Activity ?: return
        if (check(act) == Verdict.UNKNOWN && !hasLocationPermission(act))
            act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    // ───────── SDK ─────────

    private fun device(app: Context, cls: String): Any? {
        val w = VehicleContextWrapper(app)
        // D3(돌핀 등): SDK 클래스가 시스템 클래스패스에 있다.
        try {
            val d = Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, w)
            if (d != null) { injectContext(d, w); return d }
        } catch (t: Throwable) { /* D5: 아래에서 시스템 앱 APK 로 시도 */ }
        // D5(씨라7): SDK 가 시스템 앱 APK 안에 들어 있다 — 릴레이 deviceFromSystemApp 과 같은 방식.
        return deviceFromSystemApp(app, cls, w)
    }

    private val sdkPackages = listOf(
        "com.byd.data.collect", "com.byd.hvac", "com.byd.carsettings", "com.byd.mycar", "com.byd.scenemode", "com.byd.autoservice",
    )
    private val sdkLoaders = HashMap<String, ClassLoader>()
    @Volatile private var sysReportLogged = false

    @Synchronized
    private fun deviceFromSystemApp(app: Context, cls: String, w: VehicleContextWrapper): Any? {
        val why = StringBuilder()
        for (pkg in sdkPackages) {
            try {
                val cl = sdkLoaders[pkg] ?: run {
                    val ai = app.packageManager.getApplicationInfo(pkg, 0)
                    if ((ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) { why.append("$pkg=비시스템 "); return@run null }
                    val path = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).joinToString(java.io.File.pathSeparator)
                    dalvik.system.DexClassLoader(path, app.codeCacheDir.absolutePath, ai.nativeLibraryDir, app.classLoader)
                        .also { sdkLoaders[pkg] = it }
                } ?: continue
                val d = cl.loadClass(cls).getMethod("getInstance", Context::class.java).invoke(null, w)
                if (d != null) { injectContext(d, w); CrashLog.event(app, "운행 감지 SDK ${cls.substringAfterLast('.')} ← $pkg"); return d }
                why.append("$pkg=null ")
            } catch (t: Throwable) {
                val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                why.append("$pkg=${c::class.java.simpleName} ")
            }
        }
        if (!sysReportLogged) { sysReportLogged = true; CrashLog.event(app, "운행 감지 SDK 시스템앱 로드 실패 ${cls.substringAfterLast('.')}: $why") }
        return null
    }

    private fun num(dev: Any, getter: String): Double? =
        (dev.javaClass.getMethod(getter).invoke(dev) as? Number)?.toDouble()

    /** 정규화한 기어(0=N 1=R 2=D 3=P). 못 읽거나 모르는 값이면 null. */
    private fun readGear(app: Context): Int? {
        if (VehicleProbe.vehicleType().isEmpty()) return null   // 헤드유닛이 아니면 SDK 가 없다
        return try {
            val dev = gearDev ?: device(app, GEARBOX_CLASS)?.also { gearDev = it } ?: return null
            if (VehicleProbe.vehicleType().startsWith("Di5")) {
                mapD5Gear((num(dev, "getGear") ?: return null).toInt())   // D5 의 getCurrentGear 는 항상 0 이라 쓰지 않는다
            } else {
                val g = (num(dev, "getCurrentGear") ?: return null).toInt()
                if (g in GEAR_N..GEAR_P) g else null
            }
        } catch (t: Throwable) {
            if (!sdkLogged) { sdkLogged = true; CrashLog.event(app, "운행 감지 기어 읽기 실패: ${t::class.java.simpleName} ${t.message?.take(80)}") }
            null
        }
    }

    /** 차속(km/h) — 기어 게터가 얼어붙었을 때의 보조 확인용. 못 읽으면 null(판정에서 무시). */
    private fun readSpeed(app: Context): Double? {
        if (VehicleProbe.vehicleType().isEmpty()) return null
        return try {
            val dev = speedDev ?: device(app, SPEED_CLASS)?.also { speedDev = it } ?: return null
            val v = num(dev, "getCurrentSpeed") ?: return null
            if (v.isNaN() || v < 0 || v > 300) null else v
        } catch (t: Throwable) { null }
    }

    /** 장치 안쪽에 저장된 Context 필드를 권한 통과 래퍼로 바꿔 끼운다. */
    private fun injectContext(dev: Any, w: VehicleContextWrapper) {
        var c: Class<*>? = dev.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Context::class.java.isAssignableFrom(f.type)) {
                    runCatching { f.isAccessible = true; if (f.get(dev) !is VehicleContextWrapper) f.set(dev, w) }
                }
            }
            c = c.superclass
        }
    }

    // ───────── GPS 속도(기어를 못 읽을 때만) ─────────

    @Volatile private var gpsKmh: Double? = null
    @Volatile private var gpsAt = 0L
    @Volatile private var gpsPrev: Location? = null
    @Volatile private var gpsStarted = false

    private fun gpsSpeed(app: Context): Double? {
        if (!hasLocationPermission(app)) return null
        if (!gpsStarted) startGps(app)
        val k = gpsKmh ?: return null
        return if (SystemClock.elapsedRealtime() - gpsAt > 10_000L) null else k
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val kmh = if (l.hasSpeed()) l.speed * 3.6 else {
                val p = gpsPrev
                val dt = if (p == null) 0.0 else (l.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
                if (p != null && dt > 0.3) p.distanceTo(l) / dt * 3.6 else null
            }
            gpsPrev = l
            if (kmh != null) { gpsKmh = kmh; gpsAt = SystemClock.elapsedRealtime() }
        }
        @Deprecated("API 29 이하 호환용") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGps(app: Context) {
        if (gpsStarted || !hasLocationPermission(app)) return
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            gpsStarted = true
            CrashLog.event(app, "운행 감지: GPS 속도 수신 시작")
        } catch (t: Throwable) {
            CrashLog.event(app, "운행 감지: GPS 시작 실패 ${t::class.java.simpleName}: ${t.message?.take(80)}")
        }
    }

    /** 위치 권한 허용 결과 — 허용됐으면 GPS 속도 수신을 시작한다. */
    fun onLocationPermissionResult(ctx: Context) { if (hasLocationPermission(ctx)) startGps(ctx.applicationContext) }

    /** self-adb(127.0.0.1:5555)로 이 앱에 권한을 부여한다 — 부여 명령이 모두 성공해야 true. */
    private fun grantSelfPermissions(app: Context, perms: List<String>): Boolean {
        val priv = java.io.File(app.filesDir, "adbkey"); val pub = java.io.File(app.filesDir, "adbkey.pub")
        if (!(priv.exists() && pub.exists())) dadb.AdbKeyPair.generate(priv, pub)
        val kp = dadb.AdbKeyPair.read(priv, pub)
        var all = true
        dadb.Dadb.create("127.0.0.1", 5555, kp, 5_000, 10_000).use { d ->
            for (p in perms) {
                val r = d.shell("pm grant ${app.packageName} $p")
                if (r.exitCode != 0) { all = false; CrashLog.event(app, "권한 부여 실패 $p: ${r.allOutput.trim().take(120)}") }
            }
        }
        CrashLog.event(app, "권한 자기 부여 ${if (all) "성공" else "일부 실패"}: ${perms.size}개")
        return all
    }
}
