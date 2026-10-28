/**
 * Rilot Google Apps Script Backend — Validation.gs
 *
 * Schema validation, fail-closed content authorization evaluation, GitHub target
 * validation, client override detection, and sensitive credential redaction.
 */

var RILOT_CONSTANTS = {
  SCHEMA_VERSION: 3,
  REPLAY_WINDOW_MS: 5 * 60 * 1000, // 5 minutes
  CYCLE_LOCK_TTL_MS: 25 * 60 * 1000, // 25 minutes max cycle lock
  RATE_LIMIT_WINDOW_MS: 60 * 1000, // 1 minute window
  RATE_LIMIT_MAX_APP_REQUESTS: 45,
  RATE_LIMIT_MAX_ENGINE_REQUESTS: 30,
  MAX_REQUEST_BYTES: 256 * 1024, // 256 KB max JSON payload
  CYCLE_LOCK_NAME: "automation_cycle",
  APPROVED_WORKFLOW_FILES: ["insta-automation-cycle.yml"],
  FORBIDDEN_PLACEHOLDER_TARGETS: [
    "your_github_owner",
    "your_github_repo",
    "your-github-owner",
    "your-github-repo",
    "your-org",
    "your-repo",
    "example",
    "placeholder",
    "najinkya776/instaautomation",
    "najinkya776"
  ],
  CLIENT_DISPATCH_OVERRIDE_KEYS: [
    "githubOwner",
    "githubRepo",
    "githubRepoSlug",
    "githubBranch",
    "githubRef",
    "githubWorkflow",
    "githubWorkflowFile",
    "workflowFile",
    "targetRepo",
    "targetOwner",
    "targetBranch",
    "repoSlug",
    "owner",
    "repo",
    "branch",
    "ref",
    "workflow",
    "dispatchUrl",
    "githubApiUrl"
  ],
  APP_ACTIONS: [
    "app.getStatus",
    "app.setControl",
    "app.getDiscoveryConfig",
    "app.updateDiscoveryConfig",
    "app.getScheduleConfig",
    "app.updateScheduleConfig",
    "app.getCaptions",
    "app.updateCaptions",
    "app.getActivity",
    "app.safeRetry",
    "app.connectionTest"
  ],
  ENGINE_ACTIONS: [
    "engine.getConfig",
    "engine.acquireCycle",
    "engine.report",
    "engine.heartbeat",
    "engine.getRetryTarget"
  ]
};

var RILOT_REGEX = {
  GITHUB_OWNER: /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/,
  GITHUB_REPO_NAME: /^[A-Za-z0-9._-]{1,100}$/,
  GITHUB_BRANCH: /^(?!\/)(?!.*\/\/)(?!.*\.\.)[A-Za-z0-9._/-]{1,100}(?<!\/)$/,
  NONCE: /^[A-Za-z0-9_.:-]{8,128}$/,
  REQUEST_ID: /^[A-Za-z0-9_.:-]{6,128}$/,
  REEL_PK: /^[A-Za-z0-9_-]{1,64}$/,
  IDEMPOTENCY_TOKEN: /^[A-Za-z0-9_.:-]{6,128}$/,
  LOCK_OWNER_ID: /^[A-Za-z0-9_.:-]{6,128}$/
};

function formatIstTimestamp(epochMs) {
  var ms = typeof epochMs === "number" && isFinite(epochMs) ? epochMs : Date.now();
  if (typeof Utilities !== "undefined" && Utilities && typeof Utilities.formatDate === "function") {
    return Utilities.formatDate(new Date(ms), "Asia/Kolkata", "yyyy-MM-dd HH:mm:ss") + " IST";
  }
  var formatter = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
  return formatter.format(new Date(ms)) + " IST";
}

