/**
 * Rilot Google Apps Script Backend — Storage.gs
 *
 * Durable Google Sheets persistence layer replacing Cloudflare D1.
 * Manages 8 structured tabs:
 *   1. Settings       — active discovery, schedule, control, status, and authorization configuration
 *   2. Captions       — caption ID, title, body, category, enabled state, usage count, last-used timestamp
 *   3. Activity       — Reel PK, shortCode, URL, hashtag, metrics, outcome, authorization provenance, retry state
 *   4. ProcessedItems — processed Reel PKs and duplicate-prevention upload_state (PUBLISHED / IN_FLIGHT_AMBIGUOUS)
 *   5. Requests       — idempotency keys and request outcomes
 *   6. Cycles         — cycle ID, lock name, lock owner, command, dispatch status, timestamps
 *   7. Nonces         — request nonce, caller role, timestamp, and expiry for replay protection
 *   8. RateLimits     — caller identifier, window start, and request count
 */

var SHEET_SCHEMAS = {
  Settings: ["key", "value_json", "updated_at_ist", "updated_at_ms"],
  Captions: [
    "id",
    "title",
    "body",
    "category",
    "is_absurd_copypasta",
    "hashtag_template",
    "enabled",
    "usage_count",
    "last_used_epoch_ms",
    "is_favorite",
    "created_at_ms",
    "updated_at_ms"
  ],
  Activity: [
    "reel_pk",
    "short_code",
    "reel_url",
    "hashtag",
    "category",
    "creator_handle",
    "view_count",
    "like_count",
    "comment_count",
    "duration_seconds",
    "quality_score",
    "selection_reason",
    "downloader_used",
    "outcome_status",
    "failure_reason",
    "caption_preview",
    "timestamp_ist",
    "timestamp_epoch_ms",
    "retry_count",
    "idempotency_token",
    "reuse_authorized",
    "authorization_source"
  ],
  ProcessedItems: [
    "reel_pk",
    "hashtag",
    "original_url",
    "upload_state",
    "idempotency_token",
    "posted_epoch_ms",
    "updated_at_ist"
  ],
  Requests: [
    "idempotency_key",
    "action",
    "target_id",
    "status",
    "response_json",
    "created_at_ms",
    "updated_at_ms"
  ],
  Cycles: [
    "cycle_id",
    "lock_name",
    "owner_id",
    "command",
    "dispatch_status",
    "acquired_at_ms",
    "expires_at_ms",
    "updated_at_ist",
    "notes"
  ],
  Nonces: ["nonce", "caller_role", "timestamp_ms", "expires_at_ms"],
  RateLimits: ["caller_id", "window_start_ms", "request_count"]
};

