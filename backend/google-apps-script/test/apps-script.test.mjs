import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GAS_DIR = path.resolve(__dirname, "..");

/**
 * Loads all Google Apps Script (.gs) files into a single VM context,
 * accurately emulating the shared global scope of the Google Apps Script V8 runtime.
 */
function loadAppsScriptRuntime(globals = {}) {
  const context = vm.createContext({
    console,
    Date,
    Math,
    JSON,
    Intl,
    Number,
    String,
    Boolean,
    Array,
    Object,
    RegExp,
    Error,
    isFinite,
    parseInt,
    parseFloat,
    crypto: globalThis.crypto,
    ...globals,
  });

  const files = [
    "Validation.gs",
    "Auth.gs",
    "Storage.gs",
    "GitHubDispatch.gs",
    "EngineApi.gs",
    "Code.gs",
  ];

  for (const file of files) {
    const fullPath = path.join(GAS_DIR, file);
    const code = fs.readFileSync(fullPath, "utf8");
    vm.runInContext(code, context, { filename: file });
  }

  return context;
}

/**
 * In-memory mock implementation of Google Apps Script's Spreadsheet / Sheet / Range APIs.
 * Supports multiple script invocations sharing the same underlying spreadsheet state.
 */
class MockRange {
  constructor(sheet, startRow1Based, startCol1Based, numRows, numCols) {
    this.sheet = sheet;
    this.startRow = startRow1Based - 1;
    this.startCol = startCol1Based - 1;
    this.numRows = numRows;
    this.numCols = numCols;
  }

  setValues(values) {
    for (let r = 0; r < this.numRows; r++) {
      const targetRowIdx = this.startRow + r;
      while (this.sheet.rows.length <= targetRowIdx) {
        this.sheet.rows.push([]);
      }
      const rowArr = this.sheet.rows[targetRowIdx];
      const srcRow = values[r] || [];
      for (let c = 0; c < this.numCols; c++) {
        const targetColIdx = this.startCol + c;
        while (rowArr.length <= targetColIdx) {
          rowArr.push("");
        }
        rowArr[targetColIdx] = srcRow[c] !== undefined ? srcRow[c] : "";
      }
    }
  }

  getValues() {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const srcRow = this.sheet.rows[this.startRow + r] || [];
      const rowOut = [];
      for (let c = 0; c < this.numCols; c++) {
        rowOut.push(srcRow[this.startCol + c] !== undefined ? srcRow[this.startCol + c] : "");
      }
      out.push(rowOut);
    }
    return out;
  }
}

class MockSheet {
  constructor(name) {
    this.name = name;
    this.rows = [];
  }

  clear() {
    this.rows = [];
  }

  appendRow(rowValues) {
    this.rows.push(rowValues.map((v) => (v !== undefined ? v : "")));
  }

  getDataRange() {
    if (this.rows.length === 0) {
      return {
        getValues: () => [[""]],
      };
    }
    const maxCols = Math.max(1, ...this.rows.map((r) => r.length));
    const snapshot = this.rows.map((r) => {
      const copy = [];
      for (let i = 0; i < maxCols; i++) {
        copy.push(r[i] !== undefined ? r[i] : "");
      }
      return copy;
    });
    return {
      getValues: () => snapshot,
    };
  }

  getRange(row1, col1, numRows = 1, numCols = 1) {
    return new MockRange(this, row1, col1, numRows, numCols);
  }
}

class MockSpreadsheet {
  constructor(id = "mock_spreadsheet_id_123") {
    this.id = id;
    this.sheets = new Map();
  }

  getSheetByName(name) {
    return this.sheets.get(name) || null;
  }

  insertSheet(name) {
    const sheet = new MockSheet(name);
    this.sheets.set(name, sheet);
    return sheet;
  }
}

function createDefaultScriptProps(overrides = {}) {
  return {
    RILOT_APP_API_KEY: "app_key_secret_987654321",
    RILOT_ENGINE_API_KEY: "engine_key_secret_123456789",
    GITHUB_PAT: "github_pat_mock_secret_token_xyz999",
    GITHUB_OWNER: "nexerpy-sys",
    GITHUB_REPO: "rilot",
    GITHUB_BRANCH: "main",
    GITHUB_WORKFLOW_FILE: "insta-automation-cycle.yml",
    RILOT_SPREADSHEET_ID: "sheet_id_rilot_prod_001",
    ...overrides,
  };
}

