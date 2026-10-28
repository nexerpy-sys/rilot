/**
 * Rilot Google Apps Script Backend — Auth.gs
 *
 * Enforces role-separated authentication (RILOT_APP_API_KEY vs RILOT_ENGINE_API_KEY),
 * constant-time token comparison, timestamp replay window checks, durable nonce
 * deduplication, and per-role rate limiting.
 */

function constantTimeEquals(a, b) {
  if (typeof a !== "string" || typeof b !== "string") {
    return false;
  }
  var lenA = a.length;
  var lenB = b.length;
  var maxLen = Math.max(lenA, lenB);
  if (maxLen === 0) {
    return false;
  }
  var diff = lenA ^ lenB;
  for (var i = 0; i < maxLen; i++) {
    var codeA = i < lenA ? a.charCodeAt(i) : 0;
    var codeB = i < lenB ? b.charCodeAt(i) : 0;
    diff |= codeA ^ codeB;
  }
  return diff === 0;
}

function resolveActionRole(action) {
  if (typeof action !== "string") return "UNKNOWN";
  if (RILOT_CONSTANTS.APP_ACTIONS.indexOf(action) !== -1) {
    return "APP";
  }
  if (RILOT_CONSTANTS.ENGINE_ACTIONS.indexOf(action) !== -1) {
    return "ENGINE";
  }
  return "UNKNOWN";
}

function authenticateAndAuthorizeRequest(envelope, scriptProps, storage, nowMs) {
  var props = scriptProps || {};
  var action = envelope.action;
  var role = resolveActionRole(action);

  if (role === "UNKNOWN") {
    return {
      ok: false,
      code: "UNKNOWN_ACTION",
      message: "Unsupported or unknown action '" + action + "'."
    };
  }

  var appKey = String(props.RILOT_APP_API_KEY || "").trim();
  var engineKey = String(props.RILOT_ENGINE_API_KEY || "").trim();
  var githubPat = String(props.GITHUB_PAT || "").trim();

  if (!appKey || !engineKey) {
    return {
      ok: false,
      code: "SERVER_MISCONFIGURED",
      message: "Script Properties RILOT_APP_API_KEY and RILOT_ENGINE_API_KEY must both be configured on the backend."
    };
  }

  if (constantTimeEquals(appKey, engineKey)) {
    return {
      ok: false,
      code: "SERVER_MISCONFIGURED",
      message: "Security policy violation: RILOT_APP_API_KEY and RILOT_ENGINE_API_KEY must be distinct keys."
    };
  }

  if (githubPat && (constantTimeEquals(appKey, githubPat) || constantTimeEquals(engineKey, githubPat))) {
    return {
      ok: false,
      code: "SERVER_MISCONFIGURED",
      message: "Security policy violation: GITHUB_PAT must never be reused as RILOT_APP_API_KEY or RILOT_ENGINE_API_KEY."
    };
  }

  // 1. Per-role rate limiting check before processing further
  var maxReq = role === "ENGINE"
    ? Number(props.RATE_LIMIT_MAX_ENGINE_REQUESTS || RILOT_CONSTANTS.RATE_LIMIT_MAX_ENGINE_REQUESTS)
    : Number(props.RATE_LIMIT_MAX_APP_REQUESTS || RILOT_CONSTANTS.RATE_LIMIT_MAX_APP_REQUESTS);
  var windowMs = Number(props.RATE_LIMIT_WINDOW_MS || RILOT_CONSTANTS.RATE_LIMIT_WINDOW_MS);

  var rateOk = storage.checkRateLimit("role:" + role, nowMs, maxReq, windowMs);
  if (!rateOk) {
    return {
      ok: false,
      code: "RATE_LIMITED",
      message: "Rate limit exceeded for role " + role + ". Please wait before issuing additional requests."
    };
  }

  // 2. Authenticate caller token and enforce role separation
  var providedToken = String(envelope.authToken || "").trim();
  if (!providedToken) {
    return {
      ok: false,
      code: "UNAUTHORIZED",
      message: "Unauthorized: missing authToken in request envelope."
    };
  }

  var matchesApp = constantTimeEquals(providedToken, appKey);
  var matchesEngine = constantTimeEquals(providedToken, engineKey);

  if (role === "APP") {
    if (matchesEngine && !matchesApp) {
      return {
        ok: false,
        code: "FORBIDDEN_ROLE",
        message: "Forbidden: RILOT_ENGINE_API_KEY is not permitted to invoke administrative app action '" + action + "'."
      };
    }
    if (!matchesApp) {
      return {
        ok: false,
        code: "UNAUTHORIZED",
        message: "Unauthorized: invalid RILOT_APP_API_KEY."
      };
    }
  } else if (role === "ENGINE") {
    if (matchesApp && !matchesEngine) {
      return {
        ok: false,
        code: "FORBIDDEN_ROLE",
        message: "Forbidden: RILOT_APP_API_KEY is not permitted to invoke privileged engine action '" + action + "'."
      };
    }
    if (!matchesEngine) {
      return {
        ok: false,
        code: "UNAUTHORIZED",
        message: "Unauthorized: invalid RILOT_ENGINE_API_KEY."
      };
    }
  }

  // 3. Replay window timestamp check
  var reqTs = Number(envelope.timestamp);
  var replayWindowMs = Number(props.REPLAY_WINDOW_MS || RILOT_CONSTANTS.REPLAY_WINDOW_MS);
  if (!isFinite(reqTs) || reqTs <= 0 || Math.abs(nowMs - reqTs) > replayWindowMs) {
    return {
      ok: false,
      code: "EXPIRED_TIMESTAMP",
      message: "Replay protection rejected request: missing or expired timestamp."
    };
  }

  // 4. Nonce format and durable replay deduplication
  var nonce = String(envelope.nonce || "").trim();
  if (!RILOT_REGEX.NONCE.test(nonce)) {
    return {
      ok: false,
      code: "INVALID_NONCE",
      message: "Replay protection rejected request: missing or malformed nonce."
    };
  }

  var nonceRes = storage.checkAndRecordNonce(nonce, role, nowMs, replayWindowMs * 2);
  if (!nonceRes.ok) {
    return {
      ok: false,
      code: nonceRes.reason === "DUPLICATE_NONCE" ? "DUPLICATE_NONCE" : "INVALID_NONCE",
      message: nonceRes.reason === "DUPLICATE_NONCE"
        ? "Replay protection rejected request: duplicate nonce."
        : "Replay protection rejected request: invalid nonce."
    };
  }

  return {
    ok: true,
    role: role
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    constantTimeEquals: constantTimeEquals,
    resolveActionRole: resolveActionRole,
    authenticateAndAuthorizeRequest: authenticateAndAuthorizeRequest
  };
}
