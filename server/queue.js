// server/queue.js — Matchmaking queue for co-op rooms (RinCynar extension).
// FIFO queue of 4 players; if queue wait times out (QUEUE_TIMEOUT_MS), fills with AI teammates to 4.
// Triggered matches create a room in ABYSS difficulty with host = first player in queue.

import { sendSession } from './net.js';
import { ERR } from '../shared/constants.js';

export const QUEUE_TIMEOUT_MS = 90_000;
export const MATCH_SIZE = 4;
export const MATCH_DIFFICULTY = 'ABYSS';

const OK = Object.freeze({ ok: true });
const fail = (code, detail) => ({ error: code, ...(detail ? { detail } : {}) });

export class Queue {
  /**
   * @param {{
   *   lobby: import('./lobby.js').Lobby,
   *   log?: { info: Function, warn: Function, error: Function, debug?: Function },
   *   timeoutMs?: number,
   *   tickIntervalMs?: number,
   *   now?: () => number,
   * }} opts
   */
  constructor({ lobby, log = console, timeoutMs = QUEUE_TIMEOUT_MS, tickIntervalMs = 1000, now = Date.now }) {
    this.lobby = lobby;
    this.log = log;
    this.timeoutMs = timeoutMs;
    this.tickIntervalMs = tickIntervalMs;
    this.now = now;
    /** @type {Array<{ playerId: string, session: any, joinedAt: number }>} */
    this.entries = [];
    /** @type {NodeJS.Timeout|null} */
    this.timer = null;
  }

  get size() {
    return this.entries.length;
  }

  has(playerId) {
    return this.entries.some((e) => e.playerId === playerId);
  }

  /**
   * Player joins the matchmaking queue.
   * @param {any} session
   * @returns {{ ok: true } | { error: string, detail?: string }}
   */
  join(session) {
    if (!session || !session.playerId) return fail(ERR.BAD_MSG);
    const room = this.lobby.roomOf(session);
    if (room) {
      return fail(ERR.ALREADY, room.match ? 'match in progress' : 'already in a room');
    }
    if (this.has(session.playerId)) {
      return fail(ERR.ALREADY, 'already in queue');
    }

    this.entries.push({
      playerId: session.playerId,
      session,
      joinedAt: this.now(),
    });
    this.log.info?.(`[queue] ${session.name} (${session.playerId}) joined queue. Size: ${this.entries.length}`);

    if (this.entries.length >= MATCH_SIZE) {
      const batch = this.entries.splice(0, MATCH_SIZE);
      this.triggerMatch(batch);
    } else {
      this.ensureTimer();
      this.broadcastUpdate();
    }
    return OK;
  }

  /**
   * Player cancels queue or is auto-removed (disconnect / room join).
   * @param {any} session
   * @returns {{ ok: true }}
   */
  leave(session) {
    if (!session || !session.playerId) return OK;
    const idx = this.entries.findIndex((e) => e.playerId === session.playerId);
    if (idx < 0) return OK;
    this.entries.splice(idx, 1);
    this.log.info?.(`[queue] ${session.name} (${session.playerId}) left queue. Size: ${this.entries.length}`);
    if (this.entries.length === 0) {
      this.stopTimer();
    } else {
      this.broadcastUpdate();
    }
    return OK;
  }

  ensureTimer() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), this.tickIntervalMs);
    this.timer.unref?.();
  }

  stopTimer() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  tick() {
    if (this.entries.length === 0) {
      this.stopTimer();
      return;
    }
    const now = this.now();
    const oldest = this.entries[0];
    if (now - oldest.joinedAt >= this.timeoutMs) {
      this.log.info?.(`[queue] timeout (${this.timeoutMs}ms) reached for oldest player. Matching ${this.entries.length} player(s) with AI fill`);
      const batch = this.entries.splice(0, MATCH_SIZE);
      this.triggerMatch(batch);
      return;
    }
    this.broadcastUpdate();
  }

  /**
   * Creates a room for matched players and notifies them.
   * @param {Array<{ playerId: string, session: any, joinedAt: number }>} matched
   */
  triggerMatch(matched) {
    const sessions = matched
      .map((m) => m.session)
      .filter((s) => s && s.connected && !this.lobby.roomOf(s));

    if (sessions.length === 0) {
      if (this.entries.length === 0) this.stopTimer();
      else this.broadcastUpdate();
      return null;
    }

    const room = this.lobby.createQueuedRoom(sessions, MATCH_DIFFICULTY);
    if (!room) {
      this.log.error?.('[queue] failed to create queued room');
      return null;
    }

    for (const s of sessions) {
      sendSession(s, { t: 'queue.matched', code: room.code });
    }

    if (this.entries.length === 0) {
      this.stopTimer();
    } else {
      this.broadcastUpdate();
    }
    return room;
  }

  broadcastUpdate() {
    const now = this.now();
    const count = this.entries.length;
    for (let i = 0; i < count; i++) {
      const entry = this.entries[i];
      const waiting = Math.max(0, Math.round((now - entry.joinedAt) / 1000));
      sendSession(entry.session, {
        t: 'queue.update',
        position: i + 1,
        waiting,
        queued: count,
      });
    }
  }

  dispose() {
    this.stopTimer();
    this.entries.length = 0;
  }
}
