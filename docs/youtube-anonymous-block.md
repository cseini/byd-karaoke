# 유튜브 익명 접근 차단 (2026-09-04) — 원인 IPv6, 해결됨

## 증상
특정 네트워크에서 곡 재생이 전혀 안 됨. 목록·검색은 되는데 재생만 실패.
같은 시각 3rdLabPlayer(byd-thirdplayer)도 동시에 같은 증상.

## 원인 (확정)
NewPipe 추출 단계에서 유튜브가 막는다. 3rdLabPlayer 진단 로그의 실제 예외:

```
SignInConfirmNotBotException:
YouTube probably temporarily blocked anonymous watch access with this IP,
got error LOGIN_REQUIRED: "Sign in to confirm that you're not a bot"
```

**차단 기준은 로그인 여부가 아니라 IP, 그중에서도 IPv6 대역이다.**
3rdLabPlayer 서버 집계(`/ythealth`) 6시간치:

| IP | 성공 | 실패 |
|---|---|---|
| `2001:2d8:221c:730f:1d84:…` | 0 | 5 |
| `2001:2d8:221c:730f:b5e5:…` | 0 | 6 |
| `2001:2d8:73a8:613:a961:…` | 1 | 0 |
| `106.101.139.65` (IPv4) | 1 | 0 |

- 실패는 전부 **같은 `/64`**(`2001:2d8:221c:730f`). 기기 한 대가 주소만 바뀐 것.
- 같은 시각 다른 IPv6 대역과 IPv4는 정상.
- 즉 유튜브가 **IPv6 대역을 통째로** 봇 차단한 것. yt-dlp 의 `--force-ipv4` 가 대응하는 바로 그 상황.
- 앱 버그도, NewPipeExtractor 버전 문제도 아니다.

## 해결 (3rdLabPlayer v1.44 — 실기 확인 완료)
**차단 감지 시 IPv4 로만 재접속.** 로그인·쿠키 불필요.
(v1.36 의 로그인 헤더 우회는 이 문제에 효과 없었다 — 전체 인증은 봇 차단은 통과하지만
NewPipe 가 `Could not get visitorData` 로 깨지고, 쿠키만 보내면 차단도 안 풀린다.)

### 노래방앱 이식 (파일 2개, 그대로 복사 가능)

두 앱의 `YouTubeDownloader.kt` / `StreamPlayer.kt` 는 같은 구조라 아래로 끝난다.

**1) `player/YouTubeDownloader.kt`** — IPv4 전용 클라이언트 + 스레드 플래그

```kotlin
// client 선언 바로 아래
private val clientV4 = client.newBuilder()
    .dns(object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val all = okhttp3.Dns.SYSTEM.lookup(hostname)
            return all.filterIsInstance<java.net.Inet4Address>().ifEmpty { all }
        }
    })
    .build()

// execute() 안, 기존 client.newCall(...) 을 교체
val response = (if (forceV4.get() == true) clientV4 else client).newCall(builder.build()).execute()

// companion object 안
private val forceV4 = ThreadLocal.withInitial { false }

fun <T> withIpv4(block: () -> T): T {
    forceV4.set(true)
    try { return block() } finally { forceV4.set(false) }
}

/** 유튜브가 "익명 접근 차단(로그인해서 봇 아님을 증명)" 이라고 답했는지. */
fun isAnonBlocked(t: Throwable): Boolean {
    val m = "${t::class.java.simpleName} ${t.message}"
    return m.contains("not a bot", true) || m.contains("LOGIN_REQUIRED", true) ||
        m.contains("SignInConfirmNotBot", true)
}
```

**2) `player/StreamPlayer.kt` 186행 부근** — `fetchPage()` 실패 시 IPv4 재시도

```kotlin
// 기존
val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
extractor.fetchPage()

// 변경
val url = "https://www.youtube.com/watch?v=$videoId"
val extractor = try {
    ServiceList.YouTube.getStreamExtractor(url).also { it.fetchPage() }
} catch (e: Exception) {
    if (!YouTubeDownloader.isAnonBlocked(e)) throw e
    // 유튜브가 IPv6 대역을 막은 경우 — IPv4 로 다시 붙으면 풀린다.
    ServiceList.YouTube.getStreamExtractor(url).also {
        YouTubeDownloader.withIpv4 { it.fetchPage() }
    }
}
```

평소 경로는 시스템 기본 DNS 그대로 — 정상 회선에는 영향 없다.

## 진단 방법
차단 여부는 IP에 따라 갈리므로, 재현 전에 "유튜브 전면 차단인지"를 먼저 가른다.
3rdLabPlayer 의 프로브 테스트로 즉시 확인된다:

```
cd ~/project/byd-thirdplayer
./gradlew :app:testDebugUnitTest --tests '*ExtractProbeTest*' -i
```
추출 성공 + 스트림 URL HTTP 206 이면 유튜브는 정상 → 그 기기/네트워크의 IP 문제.
