// Disables DOM Fullscreen API in Stronghold Protocol Android App
// The APK is already running in native immersive fullscreen landscape mode.
// Triggering DOM fullscreen desyncs the viewport and causes display abnormalities.
(function() {
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

  // Run in extension content script context
  disableFullscreen();

  // Also inject directly into the main page context to override page window
  try {
    const script = document.createElement('script');
    script.textContent = '(' + disableFullscreen.toString() + ')();';
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  } catch (e) {}
})();