function createDefaultBackendSeed(nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  return {
    status: {
      runState: "DRY_RUN",
      authStatus: "OK",
      lastSuccessfulRunIst: "Not run yet",
      nextScheduledRunIst: "Scheduled via GitHub Actions",
      candidatesDiscovered: 0,
      qualifyingPoolCount: 0,
      videosProcessed: 0,
      successfulPosts: 0,
      duplicateSkips: 0,
      errorsCount: 0,
      workflowConclusion: "idle (dry-run default)",
      activeCycleLockOwnerId: null,
      lastDispatchedLockOwnerId: null,
      activeCycleLockUntilMs: 0,
      updatedAtIst: nowIst
    },
    discoveryConfig: {
      hashtags: ["calisthenics", "streetworkout", "gymhumor", "fitness"],
      categoriesByTag: {
        calisthenics: "Strength & Bodyweight",
        streetworkout: "Strength & Bodyweight",
        gymhumor: "Viral Copypasta & Humor",
        fitness: "General Fitness"
      },
      reuseAuthorizedByTag: {
        calisthenics: false,
        streetworkout: false,
        gymhumor: false,
        fitness: false
      },
      allowAllHashtagCandidatesWhenTargetAuthorized: false,
      authorizedCreators: [],
      ownedAccounts: [],
      authorizedCandidatePks: [],
      candidateCollectionLimitPerTag: 30,
      qualifyingPoolSize: 10,
      contentSource: "RECENT_AND_TOP",
      selectionStrategy: "QUALIFYING_POOL_WEIGHTED",
      minViewCountThreshold: 25000,
      minLikeToViewRatioPercent: 3.2,
      minDurationSeconds: 6,
      maxDurationSeconds: 60,
      recencyWeightPercent: 35,
      engagementWeightPercent: 40,
      diversityWeightPercent: 25,
      excludePreviouslyProcessed: true,
      avoidSameCreatorInWindow: true,
      preferYtdlpWithInstagrapiFallback: true
    },
    scheduleConfig: {
      timezoneId: "Asia/Kolkata",
      postIntervalHours: 6,
      maxPostsPerCycle: 2,
      dailyPostingLimit: 6,
      delayBetweenPostsSeconds: 60,
      dryRunMode: true,
      autoPublishEnabled: false,
      postingWindows: [
        {
          id: "win_morning",
          label: "Morning Commute Peak",
          startHourIst: 8,
          startMinuteIst: 30,
          endHourIst: 11,
          endMinuteIst: 0,
          isEnabled: true
        },
        {
          id: "win_evening",
          label: "Prime Evening Engagement",
          startHourIst: 18,
          startMinuteIst: 0,
          endHourIst: 21,
          endMinuteIst: 30,
          isEnabled: true
        }
      ]
    },
    captionRewriteMode: "FIXED_LIBRARY_RANDOM",
    captions: [
      {
        id: 1,
        title: "Gym Gravity Glitch Copypasta",
        body: "I asked the gym manager to turn down the gravity by 15% today because my central nervous system was still buffering from Tuesday. Somehow the bar still moved faster than my Wi-Fi.",
        category: "VIRAL_COPYPASTA",
        isAbsurdCopypasta: true,
        hashtagTemplate: "#calisthenics #gymhumor #streetworkout #reelsviral",
        enabled: true,
        usageCount: 0,
        lastUsedEpochMs: null,
        isFavorite: true
      },
      {
        id: 2,
        title: "Discipline Over Mood",
        body: "Consistency doesn't care if you're tired, busy, or unmotivated. Show up, put the reps in, and let the results speak louder than excuses.",
        category: "MOTIVATIONAL",
        isAbsurdCopypasta: false,
        hashtagTemplate: "#fitness #discipline #mindset #workoutroutine",
        enabled: true,
        usageCount: 0,
        lastUsedEpochMs: null,
        isFavorite: false
      }
    ]
  };
}

function SheetsStorage(spreadsheet, lockAdapter) {
  this.spreadsheet = spreadsheet;
  this.lockAdapter = lockAdapter || null;
  this._initialized = false;
}

SheetsStorage.prototype.withLock = function (fn) {
  if (this.lockAdapter && typeof this.lockAdapter.withLock === "function") {
    return this.lockAdapter.withLock(fn);
  }
  if (typeof LockService !== "undefined" && LockService && typeof LockService.getScriptLock === "function") {
    var lock = LockService.getScriptLock();
    lock.waitLock(15000);
    try {
      return fn();
    } finally {
      lock.releaseLock();
    }
  }
  return fn();
};

SheetsStorage.prototype._ensureTab = function (tabName) {
  var expectedHeaders = SHEET_SCHEMAS[tabName];
  if (!expectedHeaders) {
    throw new Error("Unknown sheet tab schema: " + tabName);
  }
  var sheet = this.spreadsheet.getSheetByName(tabName);
  if (!sheet) {
    sheet = this.spreadsheet.insertSheet(tabName);
  }
  var data = sheet.getDataRange().getValues();
  var needsHeader = false;
  if (!data || data.length === 0 || (data.length === 1 && data[0].length === 1 && String(data[0][0] || "") === "")) {
    needsHeader = true;
  } else {
    var firstRow = data[0];
    for (var i = 0; i < expectedHeaders.length; i++) {
      if (String(firstRow[i] || "").trim() !== expectedHeaders[i]) {
        needsHeader = true;
        break;
      }
    }
  }
  if (needsHeader) {
    if (!data || data.length === 0 || (data.length === 1 && String(data[0][0] || "") === "")) {
      sheet.clear();
      sheet.appendRow(expectedHeaders);
    } else {
      sheet.getRange(1, 1, 1, expectedHeaders.length).setValues([expectedHeaders]);
    }
  }
  return sheet;
};

