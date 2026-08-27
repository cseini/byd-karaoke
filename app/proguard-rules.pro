# R8 설정 — 목적은 '안 쓰는 코드 걷어내 APK(=OTA 다운로드) 줄이기'다.
# 이름 난독화까지 하면 리플렉션으로 도는 라이브러리(NewPipe·Rhino·Gson·Retrofit)가 조용히
# 깨질 수 있고, 차에 올라간 뒤에야 알게 된다. 그래서 이름은 그대로 두고 축소만 한다.
-dontobfuscate

# 리플렉션·제네릭 서명이 필요한 라이브러리들이 읽는 속성
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions,
                RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# ── NewPipeExtractor (유튜브 스트림 추출) ────────────────────────────
# 내부적으로 Rhino(JS 엔진)·jsoup·nanojson 을 리플렉션으로 쓴다.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**

# ── Gson: JSON ↔ 모델 매핑은 필드 이름에 의존한다 ──────────────────
-keep class com.cseini.byd.karaoke.data.youtube.SearchResponse { *; }
-keep class com.cseini.byd.karaoke.data.youtube.SearchItem { *; }
-keep class com.cseini.byd.karaoke.data.youtube.ItemId { *; }
-keep class com.cseini.byd.karaoke.data.youtube.Snippet { *; }
# 예약 목록·녹음 메타·재생 기록도 Gson 으로 저장한다(필드명이 곧 저장 포맷).
-keep class com.cseini.byd.karaoke.data.QueueItem { *; }
-keep class com.cseini.byd.karaoke.data.RecordingItem { *; }
-keep class com.cseini.byd.karaoke.data.PlayHistoryItem { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit: 인터페이스와 애노테이션이 런타임에 읽힌다 ─────────────
-keep,allowshrinking interface retrofit2.Call
-keep class com.cseini.byd.karaoke.data.youtube.YouTubeApi { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── 그 밖에 리플렉션/네이티브로 도는 것들 ──────────────────────────
-keep class fi.iki.elonen.** { *; }        # NanoHTTPD (예약·공유 서버)
-keep class dev.mobile.dadb.** { *; }      # 무음 업데이트(adb)
-dontwarn dev.mobile.dadb.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 매니페스트에 이름으로 적힌 컴포넌트(기본 규칙에 포함되지만 명시해 둔다)
-keep class com.cseini.byd.karaoke.KeyCatcherService { *; }
-keep class com.cseini.byd.karaoke.media.** { *; }
-keep class com.cseini.byd.karaoke.update.RestartReceiver { *; }
