// 앱 이벤트 로그 수신(옵트인) → D1 karaoke-logs.
// 클라이언트는 events.log 꼬리 전체를 반복해 보낸다 — 중복은 UNIQUE(device, line)로 여기서 걸러진다.
import { deviceHash, scrubLine, maybeSweep } from '../_lib/privacy.js';

export async function onRequestPost({ request, env, waitUntil }) {
  try {
    const body = await request.json();
    const device = (await deviceHash(env, String(body.device || '').slice(0, 64))) || 'anon';
    const ver = String(body.ver || '').slice(0, 32);
    const lines = Array.isArray(body.lines) ? body.lines.slice(0, 300) : [];
    if (!lines.length) return new Response(null, { status: 400 });
    const stmt = env.DB.prepare('INSERT OR IGNORE INTO logs (device, ver, line) VALUES (?1, ?2, ?3)');
    await env.DB.batch(
      lines
        .filter((l) => typeof l === 'string' && l.trim())
        .map((l) => stmt.bind(device, ver, scrubLine(l.slice(0, 8000))))
    );
    maybeSweep(env, waitUntil);
    return new Response(null, { status: 204 });
  } catch {
    return new Response(null, { status: 400 });
  }
}