function redactSensitiveValues(message, scriptProps) {
  var text = String(message || "");
  if (!text) return "";
  var props = scriptProps || {};
  var secretKeys = [
    "RILOT_APP_API_KEY",
    "RILOT_ENGINE_API_KEY",
    "GITHUB_PAT"
  ];
  for (var i = 0; i < secretKeys.length; i++) {
    var val = String(props[secretKeys[i]] || "").trim();
    if (val && val.length >= 4) {
      text = text.split(val).join("[REDACTED]");
    }
  }
  text = text.replace(/ghp_[A-Za-z0-9_]+/g, "[REDACTED_GITHUB_TOKEN]");
  text = text.replace(/github_pat_[A-Za-z0-9_]+/g, "[REDACTED_GITHUB_TOKEN]");
  text = text.replace(/Bearer\s+[A-Za-z0-9_.:-]+/gi, "Bearer [REDACTED]");
  return text;
}

function detectClientDispatchOverrideAttempt(payloadObj, queryParams) {
  var keys = RILOT_CONSTANTS.CLIENT_DISPATCH_OVERRIDE_KEYS;
  if (queryParams && typeof queryParams === "object") {
    for (var i = 0; i < keys.length; i++) {
      var qKey = keys[i];
      if (Object.prototype.hasOwnProperty.call(queryParams, qKey)) {
        return "Query parameter '" + qKey + "' is not permitted; dispatch targets are controlled exclusively by Script Properties.";
      }
    }
  }
  if (payloadObj && typeof payloadObj === "object" && !Array.isArray(payloadObj)) {
    for (var j = 0; j < keys.length; j++) {
      var bKey = keys[j];
      if (Object.prototype.hasOwnProperty.call(payloadObj, bKey)) {
        return "Payload field '" + bKey + "' is not permitted; repository, branch, and workflow configuration cannot be overridden by client requests.";
      }
    }
  }
  return null;
}

function validateRpcEnvelope(rawBody, queryParams) {
  if (queryParams && typeof queryParams === "object") {
    if (
      Object.prototype.hasOwnProperty.call(queryParams, "authToken") ||
      Object.prototype.hasOwnProperty.call(queryParams, "apiKey") ||
      Object.prototype.hasOwnProperty.call(queryParams, "token") ||
      Object.prototype.hasOwnProperty.call(queryParams, "key")
    ) {
      return {
        valid: false,
        code: "CREDENTIALS_IN_QUERY_FORBIDDEN",
        message: "Authentication credentials must never be passed in URL query parameters.",
        requestId: "unknown"
      };
    }
  }

  if (typeof rawBody !== "string" || !rawBody.trim()) {
    return {
      valid: false,
      code: "EMPTY_REQUEST_BODY",
      message: "Request body must be a non-empty JSON object.",
      requestId: "unknown"
    };
  }

  if (rawBody.length > RILOT_CONSTANTS.MAX_REQUEST_BYTES) {
    return {
      valid: false,
      code: "PAYLOAD_TOO_LARGE",
      message: "Request body exceeds maximum permitted size.",
      requestId: "unknown"
    };
  }

  var parsed;
  try {
    parsed = JSON.parse(rawBody);
  } catch (e) {
    return {
      valid: false,
      code: "MALFORMED_JSON",
      message: "Malformed JSON request body.",
      requestId: "unknown"
    };
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return {
      valid: false,
      code: "INVALID_ENVELOPE",
      message: "Request envelope must be a JSON object.",
      requestId: "unknown"
    };
  }

  var reqId = typeof parsed.requestId === "string" && parsed.requestId.trim()
    ? parsed.requestId.trim()
    : "unknown";

  if (!RILOT_REGEX.REQUEST_ID.test(reqId)) {
    return {
      valid: false,
      code: "INVALID_REQUEST_ID",
      message: "Missing or malformed requestId (expected 6-128 alphanumeric/._:- characters).",
      requestId: "unknown"
    };
  }

  var action = typeof parsed.action === "string" ? parsed.action.trim() : "";
  if (!action) {
    return {
      valid: false,
      code: "MISSING_ACTION",
      message: "Missing required 'action' field in RPC request envelope.",
      requestId: reqId
    };
  }

  var authToken = typeof parsed.authToken === "string" ? parsed.authToken.trim() : "";
  var timestamp = Number(parsed.timestamp);
  var nonce = typeof parsed.nonce === "string" ? parsed.nonce.trim() : "";
  var payload = parsed.payload === undefined || parsed.payload === null ? {} : parsed.payload;

  if (typeof payload !== "object" || Array.isArray(payload)) {
    return {
      valid: false,
      code: "INVALID_PAYLOAD",
      message: "Field 'payload' must be a JSON object.",
      requestId: reqId
    };
  }

  var envelopeOverrideErr = detectClientDispatchOverrideAttempt(parsed, queryParams);
  if (envelopeOverrideErr) {
    return {
      valid: false,
      code: "FORBIDDEN_DISPATCH_OVERRIDE",
      message: envelopeOverrideErr,
      requestId: reqId
    };
  }

  var payloadOverrideErr = detectClientDispatchOverrideAttempt(payload, null);
  if (payloadOverrideErr) {
    return {
      valid: false,
      code: "FORBIDDEN_DISPATCH_OVERRIDE",
      message: payloadOverrideErr,
      requestId: reqId
    };
  }

  return {
    valid: true,
    envelope: {
      action: action,
      authToken: authToken,
      timestamp: timestamp,
      nonce: nonce,
      requestId: reqId,
      payload: payload
    }
  };
}

