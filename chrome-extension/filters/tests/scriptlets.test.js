"use strict";

const assert = require("node:assert/strict");

class MockElement {
  constructor(tagName = "div") {
    this.tagName = tagName.toUpperCase();
    this.nodeType = 1;
    this.isConnected = true;
    this.children = [];
    this.listeners = new Map();
    this.style = {
      display: "block",
      visibility: "visible",
      opacity: "1",
      zIndex: "0"
    };
    this._rect = { width: 1280, height: 720 };
    this._clientRects = [this._rect];
    this.attributes = {};
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  dispatchEvent(type) {
    for (const listener of this.listeners.get(type) || []) {
      listener({ type, target: this });
    }
  }

  getClientRects() {
    return this.style.display === "none" ? [] : this._clientRects;
  }

  getBoundingClientRect() {
    return this._rect;
  }

  querySelectorAll(selector) {
    const results = [];
    const visit = (node) => {
      for (const child of node.children || []) {
        if (matchesSimpleSelector(child, selector)) results.push(child);
        visit(child);
      }
    };
    visit(this);
    return results;
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
    this.dispatchEvent("pause");
  }

  play() {
    this.paused = false;
    this.dispatchEvent("play");
    this.dispatchEvent("playing");
  }
}

function matchesSimpleSelector(el, selector) {
  if (selector === "video") return el.tagName === "VIDEO";
  if (selector === ".ad-slug") return Boolean(el.attributes.isAdSlug);
  return false;
}

const observers = [];
class MockMutationObserver {
  constructor(callback) {
    this.callback = callback;
    observers.push(this);
  }
  observe() {}
  disconnect() {}
  trigger(addedNodes) {
    this.callback([{ addedNodes }]);
  }
}

const rootEl = new MockElement("html");
let adMarkerVisible = false;
const adMarkerEl = new MockElement("div");
adMarkerEl.attributes.isAdSlug = true;
adMarkerEl.getClientRects = () => (adMarkerVisible ? [{ width: 100, height: 20 }] : []);
rootEl.children.push(adMarkerEl);

globalThis.Node = { ELEMENT_NODE: 1 };
globalThis.HTMLVideoElement = MockHTMLVideoElement;
globalThis.MutationObserver = MockMutationObserver;
globalThis.getComputedStyle = (el) => el.style;
globalThis.document = {
  documentElement: rootEl,
  querySelectorAll(selector) {
    return rootEl.querySelectorAll(selector);
  }
};

require("../scriptlets.js");

(function runTests() {
  // 1. Single visible video plays normally.
  const primary = new MockHTMLVideoElement();
  primary.style.zIndex = "2";
  rootEl.children.push(primary);

  assert.equal(globalThis.FilterScriptlets.runScriptlet({ name: "skip-video-ads", args: [] }), true);
  assert.equal(primary.paused, false);
  assert.equal(primary.muted, false);

  // 2. Hidden second video starts playing -> immediately paused and muted.
  const hiddenDup = new MockHTMLVideoElement();
  hiddenDup.style.display = "none";
  rootEl.children.push(hiddenDup);
  observers[0].trigger([hiddenDup]);

  assert.equal(hiddenDup.paused, true);
  assert.equal(hiddenDup.muted, true);
  assert.equal(primary.paused, false);
  assert.equal(primary.muted, false);

  // Even when primary is paused, hidden duplicate cannot play.
  primary.pause();
  hiddenDup.muted = false;
  hiddenDup.play();
  assert.equal(hiddenDup.paused, true);
  assert.equal(hiddenDup.muted, true);

  // 3. Stacked visible duplicate (lower zIndex) playing simultaneously -> suppressed.
  primary.play();
  const stackedDup = new MockHTMLVideoElement();
  stackedDup.style.zIndex = "1";
  rootEl.children.push(stackedDup);
  observers[0].trigger([stackedDup]);

  assert.equal(stackedDup.paused, true);
  assert.equal(stackedDup.muted, true);
  assert.equal(primary.paused, false);
  assert.equal(primary.muted, false);

  // User pauses primary; stale duplicate retrying play stays suppressed.
  primary.pause();
  stackedDup.muted = false;
  stackedDup.play();
  assert.equal(stackedDup.paused, true);
  assert.equal(stackedDup.muted, true);

  // 4. Ad marker accelerates primary video and restores it, without unmuting suppressed duplicate.
  primary.play();
  adMarkerVisible = true;
  primary.dispatchEvent("timeupdate");
  assert.equal(primary.muted, true);
  assert.equal(primary.playbackRate, 16);

  adMarkerVisible = false;
  primary.dispatchEvent("timeupdate");
  assert.equal(primary.muted, false);
  assert.equal(primary.playbackRate, 1);
  assert.equal(stackedDup.muted, true);
  assert.equal(stackedDup.paused, true);

  // 5. Removing primary video allows a new connected visible video to play normally.
  primary.isConnected = false;
  const nextEpisode = new MockHTMLVideoElement();
  nextEpisode.style.zIndex = "2";
  rootEl.children.push(nextEpisode);
  observers[0].trigger([nextEpisode]);

  assert.equal(nextEpisode.paused, false);
  assert.equal(nextEpisode.muted, false);

  console.log("Scriptlet tests passed.");
})();

