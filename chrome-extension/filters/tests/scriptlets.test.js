"use strict";

const assert = require("node:assert/strict");

class MockElement {
  constructor(tagName = "div") {
    this.tagName = tagName.toUpperCase();
    this.nodeType = 1;
    this.isConnected = true;
    this.children = [];
    this.listeners = new Map();
    this.style = { display: "block", visibility: "visible", opacity: "1", zIndex: "0" };
    this._rect = { width: 1280, height: 720 };
    this._clientRects = [this._rect];
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  getClientRects() {
    return this._clientRects;
  }

  querySelectorAll() {
    return [];
  }
}

class MockHTMLVideoElement extends MockElement {
  constructor() {
    super("video");
    this.paused = false;
    this.ended = false;
    this.muted = false;
    this.playbackRate = 1;
  }

  pause() {
    this.paused = true;
  }
}

const rootEl = new MockElement("html");
const video = new MockHTMLVideoElement();
rootEl.children.push(video);

globalThis.Node = { ELEMENT_NODE: 1 };
globalThis.HTMLVideoElement = MockHTMLVideoElement;
globalThis.MutationObserver = class {
  constructor() { throw new Error("No observer should be installed by the removed video scriptlet."); }
};
globalThis.getComputedStyle = (element) => element.style;
globalThis.document = {
  documentElement: rootEl,
  querySelectorAll() { return [video]; }
};

require("../scriptlets.js");

(function runTests() {
  // The legacy scriptlet must be inert even if stale cached filter data requests it.
  assert.equal(
    globalThis.FilterScriptlets.runScriptlet({ name: "skip-video-ads", args: [] }),
    false
  );
  assert.equal(video.paused, false);
  assert.equal(video.muted, false);
  assert.equal(video.playbackRate, 1);

  // Unknown scriptlets are rejected without touching media playback.
  assert.equal(
    globalThis.FilterScriptlets.runScriptlet({ name: "unknown-video-control", args: [] }),
    false
  );
  assert.equal(video.paused, false);
  assert.equal(video.muted, false);
  assert.equal(video.playbackRate, 1);

  console.log("Scriptlet tests passed.");
})();
