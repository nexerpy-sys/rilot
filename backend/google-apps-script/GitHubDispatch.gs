/**
 * Rilot Google Apps Script Backend — GitHubDispatch.gs
 *
 * Validated GitHub Actions workflow_dispatch integration via UrlFetchApp.
 * Keeps GITHUB_PAT strictly inside Apps Script Script Properties and never
 * holds a ScriptLock while performing the external HTTP request to GitHub.
 */

var ALLOWED_DISPATCH_COMMANDS = ["DRY_RUN_CYCLE", "START_CYCLE", "SAFE_RETRY"];

function dispatchGitHubWorkflow(scriptProps, rawInputs, customFetchAdapter) {
  var props = scriptProps || {};
  var targetValidation = validateGithubDispatchConfig(props);
  if (!targetValidation.valid) {
    return {
      ok: false,
      code: "DISPATCH_CONFIG_INVALID",
      message: targetValidation.error
    };
  }

  var pat = String(props.GITHUB_PAT || "").trim();
  if (!pat) {
    return {
      ok: false,
      code: "MISSING_GITHUB_PAT",
      message: "Missing GITHUB_PAT in Apps Script Script Properties; cannot dispatch GitHub Actions workflow."
    };
  }

  var inputsObj = rawInputs || {};
  var command = String(inputsObj.command || "DRY_RUN_CYCLE").trim();
  if (ALLOWED_DISPATCH_COMMANDS.indexOf(command) === -1) {
    return {
      ok: false,
      code: "INVALID_WORKFLOW_COMMAND",
      message: "Invalid workflow_dispatch command '" + command + "'."
    };
  }

  var sanitizedInputs = {
    command: command,
    dry_run: String(inputsObj.dry_run === "false" || inputsObj.dry_run === false ? "false" : "true")
  };

  if (inputsObj.reel_pk !== undefined && String(inputsObj.reel_pk).trim() !== "") {
    var pkStr = String(inputsObj.reel_pk).trim();
    if (!RILOT_REGEX.REEL_PK.test(pkStr)) {
      return {
        ok: false,
        code: "INVALID_REEL_PK",
        message: "Invalid reel_pk format for workflow dispatch."
      };
    }
    sanitizedInputs.reel_pk = pkStr;
  }

  if (inputsObj.idempotency_token !== undefined && String(inputsObj.idempotency_token).trim() !== "") {
    var idemStr = String(inputsObj.idempotency_token).trim();
    if (!RILOT_REGEX.IDEMPOTENCY_TOKEN.test(idemStr)) {
      return {
        ok: false,
        code: "INVALID_IDEMPOTENCY_TOKEN",
        message: "Invalid idempotency_token format for workflow dispatch."
      };
    }
    sanitizedInputs.idempotency_token = idemStr;
  }

  if (inputsObj.lock_owner_id !== undefined && String(inputsObj.lock_owner_id).trim() !== "") {
    var lockStr = String(inputsObj.lock_owner_id).trim();
    if (!RILOT_REGEX.LOCK_OWNER_ID.test(lockStr)) {
      return {
        ok: false,
        code: "INVALID_LOCK_OWNER_ID",
        message: "Invalid lock_owner_id format for workflow dispatch."
      };
    }
    sanitizedInputs.lock_owner_id = lockStr;
  }

  var owner = targetValidation.owner;
  var repo = targetValidation.repo;
  var branch = targetValidation.branch;
  var workflow = targetValidation.workflow;
  var url = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/workflows/" + workflow + "/dispatches";

  var requestOptions = {
    method: "post",
    contentType: "application/json",
    muteHttpExceptions: true,
    headers: {
      Authorization: "Bearer " + pat,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "Rilot-Google-Apps-Script/3.0"
    },
    payload: JSON.stringify({
      ref: branch,
      inputs: sanitizedInputs
    })
  };

  try {
    var response;
    if (typeof customFetchAdapter === "function") {
      response = customFetchAdapter(url, requestOptions);
    } else if (typeof UrlFetchApp !== "undefined" && UrlFetchApp && typeof UrlFetchApp.fetch === "function") {
      response = UrlFetchApp.fetch(url, requestOptions);
    } else {
      throw new Error("UrlFetchApp is not available in this environment.");
    }

    var statusCode = typeof response.getResponseCode === "function"
      ? Number(response.getResponseCode())
      : Number(response.status || 500);
    var bodyText = typeof response.getContentText === "function"
      ? String(response.getContentText() || "")
      : String(response.bodyText || "");

    if (statusCode === 204 || (statusCode >= 200 && statusCode < 300)) {
      return {
        ok: true,
        dispatched: true,
        target: owner + "/" + repo + "@" + branch + " (" + workflow + ")",
        note: "Triggered GitHub Actions workflow_dispatch on " + owner + "/" + repo + "@" + branch + " (" + workflow + ")."
      };
    }

    var safeErrText = redactSensitiveValues(bodyText, props).slice(0, 300);
    var code = statusCode === 401 || statusCode === 403
      ? "GITHUB_PERMISSION_DENIED"
      : "GITHUB_DISPATCH_FAILED";
    return {
      ok: false,
      code: code,
      statusCode: statusCode,
      message: "GitHub Actions API returned HTTP " + statusCode + (safeErrText ? ": " + safeErrText : "")
    };
  } catch (err) {
    var safeMsg = redactSensitiveValues(err && err.message ? err.message : String(err), props);
    return {
      ok: false,
      code: "GITHUB_DISPATCH_ERROR",
      message: "Failed to contact GitHub Actions API: " + safeMsg
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    ALLOWED_DISPATCH_COMMANDS: ALLOWED_DISPATCH_COMMANDS,
    dispatchGitHubWorkflow: dispatchGitHubWorkflow
  };
}
