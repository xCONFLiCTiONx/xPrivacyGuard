(function () {
  'use strict';

  // GPC injection — separate function so it can be conditionally called
  function injectGpcSignal() {
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
    } catch (e) {}
  }

  // DNT injection — separate function so it can be conditionally called
  function injectDntSignal() {
    try {
      if (!('doNotTrack' in Navigator.prototype)) {
        Object.defineProperty(Navigator.prototype, 'doNotTrack', {
          get: function () { return '1'; },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}
  }

  // Fingerprint protections (canvas, WebGL, audio, hardware spoofing)
  function injectFingerprintShield() {
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

    // 1. Canvas Fingerprint Protection (Micro-Noise Randomization)
    try {
      const getRandomNoise = () => Math.floor(Math.random() * 256);

      const applyCanvasNoise = (canvas) => {
        try {
          if (!canvas || canvas.width <= 0 || canvas.height <= 0) return;
          const ctx = canvas.getContext("2d");
          if (!ctx) return;

          const x = Math.min(10, canvas.width - 1);
          const y = Math.min(10, canvas.height - 1);
          const imgData = ctx.getImageData(x, y, 1, 1);
          if (imgData && imgData.data && imgData.data.length >= 4) {
            imgData.data[0] = getRandomNoise();
            imgData.data[3] = 255;
            ctx.putImageData(imgData, x, y);
          }
        } catch (e) {}
      };

      const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
      CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
        const imageData = originalGetImageData.apply(this, arguments);
        try {
          if (imageData && imageData.data && imageData.data.length >= 4) {
            imageData.data[0] = getRandomNoise();
            imageData.data[3] = 255;
          }
        } catch (e) {}
        return imageData;
      };

      const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
        applyCanvasNoise(this);
        return originalToDataURL.apply(this, arguments);
      };

      const originalToBlob = HTMLCanvasElement.prototype.toBlob;
      HTMLCanvasElement.prototype.toBlob = function(callback, ...args) {
        applyCanvasNoise(this);
        return originalToBlob.apply(this, [callback, ...args]);
      };
    } catch (e) {}

    // 2. WebGL Telemetry & Vendor Masking
    try {
      const spoofWebGL = (contextProto) => {
        if (!contextProto || !contextProto.prototype) return;
        const originalGetParameter = contextProto.prototype.getParameter;
        contextProto.prototype.getParameter = function(parameter) {
          if (
            parameter === 37445 ||
            parameter === 37446 ||
            parameter === 0x9245 ||
            parameter === 0x9246 ||
            parameter === 0x3745 ||
            parameter === 0x3746
          ) {
            return 'PROTECTED';
          }
          if (parameter === 0x1F00) return 'Google Inc.';
          if (parameter === 0x1F01) return 'ANGLE';
          return originalGetParameter.apply(this, arguments);
        };

        const oldGetExtension = contextProto.prototype.getExtension;
        contextProto.prototype.getExtension = function (name) {
          if (name === "WEBGL_debug_renderer_info") {
            return {
              UNMASKED_VENDOR_WEBGL: 37445,
              UNMASKED_RENDERER_WEBGL: 37446
            };
          }
          return oldGetExtension.call(this, name);
        };
      };
      spoofWebGL(window.WebGLRenderingContext);
      spoofWebGL(window.WebGL2RenderingContext);
    } catch (e) {}

    // 3. Audio Context Protection
    try {
      Object.defineProperty(window, "OfflineAudioContext", {
        configurable: true,
        get() {
          return undefined;
        },
      });

      Object.defineProperty(window, "webkitOfflineAudioContext", {
        configurable: true,
        get() {
          return undefined;
        },
      });
    } catch (e) {}
  }

  // Read settings and conditionally inject protections.
  // content.js runs in MAIN world so chrome.storage is not available here —
  // but the inline‑script injection below re‑enters MAIN world via an
  // injected <script> tag. We need the *content‑script* portion (which
  // DOES have chrome.storage access in Firefox‑based mobile browsers and
  // some Chromium forks) to gate the MAIN‑world injection.
  //
  // On mobile Chromium (Kiwi, etc.) the manifest declares this script in
  // MAIN world, so chrome.storage may be undefined. To handle both cases
  // we attempt chrome.storage first; if unavailable we fall back to
  // enabling everything (same as the old behaviour).

  function applyProtections(settings) {
    if (settings.gpcEnabled) {
      injectGpcSignal();
    }
    if (settings.dntEnabled) {
      injectDntSignal();
    }
    if (settings.fingerprintShieldEnabled) {
      injectFingerprintShield();
    }
  }

  // Build the MAIN‑world injection string with baked‑in settings
  function injectIntoPage(settings) {
    try {
      const script = document.createElement('script');
      script.textContent =
        '(' + function(s) {
          // Re‑declare the helpers inside the page context
          function _injectGpc() {
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
            } catch (e) {}
          }
          function _injectDnt() {
            try {
              if (!('doNotTrack' in Navigator.prototype)) {
                Object.defineProperty(Navigator.prototype, 'doNotTrack', {
                  get: function () { return '1'; },
                  configurable: true,
                  enumerable: true
                });
              }
            } catch (e) {}
          }
          if (s.gpcEnabled) _injectGpc();
          if (s.dntEnabled) _injectDnt();
        }.toString() + ')(' + JSON.stringify(settings) + ');';
      (document.head || document.documentElement).appendChild(script);
      script.remove();
    } catch (e) {}
  }

  function run(settings) {
    applyProtections(settings);
    injectIntoPage(settings);
  }

  // Try to read settings; fall back to all‑enabled if chrome.storage is
  // unavailable (MAIN world on most Chromium).
  try {
    if (typeof chrome !== 'undefined' && chrome.storage && chrome.storage.local) {
      chrome.storage.local.get({
        gpcEnabled: true,
        dntEnabled: true,
        fingerprintShieldEnabled: true
      }, function(items) {
        run(items);
      });
    } else {
      // No storage access — enable everything (safe default)
      run({ gpcEnabled: true, dntEnabled: true, fingerprintShieldEnabled: true });
    }
  } catch (e) {
    run({ gpcEnabled: true, dntEnabled: true, fingerprintShieldEnabled: true });
  }

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

