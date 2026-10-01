(function () {
  'use strict';

  function injectPrivacySignals() {
    try {
      if (!('globalPrivacyControl' in Navigator.prototype)) {
        Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', {
          get: function () { return true; },
          configurable: true,
          enumerable: true
        });
      } else {
        Object.defineProperty(navigator, 'globalPrivacyControl', {
          value: true,
          writable: false,
          configurable: true,
          enumerable: true
        });
      }

      if (!('doNotTrack' in Navigator.prototype)) {
        Object.defineProperty(Navigator.prototype, 'doNotTrack', {
          get: function () { return '1'; },
          configurable: true,
          enumerable: true
        });
      }

      try {
        Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
          get: () => 8,
          configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'deviceMemory', {
          get: () => 8,
          configurable: true
        });
        Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
          get: () => 5,
          configurable: true
        });
      } catch (e) {}

      try {
        const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
        CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
          const imageData = originalGetImageData.apply(this, arguments);
          try {
            const data = imageData.data;
            for (let i = 0; i < data.length; i += 10) {
              data[i] = data[i] ^ 1;
            }
          } catch (e) {}
          return imageData;
        };

        const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
        HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
          try {
            const ctx = this.getContext('2d');
            if (ctx && this.width > 0 && this.height > 0) {
              const imgData = ctx.getImageData(0, 0, 1, 1);
              imgData.data[0] = imgData.data[0] ^ 1;
              ctx.putImageData(imgData, 0, 0);
            }
          } catch (e) {}
          return originalToDataURL.apply(this, arguments);
        };
      } catch (e) {}

      try {
        const spoofWebGL = (contextProto) => {
          if (!contextProto) return;
          const originalGetParameter = contextProto.prototype.getParameter;
          contextProto.prototype.getParameter = function(parameter) {
            if (parameter === 0x3745) return 'Google Inc.';
            if (parameter === 0x3746) return 'ANGLE (Google, OpenGL ES 3.2)';
            if (parameter === 0x1F00) return 'Google Inc.';
            if (parameter === 0x1F01) return 'ANGLE';
            return originalGetParameter.apply(this, arguments);
          };
        };
        spoofWebGL(window.WebGLRenderingContext);
        spoofWebGL(window.WebGL2RenderingContext);
      } catch (e) {}
    } catch (e) {}
  }

  injectPrivacySignals();

  try {
    const script = document.createElement('script');
    script.textContent = `(${injectPrivacySignals.toString()})();`;
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  } catch (e) {}

  function cleanCurrentUrl() {
    try {
      const trackingParams = [
        'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content', 'utm_id',
        'fbclid', 'gclid', 'gbraid', 'wbraid', 'msclkid', 'twclid', 'igshid', 'yclid',
        'mc_eid', '_hsenc', '_hsmi', '_openstat', 'dclid'
      ];

      const url = new URL(window.location.href);
      let removedCount = 0;

      trackingParams.forEach(param => {
        if (url.searchParams.has(param)) {
          url.searchParams.delete(param);
          removedCount++;
        }
      });

      if (removedCount > 0) {
        window.history.replaceState(window.history.state, document.title, url.href);
        if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.sendMessage) {
          chrome.runtime.sendMessage({ action: 'incrementBlockedCount', count: removedCount });
        }
      }
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', cleanCurrentUrl);
  } else {
    cleanCurrentUrl();
  }
})();
