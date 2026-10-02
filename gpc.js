// gpc.js - Injected into MAIN world at document_start to respect GPC settings
(function () {
  "use strict";

  try {
    chrome.storage.local.get({ gpcEnabled: true }, function (data) {
      if (!data.gpcEnabled) {
        return; // Exit early if GPC is disabled in settings
      }

      if (typeof Navigator !== "undefined" && Navigator.prototype) {
        Object.defineProperty(Navigator.prototype, "globalPrivacyControl", {
          get: function () {
            return true;
          },
          configurable: true,
          enumerable: true,
        });
      }

      if (typeof navigator !== "undefined") {
        if (
          !("globalPrivacyControl" in navigator) ||
          navigator.globalPrivacyControl !== true
        ) {
          Object.defineProperty(navigator, "globalPrivacyControl", {
            value: true,
            writable: false,
            configurable: true,
            enumerable: true,
          });
        }
      }
    });
  } catch (e) {}
})();
