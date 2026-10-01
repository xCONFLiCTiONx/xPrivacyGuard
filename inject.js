(() => {
  "use strict";

  let config = {
    mode: "normal",
  };

  chrome.storage?.local?.get(["mode"], (data) => {
    if (data.mode) config.mode = data.mode;

    start();
  });

  function define(obj, name, value) {
    try {
      Object.defineProperty(obj, name, {
        get: () => value,
      });
    } catch (e) {}
  }

  /*
   Navigator
  */

  function protectNavigator() {
    define(navigator, "hardwareConcurrency", 8);

    define(navigator, "deviceMemory", 8);

    define(navigator, "platform", "Win32");
  }

  /*
   Screen
  */

  function protectScreen() {
    define(screen, "colorDepth", 24);
  }

  /*
   Canvas
  */

  function protectCanvas() {
    const oldDataURL = HTMLCanvasElement.prototype.toDataURL;

    HTMLCanvasElement.prototype.toDataURL = function (...args) {
      try {
        poisonCanvas(this);
      } catch (e) {}

      return oldDataURL.apply(this, args);
    };

    const oldBlob = HTMLCanvasElement.prototype.toBlob;

    HTMLCanvasElement.prototype.toBlob = function (...args) {
      try {
        poisonCanvas(this);
      } catch (e) {}

      return oldBlob.apply(this, args);
    };
  }

  function poisonCanvas(canvas) {
    try {
      const ctx = canvas.getContext("2d");

      if (!ctx) return;

      const data = ctx.getImageData(0, 0, canvas.width, canvas.height);

      if (data.data.length) {
        data.data[0] ^= 1;
        data.data[1] ^= 1;
        data.data[2] ^= 1;
      }

      ctx.putImageData(data, 0, 0);
    } catch (e) {}
  }

  /*
   WebGL
  */

  function protectWebGL() {
    const old = WebGLRenderingContext.prototype.getParameter;

    WebGLRenderingContext.prototype.getParameter = function (param) {
      if (param === 37445) return "Generic GPU";

      if (param === 37446) return "Generic Renderer";

      return old.call(this, param);
    };
  }

  /*
   Audio
  */

  function protectAudio() {
    if (config.mode === "strict") {
      define(window, "AudioContext", undefined);

      define(window, "OfflineAudioContext", undefined);

      return;
    }

    const old = AudioBuffer.prototype.getChannelData;

    AudioBuffer.prototype.getChannelData = function (...args) {
      let data = old.apply(this, args);

      try {
        if (data.length) data[0] += 0.00000001;
      } catch (e) {}

      return data;
    };
  }

  /*
   Strict API Blocking
  */

  function strictProtection() {
    if (config.mode !== "strict") return;

    define(navigator, "getBattery", undefined);

    define(navigator, "getGamepads", undefined);

    define(window, "DeviceMotionEvent", undefined);

    define(window, "DeviceOrientationEvent", undefined);

    /*
     WebRTC
    */

    define(window, "RTCPeerConnection", undefined);

    define(window, "webkitRTCPeerConnection", undefined);
  }

  /*
   Client Rects
  */

  function protectRects() {
    const old = Element.prototype.getBoundingClientRect;

    Element.prototype.getBoundingClientRect = function () {
      let rect = old.call(this);

      return new DOMRect(rect.x, rect.y, rect.width, rect.height);
    };
  }

  /*
   Start
  */

  function start() {
    protectNavigator();

    protectScreen();

    protectCanvas();

    protectWebGL();

    protectAudio();

    protectRects();

    strictProtection();

    console.log("xPrivacyGuard v2 active:", config.mode);
  }
})();
