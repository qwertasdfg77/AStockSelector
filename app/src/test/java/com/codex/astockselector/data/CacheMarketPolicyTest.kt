package com.codex.astockselector.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMarketPolicyTest {
    @Test
    fun cacheWithoutBarsDoesNotRequireMigration() {
        assertTrue(CacheMarketRepository.isCachePriceModeCompatible(0, ""))
    }

    @Test
    fun legacyCacheWithBarsRequiresMigration() {
        assertFalse(CacheMarketRepository.isCachePriceModeCompatible(1_000, ""))
    }

    @Test
    fun currentBfqCacheIsCompatible() {
        assertTrue(
            CacheMarketRepository.isCachePriceModeCompatible(
                dailyBarCount = 1_000,
                priceMode = CacheMarketRepository.CACHE_PRICE_MODE,
            ),
        )
    }

    @Test
    fun staleMissingOrFutureStockDateDoesNotMatchExpectedDate() {
        assertFalse(CacheMarketRepository.matchesExpectedTradeDate("20260813", "20260814"))
        assertFalse(CacheMarketRepository.matchesExpectedTradeDate("", "20260814"))
        assertFalse(CacheMarketRepository.matchesExpectedTradeDate("20260815", "20260814"))
    }

    @Test
    fun currentStockDateMatchesExpectedDate() {
        assertTrue(CacheMarketRepository.matchesExpectedTradeDate("20260814", "20260814"))
    }

    @Test
    fun klineUpdateEligibilityUsesCurrentMinimumAmount() {
        assertFalse(CacheMarketRepository.isEligibleForKlineUpdate(9_999_999.0, 10_000_000.0))
        assertTrue(CacheMarketRepository.isEligibleForKlineUpdate(10_000_000.0, 10_000_000.0))
        assertTrue(CacheMarketRepository.isEligibleForKlineUpdate(50_000_000.0, 10_000_000.0))
    }

    @Test
    fun exhaustedFailureDoesNotBypassRetryLimitForIncompleteStock() {
        assertFalse(
            CacheMarketRepository.shouldUpdateCacheCandidate(
                forceRebuild = false,
                failureCanRetry = false,
                isNewOrIncomplete = true,
                missingExpectedDate = true,
                retryableFailure = false,
            ),
        )
    }

    @Test
    fun forceRebuildCanRetryAfterDailyFailureLimit() {
        assertTrue(
            CacheMarketRepository.shouldUpdateCacheCandidate(
                forceRebuild = true,
                failureCanRetry = false,
                isNewOrIncomplete = true,
                missingExpectedDate = true,
                retryableFailure = false,
            ),
        )
    }

    @Test
    fun retryableGapIsScheduledForUpdate() {
        assertTrue(
            CacheMarketRepository.shouldUpdateCacheCandidate(
                forceRebuild = false,
                failureCanRetry = true,
                isNewOrIncomplete = false,
                missingExpectedDate = true,
                retryableFailure = true,
            ),
        )
    }
}
