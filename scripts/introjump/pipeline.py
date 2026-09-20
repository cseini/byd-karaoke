#!/usr/bin/env python3
"""
간주점프 데이터 파이프라인 — 맥미니 전용(유튜브 추출이 되는 유일한 곳).

주어진 유튜브 videoId 목록마다:
  1. NewPipe(Gradle IntroFrameDumpTest, YoutubeSmokeTest 와 같은 추출 경로)로 progressive
     스트림 URL 확보 — yt-dlp/임베드는 이 환경에서 봇차단되지만 NewPipe 는 된다.
  2. ffmpeg(번들, brew 불필요)로 인트로 30초를 2fps 프레임으로 추출.
  3. detect.first_lyric_onset() 으로 "첫 가사(로마자 발음줄) 등장" 시각 검출.
  4. jump_sec = max(0, onset - LEAD_SEC) 을 landing/introjumps.json 에 upsert.

실행 예:
  python3 scripts/introjump/pipeline.py HtzFBF_mWCI D67jNiKLWC8 ...
  python3 scripts/introjump/pipeline.py --file scripts/introjump/seed_videos.txt
  python3 scripts/introjump/pipeline.py --queue          # 서버 큐(사용자 실재생) 자동 처리 + 배포

결과는 landing/introjumps.json 에 누적 저장(기존 곡 보존, 재검출 시 덮어씀).
--file/videoId 직접 지정 모드는 배포하지 않는다(호출자가 확인 후 배포).
--queue 모드는 karaoke.usenu.kr/api/introjump-queue(맥미니 전용, INTROJUMP_SECRET 인증)
에서 아직 처리 안 된 곡(사용자 실재생 상위)을 가져와 처리하고, 성공/실패 무관하게
처리완료로 마킹(재시도 방지)한 뒤 landing 을 자동 재배포한다. launchd(주기 실행) 용.
"""
import json, os, subprocess, sys, tempfile, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
LANDING_DIR = os.path.join(REPO_ROOT, "landing")
LANDING_JSON = os.path.join(LANDING_DIR, "introjumps.json")
SECRET_FILE = os.path.join(HERE, ".secret")
QUEUE_URL = "https://karaoke.usenu.kr/api/introjump-queue"
INTRO_URL_FILE = "/tmp/intro_url.txt"
LEAD_SEC = 2.0   # 가사 시작보다 이만큼 일찍 점프(너무 늦게 떨어지면 첫 소절을 놓침)
INTRO_SCAN_SEC = 30
FPS = 2
TIME_BUDGET_SEC = 3600   # 하루 실행 시간 예산(곡 수 고정 대신 — 곡당 ~1분, 남으면 다음날 이어서)
FETCH_CHUNK = 20         # 큐에서 한 번에 가져올 개수(서버 최대 50)

sys.path.insert(0, HERE)
from detect import first_lyric_onset  # noqa: E402


def get_ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


GRADLE_TIMEOUT_SEC = 90   # 이 맥미니에서 다른 프로젝트 세션이 동시에 gradle 을 쓰면(예: 다른
# 세션이 --stop 을 호출) 데몬이 죽거나 응답 없어져 subprocess 가 무한정 걸릴 수 있다(실측:
# 새벽 자동실행이 3시간 넘게 멈춰있었음) — 반드시 타임아웃을 걸어 한 곡 실패로 국한시킨다.


