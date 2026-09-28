package com.cseini.byd.karaoke

import android.widget.Button
import androidx.core.content.ContextCompat

/** 화면 하단 공용 네비게이션 바 배선. 현재 화면 버튼은 강조. */
object NavBar {

    /**
     * 모든 화면(검색/녹음함/랭킹/설정)은 MainActivity 창 안의 오버레이라, 하단바는 Activity 를
     * 띄우지 않고 호스트가 화면만 바꾼다(차량 런처의 분할화면 유지).
     * current 는 "search"|"recordings"|"ranking"|"settings".
     */
    fun wireEmbedded(root: android.view.View, current: String, go: (String) -> Unit) {
        fun bindOne(id: Int, target: String) {
            val btn = root.findViewById<Button>(id) ?: return
            if (target == current) btn.setTextColor(ContextCompat.getColor(root.context, R.color.tj_cyan))
            btn.setOnClickListener { if (target != current) go(target) }
        }
        // 홈은 이미 검색화면이어도(current=="search") 항상 눌려야 한다 — "어떤 상황에서든 검색
        // 대기 상태로" 리셋하는 버튼이라, "현재 화면과 같으면 무시"하는 bindOne 규칙을 안 따른다.
        root.findViewById<Button>(R.id.nav_home)?.setOnClickListener { go("home") }
        bindOne(R.id.nav_search, "search")
        bindOne(R.id.nav_recordings, "recordings")
        bindOne(R.id.nav_settings, "settings")
    }
}
