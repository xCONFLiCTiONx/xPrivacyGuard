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
