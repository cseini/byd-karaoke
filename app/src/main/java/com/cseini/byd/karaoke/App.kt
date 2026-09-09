package com.cseini.byd.karaoke

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/**
 * 앱 Application. coil(썸네일 로딩) 메모리 캐시를 낮게 잡아 헤드유닛(저RAM)에서
 * 검색 카드·최근 목록 썸네일 때문에 프로세스가 메모리 회수로 죽는 빈도를 줄인다.
 */
class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
            .build()
}
