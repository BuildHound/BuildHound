package dev.buildhound.gradle

import com.android.build.api.variant.BuiltArtifactsLoader
import dev.buildhound.commons.payload.ArtifactType
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Measures the APK output of a variant (plan 031). APK output is a *directory* of split APKs
 * (ABI/density), enumerated via AGP's [BuiltArtifactsLoader]. Sizes come from `File.length()` over
 * paths the loader reports — never `substringAfterLast("/")` (Windows-safe,
 * AndroidArtifactsSizeReport §Limitations). Declared `@OutputFile`/inputs so the task stays
 * UP-TO-DATE and never mutates or deletes AGP's own outputs.
 */
@DisableCachingByDefault(
    because =
        "Artifact telemetry is cheap to regenerate and must not be retained or replayed by build caches"
)
abstract class ApkSizeReportTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDir: DirectoryProperty

    @get:Internal abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input abstract val module: Property<String>

    @get:Input abstract val variant: Property<String>

    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun report() {
        // Never fail the build (architecture §2 rule 3): an AGP built-artifacts format drift or a
        // write
        // failure (disk full, read-only FS) must degrade to no artifact record, never break the
        // build.
        runCatching {
            val loaded = builtArtifactsLoader.get().load(apkDir.get())
            val lines =
                loaded?.elements.orEmpty().mapNotNull { element ->
                    val file = File(element.outputFile)
                    if (file.isFile)
                        ArtifactRecordIo.encode(
                            module.get(),
                            variant.get(),
                            ArtifactType.APK,
                            file.length(),
                        )
                    else null
                }
            output.get().asFile.writeText(lines.joinToString("\n"))
        }
            .onFailure {
                logger.info("buildhound: APK size probe skipped ({})", it::class.java.simpleName)
            }
    }
}

/**
 * Measures a single AAB (app bundle) or AAR (library) file (plan 031); the artifact is one file,
 * not a directory. Same never-mutate-outputs discipline as [ApkSizeReportTask].
 */
@DisableCachingByDefault(
    because =
        "Artifact telemetry is cheap to regenerate and must not be retained or replayed by build caches"
)
abstract class FileSizeReportTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifact: RegularFileProperty

    @get:Input abstract val module: Property<String>

    @get:Input abstract val variant: Property<String>

    /** [ArtifactType] name — AAB or AAR. */
    @get:Input abstract val artifactType: Property<String>

    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun report() {
        // Never fail the build (architecture §2 rule 3): a write failure must degrade to no record.
        runCatching {
            val type =
                ArtifactType.entries.firstOrNull { it.name == artifactType.get() }
                    ?: return@runCatching
            val file = artifact.get().asFile
            val line =
                if (file.isFile)
                    ArtifactRecordIo.encode(module.get(), variant.get(), type, file.length())
                else ""
            output.get().asFile.writeText(line)
        }
            .onFailure {
                logger.info(
                    "buildhound: artifact size probe skipped ({})",
                    it::class.java.simpleName,
                )
            }
    }
}
