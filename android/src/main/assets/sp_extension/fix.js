// Stronghold Protocol WebExtension content script
// 1. Disables DOM Fullscreen API in native Android app to prevent viewport desync.
// 2. Proactively detects Cloudflare Error 1027 (Rate Limit), 502/503/52x, and triggers auto-failover.
(function() {
  // 1. Disable DOM Fullscreen API (v1.0.9)
  function disableFullscreen() {
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
  }

  disableFullscreen();

  try {
    const script = document.createElement('script');
    script.textContent = '(' + disableFullscreen.toString() + ')();';
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  } catch (e) {}

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
