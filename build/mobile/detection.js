// Common Platform Detection Helper
export function isAndroidPlatform() {
  return /Android/i.test(navigator.userAgent);
}

export function isMobileBrowser() {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
}
