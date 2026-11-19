"use strict";

const assert = require("node:assert/strict");
require("../lists.js");

const { validateListUrl, downloadList } = globalThis.FilterLists;

assert.equal(validateListUrl("https://example.com/filters.txt"), "https://example.com/filters.txt");
assert.throws(() => validateListUrl("javascript:alert(1)"), /HTTP or HTTPS/);
assert.throws(() => validateListUrl("https://user:pass@example.com/list.txt"), /credentials/);

function createResponse(url, text, contentLength) {
  return {
    ok: true,
    url,
    headers: { get: () => contentLength || null },
    body: new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(text));
        controller.close();
      }
    })
  };
}

(async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => createResponse(
      "https://example.com/filters.txt",
      "||ads.example^",
      "13"
    );
    assert.equal(await downloadList("https://example.com/filters.txt"), "||ads.example^");

    globalThis.fetch = async () => createResponse(
      "http://example.com/filters.txt",
      "||ads.example^",
      "13"
    );
    await assert.rejects(
      downloadList("https://example.com/filters.txt"),
      /must end at a credential-free HTTP\(S\) URL/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
  console.log("Filter list validation tests passed.");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
