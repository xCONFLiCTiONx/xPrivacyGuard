const mode = document.getElementById("mode");

chrome.storage.local.get(["mode"], (data) => {
  if (data.mode) mode.value = data.mode;
});

document.getElementById("save").onclick = () => {
  chrome.storage.local.set({
    mode: mode.value,
  });
};
