package com.example.config

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.example.BuildConfig
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

data class EndpointSpec(
    val method: String,
    val path: String,
    val purpose: String
)

/**
 * Verifier interface to inspect whether an Android Keystore SecretKey is backed by
 * secure hardware (Trusted Execution Environment or StrongBox).
 */
fun interface HardwareKeyVerifier {
    fun verifyHardwareBacking(secretKey: SecretKey): Boolean
}

/**
 * Standard implementation that queries KeyInfo from SecretKeyFactory("AndroidKeyStore").
 * - API 31+ (Android 12 S+): Requires SECURITY_LEVEL_TRUSTED_ENVIRONMENT or SECURITY_LEVEL_STRONGBOX.
 * - API <31: Requires isInsideSecureHardware.
 */
class AndroidHardwareKeyVerifier : HardwareKeyVerifier {
    override fun verifyHardwareBacking(secretKey: SecretKey): Boolean {
        return try {
            val factory = SecretKeyFactory.getInstance(secretKey.algorithm, "AndroidKeyStore")
            val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as? KeyInfo ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val level = keyInfo.securityLevel
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    level == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Android Keystore-backed helper for encrypting and decrypting the Rilot App API Key
 * (`RILOT_APP_API_KEY`) in local SharedPreferences using AES-256-GCM authenticated encryption.
 *
 * Security Guarantee:
 * - On Android devices, the master AES-256 key is strictly stored inside the hardware-backed
 *   AndroidKeyStore provider (TEE or StrongBox verified via KeyInfo).
 * - Under no circumstances does this class fall back to software keys or Base64 storage in production.
 * - Incomplete or malformed encrypted state is rejected immediately and never attempts fallback migration.
 * - Loading and decryption failures fail-closed and never retrieve an unencrypted fallback value.
 * - Legacy fallback storage from prior versions is migrated only when no encrypted state exists, and
 *   permanently deleted once safely stored in hardware Keystore.
 * - Explicit test hooks are provided for local JVM / Robolectric tests, and prohibited in release builds.
 */
object AppsScriptSecureKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rilot_apps_script_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    const val PREF_ENCRYPTED_TOKEN = "apps_script_api_key_ciphertext"
    const val PREF_IV = "apps_script_api_key_iv"
    const val PREF_FALLBACK_OBFUSCATED = "apps_script_api_key_fallback"

    private val productionHardwareVerifier: HardwareKeyVerifier = AndroidHardwareKeyVerifier()

    private var testHardwareVerifier: HardwareKeyVerifier? = null

    /**
     * Explicit test-only mechanism for local JVM unit tests and Robolectric environments
     * where the platform AndroidKeyStore provider is unavailable.
     * Enforced strictly: guarded by BuildConfig.DEBUG and rejected in release builds.
     */
    @VisibleForTesting
    var isTestMode: Boolean = false
        private set

    @VisibleForTesting
    var testSecretKey: SecretKey? = null
        private set

    @VisibleForTesting
    var forceKeystoreFailureForTesting: Boolean = false
        private set

    @VisibleForTesting
    fun enableTestMode(
        customKey: SecretKey? = null,
        testVerifier: HardwareKeyVerifier? = HardwareKeyVerifier { true }
    ) {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Test-only key mechanism cannot be enabled in release builds.")
        }
        isTestMode = true
        testSecretKey = customKey ?: generateSoftwareTestKey()
        testHardwareVerifier = testVerifier
        forceKeystoreFailureForTesting = false
    }

    @VisibleForTesting
    fun disableTestMode() {
        isTestMode = false
        testSecretKey = null
        testHardwareVerifier = null
        forceKeystoreFailureForTesting = false
    }

    @VisibleForTesting
    fun setTestHardwareVerifierForTesting(verifier: HardwareKeyVerifier?) {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Test hardware verifier cannot be configured in release builds.")
        }
        testHardwareVerifier = verifier
    }