SheetsStorage.prototype.ensureInitialized = function (nowMs) {
  if (this._initialized) return;
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  this.withLock(function () {
    var tabNames = Object.keys(SHEET_SCHEMAS);
    for (var i = 0; i < tabNames.length; i++) {
      self._ensureTab(tabNames[i]);
    }
    var seed = createDefaultBackendSeed(tsMs);
    if (self._getSettingUnlocked("status", null) === null) {
      self._setSettingUnlocked("status", seed.status, tsMs);
    }
    if (self._getSettingUnlocked("discoveryConfig", null) === null) {
      self._setSettingUnlocked("discoveryConfig", seed.discoveryConfig, tsMs);
    }
    if (self._getSettingUnlocked("scheduleConfig", null) === null) {
      self._setSettingUnlocked("scheduleConfig", seed.scheduleConfig, tsMs);
    }
    if (self._getSettingUnlocked("captionRewriteMode", null) === null) {
      self._setSettingUnlocked("captionRewriteMode", seed.captionRewriteMode, tsMs);
    }
    if (self._getSettingUnlocked("schemaVersion", null) === null) {
      self._setSettingUnlocked("schemaVersion", RILOT_CONSTANTS.SCHEMA_VERSION, tsMs);
    }
    var captionsSheet = self._ensureTab("Captions");
    var capRows = captionsSheet.getDataRange().getValues();
    if (!capRows || capRows.length <= 1) {
      self._writeCaptionsUnlocked(seed.captions, tsMs);
    }
    self._initialized = true;
  });
};

SheetsStorage.prototype._getSettingUnlocked = function (key, defaultValue) {
  var sheet = this._ensureTab("Settings");
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim() === key) {
      var rawJson = String(rows[i][1] || "");
      if (!rawJson) return defaultValue;
      try {
        return JSON.parse(rawJson);
      } catch (e) {
        return defaultValue;
      }
    }
  }
  return defaultValue;
};

SheetsStorage.prototype._setSettingUnlocked = function (key, value, nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var sheet = this._ensureTab("Settings");
  var rows = sheet.getDataRange().getValues();
  var serialized = JSON.stringify(value);
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim() === key) {
      sheet.getRange(i + 1, 1, 1, 4).setValues([[key, serialized, nowIst, tsMs]]);
      return;
    }
  }
  sheet.appendRow([key, serialized, nowIst, tsMs]);
};

SheetsStorage.prototype.getSetting = function (key, defaultValue) {
  this.ensureInitialized();
  return this._getSettingUnlocked(key, defaultValue);
};

SheetsStorage.prototype.setSetting = function (key, value, nowMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  return this.withLock(function () {
    self._setSettingUnlocked(key, value, nowMs);
  });
};

SheetsStorage.prototype.checkRateLimit = function (callerId, nowMs, maxRequests, windowMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var winMs = typeof windowMs === "number" ? windowMs : RILOT_CONSTANTS.RATE_LIMIT_WINDOW_MS;
  var maxReq = typeof maxRequests === "number" ? maxRequests : RILOT_CONSTANTS.RATE_LIMIT_MAX_APP_REQUESTS;

  return this.withLock(function () {
    var sheet = self._ensureTab("RateLimits");
    var rows = sheet.getDataRange().getValues();
    for (var i = 1; i < rows.length; i++) {
      if (String(rows[i][0] || "") === callerId) {
        var winStart = Number(rows[i][1] || 0);
        var count = Number(rows[i][2] || 0);
        if (!isFinite(winStart) || tsMs - winStart >= winMs) {
          sheet.getRange(i + 1, 1, 1, 3).setValues([[callerId, tsMs, 1]]);
          return true;
        }
        if (count >= maxReq) {
          return false;
        }
        sheet.getRange(i + 1, 1, 1, 3).setValues([[callerId, winStart, count + 1]]);
        return true;
      }
    }
    sheet.appendRow([callerId, tsMs, 1]);
    return true;
  });
};

