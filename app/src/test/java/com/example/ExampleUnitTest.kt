package com.example

import com.example.config.WorkerEndpointConfig
import com.example.model.CaptionCategory
import com.example.model.CaptionTemplate
import com.example.model.ConfigValidator
import com.example.model.DiscoveryConfig
import com.example.model.HashtagTarget
import com.example.model.ScheduleConfig
import com.example.network.CloudflareWorkerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `default schedule config enforces Asia Kolkata timezone and keeps auto publish disabled`() {
        val schedule = ScheduleConfig()
        assertEquals("Asia/Kolkata", schedule.timezoneId)
        assertTrue("Dry-run should be enabled by default", schedule.dryRunMode)
        assertFalse("Automatic publishing must be disabled by default", schedule.autoPublishEnabled)

        val validation = ConfigValidator.validateScheduleConfig(schedule)
        assertTrue(validation.isValid)
    }

    @Test
    fun `enabling auto publish before backend verification is rejected`() {
        val invalidSchedule = ScheduleConfig(
            autoPublishEnabled = true,
            backendTestingVerified = false
        )
        val validation = ConfigValidator.validateScheduleConfig(invalidSchedule)
        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("Automatic publishing must remain disabled") })
    }

    @Test
    fun `discovery config validates candidate collection limit independently from publishing limit`() {
        val hashtags = listOf(
            HashtagTarget(id = 1L, tag = "fitness", category = "Strength", isEnabled = true)
        )
        val validConfig = DiscoveryConfig(
            candidateCollectionLimitPerTag = 30,
            qualifyingPoolSize = 10
        )
        val result = ConfigValidator.validateDiscoveryConfig(
            config = validConfig,
            hashtags = hashtags,
            maxPostsPerCycle = 2
        )
        assertTrue(result.isValid)

        val invalidPoolConfig = validConfig.copy(qualifyingPoolSize = 1)
        val invalidResult = ConfigValidator.validateDiscoveryConfig(
            config = invalidPoolConfig,
            hashtags = hashtags,
            maxPostsPerCycle = 2
        )
        assertFalse(invalidResult.isValid)
    }

    @Test
    fun `anti-repeat caption selector avoids recently used templates`() {
        val templates = listOf(
            CaptionTemplate(
                id = 1L,
                title = "Recent 1",
                body = "Body 1 {hashtag}",
                category = CaptionCategory.VIRAL_COPYPASTA,
                isAbsurdCopypasta = true,
                usageCount = 5,
                lastUsedEpochMs = 3000L
            ),
            CaptionTemplate(
                id = 2L,
                title = "Recent 2",
                body = "Body 2 {hashtag}",
                category = CaptionCategory.VIRAL_COPYPASTA,
                isAbsurdCopypasta = true,
                usageCount = 4,
                lastUsedEpochMs = 2000L
            ),
            CaptionTemplate(
                id = 3L,
                title = "Fresh Copypasta",
                body = "Body 3 {hashtag}",
                category = CaptionCategory.VIRAL_COPYPASTA,
                isAbsurdCopypasta = true,
                usageCount = 1,
                lastUsedEpochMs = 500L
            )
        )

        val selected = ConfigValidator.selectNextCaption(templates, recentExcludeCount = 2)
        assertNotNull(selected)
        assertEquals(3L, selected?.id)
        assertNotEquals(1L, selected?.id)
        assertNotEquals(2L, selected?.id)
    }

    @Test
    fun `apps script endpoint config detects placeholder url builds rpc envelope and redacts secrets`() {
        val defaultWorker = WorkerEndpointConfig()
        assertTrue(defaultWorker.isPlaceholderEndpoint)
        assertEquals("", defaultWorker.runtimeClientToken)
        assertEquals("Not configured", defaultWorker.maskedTokenPreview)
        assertEquals("insta-automation-cycle.yml", defaultWorker.workflowFileName)
        assertTrue(WorkerEndpointConfig.ENDPOINT_SPECS.size >= 11)

        val configuredWorker = WorkerEndpointConfig(
            workerBaseUrl = "https://script.google.com/macros/s/AKfycbx1234567890/exec",
            runtimeClientToken = "rilot_app_secret_987654"
        )
        assertFalse(configuredWorker.isPlaceholderEndpoint)
        assertEquals(
            "https://script.google.com/macros/s/AKfycbx1234567890/exec",
            configuredWorker.normalizedBaseUrl()
        )
        assertEquals("rilo••••••••7654", configuredWorker.maskedTokenPreview)
        assertEquals(
            "Error with key [REDACTED] rejected",
            configuredWorker.redactSecrets("Error with key rilot_app_secret_987654 rejected")
        )

        val headersWithToken = CloudflareWorkerClientFactory.buildSecurityHeaders(
            clientToken = "rilot_app_secret_987654",
            timestampMs = 1730000000000L,
            nonce = "nonce_abc"
        )
        assertEquals("Bearer rilot_app_secret_987654", headersWithToken["Authorization"])
        assertEquals("1730000000000", headersWithToken["X-Rilot-Timestamp"])
        assertEquals("nonce_abc", headersWithToken["X-Rilot-Nonce"])

        val rpcEnvelope = CloudflareWorkerClientFactory.buildRpcEnvelope(
            action = "app.getStatus",
            authToken = "rilot_app_secret_987654",
            timestampMs = 1730000000000L,
            nonce = "nonce_abc",
            requestId = "req_status_1"
        )
        assertEquals("app.getStatus", rpcEnvelope.action)
        assertEquals("rilot_app_secret_987654", rpcEnvelope.authToken)
        assertEquals(1730000000000L, rpcEnvelope.timestamp)
        assertEquals("nonce_abc", rpcEnvelope.nonce)
        assertEquals("req_status_1", rpcEnvelope.requestId)

        val headersWithoutToken = CloudflareWorkerClientFactory.buildSecurityHeaders(
            clientToken = "   ",
            timestampMs = 1730000000000L,
            nonce = "nonce_def"
        )
        assertNull(headersWithoutToken["Authorization"])
        assertEquals(
            WorkerEndpointConfig.DEFAULT_GITHUB_REPO_REFERENCE,
            defaultWorker.githubRepoSlug
        )
    }

    @Test
    fun `discovery config defaults to fail closed content authorization and blocks unauthorized or ambiguous retries`() {
        val discovery = DiscoveryConfig()
        assertFalse(
            "Discovery config must default to fail-closed reuse authorization (false)",
            discovery.defaultReuseAuthorized
        )
        assertFalse(
            "Broad hashtag authorization policy must default to false",
            discovery.allowAllHashtagCandidatesWhenTargetAuthorized
        )
        assertFalse(com.example.model.CandidateOutcomeStatus.UNAUTHORIZED_BLOCKED.isRetryable)
        assertFalse(com.example.model.CandidateOutcomeStatus.IN_FLIGHT_AMBIGUOUS.isRetryable)
        assertFalse(com.example.model.CandidateOutcomeStatus.PUBLISHED.isRetryable)
        assertTrue(com.example.model.CandidateOutcomeStatus.DOWNLOAD_FAILED.isRetryable)
        assertTrue(com.example.model.CandidateOutcomeStatus.UPLOAD_FAILED.isRetryable)
    }
}
