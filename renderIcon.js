// renderIcon.js - Adaptive extension icon theme handler
function renderIcon() {
  const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

  function updateTheme() {
    if (!chrome.runtime?.id) {
      mediaQuery.removeEventListener("change", updateTheme);
      return;
    }

    const isDarkMode = mediaQuery.matches;

    try {
      chrome.runtime.sendMessage({ action: "updateIcon", isDarkMode }, () => {
        if (chrome.runtime.lastError) {
          mediaQuery.removeEventListener("change", updateTheme);
        }
      });
    } catch (e) {
      mediaQuery.removeEventListener("change", updateTheme);
    }
  }

  updateTheme();
  mediaQuery.addEventListener("change", updateTheme);
}

renderIcon();