SheetsStorage.prototype.checkAndRecordNonce = function (nonce, callerRole, nowMs, ttlMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var expiryMs = tsMs + (typeof ttlMs === "number" ? ttlMs : RILOT_CONSTANTS.REPLAY_WINDOW_MS * 2);

  return this.withLock(function () {
    var sheet = self._ensureTab("Nonces");
    var rows = sheet.getDataRange().getValues();
    var keptRows = [SHEET_SCHEMAS.Nonces];
    var isDuplicate = false;

    for (var i = 1; i < rows.length; i++) {
      var existingNonce = String(rows[i][0] || "").trim();
      var existingExp = Number(rows[i][3] || 0);
      if (!existingNonce) continue;
      if (isFinite(existingExp) && existingExp >= tsMs) {
        keptRows.push(rows[i]);
        if (existingNonce === nonce) {
          isDuplicate = true;
        }
      }
    }

    if (isDuplicate) {
      return { ok: false, reason: "DUPLICATE_NONCE" };
    }

    keptRows.push([nonce, String(callerRole || "APP"), tsMs, expiryMs]);
    sheet.clear();
    sheet.getRange(1, 1, keptRows.length, SHEET_SCHEMAS.Nonces.length).setValues(keptRows);
    return { ok: true };
  });
};

SheetsStorage.prototype._writeCaptionsUnlocked = function (captionsList, nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var sheet = this._ensureTab("Captions");
  var outRows = [SHEET_SCHEMAS.Captions];
  for (var i = 0; i < captionsList.length; i++) {
    var c = captionsList[i];
    outRows.push([
      Number(c.id || i + 1),
      String(c.title || ""),
      String(c.body || ""),
      String(c.category || "VIRAL_COPYPASTA"),
      c.isAbsurdCopypasta === true ? "TRUE" : "FALSE",
      String(c.hashtagTemplate || "#reels #viral"),
      c.enabled === false ? "FALSE" : "TRUE",
      Number(c.usageCount || 0),
      c.lastUsedEpochMs ? Number(c.lastUsedEpochMs) : "",
      c.isFavorite === true ? "TRUE" : "FALSE",
      Number(c.createdAtMs || tsMs),
      tsMs
    ]);
  }
  sheet.clear();
  sheet.getRange(1, 1, outRows.length, SHEET_SCHEMAS.Captions.length).setValues(outRows);
};

SheetsStorage.prototype.getCaptionsPayload = function () {
  this.ensureInitialized();
  var mode = this._getSettingUnlocked("captionRewriteMode", "FIXED_LIBRARY_RANDOM");
  var sheet = this._ensureTab("Captions");
  var rows = sheet.getDataRange().getValues();
  var captions = [];
  for (var i = 1; i < rows.length; i++) {
    var r = rows[i];
    var title = String(r[1] || "").trim();
    var body = String(r[2] || "").trim();
    if (!title || !body) continue;
    var rawLastUsed = r[8];
    captions.push({
      id: Number(r[0] || i),
      title: title,
      body: body,
      category: String(r[3] || "VIRAL_COPYPASTA"),
      isAbsurdCopypasta: String(r[4]).toUpperCase() === "TRUE" || r[4] === true,
      hashtagTemplate: String(r[5] || "#reels #viral"),
      enabled: !(String(r[6]).toUpperCase() === "FALSE" || r[6] === false),
      usageCount: Number(r[7] || 0),
      lastUsedEpochMs: rawLastUsed !== "" && rawLastUsed !== null && rawLastUsed !== undefined ? Number(rawLastUsed) : null,
      isFavorite: String(r[9]).toUpperCase() === "TRUE" || r[9] === true
    });
  }
  return {
    captionRewriteMode: mode,
    captions: captions
  };
};

SheetsStorage.prototype.saveCaptionsPayload = function (captionsPayload, nowMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  return this.withLock(function () {
    self._setSettingUnlocked("captionRewriteMode", captionsPayload.captionRewriteMode || "FIXED_LIBRARY_RANDOM", tsMs);
    self._writeCaptionsUnlocked(captionsPayload.captions || [], tsMs);
  });
};

