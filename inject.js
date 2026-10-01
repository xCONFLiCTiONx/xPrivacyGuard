(() => {
  "use strict";

  console.log("xPrivacyGuard active - Canvas, WebGL & Audio protection enabled");

  //
  // 1. Canvas Fingerprint Protection (Micro-Noise Randomization)
  //
  const noise = () => (Math.random() < 0.5 ? 1 : -1);

  const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
  HTMLCanvasElement.prototype.toDataURL = function (...args) {
    try {
      const ctx = this.getContext("2d");
      if (ctx && this.width > 0 && this.height > 0) {
        const imgData = ctx.getImageData(0, 0, Math.min(this.width, 10), Math.min(this.height, 10));
        if (imgData && imgData.data.length >= 4) {
          imgData.data[0] = (imgData.data[0] + noise() + 256) % 256;
          ctx.putImageData(imgData, 0, 0);
        }
      }
    } catch (e) {}
    return originalToDataURL.apply(this, args);
  };

  const originalToBlob = HTMLCanvasElement.prototype.toBlob;
  HTMLCanvasElement.prototype.toBlob = function (callback, ...args) {
    try {
      const ctx = this.getContext("2d");
      if (ctx && this.width > 0 && this.height > 0) {
        const imgData = ctx.getImageData(0, 0, Math.min(this.width, 10), Math.min(this.height, 10));
        if (imgData && imgData.data.length >= 4) {
          imgData.data[0] = (imgData.data[0] + noise() + 256) % 256;
          ctx.putImageData(imgData, 0, 0);
        }
      }
    } catch (e) {}
    return originalToBlob.apply(this, [callback, ...args]);
  };

  const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
  CanvasRenderingContext2D.prototype.getImageData = function (x, y, w, h, ...args) {
    const imageData = originalGetImageData.call(this, x, y, w, h, ...args);
    try {
      if (imageData && imageData.data && imageData.data.length >= 4) {
        for (let i = 0; i < Math.min(imageData.data.length, 16); i += 4) {
          imageData.data[i] = (imageData.data[i] + noise() + 256) % 256;
        }
      }
    } catch (e) {}
    return imageData;
  };

  //
  // 2. WebGL Telemetry & Vendor Masking
  //
  const maskWebGL = (targetContext) => {
    if (!targetContext || !targetContext.prototype) return;

    const oldGetParameter = targetContext.prototype.getParameter;
    targetContext.prototype.getParameter = function (parameter) {
      // 37445 = UNMASKED_VENDOR_WEBGL, 37446 = UNMASKED_RENDERER_WEBGL
      if (parameter === 37445 || parameter === 37446) {
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

  try {
    if (window.WebGLRenderingContext) maskWebGL(WebGLRenderingContext);
    if (window.WebGL2RenderingContext) maskWebGL(WebGL2RenderingContext);
  } catch (e) {}

  //
  // 3. Audio Context Blocking / Protection
  //
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
})();
