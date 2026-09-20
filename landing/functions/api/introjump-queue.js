// 맥미니 배치 전용(비공개 — INTROJUMP_SECRET 헤더 필요).
// GET  ?limit=N : 아직 처리 안 된 곡을 재생 많이 된 순으로 N개 반환.
// POST {videoId, ok}: 처리 완료 표시(성공/실패 무관 — 재시도 방지).
function auth(request, env) {
  return request.headers.get('X-Auth') === env.INTROJUMP_SECRET;
}

export async function onRequestGet({ request, env }) {
  if (!auth(request, env)) return new Response(null, { status: 403 });
  const url = new URL(request.url);
  const limit = Math.min(50, Number(url.searchParams.get('limit')) || 10);
  const { results } = await env.DB.prepare(
    `SELECT video_id, title, play_count, priority FROM played_songs
     WHERE processed = 0 ORDER BY priority DESC LIMIT ?1`
  ).bind(limit).all();
  return new Response(JSON.stringify(results || []), {
    headers: { 'Content-Type': 'application/json' },
  });
}

export async function onRequestPost({ request, env }) {
  if (!auth(request, env)) return new Response(null, { status: 403 });
  try {
    const body = await request.json();
    const videoId = String(body.videoId || '').slice(0, 32);
    if (!videoId) return new Response(null, { status: 400 });
    await env.DB.prepare('UPDATE played_songs SET processed = 1 WHERE video_id = ?1')
      .bind(videoId).run();
    return new Response(null, { status: 204 });
  } catch {
    return new Response(null, { status: 400 });
  }
}
