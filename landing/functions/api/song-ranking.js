// 우리 앱 사용자들이 실제로 많이 부른 곡 순위(공개, 조회만) — 멜론/TJ 시드 가중치(priority)는
// 제외하고 순수 play_count(진짜 재생 보고 횟수)만 본다. 시드로만 존재하고 아직 아무도 안
// 부른 곡은 play_count=0 이라 자연히 랭킹에서 빠진다.
// unique_devices 우선 정렬 — 한 헤드유닛이 같은 곡을 여러 번 불러도 순위엔 1로만 반영되게
// 해서, "여러 명이 즐겨 부르는 곡"과 "한 명이 반복 재생한 곡"을 구분한다(play_count 는 참고용).
export async function onRequestGet({ request, env }) {
  const url = new URL(request.url);
  const limit = Math.min(100, Number(url.searchParams.get('limit')) || 20);
  const { results } = await env.DB.prepare(
    `SELECT video_id, title, play_count, unique_devices FROM played_songs
     WHERE play_count > 0 ORDER BY unique_devices DESC, play_count DESC, last_played_at DESC LIMIT ?1`
  ).bind(limit).all();
  return new Response(JSON.stringify(results || []), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=300' },
  });
}
