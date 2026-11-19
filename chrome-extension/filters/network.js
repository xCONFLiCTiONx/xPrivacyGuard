(function (root) {
  "use strict";

  function compileNetworkRule(rule, id, priority) {
    const condition = rule.regex ? { regexFilter: rule.regex } : { urlFilter: rule.pattern };
    if (rule.resourceTypes && rule.resourceTypes.length) condition.resourceTypes = rule.resourceTypes;
    if (rule.domainType) condition.domainType = rule.domainType;
    if (rule.domain) {
      if (rule.domain.include.length) condition.initiatorDomains = rule.domain.include;
      if (rule.domain.exclude.length) condition.excludedInitiatorDomains = rule.domain.exclude;
    }
    if (rule.denyallow && rule.denyallow.length) condition.excludedRequestDomains = rule.denyallow;
    const action = rule.exception
      ? { type: "allow" }
      : rule.redirect
        ? { type: "redirect", redirect: { extensionPath: rule.redirect } }
        : { type: "block" };
    return {
      id,
      priority,
      action,
      condition
    };
  }

  function ruleKey(rule) {
    return JSON.stringify({
      exception: rule.exception,
      pattern: rule.pattern || null,
      regex: rule.regex || null,
      resourceTypes: rule.resourceTypes || [],
      domain: rule.domain,
      denyallow: rule.denyallow || [],
      domainType: rule.domainType,
      important: rule.important,
      redirect: rule.redirect || null
    });
  }

  root.FilterNetwork = { compileNetworkRule, ruleKey };
})(globalThis);
