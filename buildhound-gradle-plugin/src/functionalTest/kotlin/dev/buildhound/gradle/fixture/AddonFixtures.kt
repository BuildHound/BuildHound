package dev.buildhound.gradle.fixture

import dev.buildhound.commons.payload.BuildHoundExtensionContributor
import dev.buildhound.commons.payload.ExtensionContributionContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fixture addon contributors (plan 039). Compiled into the `functionalTest` source set and injected
 * onto the plugin-under-test classpath (with a hand-written `META-INF/services` file) so the core
 * finalizer's `ServiceLoader` discovers them — the closest a test can get to a real, separately-built
 * addon. [FixtureExtensionContributor] contributes a real block; [ThrowingExtensionContributor]
 * proves a bad addon can never fail the build or suppress a sibling.
 */
class FixtureExtensionContributor : BuildHoundExtensionContributor {
    override val addonId: String = "fixtureAddon"

    override fun contribute(context: ExtensionContributionContext): JsonElement =
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("taskCount", JsonPrimitive(context.tasks.size))
            put("mode", JsonPrimitive(context.mode.name))
            put("projectKey", JsonPrimitive(context.projectKey ?: ""))
        }
}

class ThrowingExtensionContributor : BuildHoundExtensionContributor {
    override val addonId: String = "throwingAddon"

    override fun contribute(context: ExtensionContributionContext): JsonElement =
        throw RuntimeException("fixture addon boom")
}
