import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.gradle.publish.PublishTask
import java.io.IOException
import java.io.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.PathSensitivity
import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.plugin.publish)
    alias(libs.plugins.shadow)
}

private object PluginWebsiteVerifier {
    fun verify() {
        val website = URI.create("https://buildhound.dev/")
        val client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        val request =
            HttpRequest.newBuilder(website)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        var failure = "no response"

        repeat(3) { attempt ->
            try {
                val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status in 200..299) return
                if (status != 429 && status !in 500..599) {
                    throw GradleException("Plugin website $website returned HTTP $status; refusing Portal upload")
                }
                failure = "HTTP $status"
            } catch (error: IOException) {
                failure = error::class.java.simpleName
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw GradleException("Plugin website verification was interrupted", error)
            }

            if (attempt < 2) {
                try {
                    Thread.sleep(2_000)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw GradleException("Plugin website verification was interrupted", error)
                }
            }
        }

        throw GradleException("Plugin website $website failed HTTPS verification after 3 attempts ($failure)")
    }
}

private class VerifyPluginWebsiteAction(private val uploadOnly: Boolean) : Action<Task>, Serializable {
    override fun execute(task: Task) {
        if (uploadOnly && task is PublishTask && task.validateOnly.getOrElse(false)) return
        PluginWebsiteVerifier.verify()
    }
}

description = "Settings plugin collecting build/task telemetry (configuration-cache safe)"

// JDK 26 builds the code; bytecode/API stay Java 21 (plan 011).
val buildToolchain = (findProperty("buildhound.toolchain") as? String)?.toIntOrNull() ?: 26

java {
    // Consumer floor (owner request, plan 011): Gradle daemons on JDK 21+.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    // This code rides the Gradle settings classpath and runs on Gradle's *embedded*
    // Kotlin stdlib (2.0 on Gradle 8.14, the support floor) — newer stdlib APIs throw
    // NoSuchMethodError at runtime (architecture §2 rule 10).
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
        // apiVersion 2.0 is a deliberate pin (Gradle's embedded Kotlin stdlib, architecture §2 rule 10);
        // silence the newer build compiler's "2.0 is deprecated" advisory rather than bumping it.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(buildToolchain))
        // Shrink foojay's resolution space on the default path; overrides stay
        // vendor-free so any locally installed JDK can serve them.
        if (buildToolchain == 26) vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Kotlin is API-capped by -Xjdk-release; this is the javac equivalent so the first
    // .java file added can't silently link against >21 APIs (review finding).
    options.release.set(21)
}

// Shadow's default runtimeClasspath includes Gradle's own API/runtime files on the 8.14 support
// floor. Resolve only BuildHound implementation dependencies for the Portal bundle; compileOnly
// Gradle/AGP APIs and Gradle's embedded Kotlin stay outside the artifact.
val pluginBundle = configurations.create("pluginBundle") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    implementation(projects.buildhoundCommons)
    // Report template + renderer; the publishing chunk decides shading vs. resource
    // embedding (plan 006) — a module dependency is correct for now.
    implementation(projects.buildhoundReport)
    // Internal-adapters capture, bundled and driven by this plugin (plan 074): one plugin, one
    // config block. The module keeps ALL internal-Gradle-API code quarantined; it is dormant until
    // a `buildhound { internalAdapters { } }` toggle is set, so bundling grants no blanket consent.
    implementation(projects.buildhoundInternalAdapters)

    // AGP Variant API for the Android artifact-size collector (plan 031). compileOnly: the plugin
    // must apply cleanly to non-Android builds, and no AGP jar ships with it — the collector is
    // loaded only after a runtime class-probe confirms AGP is present.
    compileOnly(libs.android.gradle.api)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestKit-based functional tests, kept apart from unit tests so a Gradle-version
// matrix (roadmap phase 0) can later run them against multiple distributions.
val functionalTest: SourceSet = sourceSets.create("functionalTest")

