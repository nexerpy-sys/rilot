/**
 * Rilot Google Apps Script Backend — EngineApi.gs
 *
 * Implements authenticated engine.* RPC actions for the GitHub Actions Python runner:
 *   - engine.getConfig
 *   - engine.acquireCycle
 *   - engine.getRetryTarget
 *   - engine.heartbeat
 *   - engine.report
 */

function handleEngineAction(action, payload, storage, nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var seed = createDefaultBackendSeed(tsMs);

  if (action === "engine.getConfig") {
    var status = storage.getSetting("status", seed.status);
    var discoveryConfig = storage.getSetting("discoveryConfig", seed.discoveryConfig);
    var scheduleConfig = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    var captionsPayload = storage.getCaptionsPayload();
    var postedPks = storage.getAllProcessedPks("PUBLISHED");
    var ambiguousPks = storage.getAllProcessedPks("IN_FLIGHT_AMBIGUOUS");
    var activityRecords = storage.getActivityRecords(100);

    var authMap =
      discoveryConfig.reuseAuthorizedByTag &&
      typeof discoveryConfig.reuseAuthorizedByTag === "object" &&
      !Array.isArray(discoveryConfig.reuseAuthorizedByTag)
        ? discoveryConfig.reuseAuthorizedByTag
        : {};
    var allowAllBulk = discoveryConfig.allowAllHashtagCandidatesWhenTargetAuthorized === true;

    var rawTags = Array.isArray(discoveryConfig.hashtags) ? discoveryConfig.hashtags : [];
    var hashtags = [];
    for (var i = 0; i < rawTags.length; i++) {
      var tag = String(rawTags[i] || "").trim().replace(/^#/, "");
      if (!tag) continue;
      hashtags.push({
        tag: tag,
        category:
          (discoveryConfig.categoriesByTag && discoveryConfig.categoriesByTag[tag]) ||
          "General",
        isEnabled: true,
        candidateLimitPerRun: Number(discoveryConfig.candidateCollectionLimitPerTag || 30),
        minViewsThreshold: Number(discoveryConfig.minViewCountThreshold || 25000),
        priorityWeight: 1.0,
        reuseAuthorized: authMap[tag] === true,
        allowAllHashtagCandidatesWhenTargetAuthorized: allowAllBulk
      });
    }

    var enabledCaptions = [];
    var allCaps = Array.isArray(captionsPayload.captions) ? captionsPayload.captions : [];
    for (var cIdx = 0; cIdx < allCaps.length; cIdx++) {
      if (allCaps[cIdx].enabled !== false) {
        enabledCaptions.push(allCaps[cIdx]);
      }
    }

    var safeDiscoveryConfig = JSON.parse(JSON.stringify(discoveryConfig));
    safeDiscoveryConfig.allowAllHashtagCandidatesWhenTargetAuthorized = allowAllBulk;

    return {
      ok: true,
      code: "OK",
      message: "Retrieved active engine configuration from Google Sheets.",
      data: {
        runState: status.runState,
        discoveryConfig: safeDiscoveryConfig,
        hashtags: hashtags,
        scheduleConfig: scheduleConfig,
        captionRewriteMode: captionsPayload.captionRewriteMode,
        captions: enabledCaptions,
        postedPks: postedPks,
        ambiguousPks: ambiguousPks,
        activityRecords: activityRecords,
        serverTimestampIst: nowIst
      }
    };
  }

  if (action === "engine.acquireCycle") {
    var requestedOwner = typeof payload.lockOwnerId === "string" && payload.lockOwnerId.trim()
      ? payload.lockOwnerId.trim()
      : ("cron_" + tsMs + "_" + Math.floor(Math.random() * 1000000));
    if (!RILOT_REGEX.LOCK_OWNER_ID.test(requestedOwner)) {
      return {
        ok: false,
        code: "INVALID_LOCK_OWNER_ID",
        message: "Invalid lockOwnerId format for engine.acquireCycle."
      };
    }

    var activeLock = storage.getActiveCycleLock(RILOT_CONSTANTS.CYCLE_LOCK_NAME, tsMs);
    if (activeLock && activeLock.ownerId === requestedOwner) {
      storage.extendCycleLockHeartbeat(RILOT_CONSTANTS.CYCLE_LOCK_NAME, requestedOwner, tsMs);
      return {
        ok: true,
        code: "LOCK_VERIFIED",
        message: "Verified existing cycle lock ownership.",
        data: {
          acquired: true,
          lockOwnerId: requestedOwner,
          expiresAtMs: activeLock.expiresAt
        }
      };
    }

    var command = String(payload.command || "SCHEDULED_CRON").trim();
    var lockRes = storage.acquireCycleLock(
      RILOT_CONSTANTS.CYCLE_LOCK_NAME,
      requestedOwner,
      command,
      tsMs
    );
    if (!lockRes.acquired) {
      return {
        ok: false,
        code: "CYCLE_ALREADY_LOCKED",
        message: "Another automation cycle is currently active.",
        data: {
          acquired: false,
          holderOwnerId: lockRes.holder ? lockRes.holder.ownerId : null
        }
      };
    }

    storage.updateCycleDispatchStatus(
      RILOT_CONSTANTS.CYCLE_LOCK_NAME,
      requestedOwner,
      "RUNNING",
      "Acquired by Python engine runner",
      tsMs
    );
    return {
      ok: true,
      code: "LOCK_ACQUIRED",
      message: "Cycle lock acquired by engine.",
      data: {
        acquired: true,
        lockOwnerId: requestedOwner,
        expiresAtMs: lockRes.expiresAt
      }
    };
  }

  if (action === "engine.heartbeat") {
    var hbOwner = typeof payload.lockOwnerId === "string" ? payload.lockOwnerId.trim() : "";
    if (!hbOwner || !RILOT_REGEX.LOCK_OWNER_ID.test(hbOwner)) {
      return {
        ok: false,
        code: "INVALID_LOCK_OWNER_ID",
        message: "Valid lockOwnerId is required for engine.heartbeat."
      };
    }
    var extended = storage.extendCycleLockHeartbeat(RILOT_CONSTANTS.CYCLE_LOCK_NAME, hbOwner, tsMs);
    if (!extended) {
      return {
        ok: false,
        code: "LOCK_NOT_OWNED",
        message: "No active cycle lock matching lockOwnerId '" + hbOwner + "'."
      };
    }
    return {
      ok: true,
      code: "HEARTBEAT_OK",
      message: "Extended active cycle lock TTL.",
      data: {
        lockOwnerId: hbOwner,
        serverTimestampIst: nowIst
      }
    };
  }

  if (action === "engine.getRetryTarget") {
    var reelPk = typeof payload.reelPk === "string" ? payload.reelPk.trim() : "";
    if (!RILOT_REGEX.REEL_PK.test(reelPk)) {
      return {
        ok: false,
        code: "INVALID_REEL_PK",
        message: "Valid reelPk is required for engine.getRetryTarget."
      };
    }
    var record = storage.getActivityByPk(reelPk);
    var uploadState = storage.getProcessedItemState(reelPk);
    var discCfg = storage.getSetting("discoveryConfig", seed.discoveryConfig);
    if (!record) {
      return {
        ok: false,
        code: "TARGET_NOT_FOUND",
        message: "Reel PK '" + reelPk + "' was not found in persisted Activity records."
      };
    }
    var authEval = evaluateCandidateAuthorization(record, discCfg);
    return {
      ok: true,
      code: "OK",
      message: "Retrieved retry target record.",
      data: {
        record: record,
        uploadState: uploadState,
        currentlyAuthorized: authEval.authorized,
        authorizationSource: authEval.source,
        authorizationReason: authEval.reason,
        serverTimestampIst: nowIst
      }
    };
  }

  if (action === "engine.report") {
    var report = payload || {};
    if (Array.isArray(report.postedPks) && report.postedPks.length > 0) {
      storage.upsertProcessedItems(report.postedPks, "PUBLISHED", tsMs);
    }
    if (Array.isArray(report.ambiguousPks) && report.ambiguousPks.length > 0) {
      storage.upsertProcessedItems(report.ambiguousPks, "IN_FLIGHT_AMBIGUOUS", tsMs);
    }
    if (Array.isArray(report.activityRecords) && report.activityRecords.length > 0) {
      var detectedAmbiguous = [];
      for (var rIdx = 0; rIdx < report.activityRecords.length; rIdx++) {
        var rec = report.activityRecords[rIdx];
        if (
          rec &&
          rec.reelPk &&
          ((rec.failureReason && String(rec.failureReason).indexOf("IN_FLIGHT_AMBIGUOUS") !== -1) ||
            rec.outcomeStatus === "IN_FLIGHT_AMBIGUOUS")
        ) {
          detectedAmbiguous.push(String(rec.reelPk).trim());
        }
      }
      if (detectedAmbiguous.length > 0) {
        storage.upsertProcessedItems(detectedAmbiguous, "IN_FLIGHT_AMBIGUOUS", tsMs);
      }
      storage.upsertActivityRecords(report.activityRecords, tsMs);
    }

    var currentStatus = storage.getSetting("status", seed.status);
    var currentSched = storage.getSetting("scheduleConfig", seed.scheduleConfig);
    var activeCycleLock = storage.getActiveCycleLock(RILOT_CONSTANTS.CYCLE_LOCK_NAME, tsMs);

    var reportedOwnerId =
      typeof report.lockOwnerId === "string" && report.lockOwnerId.trim()
        ? report.lockOwnerId.trim()
        : null;

    // 1. If a report supplies an explicit lockOwnerId that does NOT match the current active lock
    //    (or does not match the latest dispatched lock when unlocked), treat it as a stale/mismatched
    //    report: never release the active lock and never overwrite current lock ownership or runState.
    if (reportedOwnerId) {
      var matchesActiveLock = Boolean(activeCycleLock && reportedOwnerId === activeCycleLock.ownerId);
      var matchesLastDispatchedWhenUnlocked = Boolean(
        !activeCycleLock &&
          currentStatus.lastDispatchedLockOwnerId &&
          reportedOwnerId === currentStatus.lastDispatchedLockOwnerId
      );

      if (!matchesActiveLock && !matchesLastDispatchedWhenUnlocked) {
        return {
          ok: true,
          code: "STALE_REPORT_IGNORED",
          message: "Stale or mismatched lockOwnerId ignored; active cycle lock and ownership preserved.",
          data: {
            accepted: true,
            lockReleased: false,
            staleReportIgnored: true,
            serverTimestampIst: nowIst
          }
        };
      }
    }

    // 2. If a lock is currently active and the report has NO lockOwnerId (e.g., scheduled cron job
    //    or unowned report), NEVER release the active lock and NEVER overwrite active lock ownership
    //    fields or RUNNING_CYCLE runState.
    var lockReleased = false;
    if (activeCycleLock) {
      if (reportedOwnerId && reportedOwnerId === activeCycleLock.ownerId) {
        lockReleased = storage.releaseCycleLock(
          RILOT_CONSTANTS.CYCLE_LOCK_NAME,
          activeCycleLock.ownerId,
          "RELEASED",
          tsMs
        );
      } else {
        currentStatus.candidatesDiscovered =
          Number(currentStatus.candidatesDiscovered || 0) + Number(report.candidatesDiscovered || 0);
        currentStatus.videosProcessed =
          Number(currentStatus.videosProcessed || 0) + Number(report.videosProcessed || 0);
        currentStatus.successfulPosts =
          Number(currentStatus.successfulPosts || 0) + Number(report.successfulPosts || 0);
        currentStatus.duplicateSkips =
          Number(currentStatus.duplicateSkips || 0) + Number(report.duplicateSkips || 0);
        currentStatus.updatedAtIst = nowIst;
        storage.setSetting("status", currentStatus, tsMs);
        return {
          ok: true,
          code: "REPORT_INGESTED_LOCK_PRESERVED",
          message: "Report without matching lockOwnerId ingested without releasing active cycle lock.",
          data: {
            accepted: true,
            lockReleased: false,
            staleReportIgnored: false,
            serverTimestampIst: nowIst
          }
        };
      }
    }

    currentStatus.runState =
      report.runState || (currentSched.dryRunMode ? "DRY_RUN" : "ENABLED");
    currentStatus.authStatus = report.authStatus || "OK";
    if (report.lastSuccessfulRunIst) {
      currentStatus.lastSuccessfulRunIst = report.lastSuccessfulRunIst;
    }
    if (report.nextScheduledRunIst) {
      currentStatus.nextScheduledRunIst = report.nextScheduledRunIst;
    }
    currentStatus.candidatesDiscovered =
      Number(currentStatus.candidatesDiscovered || 0) + Number(report.candidatesDiscovered || 0);
    currentStatus.qualifyingPoolCount = Number(
      report.qualifyingPoolCount !== undefined && report.qualifyingPoolCount !== null
        ? report.qualifyingPoolCount
        : currentStatus.qualifyingPoolCount
    );
    currentStatus.videosProcessed =
      Number(currentStatus.videosProcessed || 0) + Number(report.videosProcessed || 0);
    currentStatus.successfulPosts =
      Number(currentStatus.successfulPosts || 0) + Number(report.successfulPosts || 0);
    currentStatus.duplicateSkips =
      Number(currentStatus.duplicateSkips || 0) + Number(report.duplicateSkips || 0);
    currentStatus.errorsCount = Number(
      report.errorsCount !== undefined && report.errorsCount !== null
        ? report.errorsCount
        : currentStatus.errorsCount
    );
    currentStatus.workflowConclusion = report.workflowConclusion || "completed";
    currentStatus.activeCycleLockOwnerId = null;
    currentStatus.activeCycleLockUntilMs = 0;
    currentStatus.updatedAtIst = nowIst;

    storage.setSetting("status", currentStatus, tsMs);
    return {
      ok: true,
      code: "REPORT_INGESTED",
      message: "Cycle report ingested and persisted to Google Sheets.",
      data: {
        accepted: true,
        lockReleased: lockReleased,
        staleReportIgnored: false,
        serverTimestampIst: nowIst
      }
    };
  }

  return {
    ok: false,
    code: "UNKNOWN_ACTION",
    message: "Unsupported engine action '" + action + "'."
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    handleEngineAction: handleEngineAction
  };
}
