import test from 'node:test';
import assert from 'node:assert';
import { deviceHash, scrubLine } from '../functions/_lib/privacy.js';

test('기기 번호는 비밀값이 있을 때만 해시되고 없으면 null(원문을 저장하지 않음)', async () => {
  assert.strictEqual(await deviceHash({}, 'abcdef0123456789'), null);
  const env = { DEVICE_SALT: 'salt-a' };
  const h = await deviceHash(env, 'abcdef0123456789');
  assert.match(h, /^[0-9a-f]{16}$/);
  assert.notStrictEqual(h, 'abcdef0123456789');
  assert.strictEqual(await deviceHash(env, 'abcdef0123456789'), h);
  assert.notStrictEqual(await deviceHash({ DEVICE_SALT: 'salt-b' }, 'abcdef0123456789'), h);
  assert.notStrictEqual(await deviceHash(env, 'abcdef0123456780'), h);
  assert.strictEqual(await deviceHash(env, ''), null);
});

test('앞 4바이트 sha256(링크락 기기 꼬리표 방식)과 이어지지 않는다', async () => {
  const raw = 'abcdef0123456789';
  const sha = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw)))).map((b) => b.toString(16).padStart(2, '0')).join('');
  const h = await deviceHash({ DEVICE_SALT: 'salt-a' }, raw);
  assert.ok(!h.includes(sha.slice(0, 8)) && !sha.includes(h.slice(0, 8)));
});

test('로그 줄의 식별 정보를 가린다', () => {
  const cases = [
    ['mail me a.b@example.com now', '[이메일]'],
    ['ip 203.0.113.7 ok', '[IP]'],
    ['mac aa:bb:cc:dd:ee:ff', '[MAC]'],
    ['pos 37.123456, 127.123456', '[좌표]'],
    ['call 010-1234-5678', '[전화]'],
    ['call +82 10 1234 5678', '[전화]'],
    ['nickname=홍길동 x', '[닉네임]'],
    ['android_id=0123456789abcdef', '[값]'],
    ['ssid=MyHome-5G', '[값]'],
    ['Bearer abcdefghijklmnop12345', '[토큰]'],
    ['vin LGXC74C41P0000001 end', '[차대번호]'],
  ];
  for (const [input, mark] of cases) {
    const out = scrubLine(input);
    assert.ok(out.includes(mark), input + ' -> ' + out);
  }
  assert.ok(!scrubLine('nickname=홍길동 x').includes('홍길동'));
  assert.ok(!scrubLine('android_id=0123456789abcdef').includes('0123456789abcdef'));
});

test('정상 로그 줄은 그대로 둔다', () => {
  for (const s of ['player ready v7.68 ok', '재생 시작 videoId=dQw4w9WgXcQ 12.5s', 'search q=아이유 results=20', 'buffer 1500ms underrun=0']) {
    assert.strictEqual(scrubLine(s), s);
  }
});
