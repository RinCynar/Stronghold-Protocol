// public/js/ui/onlineCounter.js — Online player counter (RinCynar extension).
// Polls GET /healthz every 15s to display the active session count on the node.

import { useEffect, useState } from '../../vendor/hooks.module.js';
import { html, Icon } from './components.js';
import { t } from '../../../shared/i18n.js';

export function OnlineCounter({ class: cls = '' }) {
  const [sessions, setSessions] = useState(null);

  useEffect(() => {
    let unmounted = false;
    const update = async () => {
      try {
        const res = await fetch('/healthz', { cache: 'no-store' });
        if (!res.ok) return;
        const data = await res.json();
        if (!unmounted && Number.isFinite(data?.sessions)) {
          setSessions(data.sessions);
        }
      } catch {
        // network / offline error, preserve existing or keep null
      }
    };

    update();
    const timer = setInterval(update, 15_000);
    return () => {
      unmounted = true;
      clearInterval(timer);
    };
  }, []);

  if (sessions == null) return null;

  return html`<span class=${`ping online-counter ${cls}`} title=${t('当前节点在线人数')}>
    <${Icon} name="users" class="ping__icon" />
    <span class="online-counter__text">${t('在线 {n} 人', { n: sessions })}</span>
  </span>`;
}
