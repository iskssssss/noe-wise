import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

// 执行生产采集函数，隔离脚本顶层的浏览器登录与模型操作。
const source = readFileSync(new URL('./check-product-sessions.mjs', import.meta.url), 'utf8');
const factory = runInNewContext(`(${source.slice(source.indexOf('function capturePublicPrivacy('),
  source.indexOf('\nfunction publicHistory('))})`, {
  textDigest: value => createHash('sha256').update(value, 'utf8').digest('hex'),
  process: { env: {} }, URL,
});

test('后续干净响应不能覆盖此前泄露，输出不包含受限正文', async () => {
  const marker = 'synthetic-private-marker';
  const collector = factory([marker], 'session');
  let listener;
  collector.observeResponses({ on: (_event, callback) => { listener = callback; } }, 'institution');
  for (const content of [marker, 'clean later response']) {
    listener({ url: () => 'http://localhost/api/v1/chat/history?session_id=session',
      request: () => ({ method: () => 'GET' }), text: async () => content });
  }
  const result = await collector.report('test:privacy');
  assert.equal(result.public_snapshots.length, 2);
  assert.equal(result.exposure_detected, true);
  assert.equal(JSON.stringify(result).includes(marker), false);
});
