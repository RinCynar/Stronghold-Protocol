// test/queue.test.js — Matchmaking queue unit and integration tests (RinCynar extension).

import { describe, test, before, after, afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { startServer } from '../server/index.js';
import { StubMatch } from '../server/match/StubMatch.js';
import { TestClient } from './helpers/wsClient.js';
import { ERR } from '../shared/constants.js';
import { validateC2S } from '../shared/protocol.js';
import { Queue } from '../server/queue.js';

function clientPool(getUrl) {
  const open = new Set();
  return {
    async connect() { const c = await TestClient.connect(getUrl()); open.add(c); return c; },
    async player(name, token) {
      const c = await this.connect();
      const w = await c.hello(name, token);
      c.id = w.playerId;
      c.token = w.token;
      return c;
    },
    async closeAll() {
      await Promise.all([...open].map((c) => c.terminate().catch(() => {})));
      open.clear();
    },
  };
}

const quietLog = () => {
  const errors = [];
  return { errors, log: { info() {}, warn() {}, debug() {}, error: (...a) => errors.push(a.map(String).join(' ')) } };
};

const ok = async (c, msg) => { const r = await c.request(msg); assert.equal(r.t, 'ok', `${msg.t}: ${JSON.stringify(r)}`); return r; };
const err = async (c, msg, code) => { const r = await c.request(msg); assert.equal(r.t, 'error', JSON.stringify(r)); assert.equal(r.code, code, JSON.stringify(r)); return r; };

describe('matchmaking queue (RinCynar extension)', () => {
  let srv;
  let pool;
  const cap = quietLog();

  before(async () => {
    srv = await startServer({ port: 0, host: '127.0.0.1', log: cap.log, MatchClass: StubMatch, lobbyGraceMs: 60_000 });
    pool = clientPool(() => `ws://127.0.0.1:${srv.port}/ws`);
  });

  afterEach(async () => {
    srv.lobby?.queue?.leave?.();
    if (srv.lobby?.queue) srv.lobby.queue.entries = [];
    await pool.closeAll();
  });

  after(async () => {
    await srv?.close();
    assert.deepEqual(cap.errors, [], 'no server errors logged');
  });

  test('protocol: queue.join and queue.leave validation', () => {
    assert.equal(validateC2S({ t: 'queue.join' }), null);
    assert.equal(validateC2S({ t: 'queue.leave' }), null);
  });

  test('join and leave queue with queue.update broadcasts', async () => {
    const p1 = await pool.player('Player1');
    const p2 = await pool.player('Player2');

    // p1 joins
    await ok(p1, { t: 'queue.join' });
    const u1 = await p1.waitFor('queue.update');
    assert.equal(u1.position, 1);
    assert.equal(u1.queued, 1);

    // p2 joins
    await ok(p2, { t: 'queue.join' });
    const u2 = await p2.waitFor('queue.update');
    assert.equal(u2.position, 2);
    assert.equal(u2.queued, 2);

    // p1 leaves
    await ok(p1, { t: 'queue.leave' });
    const u2After = await p2.waitFor('queue.update');
    assert.equal(u2After.position, 1);
    assert.equal(u2After.queued, 1);

    // clean up p2
    await ok(p2, { t: 'queue.leave' });
  });

  test('duplicate join prevention and already in room check', async () => {
    const p1 = await pool.player('Player1');

    await ok(p1, { t: 'queue.join' });
    await err(p1, { t: 'queue.join' }, ERR.ALREADY);

    await ok(p1, { t: 'queue.leave' });

    // Join room first
    await ok(p1, { t: 'room.create', mode: 'coop', difficulty: 'NORMAL' });
    await err(p1, { t: 'queue.join' }, ERR.ALREADY);
  });

  test('4 players join: triggers match immediately at ABYSS difficulty', async () => {
    const p1 = await pool.player('P1');
    const p2 = await pool.player('P2');
    const p3 = await pool.player('P3');
    const p4 = await pool.player('P4');

    await ok(p1, { t: 'queue.join' });
    await ok(p2, { t: 'queue.join' });
    await ok(p3, { t: 'queue.join' });
    await ok(p4, { t: 'queue.join' });

    const m1 = await p1.waitFor('queue.matched');
    const m2 = await p2.waitFor('queue.matched');
    const m3 = await p3.waitFor('queue.matched');
    const m4 = await p4.waitFor('queue.matched');

    assert.equal(m1.code, m2.code);
    assert.equal(m2.code, m3.code);
    assert.equal(m3.code, m4.code);

    const room = srv.lobby.getRoom(m1.code);
    assert.ok(room);
    assert.equal(room.difficulty, 'ABYSS');
    assert.equal(room.mode, 'coop');
    assert.equal(room.hostId, p1.id);
    assert.equal(room.activeHumans().length, 4);
  });

  test('timeout fills with AI to 4 players', async () => {
    let now = 100000;
    const mockLobby = {
      rooms: new Map(),
      roomOf: () => null,
      createQueuedRoom: (sessions, diff) => {
        return { code: 'TEST', sessions, diff };
      },
    };
    const queue = new Queue({
      lobby: mockLobby,
      log: cap.log,
      timeoutMs: 10_000,
      now: () => now,
    });

    const sent = [];
    const fakeSession = {
      playerId: 'p1',
      name: 'Player1',
      connected: true,
      ws: {
        readyState: 1,
        bufferedAmount: 0,
        send: (data) => sent.push(JSON.parse(data)),
      },
    };

    queue.join(fakeSession);
    assert.equal(queue.size, 1);

    // Tick before timeout: no match
    now += 5_000;
    queue.tick();
    assert.equal(queue.size, 1);

    // Tick after timeout: matched with AI
    now += 6_000;
    queue.tick();
    assert.equal(queue.size, 0);
    assert.ok(sent.some((m) => m.t === 'queue.matched' && m.code === 'TEST'));

    queue.dispose();
  });

  test('disconnect auto-removes from queue', async () => {
    const p1 = await pool.player('P1');
    await ok(p1, { t: 'queue.join' });
    assert.equal(srv.lobby.queue.size, 1);

    await p1.terminate();
    // Allow disconnect handler to run
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(srv.lobby.queue.size, 0);
  });
});
