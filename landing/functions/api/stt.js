// 음성검색 STT 프록시 — 앱이 보낸 16kHz mono WAV 를 Groq whisper-large-v3-turbo 로 전사.
// 사용자 키 불필요(워커 공용 Groq 키, GROQ_KEY secret). 429/실패 시 앱이 온디바이스 whisper 로 폴백.
// 남용 방지: ?k=<STT_SECRET> 검증(앱과 공유). lang 기본 ko.
export async function onRequestPost({ request, env }) {
  const t0 = Date.now();
  try {
    const url = new URL(request.url);
    if (env.STT_SECRET && url.searchParams.get('k') !== env.STT_SECRET) {
      return json({ ok: false, error: 'unauthorized' }, 401);
    }
    const lang = (url.searchParams.get('lang') || 'ko').slice(0, 8);

    const wav = await request.arrayBuffer();
    if (!wav || wav.byteLength < 1000) return json({ ok: false, error: 'empty_audio' }, 400);

    // turbo 우선(속도 최적화) → 한도 소진(429)이면 large-v3 로 폴백.
    // Groq 무료 한도는 모델별로 따로(각 2,000회/일) 잡혀 합산되므로 하루 ~4,000회를 확보한다.
    const MODELS = ['whisper-large-v3-turbo', 'whisper-large-v3'];
    let all429 = true;
    for (const model of MODELS) {
      const form = new FormData();
      form.append('file', new Blob([wav], { type: 'audio/wav' }), 'audio.wav');
      form.append('model', model);
      form.append('language', lang);
      form.append('response_format', 'json');
      form.append('temperature', '0');

      const resp = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${env.GROQ_KEY}` },
        body: form,
      });
      if (resp.status === 429) continue;   // 이 모델 한도 소진 → 다음 모델
      all429 = false;
      if (!resp.ok) {
        const body = await resp.text();
        return json({ ok: false, error: `groq_${resp.status}`, detail: body.slice(0, 120) }, 502);
      }
      const data = await resp.json();
      const text = String(data.text || '').trim();
      return json({ ok: true, text, ms: Date.now() - t0, engine: model }, 200);
    }
    // 모든 모델 429 → 앱이 온디바이스 폴백
    if (all429) return json({ ok: false, error: 'rate_limit' }, 429);
  } catch (e) {
    return json({ ok: false, error: String(e).slice(0, 120) }, 502);
  }
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });
}