let reqCounter = 0;
function buildRpcBody({
  action,
  authToken,
  timestamp = Date.now(),
  nonce = null,
  requestId = null,
  payload = {},
  extraEnvelopeFields = {},
}) {
  reqCounter += 1;
  return JSON.stringify({
    action,
    authToken,
    timestamp,
    nonce: nonce || `nonce_${timestamp}_${reqCounter}_abcdefgh`,
    requestId: requestId || `req_${timestamp}_${reqCounter}`,
    payload,
    ...extraEnvelopeFields,
  });
}

test("1. Authentication & API: missing/invalid keys, role separation, malformed JSON, replay window, duplicate nonces, rate limits, and secret redaction", () => {
  const runtime = loadAppsScriptRuntime();
  const spreadsheet = new MockSpreadsheet();
  const props = createDefaultScriptProps();
  const nowMs = 1760000000000;

  // 1a. Malformed JSON is rejected safely
  const malformedRes = runtime.processRpcRequest("{not valid json", {}, {
    nowMs,
    scriptProps: props,
    spreadsheet,
  });
  assert.equal(malformedRes.ok, false);
  assert.equal(malformedRes.code, "MALFORMED_JSON");

  // 1b. Credentials in URL query parameters are strictly rejected
  const queryCredRes = runtime.processRpcRequest(
    buildRpcBody({ action: "app.getStatus", authToken: props.RILOT_APP_API_KEY, timestamp: nowMs }),
    { authToken: props.RILOT_APP_API_KEY },
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(queryCredRes.ok, false);
  assert.equal(queryCredRes.code, "CREDENTIALS_IN_QUERY_FORBIDDEN");

  // 1c. Missing and incorrect app keys are rejected with UNAUTHORIZED
  const missingKeyRes = runtime.processRpcRequest(
    buildRpcBody({ action: "app.getStatus", authToken: "", timestamp: nowMs }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(missingKeyRes.ok, false);
  assert.equal(missingKeyRes.code, "UNAUTHORIZED");

  const wrongAppKeyRes = runtime.processRpcRequest(
    buildRpcBody({ action: "app.getStatus", authToken: "wrong_app_key", timestamp: nowMs }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(wrongAppKeyRes.ok, false);
  assert.equal(wrongAppKeyRes.code, "UNAUTHORIZED");

  // 1d. Missing and incorrect engine keys are rejected with UNAUTHORIZED
  const wrongEngineKeyRes = runtime.processRpcRequest(
    buildRpcBody({ action: "engine.getConfig", authToken: "wrong_engine_key", timestamp: nowMs }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(wrongEngineKeyRes.ok, false);
  assert.equal(wrongEngineKeyRes.code, "UNAUTHORIZED");

  // 1e. App key attempting to call engine-only actions is rejected with FORBIDDEN_ROLE
  for (const engineAction of ["engine.getConfig", "engine.report", "engine.acquireCycle"]) {
    const appCallsEngine = runtime.processRpcRequest(
      buildRpcBody({ action: engineAction, authToken: props.RILOT_APP_API_KEY, timestamp: nowMs }),
      {},
      { nowMs, scriptProps: props, spreadsheet }
    );
    assert.equal(appCallsEngine.ok, false);
    assert.equal(appCallsEngine.code, "FORBIDDEN_ROLE");
  }

  // 1f. Engine key attempting to call app administrative actions is rejected with FORBIDDEN_ROLE
  for (const appAction of ["app.setControl", "app.updateDiscoveryConfig", "app.updateScheduleConfig"]) {
    const engineCallsApp = runtime.processRpcRequest(
      buildRpcBody({ action: appAction, authToken: props.RILOT_ENGINE_API_KEY, timestamp: nowMs }),
      {},
      { nowMs, scriptProps: props, spreadsheet }
    );
    assert.equal(engineCallsApp.ok, false);
    assert.equal(engineCallsApp.code, "FORBIDDEN_ROLE");
  }

  // 1g. Unknown actions are rejected safely
  const unknownActionRes = runtime.processRpcRequest(
    buildRpcBody({ action: "app.dropDatabase", authToken: props.RILOT_APP_API_KEY, timestamp: nowMs }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(unknownActionRes.ok, false);
  assert.equal(unknownActionRes.code, "UNKNOWN_ACTION");

  // 1h. Expired timestamps outside replay window are rejected
  const expiredTsRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.getStatus",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs - 10 * 60 * 1000,
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(expiredTsRes.ok, false);
  assert.equal(expiredTsRes.code, "EXPIRED_TIMESTAMP");

  // 1i. Duplicate nonces are rejected
  const fixedNonce = "nonce_unique_replay_test_12345";
  const firstNonceRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.getStatus",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      nonce: fixedNonce,
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(firstNonceRes.ok, true);

  const dupNonceRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.getStatus",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      nonce: fixedNonce,
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet }
  );
  assert.equal(dupNonceRes.ok, false);
  assert.equal(dupNonceRes.code, "DUPLICATE_NONCE");

  // 1j. Sensitive value redaction scrubs GITHUB_PAT, RILOT_APP_API_KEY, and RILOT_ENGINE_API_KEY
  const leakedErrorRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: { command: "START_CYCLE" },
    }),
    {},
    {
      nowMs,
      scriptProps: props,
      spreadsheet,
      fetchAdapter: () => ({
        getResponseCode: () => 500,
        getContentText: () =>
          `GitHub error with token ${props.GITHUB_PAT} and keys ${props.RILOT_APP_API_KEY} / ${props.RILOT_ENGINE_API_KEY}`,
      }),
    }
  );
  assert.equal(leakedErrorRes.ok, false);
  assert.ok(!leakedErrorRes.message.includes(props.GITHUB_PAT));
  assert.ok(!leakedErrorRes.message.includes(props.RILOT_APP_API_KEY));
  assert.ok(!leakedErrorRes.message.includes(props.RILOT_ENGINE_API_KEY));
  assert.ok(leakedErrorRes.message.includes("[REDACTED]"));

  // 1k. Per-role rate limiting blocks excessive requests and resets after window expires
  const strictRateProps = createDefaultScriptProps({
    RATE_LIMIT_MAX_APP_REQUESTS: 3,
    RATE_LIMIT_WINDOW_MS: 60000,
  });
  const rateSheet = new MockSpreadsheet();
  for (let i = 0; i < 3; i++) {
    const r = runtime.processRpcRequest(
      buildRpcBody({ action: "app.getStatus", authToken: strictRateProps.RILOT_APP_API_KEY, timestamp: nowMs }),
      {},
      { nowMs, scriptProps: strictRateProps, spreadsheet: rateSheet }
    );
    assert.equal(r.ok, true);
  }
  const rateBlocked = runtime.processRpcRequest(
    buildRpcBody({ action: "app.getStatus", authToken: strictRateProps.RILOT_APP_API_KEY, timestamp: nowMs }),
    {},
    { nowMs, scriptProps: strictRateProps, spreadsheet: rateSheet }
  );
  assert.equal(rateBlocked.ok, false);
  assert.equal(rateBlocked.code, "RATE_LIMITED");

  // Advance clock past window -> rate limit resets
  const afterWindowMs = nowMs + 61000;
  const rateResetOk = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.getStatus",
      authToken: strictRateProps.RILOT_APP_API_KEY,
      timestamp: afterWindowMs,
    }),
    {},
    { nowMs: afterWindowMs, scriptProps: strictRateProps, spreadsheet: rateSheet }
  );
  assert.equal(rateResetOk.ok, true);
});

test("2. Persistence across separate script invocations: discovery, schedule, captions, activity lookup > 200 rows, and missing tab initialization", () => {
  const sharedSpreadsheet = new MockSpreadsheet();
  const props = createDefaultScriptProps();
  const nowMs = 1760000100000;

  // Invocation 1: Save discovery settings and schedule settings
  const runtime1 = loadAppsScriptRuntime();
  const saveDiscRes = runtime1.processRpcRequest(
    buildRpcBody({
      action: "app.updateDiscoveryConfig",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: {
        hashtags: ["calisthenics", "streetworkout"],
        categoriesByTag: { calisthenics: "Strength", streetworkout: "Park" },
        reuseAuthorizedByTag: { calisthenics: true, streetworkout: false },
        allowAllHashtagCandidatesWhenTargetAuthorized: false,
        authorizedCreators: ["@bar_athlete"],
        ownedAccounts: ["@my_studio"],
        authorizedCandidatePks: ["9001"],
        candidateCollectionLimitPerTag: 45,
        qualifyingPoolSize: 12,
        contentSource: "RECENT_AND_TOP",
        selectionStrategy: "QUALIFYING_POOL_WEIGHTED",
        minViewCountThreshold: 30000,
        minLikeToViewRatioPercent: 4.0,
        recencyWeightPercent: 30,
        engagementWeightPercent: 45,
        diversityWeightPercent: 25,
      },
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(saveDiscRes.ok, true);

  const saveSchedRes = runtime1.processRpcRequest(
    buildRpcBody({
      action: "app.updateScheduleConfig",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: {
        timezoneId: "Asia/Kolkata",
        postIntervalHours: 4,
        maxPostsPerCycle: 2,
        dailyPostingLimit: 6,
        delayBetweenPostsSeconds: 90,
        dryRunMode: true,
        autoPublishEnabled: false,
      },
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(saveSchedRes.ok, true);

  // Add, edit, enable/disable captions
  const saveCapRes = runtime1.processRpcRequest(
    buildRpcBody({
      action: "app.updateCaptions",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: {
        captionRewriteMode: "HYBRID_TEMPLATE_PLUS_AI",
        captions: [
          {
            id: 10,
            title: "Active Hook",
            body: "Weighted reps #calisthenics",
            category: "FITNESS_TECHNIQUE",
            isAbsurdCopypasta: false,
            hashtagTemplate: "#calisthenics #strength",
            enabled: true,
            usageCount: 2,
          },
          {
            id: 11,
            title: "Disabled Draft",
            body: "Draft text #fitness",
            category: "MOTIVATIONAL",
            isAbsurdCopypasta: false,
            hashtagTemplate: "#fitness",
            enabled: false,
            usageCount: 0,
          },
        ],
      },
    }),
    {},
    { nowMs, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(saveCapRes.ok, true);

  // Invocation 2 (completely fresh Apps Script instance sharing the same Google Sheet):
  // Delete the RateLimits tab manually to verify missing tabs are re-initialized safely
  sharedSpreadsheet.sheets.delete("RateLimits");
  const runtime2 = loadAppsScriptRuntime();

  const readDiscRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "app.getDiscoveryConfig",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 1000,
    }),
    {},
    { nowMs: nowMs + 1000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(readDiscRes.ok, true);
  assert.equal(readDiscRes.data.candidateCollectionLimitPerTag, 45);
  assert.equal(readDiscRes.data.qualifyingPoolSize, 12);
  assert.deepEqual(readDiscRes.data.authorizedCreators, ["bar_athlete"]);
  assert.equal(readDiscRes.data.reuseAuthorizedByTag.calisthenics, true);
  assert.equal(readDiscRes.data.reuseAuthorizedByTag.streetworkout, false);
  assert.equal(readDiscRes.data.allowAllHashtagCandidatesWhenTargetAuthorized, false);

  const readSchedRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "app.getScheduleConfig",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 1000,
    }),
    {},
    { nowMs: nowMs + 1000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(readSchedRes.ok, true);
  assert.equal(readSchedRes.data.postIntervalHours, 4);
  assert.equal(readSchedRes.data.timezoneId, "Asia/Kolkata");
  assert.equal(readSchedRes.data.dryRunMode, true);
  assert.equal(readSchedRes.data.autoPublishEnabled, false);

  const readCapsRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "app.getCaptions",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 1000,
    }),
    {},
    { nowMs: nowMs + 1000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(readCapsRes.ok, true);
  assert.equal(readCapsRes.data.captionRewriteMode, "HYBRID_TEMPLATE_PLUS_AI");
  assert.equal(readCapsRes.data.captions.length, 2);
  assert.equal(readCapsRes.data.captions[1].enabled, false);

  // Engine getConfig only receives enabled captions and never receives secrets
  const engineCfgRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "engine.getConfig",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 1000,
    }),
    {},
    { nowMs: nowMs + 1000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(engineCfgRes.ok, true);
  assert.equal(engineCfgRes.data.captions.length, 1);
  assert.equal(engineCfgRes.data.captions[0].id, 10);
  const serializedEngineCfg = JSON.stringify(engineCfgRes);
  assert.ok(!serializedEngineCfg.includes(props.GITHUB_PAT));
  assert.ok(!serializedEngineCfg.includes(props.RILOT_APP_API_KEY));

  // Seed 240 activity records and verify direct PK lookup finds the oldest record (#1)
  // even when recent activity page is limited to 50 or 100 records.
  const manyRecords = [];
  for (let i = 1; i <= 240; i++) {
    manyRecords.push({
      reelPk: `pk_historic_${i}`,
      shortCode: `CODE_${i}`,
      reelUrl: `https://www.instagram.com/reel/CODE_${i}/`,
      hashtag: "calisthenics",
      category: "Strength",
      creatorHandle: "@bar_athlete",
      viewCount: 85000 + i,
      likeCount: 5200,
      commentCount: 120,
      durationSeconds: 18,
      qualityScore: 88.5,
      selectionReason: "Weighted pool",
      downloaderUsed: "yt-dlp",
      outcomeStatus: i === 1 ? "DOWNLOAD_FAILED" : "DRY_RUN_VALIDATED",
      failureReason: i === 1 ? "CDN timeout" : null,
      captionPreview: "Caption",
      timestampIst: "2026-10-28 10:00:00 IST",
      timestampEpochMs: nowMs - (250 - i) * 1000,
      retryCount: 0,
      idempotencyToken: `idem_historic_${i}`,
      reuseAuthorized: true,
      authorizationSource: "CREATOR_ALLOWLIST",
    });
  }

  const reportManyRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "engine.report",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 2000,
      payload: {
        status: "COMPLETED",
        runState: "DRY_RUN",
        candidatesDiscovered: 240,
        activityRecords: manyRecords,
        postedPks: ["pk_already_posted_001"],
      },
    }),
    {},
    { nowMs: nowMs + 2000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(reportManyRes.ok, true);

  // Query oldest record (pk_historic_1) via engine.getRetryTarget and app.safeRetry
  const targetLookupRes = runtime2.processRpcRequest(
    buildRpcBody({
      action: "engine.getRetryTarget",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 2000,
      payload: { reelPk: "pk_historic_1" },
    }),
    {},
    { nowMs: nowMs + 2000, scriptProps: props, spreadsheet: sharedSpreadsheet }
  );
  assert.equal(targetLookupRes.ok, true);
  assert.equal(targetLookupRes.data.record.reelPk, "pk_historic_1");
  assert.equal(targetLookupRes.data.currentlyAuthorized, true);
});

test("3. GitHub Dispatch: valid dispatch, missing/placeholder/upstream config blocked, client override blocked, idempotency, and safe lock cleanup on failure", () => {
  const runtime = loadAppsScriptRuntime();
  const spreadsheet = new MockSpreadsheet();
  const props = createDefaultScriptProps();
  const nowMs = 1760000200000;

  // 3a. Missing repository configuration is rejected
  const missingRepoRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: { command: "START_CYCLE" },
    }),
    {},
    {
      nowMs,
      scriptProps: createDefaultScriptProps({ GITHUB_REPO: "" }),
      spreadsheet,
    }
  );
  assert.equal(missingRepoRes.ok, false);
  assert.equal(missingRepoRes.code, "DISPATCH_CONFIG_INVALID");

  // 3b. Upstream template najinkya776/InstaAutomation or placeholder values are rejected
  for (const badProps of [
    { GITHUB_OWNER: "najinkya776", GITHUB_REPO: "InstaAutomation" },
    { GITHUB_OWNER: "your-github-owner", GITHUB_REPO: "rilot" },
    { GITHUB_WORKFLOW_FILE: "unapproved.yml" },
    { GITHUB_BRANCH: "../invalid..branch" },
  ]) {
    const badRes = runtime.processRpcRequest(
      buildRpcBody({
        action: "app.setControl",
        authToken: props.RILOT_APP_API_KEY,
        timestamp: nowMs,
        payload: { command: "START_CYCLE" },
      }),
      {},
      {
        nowMs,
        scriptProps: createDefaultScriptProps(badProps),
        spreadsheet,
      }
    );
    assert.equal(badRes.ok, false);
    assert.equal(badRes.code, "DISPATCH_CONFIG_INVALID");
  }

  // 3c. Client attempts to override owner/repo/branch/workflow in payload or envelope are blocked
  for (const overrideField of [
    { githubOwner: "attacker" },
    { githubRepo: "evil-repo" },
    { branch: "evil-branch" },
    { workflowFile: "evil.yml" },
  ]) {
    const overrideRes = runtime.processRpcRequest(
      buildRpcBody({
        action: "app.setControl",
        authToken: props.RILOT_APP_API_KEY,
        timestamp: nowMs,
        payload: { command: "START_CYCLE", ...overrideField },
      }),
      {},
      { nowMs, scriptProps: props, spreadsheet }
    );
    assert.equal(overrideRes.ok, false);
    assert.equal(overrideRes.code, "FORBIDDEN_DISPATCH_OVERRIDE");
  }

  // 3d. Failed GitHub API call (e.g., 403 permission error) cleans up the cycle lock so subsequent dispatch succeeds
  const permFailRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: { command: "START_CYCLE", idempotencyKey: "idem_fail_dispatch_1" },
    }),
    {},
    {
      nowMs,
      scriptProps: props,
      spreadsheet,
      fetchAdapter: () => ({
        getResponseCode: () => 403,
        getContentText: () => "Resource not accessible by personal access token",
      }),
    }
  );
  assert.equal(permFailRes.ok, false);
  assert.equal(permFailRes.code, "GITHUB_PERMISSION_DENIED");

  // Because the failed dispatch cleaned up its cycle lock, the next valid dispatch succeeds!
  let capturedDispatchUrl = null;
  let capturedDispatchPayload = null;
  const successDispatchRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 1000,
      payload: { command: "START_CYCLE", idempotencyKey: "idem_ok_dispatch_2" },
    }),
    {},
    {
      nowMs: nowMs + 1000,
      scriptProps: props,
      spreadsheet,
      fetchAdapter: (url, options) => {
        capturedDispatchUrl = url;
        capturedDispatchPayload = JSON.parse(options.payload);
        return {
          getResponseCode: () => 204,
          getContentText: () => "",
        };
      },
    }
  );
  assert.equal(successDispatchRes.ok, true);
  assert.equal(successDispatchRes.code, "DISPATCHED");
  assert.equal(
    capturedDispatchUrl,
    "https://api.github.com/repos/nexerpy-sys/rilot/actions/workflows/insta-automation-cycle.yml/dispatches"
  );
  assert.equal(capturedDispatchPayload.ref, "main");
  assert.equal(capturedDispatchPayload.inputs.command, "START_CYCLE");
  assert.equal(capturedDispatchPayload.inputs.dry_run, "true");
  assert.ok(capturedDispatchPayload.inputs.lock_owner_id);

  // 3e. Repeated identical idempotencyKey returns idempotent replay without dispatching a second workflow
  let fetchCount = 0;
  const replayDispatchRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 2000,
      payload: { command: "START_CYCLE", idempotencyKey: "idem_ok_dispatch_2" },
    }),
    {},
    {
      nowMs: nowMs + 2000,
      scriptProps: props,
      spreadsheet,
      fetchAdapter: () => {
        fetchCount += 1;
        return { getResponseCode: () => 204, getContentText: () => "" };
      },
    }
  );
  assert.equal(replayDispatchRes.ok, true);
  assert.equal(replayDispatchRes.data.idempotentReplay, true);
  assert.equal(fetchCount, 0);

  // 3f. A new cycle dispatch while the current cycle lock is active is blocked with CYCLE_ALREADY_IN_FLIGHT
  const concurrentBlockedRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 3000,
      payload: { command: "DRY_RUN_CYCLE", idempotencyKey: "idem_concurrent_3" },
    }),
    {},
    { nowMs: nowMs + 3000, scriptProps: props, spreadsheet }
  );
  assert.equal(concurrentBlockedRes.ok, false);
  assert.equal(concurrentBlockedRes.code, "CYCLE_ALREADY_IN_FLIGHT");
});

