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
        bind(activity, R.id.nav_queue, QueueActivity::class.java, current, ::go)
        bind(activity, R.id.nav_recordings, RecordingsActivity::class.java, current, ::go)
        bind(activity, R.id.nav_ranking, RankingActivity::class.java, current, ::go)
        bind(activity, R.id.nav_settings, SettingsActivity::class.java, current, ::go)
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