SheetsStorage.prototype._mapRowToActivity = function (r) {
  if (!r || !r[0]) return null;
  var rawFailure = r[14];
  var isAuth = String(r[20]).toUpperCase() === "TRUE" || r[20] === true || Number(r[20]) === 1;
  return {
    reelPk: String(r[0]).trim(),
    shortCode: String(r[1] || r[0]).trim(),
    reelUrl: String(r[2] || ""),
    hashtag: String(r[3] || "fitness"),
    category: String(r[4] || "General"),
    creatorHandle: String(r[5] || "@creator"),
    viewCount: Number(r[6] || 0),
    likeCount: Number(r[7] || 0),
    commentCount: Number(r[8] || 0),
    durationSeconds: Number(r[9] || 15),
    qualityScore: Number(r[10] || 0),
    selectionReason: String(r[11] || ""),
    downloaderUsed: String(r[12] || "none"),
    outcomeStatus: String(r[13] || "QUALIFIED_QUEUED"),
    failureReason: rawFailure !== "" && rawFailure !== null && rawFailure !== undefined ? String(rawFailure) : null,
    captionPreview: String(r[15] || ""),
    timestampIst: String(r[16] || ""),
    timestampEpochMs: Number(r[17] || 0),
    retryCount: Number(r[18] || 0),
    idempotencyToken: String(r[19] || ("idem_" + String(r[0]).trim())),
    reuseAuthorized: isAuth,
    authorizationSource: isAuth ? String(r[21] || "NONE") : "NONE"
  };
};

SheetsStorage.prototype._activityToRow = function (rec, nowMs) {
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var pk = String(rec.reelPk || "").trim();
  var shortCode = String(rec.shortCode || pk).trim();
  var isAuth = rec.reuseAuthorized === true;
  var authSource = isAuth && typeof rec.authorizationSource === "string" && rec.authorizationSource.trim()
    ? rec.authorizationSource.trim()
    : "NONE";
  return [
    pk,
    shortCode,
    String(rec.reelUrl || ("https://www.instagram.com/reel/" + shortCode + "/")),
    String(rec.hashtag || "fitness"),
    String(rec.category || "General"),
    String(rec.creatorHandle || "@creator"),
    Number(rec.viewCount || 0),
    Number(rec.likeCount || 0),
    Number(rec.commentCount || 0),
    Number(rec.durationSeconds || 15),
    Number(rec.qualityScore || 0),
    String(rec.selectionReason || ""),
    String(rec.downloaderUsed || "none"),
    String(rec.outcomeStatus || "QUALIFIED_QUEUED"),
    rec.failureReason !== null && rec.failureReason !== undefined ? String(rec.failureReason) : "",
    String(rec.captionPreview || ""),
    String(rec.timestampIst || nowIst),
    Number(rec.timestampEpochMs || tsMs),
    Number(rec.retryCount || 0),
    String(rec.idempotencyToken || ("idem_" + pk)),
    isAuth ? "TRUE" : "FALSE",
    authSource
  ];
};

/**
 * Direct lookup by Reel PK across the entire Activity sheet tab,
 * completely independent of recent-activity page truncation.
 */
SheetsStorage.prototype.getActivityByPk = function (reelPk) {
  this.ensureInitialized();
  var pkStr = String(reelPk || "").trim();
  if (!pkStr) return null;
  var sheet = this._ensureTab("Activity");
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim() === pkStr) {
      return this._mapRowToActivity(rows[i]);
    }
  }
  return null;
};

SheetsStorage.prototype.getActivityRecords = function (limit, cursorEpochMs) {
  this.ensureInitialized();
  var maxItems = Math.min(200, Math.max(1, Number(limit || 50)));
  var cursor = cursorEpochMs !== undefined && cursorEpochMs !== null ? Number(cursorEpochMs) : null;
  var sheet = this._ensureTab("Activity");
  var rows = sheet.getDataRange().getValues();
  var list = [];
  for (var i = 1; i < rows.length; i++) {
    var mapped = this._mapRowToActivity(rows[i]);
    if (!mapped) continue;
    if (cursor !== null && isFinite(cursor) && mapped.timestampEpochMs >= cursor) {
      continue;
    }
    list.push(mapped);
  }
  list.sort(function (a, b) {
    return (b.timestampEpochMs || 0) - (a.timestampEpochMs || 0);
  });
  return list.slice(0, maxItems);
};

