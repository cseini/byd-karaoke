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
# OTA 릴리스 JSON 모델 — 여기 빠지면 R8 이 '아무도 인스턴스화 안 함 → fromJson 결과는 항상
# null'로 추론해 업데이트 경로(다운로드·무음설치·dadb) 전체를 죽은 코드로 제거한다
# (v4.75 lab 1차 빌드에서 실제 발생. 업데이트가 영영 안 되는 벽돌 직전 사고).
-keep class com.cseini.byd.karaoke.update.UpdateManager$Release { *; }
-keep class com.cseini.byd.karaoke.update.UpdateManager$Asset { *; }
-keep class com.cseini.byd.karaoke.update.UpdateManager$MinInfo { *; }
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
-keep class dadb.** { *; }                 # 무음 업데이트(adb) — Maven 좌표는 dev.mobile:dadb 지만 실제 패키지는 dadb
-dontwarn dadb.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# jsoup 이 참조하는 널가능성 애노테이션 — 컴파일 시에만 쓰이고 런타임엔 없어도 된다.
-dontwarn org.jspecify.annotations.**

# 매니페스트에 이름으로 적힌 컴포넌트(기본 규칙에 포함되지만 명시해 둔다)
-keep class com.cseini.byd.karaoke.KeyCatcherService { *; }
-keep class com.cseini.byd.karaoke.media.** { *; }
-keep class com.cseini.byd.karaoke.update.RestartReceiver { *; }

# BYD 마이크 서비스 AIDL — 원격 서비스와 바이너리(transaction 코드) 호환 유지 필수
-keep class com.byd.minikaraoke.** { *; }
