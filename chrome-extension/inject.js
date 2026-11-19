(() => {
  "use strict";

  console.log("xPrivacyGuard Desktop active - Canvas, WebGL & Audio protection enabled");

  const defineReadOnlyValue = (target, name, value) => {
    try {
      if (!target) return;
      const descriptor = Object.getOwnPropertyDescriptor(target, name);
      if (descriptor && descriptor.configurable === false && !descriptor.get) {
        return;
      }
      Object.defineProperty(target, name, {
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable : true,
        get: () => value
      });
    } catch (e) {}
  };

  const disableProperty = (target, name) => {
    try {
      if (!target) return;
      const descriptor = Object.getOwnPropertyDescriptor(target, name);
      if ((descriptor && !descriptor.configurable) || (!descriptor && !Object.isExtensible(target))) {
        console.warn(`xPrivacyGuard: Could not disable ${name}.`);
        return;
      }
      Object.defineProperty(target, name, {
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable : true,
        get: () => undefined
      });
    } catch (err) {
      console.warn(`xPrivacyGuard: Could not disable ${name}.`, err);
    }
  };

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

  defineReadOnlyValue(Navigator.prototype, "hardwareConcurrency", 4);
  defineReadOnlyValue(Navigator.prototype, "deviceMemory", 8);
  try {
    if (navigator) {
      defineReadOnlyValue(navigator, "hardwareConcurrency", 4);
      defineReadOnlyValue(navigator, "deviceMemory", 8);
    }
  } catch (e) {}

  // Default 2D contexts to willReadFrequently so our noise readbacks don't
  // trigger Chrome's "Multiple readback operations" performance warning.
  const patchGetContext = (proto) => {
    if (!proto || !proto.getContext) return;
    const originalGetContext = proto.getContext;
    proto.getContext = function (type, options, ...rest) {
      if (type === "2d") {
        const opts = options && typeof options === "object" ? options : {};
        if (!("willReadFrequently" in opts)) {
          options = { ...opts, willReadFrequently: true };
        }
      }
      return originalGetContext.call(this, type, options, ...rest);
    };
  };
  patchGetContext(HTMLCanvasElement.prototype);
  if (typeof OffscreenCanvas !== "undefined") patchGetContext(OffscreenCanvas.prototype);

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

  try {
    if (window.WebGLRenderingContext) maskWebGL(WebGLRenderingContext);
    if (window.WebGL2RenderingContext) maskWebGL(WebGL2RenderingContext);
  } catch (e) {}

  [
    [Document.prototype, "browsingTopics"],
    [Document.prototype, "hasPrivateToken"],
    [Document.prototype, "hasRedemptionRecord"],
    [Navigator.prototype, "joinAdInterestGroup"],
    [Navigator.prototype, "leaveAdInterestGroup"],
    [Navigator.prototype, "runAdAuction"]
  ].forEach(([target, name]) => disableProperty(target, name));

  [
    Element.prototype,
    HTMLElement.prototype,
    HTMLAreaElement.prototype,
    HTMLAnchorElement.prototype,
    HTMLImageElement.prototype,
    HTMLIFrameElement.prototype,
    HTMLScriptElement.prototype
  ].forEach((target) => disableProperty(target, "attributionSrc"));
  disableProperty(Navigator.prototype, "getBattery");
  disableProperty(window, "queryLocalFonts");

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

  const protectionLayersKey = Symbol.for("xPrivacyGuard.protectionLayers");
  const protectionLayers = window[protectionLayersKey] || {};
  protectionLayers.daily = true;
  Object.defineProperty(window, protectionLayersKey, {
    configurable: true,
    value: protectionLayers
  });
})();
