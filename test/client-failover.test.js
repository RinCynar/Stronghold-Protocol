// test/client-failover.test.js — unit tests for the opt-in multi-node WS failover (public/js/net.js).
//
// The launch portal (cloudflare/worker.js) injects sibling WS endpoints into the page as the
// `#spfb=` fragment; main.js hands them to net.setFallbackUrls(). These tests drive the Net class
// with a fake WebSocket + fake timers (same harness style as client-static.test.js) and assert:
//   * parseFallbackUrls filters/dedupes/caps the injected list,
//   * without fallbacks the client only ever tries the page origin (vanilla behaviour unchanged),
//   * with fallbacks, after FALLBACK_AFTER_ATTEMPTS consecutive origin failures the attempts cycle
//     through origin → fallbacks (the origin is re-checked once per cycle),
//   * an endpoint that produced a `welcome` is preferred again on later reconnects (homeUrl),
//   * a session survives failing over: hello + welcome complete on a fallback endpoint.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { Net, parseFallbackUrls, FALLBACK_AFTER_ATTEMPTS } from '../public/js/net.js';

// ---- harness -----------------------------------------------------------------------------------------------

const ORIGIN = 'ws://localhost:3000/ws'; // defaultWsUrl() without a browser location

function fakeTimers(start = 1_000_000) {
  let now = start;
  let seq = 0;
  const q = new Map();
  const add = (fn, ms, every) => { const id = ++seq; q.set(id, { at: now + Math.max(0, ms | 0), fn, every }); return id; };
  return {
    now: () => now,
    setTimeout: (fn, ms) => add(fn, ms, 0),
    clearTimeout: (id) => q.delete(id),
    setInterval: (fn, ms) => add(fn, ms, Math.max(1, ms | 0)),
    clearInterval: (id) => q.delete(id),
    advance(ms) {
      const end = now + ms;
      for (;;) {
        let next = null;
        for (const [id, t] of q) if (t.at <= end && (!next || t.at < next[1].at)) next = [id, t];
        if (!next) break;
        const [id, t] = next;
        now = t.at;
        if (t.every) t.at += t.every; else q.delete(id);
        t.fn();
      }
      now = end;
    },
  };
}

function makeFakeWS() {
  const sockets = [];
  class FakeWS {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.sent = [];
      sockets.push(this);
    }
    send(data) {
      if (this.readyState !== 1) throw new Error('not open');
      this.sent.push(JSON.parse(data));
    }
    close() { this.readyState = 3; }
    open() { this.readyState = 1; this.onopen?.(); }
    recv(obj) { this.onmessage?.({ data: JSON.stringify(obj) }); }
    drop(code = 1006) { this.readyState = 3; this.onclose?.({ code }); }
    last(t) { return [...this.sent].reverse().find((m) => m.t === t); }
  }
  return { FakeWS, sockets };
}

function makeNet(extra = {}) {
  const timers = fakeTimers();
  const { FakeWS, sockets } = makeFakeWS();
  const net = new Net({ WebSocket: FakeWS, timers, now: timers.now, random: () => 0.5, getToken: () => 'tok-1', ...extra });
  return { net, timers, sockets, ws: () => sockets[sockets.length - 1] };
}

// ---- tests -------------------------------------------------------------------------------------------------

describe('parseFallbackUrls', () => {
  test('keeps ws(s):// urls, dedupes, trims and caps the list', () => {
    assert.deepEqual(
      parseFallbackUrls(' wss://a.example/ws , ws://b.example/ws , wss://a.example/ws , not-a-url , https://x.example/ws '),
      ['wss://a.example/ws', 'ws://b.example/ws'],
    );
    assert.deepEqual(parseFallbackUrls(['wss://a/ws', 42, null, '']), ['wss://a/ws']);
    assert.deepEqual(parseFallbackUrls(undefined), []);
    assert.equal(parseFallbackUrls(Array.from({ length: 20 }, (_, i) => `wss://n${i}/ws`)).length, 12);
    assert.deepEqual(parseFallbackUrls(`wss://x/${'a'.repeat(300)}`), []);
  });
});

describe('Net failover', () => {
  test('without fallbacks every attempt goes to the page origin', async () => {
    const { net, timers, sockets } = makeNet();
    net.setName('凯尔希');
    net.connect();
    for (let i = 0; i < 5; i++) {
      assert.equal(sockets[sockets.length - 1].url, ORIGIN);
      sockets[sockets.length - 1].drop(1006);
      timers.advance(30_000);
    }
  });

  test('after two origin failures the attempts cycle origin → fallbacks', async () => {
    const { net, timers, sockets } = makeNet();
    net.setFallbackUrls('wss://f1.example/ws,wss://f2.example/ws');
    net.setName('凯尔希');
    const seen = [];
    net.connect();
    seen.push(sockets[sockets.length - 1].url);
    for (let i = 0; i < 6; i++) {
      sockets[sockets.length - 1].drop(1006);
      timers.advance(30_000);
      seen.push(sockets[sockets.length - 1].url);
    }
    assert.deepEqual(seen, [
      ORIGIN,                     // attempt 0: origin
      ORIGIN,                     // attempt 1 (< FALLBACK_AFTER_ATTEMPTS): origin again
      'wss://f1.example/ws',      // attempt 2: first fallback
      'wss://f2.example/ws',      // attempt 3: second fallback
      ORIGIN,                     // attempt 4: origin re-checked once per cycle
      'wss://f1.example/ws',
      'wss://f2.example/ws',
    ]);
  });

  test('an endpoint that welcomed us is preferred on later reconnects', async () => {
    const { net, timers, sockets } = makeNet();
    net.setFallbackUrls(['wss://f1.example/ws', 'wss://f2.example/ws']);
    net.setName('凯尔希');

    // Fail over to f1 and complete the handshake there.
    net.connect();
    for (let i = 0; i < FALLBACK_AFTER_ATTEMPTS; i++) {
      sockets[sockets.length - 1].drop(1006);
      timers.advance(30_000);
    }
    assert.equal(sockets[sockets.length - 1].url, 'wss://f1.example/ws');
    sockets[sockets.length - 1].open();
    const hello = sockets[sockets.length - 1].last('hello');
    sockets[sockets.length - 1].recv({ t: 'welcome', rid: hello.rid, playerId: 'p_1', token: 'tok-2', name: '凯尔希', serverNow: timers.now() });
    assert.equal(net.status, 'online');

    // The next disconnect retries f1 (the endpoint that worked) before anywhere else.
    sockets[sockets.length - 1].drop(1006);
    timers.advance(30_000);
    assert.equal(sockets[sockets.length - 1].url, 'wss://f1.example/ws');
  });

  test('a session survives failing over: hello + welcome complete on the fallback endpoint', async () => {
    const { net, timers, sockets } = makeNet();
    net.setFallbackUrls(['wss://f1.example/ws']);
    net.setName('凯尔希');
    net.connect();
    sockets[sockets.length - 1].drop(1006);
    timers.advance(30_000);
    sockets[sockets.length - 1].drop(1006);
    timers.advance(30_000);
    assert.equal(sockets[sockets.length - 1].url, 'wss://f1.example/ws');
    sockets[sockets.length - 1].open();
    const hello = sockets[sockets.length - 1].last('hello');
    assert.equal(hello.token, 'tok-1'); // the resume token travels to the fallback endpoint unchanged
    sockets[sockets.length - 1].recv({ t: 'welcome', rid: hello.rid, playerId: 'p_9', token: 'tok-2', name: '凯尔希', serverNow: timers.now() });
    assert.equal(net.status, 'online');
    assert.equal(net.playerId, 'p_9');
  });
});
