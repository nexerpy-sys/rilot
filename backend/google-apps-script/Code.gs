/**
 * Rilot Google Apps Script Backend — Code.gs
 *
 * Web App entry points (doPost, doGet), spreadsheet initialization helper
 * (initializeRilotSpreadsheet), and action router for app.* and engine.* actions.
 *
 * Architecture:
 *   Android Rilot App -> Google Apps Script Web App (Sheets Storage) -> GitHub Actions -> Python Engine
 */

function generateUuidV4() {
  if (typeof Utilities !== "undefined" && Utilities && typeof Utilities.getUuid === "function") {
    return Utilities.getUuid();
  }
  if (typeof crypto !== "undefined" && crypto && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
    var r = (Math.random() * 16) | 0;
    var v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function getScriptPropertiesMap(customProps) {
  if (customProps && typeof customProps === "object") {
    return customProps;
  }
  if (
    typeof PropertiesService !== "undefined" &&
    PropertiesService &&
    typeof PropertiesService.getScriptProperties === "function"
  ) {
    return PropertiesService.getScriptProperties().getProperties() || {};
  }
  return {};
}

function openConfiguredSpreadsheet(scriptProps, customSpreadsheet) {
  if (customSpreadsheet) {
    return customSpreadsheet;
  }
  var props = scriptProps || {};
  var spreadsheetId = String(props.RILOT_SPREADSHEET_ID || "").trim();
  if (
    !spreadsheetId ||
    spreadsheetId.toLowerCase().indexOf("your_spreadsheet_id") !== -1 ||
    spreadsheetId.toLowerCase().indexOf("placeholder") !== -1
  ) {
    throw new Error(
      "Missing or placeholder RILOT_SPREADSHEET_ID in Script Properties. Configure your real Google Spreadsheet ID before using the Web App."
    );
  }
  if (typeof SpreadsheetApp !== "undefined" && SpreadsheetApp && typeof SpreadsheetApp.openById === "function") {
    return SpreadsheetApp.openById(spreadsheetId);
  }
  throw new Error("SpreadsheetApp service is unavailable.");
}

/**
 * Manual initialization helper for the project owner in the Apps Script editor.
 * Creates/verifies all 8 required tabs and seeds safe default settings (dryRunMode=true,
 * autoPublishEnabled=false, fail-closed reuseAuthorized=false).
 */
function initializeRilotSpreadsheet() {
  var props = getScriptPropertiesMap();
  var ss = openConfiguredSpreadsheet(props, null);
  var storage = new SheetsStorage(ss, null);
  storage.ensureInitialized(Date.now());
  return {
    ok: true,
    spreadsheetId: String(props.RILOT_SPREADSHEET_ID || "").trim(),
    tabsInitialized: Object.keys(SHEET_SCHEMAS),
    initializedAtIst: formatIstTimestamp(Date.now())
  };
}

function buildEnvelopeResponse(ok, code, message, data, requestId, nowMs, scriptProps) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var safeMessage = redactSensitiveValues(message || "", scriptProps);
  return {
    ok: Boolean(ok),
    code: String(code || (ok ? "OK" : "ERROR")),
    message: safeMessage,
    data: data !== undefined ? data : null,
    requestId: String(requestId || "unknown"),
    serverTimestampIst: nowIst
  };
}

function createJsonContentOutput(responseObj) {
  var jsonString = JSON.stringify(responseObj);
  if (
    typeof ContentService !== "undefined" &&
    ContentService &&
    typeof ContentService.createTextOutput === "function"
  ) {
    return ContentService.createTextOutput(jsonString).setMimeType(ContentService.MimeType.JSON);
  }
  return {
    getContentText: function () { return jsonString; },
    getMimeType: function () { return "application/json"; },
    _json: responseObj
  };
}

/**
 * Web App GET entry point.
 * Rejects credential-bearing GET requests and instructs clients to use authenticated POST RPC.
 */
function doGet(e) {
  var nowMs = Date.now();
  var props = getScriptPropertiesMap();
  var params = (e && e.parameter) || {};
  if (params.authToken || params.apiKey || params.token || params.key) {
    return createJsonContentOutput(
      buildEnvelopeResponse(
        false,
        "CREDENTIALS_IN_QUERY_FORBIDDEN",
        "Authentication credentials must never be passed in URL query parameters. Use HTTP POST with a JSON RPC envelope.",
        null,
        "get_rejected",
        nowMs,
        props
      )
    );
  }
  return createJsonContentOutput(
    buildEnvelopeResponse(
      false,
      "POST_REQUIRED",
      "Rilot Google Apps Script Web App is active. All app.* and engine.* actions require an authenticated HTTP POST JSON envelope.",
      {
        service: "Rilot Google Apps Script Control Plane",
        timezone: "Asia/Kolkata"
      },
      "get_info",
      nowMs,
      props
    )
  );
}

/**
 * Web App POST entry point.
 */
function doPost(e) {
  var rawBody = e && e.postData && typeof e.postData.contents === "string"
    ? e.postData.contents
    : "";
  var queryParams = (e && e.parameter) || {};
  var resultObj = processRpcRequest(rawBody, queryParams, null);
  return createJsonContentOutput(resultObj);
}

function handleAppAction(action, payload, requestId, storage, scriptProps, fetchAdapter, nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var seed = createDefaultBackendSeed(tsMs);

  // 1. app.connectionTest
  if (action === "app.connectionTest") {
    var statusForTest = storage.getSetting("status", seed.status);
    var schedForTest = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    var ghCheck = validateGithubDispatchConfig(scriptProps);
    var targetSummary = ghCheck.valid
      ? (ghCheck.owner + "/" + ghCheck.repo + "@" + ghCheck.branch + " (" + ghCheck.workflow + ")")
      : ghCheck.error;
    return buildEnvelopeResponse(
      true,
      "CONNECTED",
      "Authenticated with Rilot Google Apps Script Web App and verified Google Sheets storage.",
      {
        connected: true,
        appsScriptReachable: true,
        spreadsheetInitialized: true,
        sheetTabsReady: [
          "Settings",
          "Captions",
          "Activity",
          "ProcessedItems",
          "Requests",
          "Cycles",
          "Nonces",
          "RateLimits"
        ],
        runState: statusForTest.runState,
        authStatus: statusForTest.authStatus || "OK",
        dryRunMode: Boolean(schedForTest.dryRunMode),
        autoPublishEnabled: Boolean(schedForTest.autoPublishEnabled),
        githubTargetConfigured: ghCheck.valid,
        githubDispatchValid: ghCheck.valid,
        githubDispatchIssues: ghCheck.valid ? [] : [ghCheck.error],
        githubTargetSummary: targetSummary,
        githubDispatchTarget: targetSummary,
        engineHeartbeatStatus: statusForTest.lastSuccessfulRunIst ? "HEALTHY_SYNCED" : "AWAITING_FIRST_SYNC",
        lastEngineSyncIst: statusForTest.lastSuccessfulRunIst || "Never",
        activeCycleLock: statusForTest.activeCycleLockOwnerId || "NONE",
        serverTimestampIst: nowIst
      },
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 2. app.getStatus
  if (action === "app.getStatus") {
    var status = storage.getSetting("status", seed.status);
    var activeLock = storage.getActiveCycleLock(RILOT_CONSTANTS.CYCLE_LOCK_NAME, tsMs);
    if (status.runState === "RUNNING_CYCLE" && !activeLock) {
      var sched = storage.getSetting("scheduleConfig", seed.scheduleConfig);
      status.runState = sched.dryRunMode ? "DRY_RUN" : "ENABLED";
      status.activeCycleLockOwnerId = null;
      status.activeCycleLockUntilMs = 0;
      storage.setSetting("status", status, tsMs);
    }
    return buildEnvelopeResponse(
      true,
      "OK",
      "Fetched current automation status from Google Sheets.",
      status,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 3. app.setControl
  if (action === "app.setControl") {
    var rawCmd = String(payload.command || "").trim().toUpperCase();
    var command = rawCmd === "PAUSE_AUTOMATION"
      ? "PAUSE"
      : (rawCmd === "RESUME_AUTOMATION" ? "RESUME" : rawCmd);
    if (["START_CYCLE", "PAUSE", "RESUME", "DRY_RUN_CYCLE"].indexOf(command) === -1) {
      return buildEnvelopeResponse(
        false,
        "INVALID_CONTROL_COMMAND",
        "Unsupported control command '" + command + "'.",
        { accepted: false, state: "UNKNOWN", serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }

    var idempotencyKey = typeof payload.idempotencyKey === "string" && payload.idempotencyKey.trim()
      ? payload.idempotencyKey.trim()
      : ("ctrl_" + requestId);
    var existingReq = storage.getRequestIdempotency(idempotencyKey);
    if (existingReq && existingReq.response) {
      if (existingReq.targetId && existingReq.targetId !== command) {
        return buildEnvelopeResponse(
          false,
          "IDEMPOTENCY_CONFLICT",
          "Idempotency key '" + idempotencyKey + "' was already used for a different control command.",
          { accepted: false, state: "FAILED", serverTimestampIst: nowIst },
          requestId,
          tsMs,
          scriptProps
        );
      }
      var replayData = JSON.parse(JSON.stringify(existingReq.response));
      replayData.idempotentReplay = true;
      replayData.serverTimestampIst = nowIst;
      return buildEnvelopeResponse(
        Boolean(replayData.accepted),
        replayData.accepted ? "IDEMPOTENT_REPLAY" : "REJECTED",
        replayData.message || "Idempotent control response replayed.",
        replayData,
        requestId,
        tsMs,
        scriptProps
      );
    }

    var curStatus = storage.getSetting("status", seed.status);
    var curSched = storage.getSetting("scheduleConfig", seed.scheduleConfig);

    if (curStatus.authStatus === "EXPIRED" && command !== "PAUSE") {
      return buildEnvelopeResponse(
        false,
        "SESSION_EXPIRED",
        "Cannot start or resume while instagrapi session is EXPIRED.",
        { accepted: false, state: "PAUSED", serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }

    if (command === "START_CYCLE" || command === "DRY_RUN_CYCLE") {
      var dispatchCfg = validateGithubDispatchConfig(scriptProps);
      if (!dispatchCfg.valid) {
        return buildEnvelopeResponse(
          false,
          "DISPATCH_CONFIG_INVALID",
          dispatchCfg.error,
          { accepted: false, state: "FAILED", serverTimestampIst: nowIst },
          requestId,
          tsMs,
          scriptProps
        );
      }
      if (!String((scriptProps || {}).GITHUB_PAT || "").trim()) {
        return buildEnvelopeResponse(
          false,
          "MISSING_GITHUB_PAT",
          "Missing GITHUB_PAT in Apps Script Script Properties; cannot dispatch GitHub Actions workflow.",
          { accepted: false, state: "FAILED", serverTimestampIst: nowIst },
          requestId,
          tsMs,
          scriptProps
        );
      }

      var lockOwnerId = generateUuidV4();
      var lockAttempt = storage.acquireCycleLock(
        RILOT_CONSTANTS.CYCLE_LOCK_NAME,
        lockOwnerId,
        command,
        tsMs
      );
      if (!lockAttempt.acquired) {
        return buildEnvelopeResponse(
          false,
          "CYCLE_ALREADY_IN_FLIGHT",
          "Concurrent execution guard: an automation cycle is already in flight.",
          { accepted: false, state: "RUNNING", serverTimestampIst: nowIst },
          requestId,
          tsMs,
          scriptProps
        );
      }

      var dryRunFlag =
        command === "DRY_RUN_CYCLE" ||
        Boolean(payload.dryRunEnforced) ||
        Boolean(curSched.dryRunMode) ||
        !Boolean(curSched.autoPublishEnabled);

      // Persist pending request record before calling external GitHub API
      storage.saveRequestIdempotency(
        idempotencyKey,
        action,
        command,
        "PENDING_DISPATCH",
        { accepted: false, state: "QUEUED", lockOwnerId: lockOwnerId },
        tsMs
      );

      // Perform GitHub workflow dispatch OUTSIDE any ScriptLock
      var ghResult = dispatchGitHubWorkflow(
        scriptProps,
        {
          command: command,
          dry_run: String(dryRunFlag),
          lock_owner_id: lockOwnerId
        },
        fetchAdapter
      );

      if (!ghResult.ok) {
        storage.releaseCycleLock(
          RILOT_CONSTANTS.CYCLE_LOCK_NAME,
          lockOwnerId,
          "FAILED_DISPATCH",
          tsMs
        );
        var failAck = {
          accepted: false,
          state: "FAILED",
          status: "DISPATCH_FAILED",
          message: "Failed to dispatch GitHub Actions workflow: " + ghResult.message,
          serverTimestampIst: nowIst
        };
        storage.saveRequestIdempotency(
          idempotencyKey,
          action,
          command,
          "FAILED_DISPATCH",
          failAck,
          tsMs
        );
        return buildEnvelopeResponse(
          false,
          ghResult.code || "GITHUB_DISPATCH_FAILED",
          failAck.message,
          failAck,
          requestId,
          tsMs,
          scriptProps
        );
      }

      storage.updateCycleDispatchStatus(
        RILOT_CONSTANTS.CYCLE_LOCK_NAME,
        lockOwnerId,
        "DISPATCHED",
        ghResult.note,
        tsMs
      );

      curStatus.runState = "RUNNING_CYCLE";
      curStatus.activeCycleLockOwnerId = lockOwnerId;
      curStatus.lastDispatchedLockOwnerId = lockOwnerId;
      curStatus.activeCycleLockUntilMs = lockAttempt.expiresAt;
      curStatus.workflowConclusion = dryRunFlag
        ? "dispatched (dry-run cycle)"
        : "dispatched (live cycle)";
      curStatus.updatedAtIst = nowIst;
      storage.setSetting("status", curStatus, tsMs);

      var successAck = {
        accepted: true,
        state: "DISPATCHED",
        status: "DISPATCHED",
        lockOwnerId: lockOwnerId,
        message: ghResult.note + " (Dry-Run=" + dryRunFlag + ")",
        serverTimestampIst: nowIst
      };
      storage.saveRequestIdempotency(
        idempotencyKey,
        action,
        command,
        "DISPATCHED",
        successAck,
        tsMs
      );
      return buildEnvelopeResponse(
        true,
        "DISPATCHED",
        successAck.message,
        successAck,
        requestId,
        tsMs,
        scriptProps
      );
    }

    if (command === "PAUSE") {
      var activeLockPause = storage.getActiveCycleLock(RILOT_CONSTANTS.CYCLE_LOCK_NAME, tsMs);
      if (activeLockPause && payload.lockOwnerId && payload.lockOwnerId === activeLockPause.ownerId) {
        storage.releaseCycleLock(RILOT_CONSTANTS.CYCLE_LOCK_NAME, activeLockPause.ownerId, "RELEASED", tsMs);
      }
      curStatus.runState = "PAUSED";
      curStatus.updatedAtIst = nowIst;
      storage.setSetting("status", curStatus, tsMs);
      var pauseAck = {
        accepted: true,
        state: "PAUSED",
        status: "PAUSED",
        message: "Automation schedule paused in Google Sheets.",
        serverTimestampIst: nowIst
      };
      storage.saveRequestIdempotency(idempotencyKey, action, command, "PAUSED", pauseAck, tsMs);
      return buildEnvelopeResponse(
        true,
        "PAUSED",
        pauseAck.message,
        pauseAck,
        requestId,
        tsMs,
        scriptProps
      );
    }

    if (command === "RESUME") {
      var isDryResume = Boolean(curSched.dryRunMode) || !Boolean(curSched.autoPublishEnabled);
      curStatus.runState = isDryResume ? "DRY_RUN" : "ENABLED";
      curStatus.updatedAtIst = nowIst;
      storage.setSetting("status", curStatus, tsMs);
      var resumeAck = {
        accepted: true,
        state: curStatus.runState,
        status: curStatus.runState,
        message: "Automation schedule resumed (" + curStatus.runState + ").",
        serverTimestampIst: nowIst
      };
      storage.saveRequestIdempotency(idempotencyKey, action, command, curStatus.runState, resumeAck, tsMs);
      return buildEnvelopeResponse(
        true,
        "OK",
        resumeAck.message,
        resumeAck,
        requestId,
        tsMs,
        scriptProps
      );
    }
  }

  // 4. app.getDiscoveryConfig
  if (action === "app.getDiscoveryConfig") {
    var discCfg = storage.getSetting("discoveryConfig", seed.discoveryConfig);
    return buildEnvelopeResponse(
      true,
      "OK",
      "Fetched discovery configuration.",
      discCfg,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 5. app.updateDiscoveryConfig
  if (action === "app.updateDiscoveryConfig") {
    var discValidation = validateDiscoveryConfigPayload(payload);
    if (!discValidation.valid) {
      return buildEnvelopeResponse(
        false,
        "VALIDATION_FAILED",
        discValidation.message,
        { accepted: false, message: discValidation.message, serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }
    var updatedDisc = discValidation.config;
    storage.setSetting("discoveryConfig", updatedDisc, tsMs);
    var discAck = {
      accepted: true,
      message: "Saved discovery config (" + updatedDisc.hashtags.length + " hashtags, pool=" + updatedDisc.qualifyingPoolSize + ").",
      serverTimestampIst: nowIst
    };
    return buildEnvelopeResponse(
      true,
      "OK",
      discAck.message,
      discAck,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 6. app.getScheduleConfig
  if (action === "app.getScheduleConfig") {
    var schedCfg = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    return buildEnvelopeResponse(
      true,
      "OK",
      "Fetched schedule configuration.",
      schedCfg,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 7. app.updateScheduleConfig
  if (action === "app.updateScheduleConfig") {
    var existingSched = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    var schedValidation = validateScheduleConfigPayload(payload, existingSched.postingWindows);
    if (!schedValidation.valid) {
      return buildEnvelopeResponse(
        false,
        "VALIDATION_FAILED",
        schedValidation.message,
        { accepted: false, message: schedValidation.message, serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }
    var updatedSched = schedValidation.config;
    storage.setSetting("scheduleConfig", updatedSched, tsMs);

    var statusToSync = storage.getSetting("status", seed.status);
    if (statusToSync.runState !== "PAUSED" && statusToSync.runState !== "RUNNING_CYCLE") {
      statusToSync.runState =
        updatedSched.dryRunMode || !updatedSched.autoPublishEnabled ? "DRY_RUN" : "ENABLED";
      statusToSync.updatedAtIst = nowIst;
      storage.setSetting("status", statusToSync, tsMs);
    }

    var schedAck = {
      accepted: true,
      message: "Saved Asia/Kolkata schedule (interval=" + updatedSched.postIntervalHours + "h, dryRun=" + updatedSched.dryRunMode + ").",
      serverTimestampIst: nowIst
    };
    return buildEnvelopeResponse(
      true,
      "OK",
      schedAck.message,
      schedAck,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 8. app.getCaptions
  if (action === "app.getCaptions") {
    var captionsPayload = storage.getCaptionsPayload();
    return buildEnvelopeResponse(
      true,
      "OK",
      "Fetched caption library.",
      captionsPayload,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 9. app.updateCaptions
  if (action === "app.updateCaptions") {
    var capValidation = validateCaptionsPayload(payload);
    if (!capValidation.valid) {
      return buildEnvelopeResponse(
        false,
        "VALIDATION_FAILED",
        capValidation.message,
        { accepted: false, message: capValidation.message, serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }
    var validCaps = capValidation.captionsPayload;
    storage.saveCaptionsPayload(validCaps, tsMs);
    var capAck = {
      accepted: true,
      message: "Synced " + validCaps.captions.length + " caption template(s) (" + validCaps.captionRewriteMode + ").",
      serverTimestampIst: nowIst
    };
    return buildEnvelopeResponse(
      true,
      "OK",
      capAck.message,
      capAck,
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 10. app.getActivity
  if (action === "app.getActivity") {
    var limit = payload && payload.limit ? Number(payload.limit) : 50;
    var cursor = payload && payload.cursorEpochMs ? Number(payload.cursorEpochMs) : null;
    var records = storage.getActivityRecords(limit, cursor);
    var nextCursor = records.length > 0 ? records[records.length - 1].timestampEpochMs : null;
    return buildEnvelopeResponse(
      true,
      "OK",
      "Fetched activity records.",
      {
        records: records,
        nextCursorEpochMs: nextCursor,
        serverTimestampIst: nowIst
      },
      requestId,
      tsMs,
      scriptProps
    );
  }

  // 11. app.safeRetry
  if (action === "app.safeRetry") {
    var reelPk = String(payload.reelPk || "").trim();
    if (!RILOT_REGEX.REEL_PK.test(reelPk)) {
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        "Invalid Reel PK identifier '" + reelPk + "'.",
        { accepted: false, status: "RETRY_REJECTED", targetReelPk: reelPk, serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }

    var idempotencyToken = String(payload.idempotencyToken || "").trim();
    if (!RILOT_REGEX.IDEMPOTENCY_TOKEN.test(idempotencyToken)) {
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        "Safe Retry requires a valid idempotencyToken.",
        { accepted: false, status: "RETRY_REJECTED", targetReelPk: reelPk, serverTimestampIst: nowIst },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 0. Check Requests idempotency store first
    var priorIdem = storage.getRequestIdempotency(idempotencyToken);
    if (priorIdem && priorIdem.response) {
      if (priorIdem.targetId !== reelPk) {
        return buildEnvelopeResponse(
          false,
          "IDEMPOTENCY_CONFLICT",
          "Idempotency token '" + idempotencyToken + "' was already used for a different Reel PK.",
          {
            accepted: false,
            status: "RETRY_REJECTED",
            targetReelPk: reelPk,
            message: "Idempotency token '" + idempotencyToken + "' was already used for a different Reel PK.",
            serverTimestampIst: nowIst
          },
          requestId,
          tsMs,
          scriptProps
        );
      }
      var replayObj = JSON.parse(JSON.stringify(priorIdem.response));
      replayObj.idempotentReplay = true;
      replayObj.serverTimestampIst = nowIst;
      return buildEnvelopeResponse(
        Boolean(replayObj.accepted),
        replayObj.status || "RETRY_DISPATCHED",
        replayObj.message || "Idempotent retry response replayed.",
        replayObj,
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 1. Check ProcessedItems state (PUBLISHED or IN_FLIGHT_AMBIGUOUS)
    var processedState = storage.getProcessedItemState(reelPk);
    if (processedState === "IN_FLIGHT_AMBIGUOUS") {
      var ambMsg = "Ambiguous upload guard blocked retry: Reel " + reelPk + " is in state IN_FLIGHT_AMBIGUOUS from an uncertain prior upload.";
      return buildEnvelopeResponse(
        false,
        "RETRY_UNRESOLVED_AMBIGUOUS",
        ambMsg,
        {
          accepted: false,
          status: "RETRY_UNRESOLVED_AMBIGUOUS",
          targetReelPk: reelPk,
          message: ambMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }
    if (processedState === "PUBLISHED") {
      var pubMsg = "Safe Retry Guard blocked retry: Reel " + reelPk + " is already recorded as PUBLISHED in ProcessedItems.";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        pubMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: pubMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 2. Verify activity record exists in backend persisted Activity sheet (independent of page size)
    var existing = storage.getActivityByPk(reelPk);
    if (!existing) {
      var notFoundMsg = "Candidate PK " + reelPk + " not found in backend activity history.";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        notFoundMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: notFoundMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 3. Preserve IN_FLIGHT_AMBIGUOUS duplicate-upload protection on activity record
    if (
      existing.outcomeStatus === "IN_FLIGHT_AMBIGUOUS" ||
      (existing.failureReason && String(existing.failureReason).indexOf("IN_FLIGHT_AMBIGUOUS") !== -1)
    ) {
      var recAmbMsg = "Ambiguous upload guard: Reel " + reelPk + " requires manual inspection before retry to avoid duplicate posting.";
      return buildEnvelopeResponse(
        false,
        "RETRY_UNRESOLVED_AMBIGUOUS",
        recAmbMsg,
        {
          accepted: false,
          status: "RETRY_UNRESOLVED_AMBIGUOUS",
          targetReelPk: reelPk,
          message: recAmbMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 4. Fail-closed authorization & current policy revalidation
    var activeDiscConfig = storage.getSetting("discoveryConfig", seed.discoveryConfig);
    var authEval = evaluateCandidateAuthorization(existing, activeDiscConfig);
    if (!authEval.authorized) {
      var blockedRecord = JSON.parse(JSON.stringify(existing));
      blockedRecord.outcomeStatus = "UNAUTHORIZED_BLOCKED";
      blockedRecord.failureReason = authEval.reason;
      blockedRecord.reuseAuthorized = false;
      blockedRecord.authorizationSource = "NONE";
      blockedRecord.timestampIst = nowIst;
      blockedRecord.timestampEpochMs = tsMs;
      storage.upsertActivityRecords([blockedRecord], tsMs);

      var unauthMsg = "Safe Retry Guard blocked retry: Reel " + reelPk + " is not authorized under current policy (" + authEval.reason + ")";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        unauthMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: unauthMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 5. Permit retries ONLY for explicitly retryable failure states
    if (["DOWNLOAD_FAILED", "UPLOAD_FAILED"].indexOf(existing.outcomeStatus) === -1) {
      var nonRetryMsg = "Safe Retry Guard blocked retry: Reel " + reelPk + " has status " + existing.outcomeStatus + ".";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        nonRetryMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: nonRetryMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    if (idempotencyToken === existing.idempotencyToken) {
      var dupTokMsg = "Safe Retry requires a fresh idempotencyToken distinct from the failed attempt.";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        dupTokMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: dupTokMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 6. Validate GitHub dispatch config before acquiring lock
    var retryDispatchCfg = validateGithubDispatchConfig(scriptProps);
    if (!retryDispatchCfg.valid) {
      return buildEnvelopeResponse(
        false,
        "RETRY_FAILED",
        retryDispatchCfg.error,
        {
          accepted: false,
          status: "RETRY_FAILED",
          targetReelPk: reelPk,
          message: retryDispatchCfg.error,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }
    if (!String((scriptProps || {}).GITHUB_PAT || "").trim()) {
      var noPatMsg = "Missing GITHUB_PAT in Apps Script Script Properties; cannot dispatch targeted retry.";
      return buildEnvelopeResponse(
        false,
        "RETRY_FAILED",
        noPatMsg,
        {
          accepted: false,
          status: "RETRY_FAILED",
          targetReelPk: reelPk,
          message: noPatMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    // 7. Acquire durable cycle lock with explicit lockOwnerId
    var retryLockOwnerId = generateUuidV4();
    var retryLockAttempt = storage.acquireCycleLock(
      RILOT_CONSTANTS.CYCLE_LOCK_NAME,
      retryLockOwnerId,
      "SAFE_RETRY:" + reelPk,
      tsMs
    );
    if (!retryLockAttempt.acquired) {
      var busyMsg = "Concurrent execution guard: another cycle or retry is currently in flight.";
      return buildEnvelopeResponse(
        false,
        "RETRY_REJECTED",
        busyMsg,
        {
          accepted: false,
          status: "RETRY_REJECTED",
          targetReelPk: reelPk,
          message: busyMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    var retrySched = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    var isDryRetry =
      Boolean(payload.dryRunOnly) ||
      Boolean(retrySched.dryRunMode) ||
      !Boolean(retrySched.autoPublishEnabled);

    var ghRetryResult = dispatchGitHubWorkflow(
      scriptProps,
      {
        command: "SAFE_RETRY",
        reel_pk: reelPk,
        idempotency_token: idempotencyToken,
        lock_owner_id: retryLockOwnerId,
        dry_run: String(isDryRetry)
      },
      fetchAdapter
    );

    if (!ghRetryResult.ok) {
      storage.releaseCycleLock(
        RILOT_CONSTANTS.CYCLE_LOCK_NAME,
        retryLockOwnerId,
        "FAILED_DISPATCH",
        tsMs
      );
      var retryErrMsg = "Failed to dispatch targeted retry workflow: " + ghRetryResult.message;
      return buildEnvelopeResponse(
        false,
        "RETRY_FAILED",
        retryErrMsg,
        {
          accepted: false,
          status: "RETRY_FAILED",
          targetReelPk: reelPk,
          message: retryErrMsg,
          serverTimestampIst: nowIst
        },
        requestId,
        tsMs,
        scriptProps
      );
    }

    storage.updateCycleDispatchStatus(
      RILOT_CONSTANTS.CYCLE_LOCK_NAME,
      retryLockOwnerId,
      "DISPATCHED",
      ghRetryResult.note,
      tsMs
    );

    var updatedRetryRecord = JSON.parse(JSON.stringify(existing));
    updatedRetryRecord.outcomeStatus = "RETRY_DISPATCHED";
    updatedRetryRecord.downloaderUsed = isDryRetry
      ? "dispatched SAFE_RETRY (dry-run)"
      : "dispatched SAFE_RETRY (live)";
    updatedRetryRecord.failureReason = null;
    updatedRetryRecord.timestampIst = nowIst;
    updatedRetryRecord.timestampEpochMs = tsMs;
    updatedRetryRecord.retryCount = Number(existing.retryCount || 0) + 1;
    updatedRetryRecord.idempotencyToken = idempotencyToken;
    updatedRetryRecord.reuseAuthorized = true;
    updatedRetryRecord.authorizationSource = authEval.source;
    storage.upsertActivityRecords([updatedRetryRecord], tsMs);

    var retryStatus = storage.getSetting("status", seed.status);
    retryStatus.runState = "RUNNING_CYCLE";
    retryStatus.activeCycleLockOwnerId = retryLockOwnerId;
    retryStatus.lastDispatchedLockOwnerId = retryLockOwnerId;
    retryStatus.activeCycleLockUntilMs = retryLockAttempt.expiresAt;
    retryStatus.updatedAtIst = nowIst;
    storage.setSetting("status", retryStatus, tsMs);

    var retryResponseData = {
      accepted: true,
      state: "DISPATCHED",
      status: "RETRY_DISPATCHED",
      targetReelPk: reelPk,
      lockOwnerId: retryLockOwnerId,
      idempotencyToken: idempotencyToken,
      message: "Safe retry dispatched for Reel " + existing.shortCode + " (target PK=" + reelPk + ", token=" + idempotencyToken + ").",
      serverTimestampIst: nowIst
    };
    storage.saveRequestIdempotency(
      idempotencyToken,
      action,
      reelPk,
      "RETRY_DISPATCHED",
      retryResponseData,
      tsMs
    );

    return buildEnvelopeResponse(
      true,
      "RETRY_DISPATCHED",
      retryResponseData.message,
      retryResponseData,
      requestId,
      tsMs,
      scriptProps
    );
  }

  return buildEnvelopeResponse(
    false,
    "UNKNOWN_ACTION",
    "Unsupported app action '" + action + "'.",
    null,
    requestId,
    tsMs,
    scriptProps
  );
}

/**
 * Testable core request processor used by both doPost(e) and automated unit tests.
 */
function processRpcRequest(rawBody, queryParams, testContext) {
  var ctx = testContext || {};
  var nowMs = typeof ctx.nowMs === "number" ? ctx.nowMs : Date.now();
  var scriptProps = getScriptPropertiesMap(ctx.scriptProps);

  try {
    var envValidation = validateRpcEnvelope(rawBody, queryParams);
    if (!envValidation.valid) {
      return buildEnvelopeResponse(
        false,
        envValidation.code,
        envValidation.message,
        null,
        envValidation.requestId,
        nowMs,
        scriptProps
      );
    }

    var envelope = envValidation.envelope;
    var spreadsheet = openConfiguredSpreadsheet(scriptProps, ctx.spreadsheet);
    var storage = new SheetsStorage(spreadsheet, ctx.lockAdapter || null);

    var authResult = authenticateAndAuthorizeRequest(envelope, scriptProps, storage, nowMs);
    if (!authResult.ok) {
      return buildEnvelopeResponse(
        false,
        authResult.code,
        authResult.message,
        null,
        envelope.requestId,
        nowMs,
        scriptProps
      );
    }

    if (authResult.role === "APP") {
      return handleAppAction(
        envelope.action,
        envelope.payload,
        envelope.requestId,
        storage,
        scriptProps,
        ctx.fetchAdapter || null,
        nowMs
      );
    }

    if (authResult.role === "ENGINE") {
      var engineOutcome = handleEngineAction(
        envelope.action,
        envelope.payload,
        storage,
        nowMs
      );
      return buildEnvelopeResponse(
        engineOutcome.ok,
        engineOutcome.code,
        engineOutcome.message,
        engineOutcome.data,
        envelope.requestId,
        nowMs,
        scriptProps
      );
    }

    return buildEnvelopeResponse(
      false,
      "UNKNOWN_ACTION",
      "Unknown action '" + envelope.action + "'.",
      null,
      envelope.requestId,
      nowMs,
      scriptProps
    );
  } catch (err) {
    var errMsg = err && err.message ? err.message : String(err);
    return buildEnvelopeResponse(
      false,
      "INTERNAL_ERROR",
      errMsg,
      null,
      "unknown",
      nowMs,
      scriptProps
    );
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    generateUuidV4: generateUuidV4,
    initializeRilotSpreadsheet: initializeRilotSpreadsheet,
    buildEnvelopeResponse: buildEnvelopeResponse,
    doGet: doGet,
    doPost: doPost,
    processRpcRequest: processRpcRequest
  };
}