SheetsStorage.prototype.upsertActivityRecords = function (records, nowMs) {
  if (!Array.isArray(records) || records.length === 0) return;
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  return this.withLock(function () {
    var sheet = self._ensureTab("Activity");
    var rows = sheet.getDataRange().getValues();
    var indexByPk = {};
    var outRows = [SHEET_SCHEMAS.Activity];
    for (var i = 1; i < rows.length; i++) {
      var pk = String(rows[i][0] || "").trim();
      if (!pk) continue;
      indexByPk[pk] = outRows.length;
      outRows.push(rows[i]);
    }
    for (var j = 0; j < records.length; j++) {
      var rec = records[j];
      if (!rec || !rec.reelPk) continue;
      var recPk = String(rec.reelPk).trim();
      var rowArr = self._activityToRow(rec, tsMs);
      if (Object.prototype.hasOwnProperty.call(indexByPk, recPk)) {
        outRows[indexByPk[recPk]] = rowArr;
      } else {
        indexByPk[recPk] = outRows.length;
        outRows.push(rowArr);
      }
    }
    sheet.clear();
    sheet.getRange(1, 1, outRows.length, SHEET_SCHEMAS.Activity.length).setValues(outRows);
  });
};

/**
 * Direct lookup of a Reel PK's duplicate-prevention state in ProcessedItems
 * (independent of Activity pagination).
 */
SheetsStorage.prototype.getProcessedItemState = function (reelPk) {
  this.ensureInitialized();
  var pkStr = String(reelPk || "").trim();
  if (!pkStr) return null;
  var sheet = this._ensureTab("ProcessedItems");
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim() === pkStr) {
      return String(rows[i][3] || "PUBLISHED").trim();
    }
  }
  return null;
};

SheetsStorage.prototype.getAllProcessedPks = function (filterState) {
  this.ensureInitialized();
  var sheet = this._ensureTab("ProcessedItems");
  var rows = sheet.getDataRange().getValues();
  var pks = [];
  for (var i = 1; i < rows.length; i++) {
    var pk = String(rows[i][0] || "").trim();
    var state = String(rows[i][3] || "PUBLISHED").trim();
    if (!pk) continue;
    if (!filterState || state === filterState) {
      pks.push(pk);
    }
  }
  return pks;
};

SheetsStorage.prototype.upsertProcessedItems = function (pks, uploadState, nowMs) {
  if (!Array.isArray(pks) || pks.length === 0) return;
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);
  var stateStr = String(uploadState || "PUBLISHED");

  return this.withLock(function () {
    var sheet = self._ensureTab("ProcessedItems");
    var rows = sheet.getDataRange().getValues();
    var indexByPk = {};
    var outRows = [SHEET_SCHEMAS.ProcessedItems];
    for (var i = 1; i < rows.length; i++) {
      var existingPk = String(rows[i][0] || "").trim();
      if (!existingPk) continue;
      indexByPk[existingPk] = outRows.length;
      outRows.push(rows[i]);
    }
    for (var j = 0; j < pks.length; j++) {
      var pk = String(pks[j] || "").trim();
      if (!pk) continue;
      var newRow = [pk, "", "", stateStr, "idem_" + pk, tsMs, nowIst];
      if (Object.prototype.hasOwnProperty.call(indexByPk, pk)) {
        outRows[indexByPk[pk]] = newRow;
      } else {
        indexByPk[pk] = outRows.length;
        outRows.push(newRow);
      }
    }
    sheet.clear();
    sheet.getRange(1, 1, outRows.length, SHEET_SCHEMAS.ProcessedItems.length).setValues(outRows);
  });
};