    @VisibleForTesting
    fun setForceKeystoreFailureForTesting(forceFailure: Boolean) {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Simulated Keystore failure cannot be configured in release builds.")
        }
        forceKeystoreFailureForTesting = forceFailure
    }

    @VisibleForTesting
    fun generateSoftwareTestKey(): SecretKey {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Software test keys cannot be generated in release builds.")
        }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    /**
     * Encrypts and persists the API key using AES-256-GCM.
     * Returns Result.success(Unit) on success.
     * If key generation, hardware verification, encryption, or persistence fails,
     * returns Result.failure with an explicit error and strictly avoids storing plaintext or Base64.
     */
    fun saveApiKey(prefs: SharedPreferences, rawApiKey: String): Result<Unit> {
        val trimmed = rawApiKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey(prefs)
            return Result.success(Unit)
        }
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv ?: throw IllegalStateException("Cipher failed to generate IV for AES-GCM")

            val committed = prefs.edit()
                .putString(PREF_ENCRYPTED_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .remove(PREF_FALLBACK_OBFUSCATED)
                .remove("runtime_client_token")
                .remove("worker_auth_token")
                .commit()

            if (!committed) {
                Result.failure(IllegalStateException("Failed to write encrypted credentials to storage."))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Android Keystore encryption failed: ${e.message ?: e::class.java.simpleName}",
                    e
                )
            )
        }
    }

    /**
     * Loads and decrypts the API key.
     * Returns Result.success(key) or Result.failure on decryption/loading error.
     *
     * State Rules:
     * - If either ciphertext or IV preference exists, requires both to be present and non-blank.
     * - Incomplete or malformed encrypted state returns explicit failure and never attempts fallback.
     * - Decryption failures fail-closed and never retrieve an unencrypted fallback value.
     * - Legacy fallback migration is only attempted when neither encrypted preference exists.
     */
    fun loadApiKeyResult(prefs: SharedPreferences): Result<String> {
        val hasCipherPref = prefs.contains(PREF_ENCRYPTED_TOKEN)
        val hasIvPref = prefs.contains(PREF_IV)
        val cipherB64 = prefs.getString(PREF_ENCRYPTED_TOKEN, null)
        val ivB64 = prefs.getString(PREF_IV, null)

        // If either the ciphertext preference or the IV preference exists, require both to be valid.
        if (hasCipherPref || hasIvPref || cipherB64 != null || ivB64 != null) {
            if (cipherB64.isNullOrBlank() || ivB64.isNullOrBlank()) {
                return Result.failure(
                    IllegalStateException("Incomplete encrypted state: both ciphertext and IV are required to be present and non-blank")
                )
            }

            val iv = try {
                Base64.decode(ivB64, Base64.NO_WRAP)
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException("Malformed encrypted state: invalid Base64 in IV preference", e)
                )
            }

            val ciphertext = try {
                Base64.decode(cipherB64, Base64.NO_WRAP)
            } catch (e: Exception) {
                return Result.failure(
                    IllegalStateException("Malformed encrypted state: invalid Base64 in ciphertext preference", e)
                )
            }

            if (iv.isEmpty() || ciphertext.isEmpty()) {
                return Result.failure(
                    IllegalStateException("Malformed encrypted state: ciphertext or IV is empty after decoding")
                )
            }

            return try {
                val secretKey = getOrCreateSecretKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8).trim()

                // Decrypted successfully with Keystore.
                // Safely remove any obsolete legacy fallback if still present.
                if (prefs.contains(PREF_FALLBACK_OBFUSCATED)) {
                    prefs.edit().remove(PREF_FALLBACK_OBFUSCATED).apply()
                }
                Result.success(decrypted)
            } catch (e: Exception) {
                // Decryption failure must NEVER retrieve an insecure fallback value.
                Result.failure(
                    IllegalStateException(
                        "Android Keystore decryption failed: ${e.message ?: e::class.java.simpleName}",
                        e
                    )
                )
            }
        }

        // Neither encrypted entry exists. Check if an existing legacy configuration exists from a prior app version.
        val legacyFallbackB64 = prefs.getString(PREF_FALLBACK_OBFUSCATED, null)
        if (!legacyFallbackB64.isNullOrBlank()) {
            val decoded = runCatching {
                String(Base64.decode(legacyFallbackB64, Base64.NO_WRAP), Charsets.UTF_8).trim()
            }.getOrNull()

            if (!decoded.isNullOrEmpty()) {
                // Safely migrate legitimate existing configuration to hardware Keystore encryption
                val migrationResult = saveApiKey(prefs, decoded)
                if (migrationResult.isSuccess) {
                    return Result.success(decoded)
                } else {
                    // Migration failed. Fail-closed: do NOT return insecure fallback.
                    return Result.failure(
                        IllegalStateException(
                            "Found legacy credentials but failed to migrate to secure Android Keystore: ${migrationResult.exceptionOrNull()?.message}"
                        )
                    )
                }
            }
        }

        return Result.success("")
    }

    fun loadApiKey(prefs: SharedPreferences): String {
        return loadApiKeyResult(prefs).getOrDefault("")
    }

    fun clearApiKey(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_ENCRYPTED_TOKEN)
            .remove(PREF_IV)
            .remove(PREF_FALLBACK_OBFUSCATED)
            .remove("runtime_client_token")
            .remove("worker_auth_token")
            .commit()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        // Enforce release build restrictions: test hooks and software test keys are strictly prohibited in release builds.
        if (!BuildConfig.DEBUG) {
            if (isTestMode || testSecretKey != null || testHardwareVerifier != null || forceKeystoreFailureForTesting) {
                throw SecurityException("Test hooks and test keys are strictly prohibited in release builds.")
            }
        }

        if (BuildConfig.DEBUG && forceKeystoreFailureForTesting) {
            throw KeyStoreException("Simulated Android Keystore hardware failure for testing")
        }

        if (BuildConfig.DEBUG && isTestMode && testSecretKey != null) {
            val key = testSecretKey!!
            val verifier = testHardwareVerifier ?: HardwareKeyVerifier { true }
            if (!verifier.verifyHardwareBacking(key)) {
                throw KeyStoreException("Key is not backed by secure hardware (Trusted Execution Environment or StrongBox required)")
            }
            return key
        }

        // In production and release builds, ALWAYS use the immutable productionHardwareVerifier (AndroidHardwareKeyVerifier).
        // In debug builds without test mode, testHardwareVerifier can only be used if explicitly set by a debug unit test.
        val verifier = if (BuildConfig.DEBUG && testHardwareVerifier != null) {
            testHardwareVerifier!!
        } else {
            productionHardwareVerifier
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            val key = existing.secretKey
            if (!verifier.verifyHardwareBacking(key)) {
                throw KeyStoreException("Existing Android Keystore key '$KEY_ALIAS' is not backed by secure hardware (Trusted Execution Environment or StrongBox required)")
            }
            return key
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()
        if (!verifier.verifyHardwareBacking(key)) {
            try { keyStore.deleteEntry(KEY_ALIAS) } catch (_: Throwable) {}
            throw KeyStoreException("Newly generated Android Keystore key '$KEY_ALIAS' is not backed by secure hardware (Trusted Execution Environment or StrongBox required)")
        }
        return key
    }
}