function extractNormalizedStringSet(rawList, normalizer) {
  var normFn = normalizer || function (s) { return s; };
  if (rawList === undefined || rawList === null) {
    return { valid: true, values: [] };
  }
  if (!Array.isArray(rawList)) {
    return { valid: false, values: [] };
  }
  var seen = {};
  var result = [];
  for (var i = 0; i < rawList.length; i++) {
    var item = rawList[i];
    if (typeof item !== "string") {
      return { valid: false, values: [] };
    }
    var cleaned = normFn(item.trim());
    if (cleaned && !seen[cleaned]) {
      seen[cleaned] = true;
      result.push(cleaned);
    }
  }
  return { valid: true, values: result };
}

/**
 * Revalidates whether a candidate record is currently authorized for reuse
 * under the active discoveryConfig (fail-closed).
 */
function evaluateCandidateAuthorization(record, discoveryConfig) {
  if (!record || typeof record !== "object") {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: missing or malformed candidate record (fail-closed)."
    };
  }
  if (record.reuseAuthorized !== true || record.outcomeStatus === "UNAUTHORIZED_BLOCKED") {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: candidate record was not authorized for reuse (fail-closed)."
    };
  }
  if (!discoveryConfig || typeof discoveryConfig !== "object" || Array.isArray(discoveryConfig)) {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: missing or malformed discoveryConfig (fail-closed)."
    };
  }

  var bulkPolicy = discoveryConfig.allowAllHashtagCandidatesWhenTargetAuthorized;
  if (bulkPolicy !== undefined && typeof bulkPolicy !== "boolean") {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: allowAllHashtagCandidatesWhenTargetAuthorized must be a strict boolean (fail-closed)."
    };
  }
  var allowAllHashtagCandidates = bulkPolicy === true;

  var normHandle = function (h) { return h.replace(/^@/, "").toLowerCase(); };
  var ownedRes = extractNormalizedStringSet(discoveryConfig.ownedAccounts, normHandle);
  var creatorsRes = extractNormalizedStringSet(discoveryConfig.authorizedCreators, normHandle);
  var pksRes = extractNormalizedStringSet(discoveryConfig.authorizedCandidatePks, function (s) { return s; });

  if (!ownedRes.valid || !creatorsRes.valid || !pksRes.valid) {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: malformed authorization allowlists in discoveryConfig (fail-closed)."
    };
  }

  var reelPk = String(record.reelPk || "").trim();
  var creator = normHandle(String(record.creatorHandle || "").trim());
  var tag = String(record.hashtag || "").trim().replace(/^#/, "");

  // 1. Owned account (independent of hashtag target revocation)
  if (creator && ownedRes.values.indexOf(creator) !== -1) {
    return {
      authorized: true,
      source: "ORIGINAL_ACCOUNT",
      reason: "Authorized via ownedAccounts (@" + creator + ")."
    };
  }

  // 2. Separate, still-valid explicit candidate license (independent of hashtag target revocation)
  if (reelPk && pksRes.values.indexOf(reelPk) !== -1) {
    return {
      authorized: true,
      source: "EXPLICIT_LICENSE",
      reason: "Authorized via explicit candidate license (" + reelPk + ")."
    };
  }

  // 3. Validate current hashtag target state in discoveryConfig
  var enabledTags = [];
  if (Array.isArray(discoveryConfig.hashtags)) {
    for (var i = 0; i < discoveryConfig.hashtags.length; i++) {
      if (typeof discoveryConfig.hashtags[i] === "string") {
        enabledTags.push(discoveryConfig.hashtags[i].trim().replace(/^#/, "").toLowerCase());
      }
    }
  }
  var tagEnabled = Boolean(tag && enabledTags.indexOf(tag.toLowerCase()) !== -1);

  var rawAuthMap = discoveryConfig.reuseAuthorizedByTag;
  if (
    rawAuthMap !== undefined &&
    (rawAuthMap === null || typeof rawAuthMap !== "object" || Array.isArray(rawAuthMap))
  ) {
    return {
      authorized: false,
      source: "NONE",
      reason: "Unauthorized: malformed reuseAuthorizedByTag in discoveryConfig (fail-closed)."
    };
  }

  var tagReuseAuthorized = false;
  if (rawAuthMap && tag) {
    var keys = Object.keys(rawAuthMap);
    for (var kIdx = 0; kIdx < keys.length; kIdx++) {
      var k = keys[kIdx];
      var v = rawAuthMap[k];
      if (typeof v !== "boolean") {
        return {
          authorized: false,
          source: "NONE",
          reason: "Unauthorized: reuseAuthorizedByTag['" + k + "'] is not a strict boolean (fail-closed)."
        };
      }
      if (k.trim().replace(/^#/, "").toLowerCase() === tag.toLowerCase()) {
        tagReuseAuthorized = v === true;
      }
    }
  }

  var targetCurrentlyAuthorized = tagEnabled && tagReuseAuthorized;

  // 4. Creator allowlist on a currently authorized hashtag target
  if (targetCurrentlyAuthorized && creator && creatorsRes.values.indexOf(creator) !== -1) {
    return {
      authorized: true,
      source: "CREATOR_ALLOWLIST",
      reason: "Authorized via authorizedCreators (@" + creator + ") on authorized hashtag #" + tag + "."
    };
  }

  // 5. Explicit bulk hashtag policy on a currently authorized hashtag target
  if (targetCurrentlyAuthorized && allowAllHashtagCandidates) {
    return {
      authorized: true,
      source: "TARGET_POLICY_GRANT",
      reason: "Authorized via explicit allowAllHashtagCandidatesWhenTargetAuthorized=true on #" + tag + "."
    };
  }

  return {
    authorized: false,
    source: "NONE",
    reason: "Unauthorized: authorization policy was revoked or requires creator ownership, allowlisted creator, explicit candidate license, or explicit allowAllHashtagCandidatesWhenTargetAuthorized=true (fail-closed)."
  };
}

function validateDiscoveryConfigPayload(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return { valid: false, message: "Validation failed: discovery config payload must be an object." };
  }

  var rawHashtags = Array.isArray(payload.hashtags)
    ? payload.hashtags
    : (Array.isArray(payload.activeHashtags) ? payload.activeHashtags : []);
  var hashtags = [];
  for (var i = 0; i < rawHashtags.length; i++) {
    var clean = String(rawHashtags[i] || "").trim().replace(/^#/, "");
    if (clean && hashtags.indexOf(clean) === -1) {
      hashtags.push(clean);
    }
  }
  if (hashtags.length === 0) {
    return {
      valid: false,
      message: "Validation failed: at least one enabled hashtag is required."
    };
  }

  var weightSum =
    Number(payload.recencyWeightPercent || 0) +
    Number(payload.engagementWeightPercent || 0) +
    Number(payload.diversityWeightPercent || 0);
  if (weightSum !== 100) {
    return {
      valid: false,
      message: "Validation failed: scoring weights must sum to 100% (got " + weightSum + "%)."
    };
  }

  var rawAuthMap = payload.reuseAuthorizedByTag;
  var reuseAuthorizedByTag = {};
  if (rawAuthMap !== undefined && rawAuthMap !== null) {
    if (typeof rawAuthMap !== "object" || Array.isArray(rawAuthMap)) {
      return {
        valid: false,
        message: "Validation failed: reuseAuthorizedByTag must be an object mapping tags to strict booleans."
      };
    }
    var authKeys = Object.keys(rawAuthMap);
    for (var j = 0; j < authKeys.length; j++) {
      var key = authKeys[j];
      var val = rawAuthMap[key];
      if (typeof val !== "boolean") {
        return {
          valid: false,
          message: "Validation failed: reuseAuthorizedByTag['" + key + "'] must be a strict boolean (true/false)."
        };
      }
      reuseAuthorizedByTag[key.trim().replace(/^#/, "")] = val === true;
    }
  }
  for (var t = 0; t < hashtags.length; t++) {
    var tag = hashtags[t];
    if (!Object.prototype.hasOwnProperty.call(reuseAuthorizedByTag, tag)) {
      reuseAuthorizedByTag[tag] = false;
    }
  }

  if (
    payload.allowAllHashtagCandidatesWhenTargetAuthorized !== undefined &&
    typeof payload.allowAllHashtagCandidatesWhenTargetAuthorized !== "boolean"
  ) {
    return {
      valid: false,
      message: "Validation failed: allowAllHashtagCandidatesWhenTargetAuthorized must be a strict boolean (true/false)."
    };
  }
  var allowAllHashtagCandidatesWhenTargetAuthorized =
    payload.allowAllHashtagCandidatesWhenTargetAuthorized === true;

  var creatorsRes = extractNormalizedStringSet(payload.authorizedCreators, function (c) {
    return c.replace(/^@/, "").toLowerCase();
  });
  if (!creatorsRes.valid) {
    return {
      valid: false,
      message: "Validation failed: authorizedCreators must be an array of strings."
    };
  }

  var ownedRes = extractNormalizedStringSet(payload.ownedAccounts, function (c) {
    return c.replace(/^@/, "").toLowerCase();
  });
  if (!ownedRes.valid) {
    return {
      valid: false,
      message: "Validation failed: ownedAccounts must be an array of strings."
    };
  }

  if (
    payload.authorizedCandidatePks !== undefined &&
    (!Array.isArray(payload.authorizedCandidatePks) ||
      payload.authorizedCandidatePks.some(function (pk) { return typeof pk !== "string"; }))
  ) {
    return {
      valid: false,
      message: "Validation failed: authorizedCandidatePks must be an array of strings."
    };
  }

  var authorizedCandidatePks = [];
  if (Array.isArray(payload.authorizedCandidatePks)) {
    for (var pIdx = 0; pIdx < payload.authorizedCandidatePks.length; pIdx++) {
      var pkTrimmed = payload.authorizedCandidatePks[pIdx].trim();
      if (RILOT_REGEX.REEL_PK.test(pkTrimmed) && authorizedCandidatePks.indexOf(pkTrimmed) === -1) {
        authorizedCandidatePks.push(pkTrimmed);
      }
    }
  }

  var preferYtdlp = payload.preferYtdlpWithInstagrapiFallback !== undefined
    ? Boolean(payload.preferYtdlpWithInstagrapiFallback)
    : (payload.preferYtdlpFallback !== undefined ? Boolean(payload.preferYtdlpFallback) : true);

  return {
    valid: true,
    config: {
      hashtags: hashtags,
      activeHashtags: hashtags,
      categoriesByTag: payload.categoriesByTag && typeof payload.categoriesByTag === "object" ? payload.categoriesByTag : {},
      reuseAuthorizedByTag: reuseAuthorizedByTag,
      allowAllHashtagCandidatesWhenTargetAuthorized: allowAllHashtagCandidatesWhenTargetAuthorized,
      authorizedCreators: creatorsRes.values,
      ownedAccounts: ownedRes.values,
      authorizedCandidatePks: authorizedCandidatePks,
      candidateCollectionLimitPerTag: Math.min(100, Math.max(5, Number(payload.candidateCollectionLimitPerTag || 30))),
      qualifyingPoolSize: Math.max(2, Number(payload.qualifyingPoolSize || 10)),
      contentSource: String(payload.contentSource || "RECENT_AND_TOP"),
      selectionStrategy: String(payload.selectionStrategy || "QUALIFYING_POOL_WEIGHTED"),
      minViewCountThreshold: Math.max(0, Number(payload.minViewCountThreshold || 25000)),
      minLikeToViewRatioPercent: Math.max(0, Number(payload.minLikeToViewRatioPercent || 3.2)),
      minDurationSeconds: Math.max(3, Number(payload.minDurationSeconds || 6)),
      maxDurationSeconds: Math.min(180, Number(payload.maxDurationSeconds || 60)),
      recencyWeightPercent: Number(payload.recencyWeightPercent),
      engagementWeightPercent: Number(payload.engagementWeightPercent),
      diversityWeightPercent: Number(payload.diversityWeightPercent),
      excludePreviouslyProcessed: Boolean(payload.excludePreviouslyProcessed === undefined ? true : payload.excludePreviouslyProcessed),
      avoidSameCreatorInWindow: Boolean(payload.avoidSameCreatorInWindow === undefined ? true : payload.avoidSameCreatorInWindow),
      preferYtdlpWithInstagrapiFallback: preferYtdlp,
      preferYtdlpFallback: preferYtdlp
    }
  };
}

function validateScheduleConfigPayload(payload, fallbackPostingWindows) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return { valid: false, message: "Validation failed: schedule config payload must be an object." };
  }
  if (payload.timezoneId !== "Asia/Kolkata") {
    return {
      valid: false,
      message: "Schedule timezoneId must be 'Asia/Kolkata'."
    };
  }
  var maxPerCycle = Number(payload.maxPostsPerCycle || 2);
  var dailyLimit = Number(payload.dailyPostingLimit || 6);
  if (maxPerCycle < 1 || dailyLimit < maxPerCycle) {
    return {
      valid: false,
      message: "Invalid schedule limits: dailyPostingLimit must be >= maxPostsPerCycle >= 1."
    };
  }

  return {
    valid: true,
    config: {
      timezoneId: "Asia/Kolkata",
      postIntervalHours: Math.max(1, Number(payload.postIntervalHours || 6)),
      maxPostsPerCycle: maxPerCycle,
      dailyPostingLimit: dailyLimit,
      delayBetweenPostsSeconds: Math.max(15, Number(payload.delayBetweenPostsSeconds || 60)),
      dryRunMode: Boolean(payload.dryRunMode === undefined ? true : payload.dryRunMode),
      autoPublishEnabled: Boolean(payload.autoPublishEnabled === undefined ? false : payload.autoPublishEnabled),
      postingWindows: Array.isArray(payload.postingWindows) ? payload.postingWindows : (fallbackPostingWindows || [])
    }
  };
}

function validateCaptionsPayload(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return { valid: false, message: "Validation failed: captions payload must be an object." };
  }
  var captions = Array.isArray(payload.captions) ? payload.captions : [];
  var normalized = [];
  for (var i = 0; i < captions.length; i++) {
    var c = captions[i];
    if (!c || typeof c !== "object" || !String(c.title || "").trim() || !String(c.body || "").trim()) {
      return {
        valid: false,
        message: "All caption templates must have a non-empty title and body."
      };
    }
    normalized.push({
      id: Number(c.id || i + 1),
      title: String(c.title).trim(),
      body: String(c.body).trim(),
      category: String(c.category || "VIRAL_COPYPASTA"),
      isAbsurdCopypasta: Boolean(c.isAbsurdCopypasta),
      hashtagTemplate: String(c.hashtagTemplate || "#reels #viral"),
      enabled: c.enabled === undefined ? true : Boolean(c.enabled),
      usageCount: Number(c.usageCount || 0),
      lastUsedEpochMs: c.lastUsedEpochMs ? Number(c.lastUsedEpochMs) : null,
      isFavorite: Boolean(c.isFavorite)
    });
  }
  return {
    valid: true,
    captionsPayload: {
      captionRewriteMode: String(payload.captionRewriteMode || "FIXED_LIBRARY_RANDOM"),
      captions: normalized
    }
  };
}

function validateGithubDispatchConfig(scriptProps) {
  var props = scriptProps || {};
  var rawOwner = String(props.GITHUB_OWNER || "").trim();
  var rawRepo = String(props.GITHUB_REPO || "").trim();
  var rawBranch = String(props.GITHUB_BRANCH || "").trim();
  var rawWorkflow = String(props.GITHUB_WORKFLOW_FILE || "").trim();

  if (!rawOwner || !rawRepo || !rawBranch || !rawWorkflow) {
    return {
      valid: false,
      error: "Missing required GitHub dispatch configuration in Script Properties: GITHUB_OWNER, GITHUB_REPO, GITHUB_BRANCH, and GITHUB_WORKFLOW_FILE must all be explicitly configured."
    };
  }

  var forbidden = RILOT_CONSTANTS.FORBIDDEN_PLACEHOLDER_TARGETS;
  var slugLower = (rawOwner + "/" + rawRepo).toLowerCase();
  if (
    forbidden.indexOf(rawOwner.toLowerCase()) !== -1 ||
    forbidden.indexOf(rawRepo.toLowerCase()) !== -1 ||
    forbidden.indexOf(slugLower) !== -1
  ) {
    return {
      valid: false,
      error: "Invalid GitHub dispatch target '" + rawOwner + "/" + rawRepo + "': placeholder or upstream fallback repositories are not permitted."
    };
  }

  if (!RILOT_REGEX.GITHUB_OWNER.test(rawOwner)) {
    return {
      valid: false,
      error: "Malformed GITHUB_OWNER '" + rawOwner + "'."
    };
  }

  if (
    rawRepo.indexOf("/") !== -1 ||
    rawRepo === "." ||
    rawRepo === ".." ||
    !RILOT_REGEX.GITHUB_REPO_NAME.test(rawRepo)
  ) {
    return {
      valid: false,
      error: "Malformed GITHUB_REPO '" + rawRepo + "': must be a repository name without slashes."
    };
  }

  if (!RILOT_REGEX.GITHUB_BRANCH.test(rawBranch)) {
    return {
      valid: false,
      error: "Malformed GITHUB_BRANCH '" + rawBranch + "'."
    };
  }

  if (RILOT_CONSTANTS.APPROVED_WORKFLOW_FILES.indexOf(rawWorkflow) === -1) {
    return {
      valid: false,
      error: "Unapproved GITHUB_WORKFLOW_FILE '" + rawWorkflow + "': only 'insta-automation-cycle.yml' is permitted."
    };
  }

  return {
    valid: true,
    owner: rawOwner,
    repo: rawRepo,
    branch: rawBranch,
    workflow: rawWorkflow
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    RILOT_CONSTANTS: RILOT_CONSTANTS,
    RILOT_REGEX: RILOT_REGEX,
    formatIstTimestamp: formatIstTimestamp,
    redactSensitiveValues: redactSensitiveValues,
    detectClientDispatchOverrideAttempt: detectClientDispatchOverrideAttempt,
    validateRpcEnvelope: validateRpcEnvelope,
    evaluateCandidateAuthorization: evaluateCandidateAuthorization,
    validateDiscoveryConfigPayload: validateDiscoveryConfigPayload,
    validateScheduleConfigPayload: validateScheduleConfigPayload,
    validateCaptionsPayload: validateCaptionsPayload,
    validateGithubDispatchConfig: validateGithubDispatchConfig
  };
}
