package com.example

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.config.AndroidHardwareKeyVerifier
import com.example.config.AppsScriptSecureKeyStore
import com.example.config.HardwareKeyVerifier
import com.example.config.WorkerEndpointConfig
import com.example.data.RilotDatabase
import com.example.data.RilotRepository
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppsScriptSecureKeyStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppsScriptSecureKeyStore.enableTestMode()
    }

    @After
    fun tearDown() {
        AppsScriptSecureKeyStore.disableTestMode()
    }

    @Test
    fun `keystore failure returns explicit failure and never writes plaintext or Base64`() {
        val prefs = context.getSharedPreferences("test_keystore_failure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Force Keystore failure
        AppsScriptSecureKeyStore.setForceKeystoreFailureForTesting(true)

        val result = AppsScriptSecureKeyStore.saveApiKey(prefs, "super_secret_app_api_key_9999")

        // 1. Must return an explicit failure
        assertTrue("saveApiKey must fail when Keystore throws", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Android Keystore encryption failed") == true
        )

        // 2. Must NOT store plaintext or Base64 in any preference keys
        assertNull(
            "Must not write encrypted token on failure",
            prefs.getString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, null)
        )
        assertNull(
            "Must not write IV on failure",
            prefs.getString(AppsScriptSecureKeyStore.PREF_IV, null)
        )
        assertNull(
            "Must NEVER fall back to Base64 storage on Keystore failure",
            prefs.getString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, null)
        )
        assertNull(
            "Must not write plaintext token",
            prefs.getString("runtime_client_token", null)
        )

        // 3. Loading must return empty string (fail-closed)
        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))
    }

    @Test
    fun `successful encrypted storage persists AES-GCM ciphertext and IV and decrypts accurately`() {
        val prefs = context.getSharedPreferences("test_keystore_success_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val rawKey = "rilot_test_api_key_valid_secret_12345"
        val saveResult = AppsScriptSecureKeyStore.saveApiKey(prefs, rawKey)

        assertTrue("saveApiKey must succeed when test key is active", saveResult.isSuccess)

        val cipherB64 = prefs.getString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, null)
        val ivB64 = prefs.getString(AppsScriptSecureKeyStore.PREF_IV, null)

        // Verify encrypted token exists and is neither plaintext nor Base64 of raw string
        assertTrue(!cipherB64.isNullOrBlank())
        assertTrue(!ivB64.isNullOrBlank())
        assertNotEquals(rawKey, cipherB64)
        assertNotEquals(Base64.encodeToString(rawKey.toByteArray(), Base64.NO_WRAP), cipherB64)

        // Verify obsolete fallback key is absent
        assertNull(prefs.getString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, null))

        // Verify successful round-trip decryption
        val loadedKey = AppsScriptSecureKeyStore.loadApiKey(prefs)
        assertEquals(rawKey, loadedKey)
    }

    @Test
    fun `decryption failure with incorrect key returns explicit failure without using fallback`() {
        val prefs = context.getSharedPreferences("test_wrong_key_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val keyA = AppsScriptSecureKeyStore.generateSoftwareTestKey()
        val keyB = AppsScriptSecureKeyStore.generateSoftwareTestKey()

        // Encrypt with Key A
        AppsScriptSecureKeyStore.enableTestMode(keyA)
        val rawKey = "api_key_encrypted_with_key_a"
        assertTrue(AppsScriptSecureKeyStore.saveApiKey(prefs, rawKey).isSuccess)

        // Plant an obsolete/insecure fallback value in preferences
        prefs.edit().putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, "insecure_base64_decoy").commit()

        // Switch active key to Key B (incorrect key)
        AppsScriptSecureKeyStore.enableTestMode(keyB)

        // Attempting to load with wrong key must fail-closed
        val loadResult = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue("loadApiKeyResult must fail when key is incorrect", loadResult.isFailure)

        val loaded = AppsScriptSecureKeyStore.loadApiKey(prefs)
        assertEquals("loadApiKey must return empty string and not retrieve fallback", "", loaded)
    }

    @Test
    fun `corrupted ciphertext or IV returns explicit failure and does not retrieve fallback`() {
        val prefs = context.getSharedPreferences("test_corrupt_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertTrue(AppsScriptSecureKeyStore.saveApiKey(prefs, "sample_key").isSuccess)

        // Corrupt ciphertext
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, Base64.encodeToString("corrupted_payload".toByteArray(), Base64.NO_WRAP))
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, "insecure_base64_decoy")
            .commit()

        val loadResult = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue(loadResult.isFailure)
        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))
    }

    @Test
    fun `clearing credentials removes encrypted tokens, IV, and any legacy fallback`() {
        val prefs = context.getSharedPreferences("test_clear_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertTrue(AppsScriptSecureKeyStore.saveApiKey(prefs, "key_to_clear").isSuccess)
        assertEquals("key_to_clear", AppsScriptSecureKeyStore.loadApiKey(prefs))

        AppsScriptSecureKeyStore.clearApiKey(prefs)

        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_IV))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED))
    }

    @Test
    fun `safe migration of legitimate existing configuration from legacy fallback to AES-GCM`() {
        val prefs = context.getSharedPreferences("test_migrate_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val legacySecret = "legacy_pre_migration_api_key_555"
        val encodedLegacy = Base64.encodeToString(legacySecret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // Seed with only legacy fallback (no ciphertext and no IV)
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, encodedLegacy)
            .commit()

        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_IV))

        // Loading should safely migrate to AES-GCM and return the secret
        val loadedKey = AppsScriptSecureKeyStore.loadApiKey(prefs)
        assertEquals(legacySecret, loadedKey)

        // Legacy fallback must be removed after migration
        assertFalse(
            "Legacy fallback storage must be removed once safely migrated",
            prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED)
        )

        // Encrypted ciphertext and IV must now be present
        assertTrue(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertTrue(prefs.contains(AppsScriptSecureKeyStore.PREF_IV))

        // Subsequent load should read from encrypted storage directly
        assertEquals(legacySecret, AppsScriptSecureKeyStore.loadApiKey(prefs))
    }

    @Test
    fun `repository updateWorkerEndpointUrl propagates Keystore failure and avoids corrupting memory config`() {
        val prefs = context.getSharedPreferences("test_repo_keystore_failure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val db = Room.inMemoryDatabaseBuilder(context, RilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val repo = RilotRepository(
            dao = db.rilotDao(),
            prefs = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Simulate Keystore failure
        AppsScriptSecureKeyStore.setForceKeystoreFailureForTesting(true)

        val result = repo.updateWorkerEndpointUrl(
            url = "https://script.google.com/macros/s/AKfycb_sample/exec",
            repoSlug = "nexerpy-sys/rilot",
            clientToken = "unpersistable_api_key"
        )

        assertTrue("Repository update must fail when Keystore throws", result.isFailure)
        assertEquals(
            "Memory config runtimeClientToken must remain empty on Keystore failure",
            "",
            repo.workerEndpointConfig.value.runtimeClientToken
        )
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED))

        db.close()
    }

    // --- NEW REGRESSION TESTS REQUIRED BY SPECIFICATION ---

    @Test
    fun `hardware-backed security-level acceptance allows saving and loading credentials`() {
        val prefs = context.getSharedPreferences("test_hw_acceptance", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        var verifierCalled = false
        AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting { key ->
            verifierCalled = true
            true // Verified as TRUSTED_ENVIRONMENT or STRONGBOX
        }

        val saveRes = AppsScriptSecureKeyStore.saveApiKey(prefs, "tee_backed_api_key_777")
        assertTrue(saveRes.isSuccess)
        assertTrue("Hardware verifier must be invoked during key operations", verifierCalled)

        val loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue(loadRes.isSuccess)
        assertEquals("tee_backed_api_key_777", loadRes.getOrNull())
    }

    @Test
    fun `software-backed key rejection prevents credential saving with explicit failure`() {
        val prefs = context.getSharedPreferences("test_software_key_rejection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Hardware verifier reports that the key is software-backed (not inside TEE/StrongBox)
        AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting { key ->
            false
        }

        val saveRes = AppsScriptSecureKeyStore.saveApiKey(prefs, "software_insecure_key_111")
        assertTrue("Saving must be rejected when key is software-backed", saveRes.isFailure)
        assertTrue(
            saveRes.exceptionOrNull()?.message?.contains("not backed by secure hardware") == true
        )

        // Nothing must be persisted
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_IV))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED))
    }

    @Test
    fun `missing or unsupported security-level information is rejected with explicit failure`() {
        val prefs = context.getSharedPreferences("test_unsupported_security_level", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Simulates provider failing to provide KeyInfo or throwing UnsupportedOperationException
        AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting { key ->
            throw UnsupportedOperationException("KeyInfo not supported by this cryptographic provider")
        }

        val saveRes = AppsScriptSecureKeyStore.saveApiKey(prefs, "unsupported_info_key")
        assertTrue("Saving must fail when security-level info is missing/unsupported", saveRes.isFailure)
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
        assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED))
    }

    @Test
    fun `an existing key that fails hardware verification is rejected on load with explicit failure`() {
        val prefs = context.getSharedPreferences("test_existing_key_fails_hw", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // 1. First, save key with valid hardware verification
        AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting { true }
        assertTrue(AppsScriptSecureKeyStore.saveApiKey(prefs, "key_to_be_invalidated").isSuccess)

        // 2. Later, when loading, the hardware verifier rejects the key
        AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting { false }

        val loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue("Loading must fail when existing key fails hardware verification", loadRes.isFailure)
        assertTrue(
            loadRes.exceptionOrNull()?.message?.contains("not backed by secure hardware") == true
        )
        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))
    }

    @Test
    fun `missing ciphertext with an existing IV returns explicit failure and does not attempt migration`() {
        val prefs = context.getSharedPreferences("test_missing_cipher_with_iv", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Plant existing IV and a legacy fallback value
        val dummyIv = Base64.encodeToString(ByteArray(12) { 1 }, Base64.NO_WRAP)
        val legacyDecoy = Base64.encodeToString("legacy_decoy_secret".toByteArray(), Base64.NO_WRAP)
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_IV, dummyIv)
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, legacyDecoy)
            .commit()

        val loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue("Incomplete state (missing ciphertext) must return explicit failure", loadRes.isFailure)
        assertTrue(
            loadRes.exceptionOrNull()?.message?.contains("Incomplete encrypted state") == true
        )

        // Must never return the planted legacy fallback
        val key = AppsScriptSecureKeyStore.loadApiKey(prefs)
        assertEquals("Must return empty string and never the legacy decoy", "", key)
    }

    @Test
    fun `existing ciphertext with a missing IV returns explicit failure and does not attempt migration`() {
        val prefs = context.getSharedPreferences("test_cipher_with_missing_iv", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Plant existing ciphertext and a legacy fallback value
        val dummyCipher = Base64.encodeToString(ByteArray(32) { 2 }, Base64.NO_WRAP)
        val legacyDecoy = Base64.encodeToString("legacy_decoy_secret".toByteArray(), Base64.NO_WRAP)
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, dummyCipher)
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, legacyDecoy)
            .commit()

        val loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue("Incomplete state (missing IV) must return explicit failure", loadRes.isFailure)
        assertTrue(
            loadRes.exceptionOrNull()?.message?.contains("Incomplete encrypted state") == true
        )

        // Must never return the planted legacy fallback
        val key = AppsScriptSecureKeyStore.loadApiKey(prefs)
        assertEquals("Must return empty string and never the legacy decoy", "", key)
    }

    @Test
    fun `confirmation that incomplete or malformed encrypted state never returns a planted legacy fallback`() {
        val prefs = context.getSharedPreferences("test_malformed_state_decoy", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val legacyDecoy = Base64.encodeToString("stolen_secret_fallback".toByteArray(), Base64.NO_WRAP)

        // 1. Both preferences exist, but IV is invalid Base64
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, "valid_b64==")
            .putString(AppsScriptSecureKeyStore.PREF_IV, "not_valid_base64!!!")
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, legacyDecoy)
            .commit()

        var loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue(loadRes.isFailure)
        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))

        // 2. Both preferences exist, but ciphertext decodes to empty bytes
        prefs.edit()
            .putString(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN, "")
            .putString(AppsScriptSecureKeyStore.PREF_IV, Base64.encodeToString(ByteArray(12), Base64.NO_WRAP))
            .putString(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED, legacyDecoy)
            .commit()

        loadRes = AppsScriptSecureKeyStore.loadApiKeyResult(prefs)
        assertTrue(loadRes.isFailure)
        assertEquals("", AppsScriptSecureKeyStore.loadApiKey(prefs))
    }

    private fun withSimulatedReleaseBuild(block: () -> Unit) {
        val field = com.example.BuildConfig::class.java.getDeclaredField("DEBUG")
        field.isAccessible = true
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val base = unsafe.staticFieldBase(field)
        val offset = unsafe.staticFieldOffset(field)
        val original = com.example.BuildConfig.DEBUG
        try {
            unsafe.putBoolean(base, offset, false)
            assertEquals(false, com.example.BuildConfig.DEBUG)
            block()
        } finally {
            unsafe.putBoolean(base, offset, original)
            assertEquals(true, com.example.BuildConfig.DEBUG)
        }
    }

    @Test
    fun `confirmation that the test-only key mechanism cannot be enabled in release builds`() {
        withSimulatedReleaseBuild {
            try {
                AppsScriptSecureKeyStore.enableTestMode()
                fail("Enabling test mode in a release build must throw SecurityException")
            } catch (e: SecurityException) {
                assertTrue(
                    "Exception message must explain release build prohibition",
                    e.message?.contains("release builds") == true
                )
            }
        }
    }

    @Test
    fun `confirmation that test hardware verifier cannot be configured in release builds`() {
        withSimulatedReleaseBuild {
            try {
                AppsScriptSecureKeyStore.setTestHardwareVerifierForTesting(HardwareKeyVerifier { true })
                fail("Configuring test hardware verifier in a release build must throw SecurityException")
            } catch (e: SecurityException) {
                assertTrue(
                    "Exception message must explain release build prohibition",
                    e.message?.contains("release builds") == true
                )
            }
        }
    }

    @Test
    fun `confirmation that simulated keystore failure cannot be configured in release builds`() {
        withSimulatedReleaseBuild {
            try {
                AppsScriptSecureKeyStore.setForceKeystoreFailureForTesting(true)
                fail("Configuring simulated Keystore failure in a release build must throw SecurityException")
            } catch (e: SecurityException) {
                assertTrue(
                    "Exception message must explain release build prohibition",
                    e.message?.contains("release builds") == true
                )
            }
        }
    }

    @Test
    fun `confirmation that software test keys cannot be generated in release builds`() {
        withSimulatedReleaseBuild {
            try {
                AppsScriptSecureKeyStore.generateSoftwareTestKey()
                fail("Generating software test keys in a release build must throw SecurityException")
            } catch (e: SecurityException) {
                assertTrue(
                    "Exception message must explain release build prohibition",
                    e.message?.contains("release builds") == true
                )
            }
        }
    }

    @Test
    fun `confirmation that test hooks cannot bypass hardware verification or activate test-key mode in a release build`() {
        val prefs = context.getSharedPreferences("test_release_guard_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // In debug mode, test mode was activated with a software key and permissive verifier
        assertTrue(AppsScriptSecureKeyStore.isTestMode)
        assertTrue(AppsScriptSecureKeyStore.testSecretKey != null)

        // When executing under a release build, test hooks and keys are strictly prohibited from bypassing hardware verification
        withSimulatedReleaseBuild {
            val result = AppsScriptSecureKeyStore.saveApiKey(prefs, "sample_key_in_release")
            assertTrue("Key saving must fail when test hooks are present in release build", result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(
                "Must reject with SecurityException or Keystore failure",
                exception is SecurityException || exception?.cause is SecurityException ||
                    exception?.message?.contains("release builds") == true
            )
            // Ensure no credentials were saved
            assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_ENCRYPTED_TOKEN))
            assertFalse(prefs.contains(AppsScriptSecureKeyStore.PREF_FALLBACK_OBFUSCATED))
        }
    }
}
