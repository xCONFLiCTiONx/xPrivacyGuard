(() => {
  "use strict";

  function disableProperty(target, name) {
    if (!target) return;

    try {
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
  }

  disableProperty(Navigator.prototype, "connection");
  disableProperty(Navigator.prototype, "getBattery");

  [
    "RTCPeerConnection",
    "webkitRTCPeerConnection",
    "Accelerometer",
    "AmbientLightSensor",
    "AbsoluteOrientationSensor",
    "GravitySensor",
    "Gyroscope",
    "LinearAccelerationSensor",
    "Magnetometer",
    "RelativeOrientationSensor"
  ].forEach((name) => disableProperty(window, name));

  disableProperty(window, "queryLocalFonts");

  const screenPrototype = Object.getPrototypeOf(window.screen);
  ["width", "height", "colorDepth", "pixelDepth"].forEach((name) => {
    disableProperty(screenPrototype, name);
  });
  disableProperty(window, "devicePixelRatio");

  const originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function (type, ...args) {
    if (type === "webgl" || type === "experimental-webgl" || type === "webgl2") {
      return null;
    }
    return originalGetContext.call(this, type, ...args);
  };

  if (typeof OffscreenCanvas !== "undefined") {
    const originalOffscreenGetContext = OffscreenCanvas.prototype.getContext;
    OffscreenCanvas.prototype.getContext = function (type, ...args) {
      if (type === "webgl" || type === "experimental-webgl" || type === "webgl2") {
        return null;
      }
      return originalOffscreenGetContext.call(this, type, ...args);
    };
  }

  disableProperty(window, "WebGLRenderingContext");
  disableProperty(window, "WebGL2RenderingContext");

  if (typeof CanvasRenderingContext2D !== "undefined") {
    disableProperty(CanvasRenderingContext2D.prototype, "measureText");
  }

  if (typeof AudioBuffer !== "undefined") {
    const perturbSamples = (samples) => {
      if (!samples || typeof samples.length !== "number") return samples;
      for (let i = 0; i < samples.length; i++) {
        samples[i] = samples[i] + (Math.random() - 0.5) * 0.0001;
      }
      return samples;
    };

    const originalGetChannelData = AudioBuffer.prototype.getChannelData;
    AudioBuffer.prototype.getChannelData = function (channel) {
      const samples = originalGetChannelData.call(this, channel);
      return perturbSamples(samples);
    };

    const originalCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
    AudioBuffer.prototype.copyFromChannel = function (destination, channel, start = 0) {
      originalCopyFromChannel.call(this, destination, channel, start);
      return perturbSamples(destination);
    };
  }

  const userAgentData = navigator.userAgentData;
  if (userAgentData) {
    const prototype = Object.getPrototypeOf(userAgentData);
    const descriptor = Object.getOwnPropertyDescriptor(prototype, "getHighEntropyValues");
    if (descriptor && descriptor.configurable && typeof descriptor.value === "function") {
      const getHighEntropyValues = descriptor.value;
      const highEntropyHints = [
        "architecture",
        "bitness",
        "formFactors",
        "fullVersionList",
        "model",
        "platformVersion",
        "uaFullVersion",
        "wow64"
      ];

      Object.defineProperty(prototype, "getHighEntropyValues", {
        ...descriptor,
        value: function (hints) {
          return getHighEntropyValues.call(this, hints).then((values) => {
            const sanitized = { ...values };
            highEntropyHints.forEach((hint) => delete sanitized[hint]);
            return sanitized;
          });
        }
      });
    }
  }

  const protectionLayersKey = Symbol.for("xPrivacyGuard.protectionLayers");
  const protectionLayers = window[protectionLayersKey] || {};
  protectionLayers.cautious = true;
  Object.defineProperty(window, protectionLayersKey, {
    configurable: true,
    value: protectionLayers
  });
})();
