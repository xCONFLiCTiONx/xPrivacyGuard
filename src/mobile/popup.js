document.addEventListener('DOMContentLoaded', () => {
  const toggleGpc = document.getElementById('toggle-gpc');
  const toggleDnt = document.getElementById('toggle-dnt');
  const toggleCleaner = document.getElementById('toggle-cleaner');
  const toggleFingerprint = document.getElementById('toggle-fingerprint');
  const blockedCountElem = document.getElementById('blocked-count');
  const btnReset = document.getElementById('btn-reset');

  chrome.storage.local.get({
    gpcEnabled: true,
    dntEnabled: true,
    cleanUrlsEnabled: true,
    fingerprintShieldEnabled: true,
    blockedCount: 0
  }, (items) => {
    toggleGpc.checked = items.gpcEnabled;
    toggleDnt.checked = items.dntEnabled;
    toggleCleaner.checked = items.cleanUrlsEnabled;
    toggleFingerprint.checked = items.fingerprintShieldEnabled;
    blockedCountElem.textContent = items.blockedCount.toLocaleString();
  });

  toggleGpc.addEventListener('change', () => {
    chrome.storage.local.set({ gpcEnabled: toggleGpc.checked });
  });

  toggleDnt.addEventListener('change', () => {
    chrome.storage.local.set({ dntEnabled: toggleDnt.checked });
  });

  toggleCleaner.addEventListener('change', () => {
    chrome.storage.local.set({ cleanUrlsEnabled: toggleCleaner.checked });
  });

  toggleFingerprint.addEventListener('change', () => {
    chrome.storage.local.set({ fingerprintShieldEnabled: toggleFingerprint.checked });
  });

  btnReset.addEventListener('click', () => {
    chrome.storage.local.set({ blockedCount: 0 }, () => {
      blockedCountElem.textContent = '0';
    });
  });
});
