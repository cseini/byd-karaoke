package com.cseini.byd.karaoke.data.youtube

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ── YouTube Data API v3 search.list 응답 모델(필요 필드만) ──
data class SearchResponse(val items: List<SearchItem> = emptyList())
data class SearchItem(val id: ItemId?, val snippet: Snippet?)
data class ItemId(val videoId: String?)
data class Snippet(val title: String?, val channelTitle: String?)

interface YouTubeApi {
    @GET("youtube/v3/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("key") apiKey: String,
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("videoEmbeddable") embeddable: String = "true",
        @Query("maxResults") maxResults: Int = 20,
        @Query("regionCode") regionCode: String = "KR",
    ): SearchResponse

    companion object {
        fun create(): YouTubeApi = Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YouTubeApi::class.java)
    }
}
