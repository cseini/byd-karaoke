package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.data.RecordingItem

/**
 * 녹음함/랭킹/설정 화면을 독립 Activity 로도, MainActivity 임베드 오버레이로도 재사용하기 위한 호스트.
 * embedded=true(테스트 앱, 분할화면 유지) 면 화면 안에서 처리하고, false(prod) 면 Activity 로 동작.
 */
interface ScreenHost {
    val embedded: Boolean
    fun onScreenBack()
    fun onReplayRecording(item: RecordingItem)
    /** 임베드 상태에서 하단바로 화면 전환: "search" | "recordings" | "ranking" | "settings". */
    fun onNavigate(target: String) {}

    /**
     * 설정이 저장됐을 때. 임베드 화면은 같은 창이라 닫아도 onResume 이 안 불리므로,
     * 호스트가 여기서 화면·기능(음성 버튼 노출, 물리버튼 등)을 즉시 갱신한다.
     */
    fun onSettingsSaved() {}
}
