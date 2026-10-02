document.addEventListener("DOMContentLoaded", () => {
  const gpcToggle = document.getElementById("gpcToggle");

  if (!gpcToggle) return;

  chrome.storage.local.get(["gpcEnabled"], (result) => {
    gpcToggle.checked = result.gpcEnabled !== false;
  });

  gpcToggle.addEventListener("change", () => {
    const isEnabled = gpcToggle.checked;
    chrome.storage.local.set({ gpcEnabled: isEnabled }, () => {
      console.log(`GPC mobile setting updated to: ${isEnabled}`);
    });
  });
});
