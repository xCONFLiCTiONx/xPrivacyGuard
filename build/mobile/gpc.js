(async function () {
  chrome.storage.local.get(["gpcEnabled"], (result) => {
    if (result.gpcEnabled === false) {
      return;
    }

    try {
      Object.defineProperty(navigator, "globalPrivacyControl", {
        get: () => true,
        configurable: true,
        enumerable: true,
      });
    } catch (e) {
      // Fallback if property definition fails
    }
  });
})();
