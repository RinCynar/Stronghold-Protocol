// test/lobby-transferHost.test.js — room.transferHost (RinCynar extension):
// Before the match the host can actively transfer ownership to another connected human player in the room.

import { describe, test, before, after, afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { startServer } from '../server/index.js';
import { StubMatch } from '../server/match/StubMatch.js';
import { TestClient } from './helpers/wsClient.js';
import { ERR } from '../shared/constants.js';
import { validateC2S } from '../shared/protocol.js';

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
const seatOf = (state, id) => state.seats.find((s) => s && s.playerId === id) || null;

async function createRoom(c) {
  await ok(c, { t: 'room.create', mode: 'coop', difficulty: 'NORMAL' });
  return c.waitFor('room.state', (s) => s.hostId === c.id);
}

async function joinRoom(c, code) {
  await ok(c, { t: 'room.join', code });
  return c.waitFor('room.state', (s) => s.code === code && !!seatOf(s, c.id));
}

describe('room.transferHost (lobby)', () => {
  let srv;
  let pool;
  const cap = quietLog();

  before(async () => {
    srv = await startServer({ port: 0, host: '127.0.0.1', log: cap.log, MatchClass: StubMatch, lobbyGraceMs: 60_000 });
    pool = clientPool(() => `ws://127.0.0.1:${srv.port}/ws`);
  });

  afterEach(async () => { await pool.closeAll(); });
  after(async () => {
    await srv?.close();
    assert.deepEqual(cap.errors, [], 'no server errors logged');
  });

  test('protocol: room.transferHost { playerId } validation', () => {
    assert.equal(validateC2S({ t: 'room.transferHost', playerId: 'p_0123456789' }), null);
    assert.notEqual(validateC2S({ t: 'room.transferHost' }), null, 'playerId is required');
    assert.notEqual(validateC2S({ t: 'room.transferHost', playerId: '' }), null);
    assert.notEqual(validateC2S({ t: 'room.transferHost', playerId: 123 }), null);
  });

  test('the host transfers host to another connected player: hostId updates and UI broadcasts', async () => {
    const host = await pool.player('Host');
    const st = await createRoom(host);
    const guest = await pool.player('Guest');
    await joinRoom(guest, st.code);

    await ok(host, { t: 'room.transferHost', playerId: guest.id });
    const after = await host.waitFor('room.state', (s) => s.hostId === guest.id);
    assert.equal(after.hostId, guest.id);

    // Guest is now host and can set difficulty
    await ok(guest, { t: 'room.setDifficulty', difficulty: 'HARD' });
    const hardState = await host.waitFor('room.state', (s) => s.difficulty === 'HARD');
    assert.equal(hardState.difficulty, 'HARD');

    // Former host is no longer host
    await err(host, { t: 'room.setDifficulty', difficulty: 'ABYSS' }, ERR.NOT_HOST);
  });

  test('refusals: not host, yourself, AI, disconnected player, during match, not in room', async () => {
    const host = await pool.player('Host');
    const st = await createRoom(host);
    const guest = await pool.player('Guest');
    await joinRoom(guest, st.code);

    // Not the host
    await err(guest, { t: 'room.transferHost', playerId: host.id }, ERR.NOT_HOST);

    // Transfer to yourself
    await err(host, { t: 'room.transferHost', playerId: host.id }, ERR.BAD_TARGET);

    // Transfer to an AI
    await ok(host, { t: 'room.addBot' });
    const withBot = await host.waitFor('room.state', (s) => s.seats.some((x) => x && x.isBot));
    const botSeat = withBot.seats.find((x) => x && x.isBot);
    await err(host, { t: 'room.transferHost', playerId: botSeat.playerId }, ERR.BAD_TARGET);

    // Transfer to disconnected player
    await guest.terminate();
    await host.waitFor('room.state', (s) => seatOf(s, guest.id)?.connected === false);
    await err(host, { t: 'room.transferHost', playerId: guest.id }, ERR.BAD_TARGET);

    // Transfer to non-existent player
    await err(host, { t: 'room.transferHost', playerId: 'p_nonexistent' }, ERR.BAD_TARGET);

    // Not in room
    const outsider = await pool.player('Outsider');
    await err(outsider, { t: 'room.transferHost', playerId: host.id }, ERR.NOT_IN_ROOM);
  });
});
