(() => {
  "use strict";

  const xPrivacyGuard = {
    settings: {
      canvas: true,
      webgl: true,
      audio: true,
      navigator: true,
    },

    navigatorProtection() {
      try {
        Object.defineProperty(navigator, "hardwareConcurrency", {
          get: () => 8,
        });

        Object.defineProperty(navigator, "deviceMemory", {
          get: () => 8,
        });
      } catch (e) {}
    },

    canvasProtection() {
      const original = HTMLCanvasElement.prototype.toDataURL;

      HTMLCanvasElement.prototype.toDataURL = function (...args) {
        try {
          const ctx = this.getContext("2d");

          if (ctx) {
            const image = ctx.getImageData(0, 0, this.width, this.height);

            if (image.data.length) {
              image.data[0] ^= 1;
              image.data[1] ^= 1;
            }

            ctx.putImageData(image, 0, 0);
          }
        } catch (e) {}

        return original.apply(this, args);
      };
    },

    webglProtection() {
      const original = WebGLRenderingContext.prototype.getParameter;

      WebGLRenderingContext.prototype.getParameter = function (parameter) {
        // UNMASKED_VENDOR_WEBGL
        if (parameter === 37445) {
          return "Generic GPU";
        }

        // UNMASKED_RENDERER_WEBGL
        if (parameter === 37446) {
          return "Generic Renderer";
        }

        return original.call(this, parameter);
      };
    },

    audioProtection() {
      const original = AudioBuffer.prototype.getChannelData;

      AudioBuffer.prototype.getChannelData = function (...args) {
        const data = original.apply(this, args);

        try {
          if (data.length) {
            data[0] += 0.00000001;
          }
        } catch (e) {}

        return data;
      };
    },

    start() {
      if (this.settings.navigator) this.navigatorProtection();

      if (this.settings.canvas) this.canvasProtection();

      if (this.settings.webgl) this.webglProtection();

      if (this.settings.audio) this.audioProtection();

      console.log("xPrivacyGuard enabled");
    },
  };

  xPrivacyGuard.start();
})();
