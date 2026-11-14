package com.cseini.byd.karaoke

import android.content.Context
import java.util.UUID

/** 설치할 때 한 번 만든 무작위 값 — 기기 고유번호(ANDROID_ID)와 무관하고, 앱을 지우면 새로 만들어진다. */
object InstallId {
    fun get(ctx: Context): String {
        val p = ctx.applicationContext.getSharedPreferences("install_id", Context.MODE_PRIVATE)
        return p.getString("v", null) ?: UUID.randomUUID().toString().also { p.edit().putString("v", it).apply() }
    }
}
