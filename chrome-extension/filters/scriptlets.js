(function (root) {
  "use strict";

  function isSafeSelector(selector) {
    return typeof selector === "string"
      && selector.length > 0
      && selector.length <= 512
      && !/[{};<@]/.test(selector)
      && !/^(?:\*|html|body|html\s*,|body\s*,)/i.test(selector.trim());
  }

  function runScriptlet(rule) {
    if (rule.name === "skip-video-ads") return runVideoAdAcceleration();

    const [first, second] = rule.args;
    if (!isSafeSelector(second)) return false;
    try {
      document.querySelector(second);
    } catch (error) {
      return false;
    }

    if (rule.name === "remove-class") {
      const classes = String(first).split(/\s+/).filter((name) => /^[\w-]{1,80}$/.test(name));
      if (!classes.length) return false;
      const apply = (node) => {
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        if (node.matches(second)) node.classList.remove(...classes);
        const matches = node.querySelectorAll(second);
        if (matches.length <= 500) matches.forEach((element) => element.classList.remove(...classes));
      };
      const matches = document.querySelectorAll(second);
      if (matches.length > 500) return false;
      matches.forEach((element) => element.classList.remove(...classes));
      return observeAddedNodes(apply);
    }

    if (rule.name === "remove-node-text") {
      const needle = String(first).slice(0, 160).toLowerCase();
      if (!needle) return false;
      const apply = (node) => {
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        const candidates = [];
        if (node.matches(second)) candidates.push(node);
        const matches = node.querySelectorAll(second);
        if (matches.length > 500) return;
        candidates.push(...matches);
        for (const element of candidates) {
          if (element.textContent.toLowerCase().includes(needle)) element.remove();
        }
      };
      const matches = document.querySelectorAll(second);
      if (matches.length > 500) return false;
      matches.forEach((element) => {
        if (element.textContent.toLowerCase().includes(needle)) element.remove();
      });
      return observeAddedNodes(apply);
    }
    return false;
  }

  function runVideoAdAcceleration() {
    const adMarkers = [
      '[class^="ad-" i]',
      '[class*=" ad-" i]',
      '[class^="ad_" i]',
      '[class*=" ad_" i]',
      '[class*="-ad-" i]',
      '[class*="_ad_" i]',
      '[data-testid^="ad-" i]',
      '[data-testid^="ad_" i]',
      '[data-testid*="-ad-" i]',
      '[data-testid*="_ad_" i]',
      '[data-testid="ad" i]',
      '[id^="ad-" i]',
      '[id*="-ad-" i]',
      '[aria-label="Ad" i]',
      '[aria-label^="Ad " i]',
      '[aria-label*="Ad break" i]',
      '[aria-label*="advertisement" i]',
      '.ad-slug',
      '.videoAdUi',
      '.ad-time',
      '.adCountdown'
    ];
    const camelAdTokenRe = /(?:^|[\s_-])[Aa]d(?:[Uu][Ii]|[Bb]reak|[Pp]od|[Bb]adge|[Cc]ountdown|[Oo]verlay|[Pp]layer|[Bb]anner|[Cc]ontainer|[Mm]arker|[Tt]imer|[Tt]ime|[Ii]nfo|[Tt]ag|[Ll]abel|[Nn]otice|[Mm]essage|[Bb]eacon|[Ss]lug)|(?:[a-z0-9])Ad(?:Ui|UI|Break|Pod|Badge|Countdown|Overlay|Player|Banner|Container|Marker|Timer|Time|Info|Tag|Label|Notice|Message|Beacon|Slug)/;
    const camelCandidateSelector = '[class*="Ad" i], [data-testid*="Ad" i], [id*="Ad" i]';
    const videos = new Map();
    let attachCounter = 0;
    let primaryVideo = null;

    function queryAllWithShadow(selector, rootNode = document) {
      const results = [];
      try {
        results.push(...rootNode.querySelectorAll(selector));
      } catch (error) {
        return results;
      }
      const all = rootNode.querySelectorAll("*");
      for (const el of all) {
        if (el.shadowRoot) {
          results.push(...queryAllWithShadow(selector, el.shadowRoot));
        }
      }
      return results;
    }

    function isElementVisible(element) {
      const style = getComputedStyle(element);
      return element.getClientRects().length > 0
        && style.visibility !== "hidden"
        && style.display !== "none"
        && parseFloat(style.opacity) !== 0;
    }

    function hasBoundaryAdToken(element) {
      const attrs = [
        typeof element.className === "string" ? element.className : "",
        element.getAttribute ? (element.getAttribute("data-testid") || "") : "",
        element.id || ""
      ];
      return attrs.some((val) => val && camelAdTokenRe.test(val));
    }

    function isVisibleAdMarker() {
      if (adMarkers.some((selector) => queryAllWithShadow(selector).some(isElementVisible))) {
        return true;
      }
      return queryAllWithShadow(camelCandidateSelector).some(
        (element) => hasBoundaryAdToken(element) && isElementVisible(element)
      );
    }

    function isVideoVisible(video) {
      if (!video.isConnected) return false;
      if (video.getClientRects().length === 0) return false;
      const style = getComputedStyle(video);
      if (style.display === "none" || style.visibility === "hidden" || parseFloat(style.opacity) === 0) {
        return false;
      }
      const rect = video.getBoundingClientRect();
      return rect.width > 1 && rect.height > 1;
    }

    function suppressVideo(video, state) {
      if (!video.paused) {
        try {
          video.pause();
        } catch (error) {
          // Ignore media pause errors on detached or restricted elements.
        }
      }
      video.muted = true;
      state.accelerating = false;
      state.suppressedDuplicate = true;
    }

    function scoreVideo(video, state) {
      const visible = isVideoVisible(video) ? 1 : 0;
      const style = video.isConnected ? getComputedStyle(video) : null;
      const zIndex = style ? (parseInt(style.zIndex, 10) || 0) : 0;
      const rect = video.isConnected ? video.getBoundingClientRect() : { width: 0, height: 0 };
      const area = Math.max(0, rect.width) * Math.max(0, rect.height);
      return [visible, zIndex, area, state.lastPlayedAt || 0, state.attachSeq || 0];
    }

    function compareScores(left, right) {
      for (let i = 0; i < left.length; i += 1) {
        if (left[i] !== right[i]) return left[i] - right[i];
      }
      return 0;
    }

    function pruneDisconnectedVideos() {
      for (const [video, state] of videos) {
        if (video.isConnected) continue;
        suppressVideo(video, state);
        if (primaryVideo === video) primaryVideo = null;
        videos.delete(video);
      }
    }

    function enforceSingleActiveVideo() {
      pruneDisconnectedVideos();

      if (primaryVideo && (!primaryVideo.isConnected || !isVideoVisible(primaryVideo) || primaryVideo.ended)) {
        primaryVideo = null;
      }

      const connectedEntries = Array.from(videos.entries());
      const hasVisibleVideo = connectedEntries.some(([video]) => isVideoVisible(video));

      if (hasVisibleVideo) {
        for (const [video, state] of connectedEntries) {
          if (!isVideoVisible(video) && !video.paused && !video.ended) {
            suppressVideo(video, state);
          }
        }
      }

      const playingEntries = connectedEntries.filter(([video]) => !video.paused && !video.ended);
      if (playingEntries.length > 1) {
        let winner = playingEntries[0];
        let winnerScore = scoreVideo(winner[0], winner[1]);
        for (let i = 1; i < playingEntries.length; i += 1) {
          const candidate = playingEntries[i];
          const candidateScore = scoreVideo(candidate[0], candidate[1]);
          if (compareScores(candidateScore, winnerScore) > 0) {
            winner = candidate;
            winnerScore = candidateScore;
          }
        }
        primaryVideo = winner[0];
        winner[1].suppressedDuplicate = false;
        for (const [video, state] of playingEntries) {
          if (video !== primaryVideo) suppressVideo(video, state);
        }
        return;
      }

      if (playingEntries.length === 1) {
        const [onlyVideo, onlyState] = playingEntries[0];
        if (onlyState.suppressedDuplicate && primaryVideo && primaryVideo !== onlyVideo) {
          suppressVideo(onlyVideo, onlyState);
          return;
        }
        onlyState.suppressedDuplicate = false;
        if (isVideoVisible(onlyVideo)) primaryVideo = onlyVideo;
      }
    }

    function updateVideo(video) {
      const state = videos.get(video);
      if (!state || !video.isConnected) return;
      if (state.suppressedDuplicate) {
        if (!video.paused) suppressVideo(video, state);
        return;
      }

      if (isVisibleAdMarker()) {
        if (!state.accelerating) {
          state.playbackRate = video.playbackRate;
          state.muted = video.muted;
          state.accelerating = true;
        }
        video.muted = true;
        video.playbackRate = 16;
      } else if (state.accelerating) {
        if (video.playbackRate === 16) video.playbackRate = state.playbackRate;
        if (video.muted) video.muted = state.muted;
        state.accelerating = false;
      }
    }

    function attachVideo(video) {
      if (videos.has(video)) return;
      attachCounter += 1;
      videos.set(video, {
        accelerating: false,
        suppressedDuplicate: false,
        attachSeq: attachCounter,
        lastPlayedAt: video.paused ? 0 : Date.now()
      });
      ["play", "playing"].forEach((eventName) => {
        video.addEventListener(eventName, () => {
          const state = videos.get(video);
          if (state) state.lastPlayedAt = Date.now();
          enforceSingleActiveVideo();
          updateVideo(video);
        });
      });
      video.addEventListener("pause", () => {
        enforceSingleActiveVideo();
      });
      ["timeupdate", "ratechange", "volumechange"].forEach((eventName) => {
        video.addEventListener(eventName, () => {
          enforceSingleActiveVideo();
          updateVideo(video);
        });
      });
      enforceSingleActiveVideo();
      updateVideo(video);
    }

    function scanVideos(node) {
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      if (node instanceof HTMLVideoElement) attachVideo(node);
      queryAllWithShadow("video", node).forEach(attachVideo);
      enforceSingleActiveVideo();
      videos.forEach((state, video) => updateVideo(video));
    }

    queryAllWithShadow("video").forEach(attachVideo);
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        if (record.addedNodes) record.addedNodes.forEach(scanVideos);
      }
      enforceSingleActiveVideo();
      videos.forEach((state, video) => updateVideo(video));
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["class", "style", "hidden", "aria-hidden"]
    });
    return true;
  }

  function observeAddedNodes(callback) {
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) callback(node);
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    return true;
  }

  root.FilterScriptlets = { runScriptlet };
})(globalThis);