/**
 * Configuration for the Rilot Google Apps Script Web App backend (`https://script.google.com/macros/s/.../exec`)
 * and GitHub Actions workflow dispatch.
 *
 * NOTE: `githubRepoSlug` is an informational UI reference only. The actual GitHub owner,
 * repository (`nexerpy-sys/rilot`), branch (`main`), workflow (`insta-automation-cycle.yml`),
 * and `GITHUB_PAT` used for `workflow_dispatch` are enforced server-side in Google Apps Script Properties.
 */
data class WorkerEndpointConfig(
    val workerBaseUrl: String = DEFAULT_PLACEHOLDER_URL,
    val githubRepoSlug: String = DEFAULT_GITHUB_REPO_REFERENCE,
    val runtimeClientToken: String = "",
    val workflowFileName: String = DEFAULT_WORKFLOW_FILE_NAME,
    val timeoutSeconds: Long = 25L
) {
    val isPlaceholderEndpoint: Boolean
        get() = workerBaseUrl.isBlank() ||
            workerBaseUrl.contains("YOUR_DEPLOYMENT_ID", ignoreCase = true) ||
            !workerBaseUrl.trim().startsWith("https://script.google.com/macros/s/", ignoreCase = true) ||
            workerBaseUrl.contains("example.com", ignoreCase = true)

    fun normalizedBaseUrl(): String {
        val trimmed = workerBaseUrl.trim()
        if (trimmed.endsWith("/exec", ignoreCase = true) || trimmed.endsWith("/dev", ignoreCase = true)) {
            return trimmed
        }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    val maskedTokenPreview: String
        get() {
            val clean = runtimeClientToken.trim()
            if (clean.isEmpty()) return "Not configured"
            if (clean.length <= 8) return "••••••••"
            return "${clean.take(4)}••••••••${clean.takeLast(4)}"
        }

    fun redactSecrets(message: String?): String {
        if (message.isNullOrBlank()) return ""
        val cleanToken = runtimeClientToken.trim()
        return if (cleanToken.length >= 4) {
            message.replace(cleanToken, "[REDACTED]")
        } else {
            message
        }
    }

    companion object {
        const val DEFAULT_PLACEHOLDER_URL =
            "https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec"
        const val DEFAULT_GITHUB_REPO_REFERENCE =
            "Configured in Apps Script Properties (nexerpy-sys/rilot @ main)"
        const val DEFAULT_WORKFLOW_FILE_NAME =
            "insta-automation-cycle.yml"

        val ENDPOINT_SPECS: List<EndpointSpec> = listOf(
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.connectionTest",
                purpose = "Verify Google Apps Script reachability, Google Sheets tabs initialization, GitHub dispatch config validity, and engine heartbeat."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.getStatus",
                purpose = "Fetch current automation run state, session health, IST timestamps, and cycle metrics from Google Sheets."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.setControl",
                purpose = "Start cycle, pause/resume automation, or trigger GitHub Actions workflow_dispatch on nexerpy-sys/rilot (main)."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.getDiscoveryConfig",
                purpose = "Retrieve persisted hashtag targets, candidate pool limits, scoring weights, and fail-closed authorization policies."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.updateDiscoveryConfig",
                purpose = "Sync hashtag targets, candidate collection limit, qualifying pool size, scoring weights, and explicit reuse authorization."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.getScheduleConfig",
                purpose = "Retrieve Asia/Kolkata (IST) posting windows, interval hours, max posts per cycle, daily caps, and dry-run/auto-publish flags."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.updateScheduleConfig",
                purpose = "Sync Asia/Kolkata (IST) posting windows, interval hours, max posts per cycle, daily caps, and dry-run/auto-publish flags."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.getCaptions",
                purpose = "Retrieve fixed & viral copypasta caption library and rotation usage counters from the Captions sheet tab."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.updateCaptions",
                purpose = "Push updated caption library templates and rewrite mode to the Captions sheet tab."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.getActivity",
                purpose = "Retrieve recent candidate evaluations, selection reasons, downloader logs, and posted.db status from the Activity sheet tab."
            ),
            EndpointSpec(
                method = "POST (RPC)",
                path = "action: app.safeRetry",
                purpose = "Idempotently retry a failed download/upload after verifying PK is not in posted.db and revalidating authorization."
            )
        )
    }
}
