package com.cseini.byd.karaoke.data

/** 검색 결과·재생 곡 모델(videoId + 표시 정보). */
data class QueueItem(
    val videoId: String,
    val title: String,
    val channel: String = "",
)
