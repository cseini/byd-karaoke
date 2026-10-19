package com.cseini.byd.karaoke

/**
 * 카페 인증 기기 슬롯 집계용 — 이 기기가 BYD 헤드유닛인지 판별하는 ro.vehicle.type 읽기.
 * bydautotrip DiLink5Support.sysProp()/vehicleType()과 완전히 동일한 패턴을 그대로 가져왔다.
 * 일반 안드로이드 API(SystemProperties 리플렉션)만 쓰므로 추가 권한이 필요 없다.
 */
object VehicleProbe {

    /** null=리플렉션 실패, ""=프로퍼티 없음(일반 폰). */
    private fun sysProp(key: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    } catch (t: Throwable) { null }

    /** 서버 전송용 — 못 읽으면 빈 문자열. */
    fun vehicleType(): String = sysProp("ro.vehicle.type") ?: ""

    private val VIN_PROPS = arrayOf("persist.sys.cloud.last_vin", "sys.virtual.vin")

    /** 카페 인증 기기슬롯 집계(차 1대=VIN) — 오토전비 CafeAuthSync.java 동일 패턴.
     *  헤드유닛 getprop이 가끔 손상된 값(16자·6자 등)을 주므로 표준 VIN 형식 검증 후에만 반환. */
    fun vin(): String? {
        for (key in VIN_PROPS) {
            val v = sysProp(key)?.trim()?.uppercase() ?: continue
            if (isValidVin(v)) return v
        }
        return null
    }

    private fun isValidVin(s: String): Boolean {
        if (s.length != 17) return false
        for (c in s) {
            val ok = (c in '0'..'9') || (c in 'A'..'Z' && c != 'I' && c != 'O' && c != 'Q')
            if (!ok) return false
        }
        return true
    }
}
