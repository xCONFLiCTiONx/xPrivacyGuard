(() => {
  "use strict";

  console.log("xPrivacyGuard v4 active");

  //
  // Canvas Blocking
  //

  HTMLCanvasElement.prototype.toDataURL = function () {
    throw new Error("Canvas fingerprinting blocked by xPrivacyGuard");
  };

  HTMLCanvasElement.prototype.toBlob = function () {
    throw new Error("Canvas fingerprinting blocked by xPrivacyGuard");
  };

  CanvasRenderingContext2D.prototype.getImageData = function () {
    throw new Error("Canvas fingerprinting blocked by xPrivacyGuard");
  };

  //
  // Audio Blocking
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

  //
  // Optional WebGL Privacy
  //

  try {
    const old = WebGLRenderingContext.prototype.getParameter;

    WebGLRenderingContext.prototype.getParameter = function (parameter) {
      if (parameter === 37445) return "Protected";

      if (parameter === 37446) return "Protected";

      return old.call(this, parameter);
    };
  } catch (e) {}
})();
