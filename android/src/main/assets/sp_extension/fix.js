// Stronghold Protocol WebExtension content script
// 1. Disables DOM Fullscreen API in native Android app to prevent viewport desync.
// 2. Proactively detects Cloudflare Error 1027 (Rate Limit), 502/503/52x, and triggers auto-failover.
(function() {
  // Main page execution hook
  function injectIntoPage() {
    // 1. Disable DOM Fullscreen API (v1.0.9)
    try {
      if (typeof document !== 'undefined') {
        Object.defineProperty(document, 'fullscreenEnabled', {
          get: function() { return false; },
          configurable: true
        });
        Object.defineProperty(document, 'webkitFullscreenEnabled', {
          get: function() { return false; },
          configurable: true
        });
      }
      if (typeof Element !== 'undefined' && Element.prototype) {
        if (Element.prototype.requestFullscreen) {
          Element.prototype.requestFullscreen = function() {
            return Promise.reject(new Error("DOM fullscreen disabled in native Android app"));
          };
        }
        if (Element.prototype.webkitRequestFullscreen) {
          Element.prototype.webkitRequestFullscreen = function() {
            return Promise.reject(new Error("DOM fullscreen disabled in native Android app"));
          };
        }
      }
    } catch (e) {}

    // 2. UI Latency Optimization (v1.0.11): ceil(ping / 10), timeout if > 1500ms
    try {
      function transformPing(raw) {
        if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) return raw;
        if (raw > 1500) return 9999;
        return Math.ceil(raw / 10);
      }

      function hookStore(store) {
        if (!store || store.__sp_ping_hooked) return;
        store.__sp_ping_hooked = true;

        const origSet = store.set;
        store.set = function(patch) {
          let p = patch;
          if (typeof patch === 'function') {
            p = patch(store.get());
          }
          if (p && typeof p === 'object' && p.connection) {
            const conn = { ...p.connection };
            if (typeof conn.ping === 'number' && Number.isFinite(conn.ping)) {
              conn.ping = transformPing(conn.ping);
            }
            p = { ...p, connection: conn };
          }
          return origSet.call(this, p);
        };

        const origPatch = store.patch;
        if (typeof origPatch === 'function') {
          store.patch = function(key, value) {
            if (key === 'connection') {
              let v = typeof value === 'function' ? value(store.get().connection) : value;
              if (v && typeof v === 'object' && typeof v.ping === 'number' && Number.isFinite(v.ping)) {
                v = { ...v, ping: transformPing(v.ping) };
              }
              return origPatch.call(this, key, v);
            }
            return origPatch.apply(this, arguments);
          };
        }
      }

      let _sp = window.__SP__;
      if (_sp && _sp.store) {
        hookStore(_sp.store);
      }
      Object.defineProperty(window, '__SP__', {
        get: function() { return _sp; },
        set: function(val) {
          _sp = val;
          if (val && val.store) {
            hookStore(val.store);
          }
        },
        configurable: true,
        enumerable: true
      });
    } catch (e) {}
  }

  // Run in content script context
  try {
    if (typeof Element !== 'undefined' && Element.prototype && Element.prototype.requestFullscreen) {
      Element.prototype.requestFullscreen = function() {
        return Promise.reject(new Error("DOM fullscreen disabled in native Android app"));
      };
    }
  } catch (e) {}

  // Inject into page main context
  try {
    const script = document.createElement('script');
    script.textContent = '(' + injectIntoPage.toString() + ')();';
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  } catch (e) {}

  // Periodic DOM title sanitizer for timeout display
  setInterval(function() {
    try {
      const pills = document.querySelectorAll('.ping');
      for (let i = 0; i < pills.length; i++) {
        const p = pills[i];
        if (p.title && p.title.includes('9999ms')) {
          p.title = '连接超时 (>1500ms)';
        }
      }
    } catch (e) {}
  }, 400);

  // 2. Automated Node Health & Cloudflare Error Detection (v1.0.10)
  let hasTriggeredFailover = false;

  function triggerFailover(reason) {
    if (hasTriggeredFailover) return;
    hasTriggeredFailover = true;
    console.warn('[Stronghold] Node error detected: ' + reason + ', switching to backup candidate...');
    try {
      window.location.replace('https://retry.local/next?reason=' + encodeURIComponent(reason));
    } catch (e) {
      window.location.href = 'https://retry.local/next?reason=' + encodeURIComponent(reason);
    }
  }

  function checkHealth() {
    if (hasTriggeredFailover) return;
    try {
      // 1. Check HTTP response status code via Navigation Timing API
      if (typeof performance !== 'undefined' && performance.getEntriesByType) {
        const navEntries = performance.getEntriesByType('navigation');
        if (navEntries && navEntries.length > 0) {
          const status = navEntries[0].responseStatus;
          if (status && status >= 400) {
            triggerFailover('HTTP_' + status);
            return;
          }
        }
      }

      // 2. Check Document Title
      const title = (document.title || '').toLowerCase().trim();
      if (title.length > 0) {
        if (title.includes('error 1027') || title.includes('rate limited') ||
            title.includes('plan limits') || title.includes('error code: 1027') ||
            title.includes('502 bad gateway') || title.includes('503 service') ||
            title.includes('504 gateway') || title.includes('520 web server') ||
            title.includes('521 web server') || title.includes('522 connection') ||
            title.includes('523 origin') || title.includes('524 a timeout') ||
            title.includes('525 ssl') || title.includes('526 invalid') ||
            title.includes('attention required! | cloudflare') ||
            (title.includes('cloudflare') && (title.includes('error') || title.includes('limited') || title.includes('temporarily')))) {
          triggerFailover('TITLE_' + title.slice(0, 30));
          return;
        }
      }

      // 3. Check Document Body text & Cloudflare error DOM elements
      const body = document.body;
      if (body) {
        const text = (body.innerText || body.textContent || '').toLowerCase();
        if (text.includes('error 1027') || text.includes('error code: 1027') ||
            text.includes('temporarily rate limited') || text.includes('reached their plan limits') ||
            (text.includes('cloudflare ray id') && (text.includes('error') || text.includes('limited'))) ||
            document.querySelector('.cf-error-details') ||
            document.querySelector('#cf-wrapper') ||
            document.querySelector('.cf-subheadline')) {
          triggerFailover('BODY_1027_ERROR');
          return;
        }
      }
    } catch (e) {}
  }

  // Check immediately
  checkHealth();

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', checkHealth);
  } else {
    checkHealth();
  }

  // Periodic check for dynamic error pages
  let pollCount = 0;
  const pollInterval = setInterval(function() {
    pollCount++;
    checkHealth();
    if (pollCount > 15 || hasTriggeredFailover) {
      clearInterval(pollInterval);
    }
  }, 200);

  window.addEventListener('load', checkHealth);
})();