dependencies {
    // Custom source sets are outside KGP's automatic kotlin("test") version management,
    // so the test stack is declared explicitly here.
    "functionalTestImplementation"(projects.buildhoundCommons)
    "functionalTestImplementation"(libs.kotlinx.serialization.json)
    "functionalTestImplementation"(libs.kotlin.test.junit5)
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    // Runtime-only, separately compiled addon used to prove the published core preserves the
    // public BuildHoundExtensionContributor ABI across the Shadow JAR boundary (plan 087).
    if (rootProject.findProject(":buildhound-addon-test-sharding") != null) {
        "functionalTestRuntimeOnly"(project(":buildhound-addon-test-sharding"))
    }
    "functionalTestRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    // Advertise the final product URL now. The release workflow verifies HTTPS before validation,
    // so the Portal cannot receive this metadata while buildhound.dev has an invalid certificate.
    website = "https://buildhound.dev"
    vcsUrl = "https://github.com/BuildHound/BuildHound"
    testSourceSets(functionalTest)
    plugins {
        create("buildhound") {
            id = "dev.buildhound"
            implementationClass = "dev.buildhound.gradle.BuildHoundSettingsPlugin"
            displayName = "BuildHound"
            description = "Collects Gradle build, task, and cache telemetry and ships it to a BuildHound server"
            tags = listOf("telemetry", "build-performance", "observability")
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

// The Plugin Portal publishes only this project. Bundle the three internal runtime projects and
// kotlinx-serialization so consumers do not need unpublished dev.buildhound Maven coordinates.
// Gradle itself supplies Kotlin stdlib (2.0 at our Gradle 8.14 floor); embedding the build's 2.4
// stdlib would violate architecture §2 rule 10. Serialization keeps its public package because the
// addon SPI exposes JsonElement; relocating it would rewrite that binary contract and break every
// separately compiled contributor. Plugin Publish automatically selects shadowJar and classifies
// the ordinary JAR as `main`, leaving the bundled JAR as the primary artifact.
tasks.named<ShadowJar>("shadowJar") {
    // Plugin Publish requires the bundled artifact to be the unclassified primary JAR.
    archiveClassifier.set("")
    // Shadow 9.2.2 is the newest release compatible with Gradle 8.14, but its metadata helper
    // only reads through Kotlin 2.3. These package indexes are not used at runtime (and BuildHound
    // uses no Kotlin reflection), so omit them; class metadata and bytecode remain intact.
    exclude("META-INF/*.kotlin_module")
    configurations.set(listOf(pluginBundle))
    mergeServiceFiles()
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains:annotations:.*"))
    }
}

// Keep validation usable while the certificate is being repaired, but make every real local/CI
// upload fail closed inside the upload task itself. The standalone task is safe to run as a preflight.
tasks.register("verifyPluginWebsite") {
    description = "Verifies the public Plugin Portal website over HTTPS"
    group = "publishing"
    doLast(VerifyPluginWebsiteAction(uploadOnly = false))
}
tasks.named<PublishTask>("publishPlugins") {
    doFirst(VerifyPluginWebsiteAction(uploadOnly = true))
}

// A local Maven repository used only by PortalPublicationFunctionalTest. Resolving the plugin by
// marker id/version (without withPluginClasspath/includeBuild) proves the published shape is usable.
val releaseTestRepository = layout.buildDirectory.dir("release-test-repository")
publishing {
    repositories {
        maven {
            name = "releaseTest"
            url = releaseTestRepository.get().asFile.toURI()
        }
    }
}

// Snapshot publication filenames include a timestamp and build number. Start each fixture
// publication from an empty repository so its Maven metadata can name exactly one current artifact
// instead of accumulating candidates that could make the functional test resolve stale output.
val cleanReleaseTestRepository = tasks.register<Delete>("cleanReleaseTestRepository") {
    delete(releaseTestRepository)
}
tasks.withType<PublishToMavenRepository>().configureEach {
    // Repository is assigned after task creation; the generated task name is available immediately.
    if (name.endsWith("ToReleaseTestRepository")) dependsOn(cleanReleaseTestRepository)
}

// Inner-build CC mode for the TestKit suite (plan 021): forwarded from the
// `-Pbuildhound.testkit.cc` Gradle property (default `on`) as a system property the tests
// read. Provider-based so it is a proper CC input of this build (which itself keeps CC on).
val testkitCcMode: String = providers.gradleProperty("buildhound.testkit.cc").getOrElse("on")

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs TestKit functional tests (excludes the watched isolated-projects suite)"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    // The isolated-projects suite is watched (non-blocking), run only by isolatedProjectsTest.
    useJUnitPlatform { excludeTags("isolated-projects") }
    systemProperty("buildhound.testkit.cc", testkitCcMode)
    systemProperty("buildhound.release-test-repository", releaseTestRepository.get().asFile.absolutePath)
    systemProperty("buildhound.release-version", version.toString())
    // Fingerprint the deterministic publication sources, not the generated repository: SNAPSHOT
    // Maven metadata and filenames change on every publish even when the artifact is identical.
    // These are the files the fixture resolves and validates, including the plugin marker POM.
    inputs.files(
        tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
        tasks.named("generatePomFileForPluginMavenPublication"),
        tasks.named("generateMetadataFileForPluginMavenPublication"),
        tasks.named("generatePomFileForBuildhoundPluginMarkerMavenPublication"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    dependsOn("publishAllPublicationsToReleaseTestRepository")
}

// Watched (non-blocking) isolated-projects suite (plan 021): runs the same functionalTest
// classes but only the @Tag("isolated-projects") cases; deliberately NOT wired into `check`.
val isolatedProjectsTestTask = tasks.register<Test>("isolatedProjectsTest") {
    description = "Runs the watched isolated-projects TestKit suite"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform { includeTags("isolated-projects") }
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // TestKit spawns the fixture Gradle on the test JVM, and the floor Gradle (8.14)
    // cannot RUN on JDK 26 — consumers run on 21+, so tests execute on the real
    // consumer floor while compilation stays on the 26 toolchain (plan 011).
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Root for the per-test "fresh daemon" TestKit dirs (plan 049). Kept under the module
    // build/ dir — never inside a test's @TempDir — so a lingering daemon's open file handles
    // can't break @TempDir deletion on macOS/Windows; `clean` reclaims the daemons.
    //
    // The path is absolute on purpose: TestKit's daemon starter rejects a relative testkit dir
    // (IdentityFileResolver -> UnsupportedOperationException). That absolute path lands in this
    // cacheable Test task's @Input fingerprint, making functionalTest non-relocatable across
    // machines — accepted (plan 049, §3.1 review): a suite that spawns real Gradle daemons and
    // reads the live environment is inherently machine-specific and must never be served from
    // another machine's build cache anyway.
    systemProperty(
        "buildhound.testkit.root",
        layout.buildDirectory.dir("functionalTest-testkit").get().asFile.absolutePath,
    )
}