SheetsStorage.prototype.getRequestIdempotency = function (idempotencyKey) {
  this.ensureInitialized();
  var keyStr = String(idempotencyKey || "").trim();
  if (!keyStr) return null;
  var sheet = this._ensureTab("Requests");
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim() === keyStr) {
      var parsedResp = null;
      try {
        parsedResp = JSON.parse(String(rows[i][4] || "{}"));
      } catch (e) {
        parsedResp = null;
      }
      return {
        idempotencyKey: keyStr,
        action: String(rows[i][1] || ""),
        targetId: String(rows[i][2] || ""),
        status: String(rows[i][3] || ""),
        response: parsedResp,
        createdAtMs: Number(rows[i][5] || 0),
        updatedAtMs: Number(rows[i][6] || 0)
      };
    }
  }
  return null;
};

SheetsStorage.prototype.saveRequestIdempotency = function (idempotencyKey, action, targetId, status, responseObj, nowMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var keyStr = String(idempotencyKey || "").trim();
  if (!keyStr) return;

  return this.withLock(function () {
    var sheet = self._ensureTab("Requests");
    var rows = sheet.getDataRange().getValues();
    var serialized = JSON.stringify(responseObj || {});
    for (var i = 1; i < rows.length; i++) {
      if (String(rows[i][0] || "").trim() === keyStr) {
        var createdMs = Number(rows[i][5] || tsMs);
        sheet.getRange(i + 1, 1, 1, 7).setValues([[
          keyStr,
          String(action || ""),
          String(targetId || ""),
          String(status || ""),
          serialized,
          createdMs,
          tsMs
        ]]);
        return;
      }
    }
    sheet.appendRow([
      keyStr,
      String(action || ""),
      String(targetId || ""),
      String(status || ""),
      serialized,
      tsMs,
      tsMs
    ]);
  });
};

SheetsStorage.prototype.acquireCycleLock = function (lockName, ownerId, command, nowMs, ttlMs) {
  if (!ownerId) {
    throw new Error("acquireCycleLock requires a non-empty ownerId");
  }
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var expMs = tsMs + (typeof ttlMs === "number" ? ttlMs : RILOT_CONSTANTS.CYCLE_LOCK_TTL_MS);
  var nowIst = formatIstTimestamp(tsMs);

  return this.withLock(function () {
    var sheet = self._ensureTab("Cycles");
    var rows = sheet.getDataRange().getValues();
    for (var i = 1; i < rows.length; i++) {
      var rowLockName = String(rows[i][1] || "").trim();
      var rowStatus = String(rows[i][4] || "").trim();
      var rowExpires = Number(rows[i][6] || 0);
      if (rowLockName === lockName && rowStatus !== "RELEASED" && rowStatus !== "FAILED_DISPATCH") {
        if (isFinite(rowExpires) && rowExpires > tsMs) {
          return {
            acquired: false,
            holder: {
              cycleId: String(rows[i][0] || ""),
              lockName: rowLockName,
              ownerId: String(rows[i][2] || ""),
              command: String(rows[i][3] || ""),
              dispatchStatus: rowStatus,
              acquiredAt: Number(rows[i][5] || 0),
              expiresAt: rowExpires
            }
          };
        }
        // Mark expired lock as EXPIRED
        sheet.getRange(i + 1, 5, 1, 1).setValues([["EXPIRED"]]);
      }
    }

    var cycleId = "cycle_" + ownerId;
    sheet.appendRow([
      cycleId,
      lockName,
      ownerId,
      String(command || "START_CYCLE"),
      "PENDING_DISPATCH",
      tsMs,
      expMs,
      nowIst,
      "Lock acquired prior to workflow dispatch"
    ]);
    return {
      acquired: true,
      cycleId: cycleId,
      ownerId: ownerId,
      expiresAt: expMs
    };
  });
};

SheetsStorage.prototype.updateCycleDispatchStatus = function (lockName, ownerId, dispatchStatus, notes, nowMs) {
  if (!ownerId) return false;
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);

  return this.withLock(function () {
    var sheet = self._ensureTab("Cycles");
    var rows = sheet.getDataRange().getValues();
    for (var i = rows.length - 1; i >= 1; i--) {
      if (
        String(rows[i][1] || "").trim() === lockName &&
        String(rows[i][2] || "").trim() === ownerId
      ) {
        sheet.getRange(i + 1, 5, 1, 1).setValues([[String(dispatchStatus || "DISPATCHED")]]);
        sheet.getRange(i + 1, 8, 1, 2).setValues([[nowIst, String(notes || "")]]);
        return true;
      }
    }
    return false;
  });
};

