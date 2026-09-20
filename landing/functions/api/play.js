// 앱이 노래방 모드로 곡을 재생할 때마다 호출(옵트인 아님, fire-and-forget) → D1 played_songs.
// 목적: 사용자들이 실제로 많이 부르는 TJ/금영 곡을 자동으로 추려 간주점프 데이터화 대상으로 삼는다.
export async function onRequestPost({ request, env }) {
  try {
    const body = await request.json();
    const videoId = String(body.videoId || '').slice(0, 32);
    const title = String(body.title || '').slice(0, 200);
    if (!videoId) return new Response(null, { status: 400 });
    const now = new Date().toISOString();
    // play_count = 진짜 사용자 재생수(인기 집계용, 시드로 안 건드림). priority = 간주점프
    // 큐 처리 순서(시드는 여기에 가중치를 심고, play_count 는 0부터 순수 집계).
    // source 는 최초 INSERT 때만 결정(기본 'user') — 이미 'seed' 인 곡을 사용자가 불러도
    // 시드 표시는 유지하면서 play_count 만 정상적으로 누적된다(ON CONFLICT 에서 source 미언급).
    await env.DB.prepare(
      `INSERT INTO played_songs (video_id, title, play_count, priority, first_played_at, last_played_at, processed)
       VALUES (?1, ?2, 1, 1, ?3, ?3, 0)
       ON CONFLICT(video_id) DO UPDATE SET
         play_count = play_count + 1,
         priority = priority + 1,
         last_played_at = ?3,
         title = excluded.title`
    ).bind(videoId, title, now).run();
    return new Response(null, { status: 204 });
  } catch {
    return new Response(null, { status: 400 });
  }
}
