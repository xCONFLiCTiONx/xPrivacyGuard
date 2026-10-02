// mobile/gpc.js (Main World Safe Version)
(function () {
  // Since MAIN world can't call chrome APIs, check if an attribute or meta tag 
  // was set by the isolated content script, or execute conditionally.
  // Alternatively, if managed purely via content.js injection above, this file can be left blank or removed from main world injection.

  const metaTag = document.querySelector('meta[name="xprivacy-gpc"]');
  if (metaTag && metaTag.getAttribute('content') === 'false') {
    return; // Respect user disable
  }

  try {
    Object.defineProperty(navigator, 'globalPrivacyControl', {
      get: () => true,
      configurable: true,
      enumerable: true
    });
  } catch (e) { }
})();