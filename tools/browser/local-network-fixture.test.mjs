import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {startFixture} from './local-network-fixture.mjs';

test('two real origins retain independent side-effect counts and exact request bodies', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'skillforge-owned-network-'));
  const fixture = await startFixture({directory, ports: [0, 0]});
  try {
    const [a, b] = fixture.manifest.origins;
    assert.notEqual(a, b);
    const response = await fetch(a + '/api/echo?case=modified', {method: 'POST', headers: {'X-Fixture-Value': 'changed'}, body: 'exact-owned-body'});
    assert.equal(response.status, 200);
    const [factsA, factsB] = await Promise.all([a, b].map(async origin => (await fetch(origin + '/__facts')).json()));
    assert.equal(factsA.events.length, 1);
    assert.equal(factsB.events.length, 0);
    assert.equal(factsA.events[0].body, 'exact-owned-body');
    assert.equal(factsA.events[0].testHeader, 'changed');
    assert.equal(factsA.run, factsB.run);
    assert.equal((await fetch(a + '/other')).status, 404);
    assert.equal((await fetch(a + '/api/echo?case=modified', {method: 'PUT'})).status, 404);
    const lines = (await fs.readFile(path.join(directory, 'server-requests.jsonl'), 'utf8')).trim().split('\n');
    assert.equal(lines.length, 1);
  } finally {await fixture.close(); await fs.rm(directory, {recursive: true, force: true});}
});
