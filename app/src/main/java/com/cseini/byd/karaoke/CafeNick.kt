package com.cseini.byd.karaoke

import android.content.Context

object CafeNick {
    /** 설치할 때 한 번 만든 무작위 8hex 꼬리표(PII 아님) — 기기 고유번호와 무관하고 다른 앱과도 연결되지 않는다. 앱을 지우면 새로 만들어진다. */
    fun deviceTag(ctx: Context): String {
        val p = ctx.applicationContext.getSharedPreferences("device_tag", Context.MODE_PRIVATE)
        p.getString("v", null)?.let { return it }
        val b = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }.also { p.edit().putString("v", it).apply() }
    }
}
