// public/js/ui/matchmaking.js — Matchmaking queue UI (RinCynar extension).
// Provides MatchmakingButton and modal panel showing queue position, waiting seconds, and total queued.

import { useEffect, useState, useRef } from '../../vendor/hooks.module.js';
import { html, Button, Icon, MicroLabel, Modal, Spinner } from './components.js';
import { toast, toastError } from './toasts.js';
import { net } from '../net.js';
import { store } from '../store.js';
import { t } from '../../../shared/i18n.js';

export function MatchmakingButton({ class: cls = '' }) {
  const [inQueue, setInQueue] = useState(false);
  const [busy, setBusy] = useState(false);
  const [queueState, setQueueState] = useState({ position: 1, waiting: 0, queued: 1 });

  useEffect(() => {
    const onUpdate = (msg) => {
      setInQueue(true);
      setQueueState({
        position: msg.position || 1,
        waiting: msg.waiting || 0,
        queued: msg.queued || 1,
      });
    };

    const onMatched = (msg) => {
      setInQueue(false);
      toast(t('匹配成功！正在进入同盟…'), 'success');
    };

    const onStatus = (snap) => {
      if (snap.status !== 'online') {
        setInQueue(false);
      }
    };

    net.on('queue.update', onUpdate);
    net.on('queue.matched', onMatched);
    net.on('status', onStatus);

    // If room becomes active, exit queue UI
    const unsub = store.subscribe(() => {
      if (store.get().room) {
        setInQueue(false);
      }
    });

    return () => {
      net.off?.('queue.update', onUpdate);
      net.off?.('queue.matched', onMatched);
      net.off?.('status', onStatus);
      unsub?.();
    };
  }, []);

  const startQueue = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await net.request('queue.join', {});
      setInQueue(true);
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  const cancelQueue = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await net.request('queue.leave', {});
      setInQueue(false);
    } catch (err) {
      toastError(err);
    } finally {
      setBusy(false);
    }
  };

  return html`
    <div class=${`matchmaking-entry ${cls}`}>
      <${Button}
        variant="amber"
        size="xl"
        block=${true}
        icon="users"
        loading=${busy && !inQueue}
        onClick=${startQueue}
      >
        ${t('随机匹配')}
      <//>

      <${Modal}
        open=${inQueue}
        title=${t('寻找同盟中…')}
        micro="MATCHMAKING"
        tone="amber"
        closeOnBackdrop=${false}
        actions=${html`
          <${Button} variant="secondary" size="lg" icon="close" loading=${busy} onClick=${cancelQueue}>
            ${t('取消匹配')}
          <//>
        `}
      >
        <div class="mm-modal__body">
          <div class="mm-modal__row">
            <span class="mm-modal__label">${t('当前队列位置')}</span>
            <span class="mm-modal__val">${t('第 {pos} 位', { pos: queueState.position })}</span>
          </div>
          <div class="mm-modal__row">
            <span class="mm-modal__label">${t('当前排队人数')}</span>
            <span class="mm-modal__val">${t('{count} 人', { count: queueState.queued })}</span>
          </div>
          <div class="mm-modal__row">
            <span class="mm-modal__label">${t('已等待时间')}</span>
            <span class="mm-modal__val">${t('{sec} 秒', { sec: queueState.waiting })}</span>
          </div>
          <div class="mm-modal__hint">
            <${Spinner} size="sm" />
            <span>${t('满 4 人将自动开局；超时 90 秒将由 AI 补位开局。')}</span>
          </div>
        </div>
      <//>
    </div>
  `;
}
