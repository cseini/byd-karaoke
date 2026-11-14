const enc = new TextEncoder();

function hex(buf) {
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function deviceHash(env, id) {
  const raw = String(id || '');
  if (!raw || !env.DEVICE_SALT) return null;
  const key = await crypto.subtle.importKey('raw', enc.encode(env.DEVICE_SALT), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', key, enc.encode(raw))).slice(0, 16);
}

const RULES = [
  [/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[이메일]'],
  [/Bearer\s+[A-Za-z0-9._~+/=-]{10,}/gi, 'Bearer [토큰]'],
  [/\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b/g, '[MAC]'],
  [/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '[IP]'],
  [/-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}/g, '[좌표]'],
  [/(?:\+?82[-\s]?)?\(?0?1[016789]\)?[-\s]?\d{3,4}[-\s]?\d{4}\b/g, '[전화]'],
  [/((?:android_?id|device_?id|imei|ssid)\s*[=:]\s*)[^\s,;&]+/gi, '$1[값]'],
  [/(nick(?:name)?\s*[=:]\s*)[^\s,;&]+/gi, '$1[닉네임]'],
  [/\b[A-HJ-NPR-Z0-9]{17}\b/g, '[차대번호]'],
];

export function scrubLine(line) {
  let s = String(line);
  for (const [re, to] of RULES) s = s.replace(re, to);
  return s;
}

export async function sweep(env) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM logs WHERE received_at < datetime('now', '-30 days')"),
    env.DB.prepare('DELETE FROM song_device_plays WHERE last_played_at < ?1').bind(new Date(Date.now() - 90 * 86400000).toISOString()),
  ]);
}

export function maybeSweep(env, waitUntil) {
  if (Math.random() < 0.02) waitUntil(sweep(env).catch(() => {}));
}
