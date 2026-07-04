package dev.buildhound.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import java.io.File
import org.gradle.api.Project

/**
 * AGP-coupled Android artifact-size collector (plan 031).
 *
 * BuildHound is a *settings* plugin, so AGP is not on its classpath (it is applied to *projects*).
 * [install] therefore references NO AGP symbol itself — it only registers `withPlugin` reactions
 * whose bodies delegate to [installApp]/[installLib], and each delegate runs inside a
 * `runCatching(Throwable)`. So on a non-Android build nothing links AGP, and even if AGP's types are
 * unresolvable from the settings classloader the resulting `NoClassDefFoundError` degrades to "no
 * artifacts" — never a failed build (architecture §2 never-fail rule). The AGP-touching code
 * ([installApp]/[installLib], [SizeReportTask]) links AGP only when actually invoked on an Android
 * project.
 *
 * For each variant it wires a read-only size task with
 * `variant.artifacts.use(task).wiredWith(..).toListenTo(SingleArtifact.*)` — AGP's non-destructive
 * "listen" mechanism (proven CC- + isolated-projects-safe by AndroidArtifactsSizeReport). App
 * variants measure APK + AAB; library variants measure AAR. Each task writes JSON lines into the
 * shared [artifactsDir] (rooted at the root build dir) that the Flow finalizer reads at build end;
 * AGP's own outputs are never deleted or replaced.
 */
internal object AndroidArtifactCollector {

    fun install(project: Project, artifactsDir: File) {
        project.pluginManager.withPlugin("com.android.application") {
            runCatching { installApp(project, artifactsDir) }.onFailure { warn(project, it) }
        }
        project.pluginManager.withPlugin("com.android.library") {
            runCatching { installLib(project, artifactsDir) }.onFailure { warn(project, it) }
        }
    }

    private fun warn(project: Project, error: Throwable) {
        // Class name only — never process/build state; a linking or wiring failure is non-fatal.
        project.logger.info("[buildhound] android artifact size collection unavailable: {}", error::class.java.simpleName)
    }

    private fun installApp(project: Project, artifactsDir: File) {
        project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
            registerApk(project, variant, artifactsDir)
            registerFile(project, variant, artifactsDir, SingleArtifact.BUNDLE, "Aab", "AAB")
        }
    }

    private fun installLib(project: Project, artifactsDir: File) {
        project.extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
            registerFile(project, variant, artifactsDir, SingleArtifact.AAR, "Aar", "AAR")
        }
    }

    private fun registerApk(project: Project, variant: Variant, artifactsDir: File) {
        val task = project.tasks.register(taskName(variant, "Apk"), ApkSizeReportTask::class.java) { t ->
            t.module.set(project.path)
            t.variant.set(variant.name)
            t.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            t.output.set(outputFile(project, artifactsDir, variant, "apk"))
        }
        variant.artifacts.use(task).wiredWith(ApkSizeReportTask::apkDir).toListenTo(SingleArtifact.APK)
    }

    private fun registerFile(
        project: Project,
        variant: Variant,
        artifactsDir: File,
        artifact: SingleArtifact<org.gradle.api.file.RegularFile>,
        taskSuffix: String,
        artifactType: String,
    ) {
        val task = project.tasks.register(taskName(variant, taskSuffix), FileSizeReportTask::class.java) { t ->
            t.module.set(project.path)
            t.variant.set(variant.name)
            t.artifactType.set(artifactType)
            t.output.set(outputFile(project, artifactsDir, variant, artifactType.lowercase()))
        }
        variant.artifacts.use(task).wiredWith(FileSizeReportTask::artifact).toListenTo(artifact)
    }

    private fun taskName(variant: Variant, suffix: String): String =
        "buildhound${suffix}Size${variant.name.replaceFirstChar { it.uppercase() }}"

    /** Collision-free per (module, variant, kind); the module path is sanitized for a file name. */
    private fun outputFile(project: Project, artifactsDir: File, variant: Variant, kind: String): File {
        val modulePart = project.path.trim(':').ifEmpty { "root" }.replace(':', '-')
        return File(artifactsDir, "$modulePart-${variant.name}-$kind.jsonl")
    }
}
