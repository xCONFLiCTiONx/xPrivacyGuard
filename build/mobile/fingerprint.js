// Common Fingerprint Protection Modules
export function applyFingerprintShields() {
  try {
    // 1. Global Privacy Control & DNT
    if (!('globalPrivacyControl' in Navigator.prototype)) {
      Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', {
        get: () => true,
        configurable: true,
        enumerable: true
      });
    }
    if (!('doNotTrack' in Navigator.prototype)) {
      Object.defineProperty(Navigator.prototype, 'doNotTrack', {
        get: () => '1',
        configurable: true,
        enumerable: true
      });
    }

    // 2. Canvas Noise Injection
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

    const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
    HTMLCanvasElement.prototype.toDataURL = function (...args) {
      applyCanvasNoise(this);
      return originalToDataURL.apply(this, args);
    };

    const originalToBlob = HTMLCanvasElement.prototype.toBlob;
    HTMLCanvasElement.prototype.toBlob = function (callback, ...args) {
      applyCanvasNoise(this);
      return originalToBlob.apply(this, [callback, ...args]);
    };

    const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
    CanvasRenderingContext2D.prototype.getImageData = function (x, y, w, h, ...args) {
      const imageData = originalGetImageData.call(this, x, y, w, h, ...args);
      try {
        if (imageData && imageData.data && imageData.data.length >= 4) {
          imageData.data[0] = getRandomNoise();
          imageData.data[3] = 255;
        }
      } catch (e) {}
      return imageData;
    };

    // 3. WebGL Telemetry & Vendor Masking
    const maskWebGL = (targetContext) => {
      if (!targetContext || !targetContext.prototype) return;
      const oldGetParameter = targetContext.prototype.getParameter;
      targetContext.prototype.getParameter = function (parameter) {
        if (
          parameter === 37445 ||
          parameter === 37446 ||
          parameter === 0x9245 ||
          parameter === 0x9246 ||
          parameter === 0x3745 ||
          parameter === 0x3746
        ) {
          return "PROTECTED";
        }
        return oldGetParameter.call(this, parameter);
      };
      const oldGetExtension = targetContext.prototype.getExtension;
      targetContext.prototype.getExtension = function (name) {
        if (name === "WEBGL_debug_renderer_info") {
          return {
            UNMASKED_VENDOR_WEBGL: 37445,
            UNMASKED_RENDERER_WEBGL: 37446
          };
        }
        return oldGetExtension.call(this, name);
      };
    };

    if (window.WebGLRenderingContext) maskWebGL(WebGLRenderingContext);
    if (window.WebGL2RenderingContext) maskWebGL(WebGL2RenderingContext);

    // 4. AudioContext Protection
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

  } catch (e) {}
}
