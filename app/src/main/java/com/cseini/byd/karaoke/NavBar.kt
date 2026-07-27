package com.cseini.byd.karaoke

import android.app.Activity
import android.content.Intent
import android.widget.Button
import androidx.core.content.ContextCompat

/** 화면 하단 공용 네비게이션 바 배선. 현재 화면 버튼은 강조. */
object NavBar {

    fun wire(activity: Activity, current: Class<*>) {
        fun go(cls: Class<*>) {
            if (cls == current) return
            activity.startActivity(
                Intent(activity, cls).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        bind(activity, R.id.nav_search, MainActivity::class.java, current, ::go)
        bind(activity, R.id.nav_recordings, RecordingsActivity::class.java, current, ::go)
        bind(activity, R.id.nav_ranking, RankingActivity::class.java, current, ::go)
        bind(activity, R.id.nav_settings, SettingsActivity::class.java, current, ::go)
    }

    /**
     * 임베드(단일 화면) 모드용 배선. Activity 를 띄우지 않고 호스트가 화면만 바꾸므로
     * 분할화면이 유지된다. current 는 "search"|"recordings"|"ranking"|"settings".
     */
    fun wireEmbedded(root: android.view.View, current: String, go: (String) -> Unit) {
        fun bindOne(id: Int, target: String) {
            val btn = root.findViewById<Button>(id) ?: return
            if (target == current) btn.setTextColor(ContextCompat.getColor(root.context, R.color.tj_cyan))
            btn.setOnClickListener { if (target != current) go(target) }
        }
        bindOne(R.id.nav_search, "search")
        bindOne(R.id.nav_recordings, "recordings")
        bindOne(R.id.nav_ranking, "ranking")
        bindOne(R.id.nav_settings, "settings")
    }

    private fun bind(
        activity: Activity,
        id: Int,
        target: Class<*>,
        current: Class<*>,
        go: (Class<*>) -> Unit,
    ) {
        val btn = activity.findViewById<Button>(id) ?: return
        if (target == current) {
            btn.setTextColor(ContextCompat.getColor(activity, R.color.tj_cyan))
        }
        btn.setOnClickListener { go(target) }
    }
}