SheetsStorage.prototype.getActiveCycleLock = function (lockName, nowMs) {
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();

  return this.withLock(function () {
    var sheet = self._ensureTab("Cycles");
    var rows = sheet.getDataRange().getValues();
    for (var i = rows.length - 1; i >= 1; i--) {
      var rowLockName = String(rows[i][1] || "").trim();
      var rowStatus = String(rows[i][4] || "").trim();
      var rowExpires = Number(rows[i][6] || 0);
      if (
        rowLockName === lockName &&
        (rowStatus === "PENDING_DISPATCH" || rowStatus === "DISPATCHED" || rowStatus === "RUNNING")
      ) {
        if (isFinite(rowExpires) && rowExpires > tsMs) {
          return {
            cycleId: String(rows[i][0] || ""),
            lockName: rowLockName,
            ownerId: String(rows[i][2] || ""),
            command: String(rows[i][3] || ""),
            dispatchStatus: rowStatus,
            acquiredAt: Number(rows[i][5] || 0),
            expiresAt: rowExpires
          };
        }
        sheet.getRange(i + 1, 5, 1, 1).setValues([["EXPIRED"]]);
      }
    }
    return null;
  });
};

/**
 * Releases a cycle lock ONLY if the caller presents the matching ownerId.
 */
SheetsStorage.prototype.releaseCycleLock = function (lockName, ownerId, releaseReason, nowMs) {
  if (!ownerId) return false;
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var nowIst = formatIstTimestamp(tsMs);

  return this.withLock(function () {
    var sheet = self._ensureTab("Cycles");
    var rows = sheet.getDataRange().getValues();
    var released = false;
    for (var i = rows.length - 1; i >= 1; i--) {
      var rowLockName = String(rows[i][1] || "").trim();
      var rowOwnerId = String(rows[i][2] || "").trim();
      var rowStatus = String(rows[i][4] || "").trim();
      if (
        rowLockName === lockName &&
        rowOwnerId === ownerId &&
        rowStatus !== "RELEASED" &&
        rowStatus !== "FAILED_DISPATCH" &&
        rowStatus !== "EXPIRED"
      ) {
        sheet.getRange(i + 1, 5, 1, 1).setValues([[releaseReason || "RELEASED"]]);
        sheet.getRange(i + 1, 8, 1, 2).setValues([[nowIst, "Lock released by owner " + ownerId]]);
        released = true;
      }
    }
    return released;
  });
};

SheetsStorage.prototype.extendCycleLockHeartbeat = function (lockName, ownerId, nowMs, ttlMs) {
  if (!ownerId) return false;
  this.ensureInitialized(nowMs);
  var self = this;
  var tsMs = typeof nowMs === "number" ? nowMs : Date.now();
  var newExp = tsMs + (typeof ttlMs === "number" ? ttlMs : RILOT_CONSTANTS.CYCLE_LOCK_TTL_MS);
  var nowIst = formatIstTimestamp(tsMs);

  return this.withLock(function () {
    var sheet = self._ensureTab("Cycles");
    var rows = sheet.getDataRange().getValues();
    for (var i = rows.length - 1; i >= 1; i--) {
      var rowLockName = String(rows[i][1] || "").trim();
      var rowOwnerId = String(rows[i][2] || "").trim();
      var rowStatus = String(rows[i][4] || "").trim();
      var rowExpires = Number(rows[i][6] || 0);
      if (
        rowLockName === lockName &&
        rowOwnerId === ownerId &&
        (rowStatus === "PENDING_DISPATCH" || rowStatus === "DISPATCHED" || rowStatus === "RUNNING") &&
        rowExpires > tsMs
      ) {
        sheet.getRange(i + 1, 5, 1, 1).setValues([["RUNNING"]]);
        sheet.getRange(i + 1, 7, 1, 2).setValues([[newExp, nowIst]]);
        return true;
      }
    }
    return false;
  });
};

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    SHEET_SCHEMAS: SHEET_SCHEMAS,
    createDefaultBackendSeed: createDefaultBackendSeed,
    SheetsStorage: SheetsStorage
  };
}