def extract_stream_url(video_id):
    """Gradle IntroFrameDumpTest 로 NewPipe 추출 → /tmp/intro_url.txt 파싱."""
    if os.path.exists(INTRO_URL_FILE):
        os.remove(INTRO_URL_FILE)
    env = dict(os.environ, RUN_YT_SMOKE="1", SMOKE_VIDEO=video_id)
    try:
        subprocess.run(
            ["./gradlew", ":app:testProdReleaseUnitTest",
             "--tests", "com.cseini.byd.karaoke.IntroFrameDumpTest", "--rerun", "-q"],
            cwd=REPO_ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=GRADLE_TIMEOUT_SEC,
        )
    except subprocess.TimeoutExpired:
        print(f"[{video_id}] gradle 응답 없음({GRADLE_TIMEOUT_SEC}s 초과, 다른 세션과 데몬 충돌 등) — 이 곡만 건너뜀")
        return None, None
    if not os.path.exists(INTRO_URL_FILE):
        return None, None
    title, video_only_480, muxed = None, None, None
    with open(INTRO_URL_FILE, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "TITLE":
                title = parts[1] if len(parts) > 1 else None
            elif parts[0] == "VIDEOONLY" and len(parts) > 2 and parts[1] == "480p" and video_only_480 is None:
                video_only_480 = parts[2]
            elif parts[0] == "MUXED" and len(parts) > 2 and muxed is None:
                muxed = parts[2]
    return title, (video_only_480 or muxed)


# app/.../player/YouTubeDownloader.kt 의 USER_AGENT 와 동일 — googlevideo 는 이 UA 로
# 스트림 URL 을 발급했는데 다른(또는 없는) UA 로 받으러 오면 간헐적으로 403 을 낸다.
# 이게 빠져 있으면 검출 실패("첫가사 미검출")가 사실 프레임을 아예 못 뽑은 것일 수 있다.
STREAM_USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


def extract_frames(stream_url, out_dir):
    ff = get_ffmpeg()
    os.makedirs(out_dir, exist_ok=True)
    subprocess.run(
        [ff, "-y", "-loglevel", "error", "-user_agent", STREAM_USER_AGENT,
         "-t", str(INTRO_SCAN_SEC), "-i", stream_url,
         "-vf", f"fps={FPS},scale=640:-1", os.path.join(out_dir, "f_%03d.png")],
        check=False,
    )


def load_db():
    if os.path.exists(LANDING_JSON):
        with open(LANDING_JSON, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_db(db):
    with open(LANDING_JSON, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=1, sort_keys=True)


def process_one(video_id, db):
    """성공(간주점프 데이터 확보)하면 True, 아니면 False — 어느 쪽이든 "시도는 끝남"."""
    print(f"[{video_id}] 스트림 추출 중…")
    title, url = extract_stream_url(video_id)
    if not url:
        print(f"[{video_id}] 추출 실패(스트림 없음) — 건너뜀")
        return False
    with tempfile.TemporaryDirectory(prefix="introjump_") as tmp:
        extract_frames(url, tmp)
        onset, evidence = first_lyric_onset(tmp, fps=FPS)
    if onset is None:
        print(f"[{video_id}] 첫가사 미검출 — 건너뜀 (title={title})")
        return False
    jump_sec = round(max(0.0, onset - LEAD_SEC), 1)
    db[video_id] = {"jump_sec": jump_sec, "onset_sec": onset, "title": title or ""}
    print(f"[{video_id}] onset={onset}s → jump={jump_sec}s (title={title}) 근거={evidence}")
    return True


def load_secret():
    if not os.path.exists(SECRET_FILE):
        return None
    with open(SECRET_FILE, encoding="utf-8") as f:
        return f.read().strip()


def http_json(url, secret, method="GET", body=None):
    # Cloudflare 가 Python urllib 기본 User-Agent 를 봇으로 차단(error code 1010) →
    # 일반 브라우저처럼 보이는 UA 로 우회.
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-Auth", secret)
    req.add_header("User-Agent", "byd-karaoke-introjump/1.0")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def mark_processed(video_id, secret):
    try:
        http_json(QUEUE_URL, secret, method="POST", body={"videoId": video_id})
    except Exception as e:
        print(f"[{video_id}] processed 마킹 실패(다음에 재시도됨): {e}")


def deploy_landing():
    """dist 재생성(introjumps.json 포함) + wrangler pages deploy. release-process 패턴 재사용."""
    dist = os.path.join(LANDING_DIR, "dist")
    if not os.path.isdir(dist):
        os.makedirs(dist)
    for name in ("index.html", "min.json", "introjumps.json"):
        src = os.path.join(LANDING_DIR, name)
        if os.path.exists(src):
            with open(src, "rb") as sf, open(os.path.join(dist, name), "wb") as df:
                df.write(sf.read())
    subprocess.run(
        ["npx", "wrangler", "pages", "deploy", "dist", "--project-name=karaoke", "--commit-dirty=true"],
        cwd=LANDING_DIR, check=False,
    )


def run_queue():
    """1시간 시간 예산 동안 큐(재생 많은 순)를 계속 가져와 처리. 곡 수 고정 안 함 —
    다 처리하고도 시간이 남으면 그날은 그걸로 끝, 시간이 부족하면 남은 곡은 서버에
    processed=0 로 남아 다음 실행(다음날) 때 이어서 처리된다(유실 없음)."""
    secret = load_secret()
    if not secret:
        print(f"비밀키 없음({SECRET_FILE}) — --queue 모드 사용 불가")
        sys.exit(1)

    db = load_db()
    start = time.time()
    total_attempted = 0
    changed = False
    consecutive_timeouts = 0   # gradle 이 죽어있으면(다른 세션과 충돌 등) 매 곡 GRADLE_TIMEOUT_SEC
    # 근처로 걸린다 — 연속 3번이면 오늘은 포기하고 조기 종료(무한정 시간만 날리는 것 방지).

    while time.time() - start < TIME_BUDGET_SEC:
        try:
            chunk = http_json(f"{QUEUE_URL}?limit={FETCH_CHUNK}", secret)
        except Exception as e:
            print(f"큐 조회 실패: {e}")
            break
        if not chunk:
            print("처리할 곡 없음(모두 처리됨 또는 재생 기록 없음) — 종료")
            break
        for item in chunk:
            if time.time() - start >= TIME_BUDGET_SEC:
                print(f"시간 예산({TIME_BUDGET_SEC}s) 소진 — 남은 곡은 다음 실행에서 이어감")
                break
            vid = item["video_id"]
            t0 = time.time()
            ok = process_one(vid, db)
            if not ok:
                # 실측: 연속 처리 중 부하·타임아웃으로 실패한 곡을 곧바로 재시도하면
                # 대부분 성공한다(가시·Vitamin ME 등 "미검출"이 재실행 시 바로 잡힘,
                # 알고리즘 결함이 아니라 일시적 처리 실패였음) — 1회만 더 시도.
                print(f"[{vid}] 재시도…")
                ok = process_one(vid, db)
            elapsed_one = time.time() - t0
            total_attempted += 1
            if ok:
                save_db(db)
                changed = True
            mark_processed(vid, secret)  # 성공/실패 무관 — 재시도 방지
            if not ok and elapsed_one >= GRADLE_TIMEOUT_SEC * 1.5:
                consecutive_timeouts += 1
                if consecutive_timeouts >= 3:
                    print("gradle 이 연속으로 응답 없음 — 오늘은 여기서 포기(다음 실행에서 재시도)")
                    break
            else:
                consecutive_timeouts = 0
        else:
            continue  # for 를 끝까지 돌았으면(중간에 break 안 했으면) 다음 chunk 계속
        break  # for 안에서 시간초과/연속타임아웃으로 break 했으면 while 도 종료

    if changed:
        print("landing 재배포 중…")
        deploy_landing()
    elapsed = round(time.time() - start, 1)
    print(f"큐 처리 완료. {total_attempted}곡 시도({elapsed}s), DB 총 {len(db)}곡 → {LANDING_JSON}")


def main():
    args = sys.argv[1:]
    if args and args[0] == "--queue":
        run_queue()
        return

    video_ids = []
    if args and args[0] == "--file":
        with open(args[1], encoding="utf-8") as f:
            video_ids = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    else:
        video_ids = args
    if not video_ids:
        print("사용법: pipeline.py <videoId...> | --file <목록파일> | --queue")
        sys.exit(1)

    db = load_db()
    for vid in video_ids:
        process_one(vid, db)
        save_db(db)  # 곡마다 즉시 저장 — 중간에 중단돼도 유실 없음
    print(f"완료. 총 {len(db)}곡 저장됨 → {LANDING_JSON}")


if __name__ == "__main__":
    main()
