package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.data.RecordingItem

/**
 * 녹음함/랭킹/설정 화면을 MainActivity 임베드 오버레이로 띄우기 위한 호스트.
 * 창을 하나만 써야 차량 런처의 분할화면이 유지된다.
 */
interface ScreenHost {
    fun onScreenBack()
    fun onReplayRecording(item: RecordingItem)
    /** 임베드 상태에서 하단바로 화면 전환: "search" | "recordings" | "ranking" | "settings". */
    fun onNavigate(target: String) {}

    /**
     * 설정이 저장됐을 때. 임베드 화면은 같은 창이라 닫아도 onResume 이 안 불리므로,
     * 호스트가 여기서 화면·기능(음성 버튼 노출, 물리버튼 등)을 즉시 갱신한다.
     */
    fun onSettingsSaved() {}

    /** 설정에서 녹음을 전체 삭제했을 때 — 호스트의 인메모리 녹음 목록을 다시 읽는다. */
    fun onRecordingsChanged() {}

    /** 마이크 버튼 진단 시작/종료 — 장치 정보·버튼 신호(hex)를 onLine 으로 스트리밍. */
    fun onMicDiagStart(onLine: (String) -> Unit) {}
    fun onMicDiagStop() {}

    /** 마이크 버튼 학습 — 눌렀다 뗀 버튼의 (바이트인덱스, 값, hex)를 전달. */
    fun onMicLearnStart(onCapture: (Int, Int, String) -> Unit) {}
    fun onMicLearnStop() {}

    /** 유튜브 스트림 추출이 연속 실패(방식 변경 의심)했을 때 호스트에 알림. */
    fun onRepeatedPlayFailure() {}
}
