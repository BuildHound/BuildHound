package dev.buildhound.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Entry point, applied in `settings.gradle.kts` (spec §3.1). Everything registered here must be
 * configuration-cache safe: no `Project` references at execution time, state flows through
 * providers and build-service parameters only.
 */
@Suppress("UnstableApiUsage")
abstract class BuildHoundSettingsPlugin @Inject constructor(
    private val eventsListenerRegistry: BuildEventsListenerRegistry,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
    private val buildFeatures: BuildFeatures,
) : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("buildhound", BuildHoundExtension::class.java)
        extension.enabled.convention(true)
        extension.mode.convention(TelemetryMode.AUTO)
        extension.identity.pseudonymize.convention(true)
        extension.htmlReport.enabled.convention(true)
        extension.localBuilds.enabled.convention(true)
        extension.localBuilds.requireOptInFile.convention(true)

        // In a composite, only the root build observes: task events from included builds
        // already reach the root's listener, and a second flow action would consume the
        // DaemonState mark twice and emit duplicate summaries (review finding, plan 003).
        if (settings.gradle.parent != null) {
            logger.info("[buildhound] applied in an included build; the root build's plugin collects")
            return
        }

        DaemonState.configurationRan()

        val collector = settings.gradle.sharedServices.registerIfAbsent(
            TaskEventCollector.SERVICE_NAME,
            TaskEventCollector::class.java,
        ) {}

        eventsListenerRegistry.onTaskCompletion(collector)

        // Salt IO happens inside the ValueSource at execution time: config-phase file
        // access is a CC fingerprint input, and creating the salt at apply time would
        // invalidate the next build's cache entry. Only the path is captured here.
        val environment = settings.providers.of(EnvironmentValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.pseudonymize.set(extension.identity.pseudonymize)
            spec.parameters.identitySaltFile.set(File(settings.rootDir, SALT_PATH).absolutePath)
        }

        val vcs = settings.providers.of(VcsValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            // Test seam + escape hatch (plan 015) for repos where a healthy git needs >10 s.
            spec.parameters.timeoutMillis.set(
                settings.providers.gradleProperty("buildhound.vcs.timeout.ms")
                    .map { raw -> raw.toLongOrNull()?.takeIf { it > 0 } ?: GitExec.DEFAULT_TIMEOUT_MS },
            )
        }

        val ci = settings.providers.of(CiValueSource::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
        }

        // Flow API is the CC-safe "build finished" hook (spec §3.2). The finalizer
        // assembles the payload and writes it next to the build outputs; the HTML
        // artifact and upload chunks build on it. It must never fail the build.
        flowScope.always(TelemetryFinalizerAction::class.java) { spec ->
            spec.parameters.enabled.set(extension.enabled)
            spec.parameters.mode.set(extension.mode)
            spec.parameters.tags.set(extension.tags)
            spec.parameters.collector.set(collector)
            spec.parameters.buildFailed.set(flowProviders.buildWorkResult.map { it.failure.isPresent })
            spec.parameters.environment.set(environment)
            spec.parameters.vcs.set(vcs)
            spec.parameters.ci.set(ci)
            spec.parameters.configurationCacheRequested.set(buildFeatures.configurationCache.requested.getOrElse(false))
            // Lazy: the settings script sets rootProject.name after apply() runs.
            spec.parameters.projectKey.set(settings.providers.provider { settings.rootProject.name })
            spec.parameters.requestedTasks.set(settings.startParameter.taskNames.toList())
            spec.parameters.outputDir.set(File(settings.rootDir, "build/buildhound").absolutePath)
            spec.parameters.htmlReportEnabled.set(extension.htmlReport.enabled)
            spec.parameters.rootDir.set(settings.rootDir.absolutePath)
            spec.parameters.serverUrl.set(extension.server.url)
            spec.parameters.serverToken.set(extension.server.token)
            spec.parameters.localBuildsEnabled.set(extension.localBuilds.enabled)
            spec.parameters.requireOptInFile.set(extension.localBuilds.requireOptInFile)
            // Test seam + advanced override; default (~/.buildhound/optin) resolves at execution.
            spec.parameters.optInFile.set(settings.providers.gradleProperty("buildhound.optin.file"))
        }
    }

    private companion object {
        const val SALT_PATH = ".gradle/buildhound/identity.salt"
        val logger = Logging.getLogger(BuildHoundSettingsPlugin::class.java)
    }
}
