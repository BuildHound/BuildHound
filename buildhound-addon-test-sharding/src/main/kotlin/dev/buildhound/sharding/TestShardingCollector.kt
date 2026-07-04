package dev.buildhound.sharding

import dev.buildhound.commons.payload.BuildHoundExtensionContributor
import dev.buildhound.commons.payload.BuildHoundJson
import dev.buildhound.commons.payload.ExtensionContributionContext
import kotlinx.serialization.json.JsonElement

/**
 * Contributes `extensions["testSharding"]` (plan 040) — the ServiceLoader-discovered
 * [BuildHoundExtensionContributor] core's finalizer evaluates (plan 039). Read-and-clears the sharding
 * outcome recorded by this build's filter application, so the payload records `shardPlanId`/`shardIndex`
 * and whether the filter actually applied (false = run-all fallback). Returns null when this build did
 * not shard (no index / inert) — the common non-CI case. Only the *feedback* needs core; the filter
 * itself works without core applied.
 */
class TestShardingCollector : BuildHoundExtensionContributor {

    override val addonId: String = TestShardingExtension.EXTENSION_KEY

    override fun contribute(context: ExtensionContributionContext): JsonElement? {
        val outcome = ShardingState.takeOutcome() ?: return null
        return BuildHoundJson.payload.encodeToJsonElement(TestShardingExtension.serializer(), outcome)
    }
}