test("4. Cycle Lock Ownership & Engine Reporting: stale reports ignored, unowned reports preserve active lock, matching lockOwnerId releases lock, and safe retry revalidates authorization", () => {
  const runtime = loadAppsScriptRuntime();
  const spreadsheet = new MockSpreadsheet();
  const props = createDefaultScriptProps();
  const nowMs = 1760000300000;

  // Dispatch a cycle to acquire lockOwnerId_A
  const dispatchRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.setControl",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs,
      payload: { command: "DRY_RUN_CYCLE", idempotencyKey: "idem_lock_test_1" },
    }),
    {},
    {
      nowMs,
      scriptProps: props,
      spreadsheet,
      fetchAdapter: () => ({ getResponseCode: () => 204, getContentText: () => "" }),
    }
  );
  assert.equal(dispatchRes.ok, true);
  const activeOwnerId = dispatchRes.data.lockOwnerId;
  assert.ok(activeOwnerId);

  // 4a. Stale report with a wrong lockOwnerId is ignored and does NOT release activeOwnerId's lock
  const staleReportRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "engine.report",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 1000,
      payload: {
        status: "COMPLETED",
        lockOwnerId: "stale_lock_owner_999",
        runState: "DRY_RUN",
        candidatesDiscovered: 10,
      },
    }),
    {},
    { nowMs: nowMs + 1000, scriptProps: props, spreadsheet }
  );
  assert.equal(staleReportRes.ok, true);
  assert.equal(staleReportRes.code, "STALE_REPORT_IGNORED");
  assert.equal(staleReportRes.data.lockReleased, false);
  assert.equal(staleReportRes.data.staleReportIgnored, true);

  // 4b. Unowned report (no lockOwnerId) while activeOwnerId is running updates metrics without releasing active lock
  const unownedReportRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "engine.report",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 2000,
      payload: {
        status: "COMPLETED",
        runState: "DRY_RUN",
        candidatesDiscovered: 5,
      },
    }),
    {},
    { nowMs: nowMs + 2000, scriptProps: props, spreadsheet }
  );
  assert.equal(unownedReportRes.ok, true);
  assert.equal(unownedReportRes.code, "REPORT_INGESTED_LOCK_PRESERVED");
  assert.equal(unownedReportRes.data.lockReleased, false);

  // 4c. Report with matching activeOwnerId releases the lock cleanly
  const validReportRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "engine.report",
      authToken: props.RILOT_ENGINE_API_KEY,
      timestamp: nowMs + 3000,
      payload: {
        status: "COMPLETED",
        lockOwnerId: activeOwnerId,
        runState: "DRY_RUN",
        candidatesDiscovered: 12,
        videosProcessed: 2,
        activityRecords: [
          {
            reelPk: "reel_ambiguous_555",
            shortCode: "AMB555",
            hashtag: "calisthenics",
            creatorHandle: "@creator",
            outcomeStatus: "UPLOAD_FAILED",
            failureReason: "Timeout (IN_FLIGHT_AMBIGUOUS)",
            idempotencyToken: "idem_amb_555",
            reuseAuthorized: true,
            authorizationSource: "ORIGINAL_ACCOUNT",
          },
          {
            reelPk: "reel_revoked_777",
            shortCode: "REV777",
            hashtag: "calisthenics",
            creatorHandle: "@former_creator",
            outcomeStatus: "DOWNLOAD_FAILED",
            failureReason: "CDN error",
            idempotencyToken: "idem_rev_777",
            reuseAuthorized: true,
            authorizationSource: "CREATOR_ALLOWLIST",
          },
        ],
      },
    }),
    {},
    { nowMs: nowMs + 3000, scriptProps: props, spreadsheet }
  );
  assert.equal(validReportRes.ok, true);
  assert.equal(validReportRes.code, "REPORT_INGESTED");
  assert.equal(validReportRes.data.lockReleased, true);

  // 4d. Safe retry on IN_FLIGHT_AMBIGUOUS item is blocked with RETRY_UNRESOLVED_AMBIGUOUS
  const retryAmbRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.safeRetry",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 4000,
      payload: {
        reelPk: "reel_ambiguous_555",
        idempotencyToken: "idem_amb_555_r1",
      },
    }),
    {},
    { nowMs: nowMs + 4000, scriptProps: props, spreadsheet }
  );
  assert.equal(retryAmbRes.ok, false);
  assert.equal(retryAmbRes.code, "RETRY_UNRESOLVED_AMBIGUOUS");

  // 4e. Safe retry on item whose creator authorization was revoked is blocked (fail-closed)
  const retryRevokedRes = runtime.processRpcRequest(
    buildRpcBody({
      action: "app.safeRetry",
      authToken: props.RILOT_APP_API_KEY,
      timestamp: nowMs + 5000,
      payload: {
        reelPk: "reel_revoked_777",
        idempotencyToken: "idem_rev_777_r1",
      },
    }),
    {},
    { nowMs: nowMs + 5000, scriptProps: props, spreadsheet }
  );
  assert.equal(retryRevokedRes.ok, false);
  assert.equal(retryRevokedRes.code, "RETRY_REJECTED");
});
