---
title: "Best Practices for Tasks"
source: "https://docs.gradle.org/current/userguide/best_practices_tasks.html"
author:
published:
created: 2026-07-07
description:
tags:
  - "clippings"
---
The task [dependsOn](https://docs.gradle.org/current/javadoc/org/gradle/api/DefaultTask.html#setDependsOn\(java.lang.Iterable\)) method should only be used for [lifecycle tasks](https://docs.gradle.org/current/userguide/organizing_tasks.html#sec:lifecycle_tasks) (tasks without task actions).

Tasks with actions should declare their inputs and outputs so that Gradle’s up-to-date checking can automatically determine when these tasks need to be run or rerun.

Using `dependsOn` to link tasks is a much coarser-grained mechanism that does **not** allow Gradle to understand why a task requires a prerequisite task to run, or which specific files from a prerequisite task are needed. `dependsOn` forces Gradle to assume that *every* file produced by a prerequisite task is needed by this task. This can lead to unnecessary task execution and decreased build performance.

Here is a task that writes output to two separate files:

```kotlin
KotlinGroovy
```
```groovy
abstract class SimplePrintingTask extends DefaultTask {
    @OutputFile
    abstract RegularFileProperty getMessageFile()

    @OutputFile
    abstract RegularFileProperty getAudienceFile()

    @TaskAction (1)
    void run() {
        messageFile.get().asFile.write("Hello")
        audienceFile.get().asFile.write("World")
    }
}

tasks.register("helloWorld", SimplePrintingTask) { (2)
    messageFile = layout.buildDirectory.file("message.txt")
    audienceFile = layout.buildDirectory.file("audience.txt")
}
```

| **1** | **Task With Multiple Outputs**: `helloWorld` task prints "Hello" to its `messageFile` and "World" to its `audienceFile`. |
| --- | --- |
| **2** | **Registering the Task**: `helloWorld` produces "message.txt" and "audience.txt" outputs. |

If you want to translate the greeting in the `message.txt` file using another task, you could do this:

```kotlin
KotlinGroovy
```
```groovy
abstract class SimpleTranslationTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getMessageFile()

    @OutputFile
    abstract RegularFileProperty getTranslatedFile()

    SimpleTranslationTask() {
        messageFile.convention(project.layout.buildDirectory.file("message.txt"))
        translatedFile.convention(project.layout.buildDirectory.file("translated.txt"))
    }

    @TaskAction (1)
    void run() {
        def message = messageFile.get().asFile.text
        def translatedMessage = message == "Hello" ? "Bonjour" : "Unknown"

        logger.lifecycle("Translation: " + translatedMessage)
        translatedFile.get().asFile.write(translatedMessage)
    }
}

tasks.register("translateBad", SimpleTranslationTask) {
    dependsOn(tasks.named("helloWorld")) (2)
}
```

| **1** | **Translation Task Setup**: `translateBad` requires `helloWorld` to run first to produce the message file otherwise it will fail with an error as the file does not exist. |
| --- | --- |
| **2** | **Explicit Task Dependency**: Running `translateBad` will cause `helloWorld` to run first, but Gradle does not understand *why*. |

Instead, you should explicitly wire task inputs and outputs like this:

```kotlin
KotlinGroovy
```
```groovy
abstract class SimpleTranslationTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getMessageFile()

    @OutputFile
    abstract RegularFileProperty getTranslatedFile()

    SimpleTranslationTask() {
        messageFile.convention(project.layout.buildDirectory.file("message.txt"))
        translatedFile.convention(project.layout.buildDirectory.file("translated.txt"))
    }

    @TaskAction (1)
    void run() {
        def message = messageFile.get().asFile.text
        def translatedMessage = message == "Hello" ? "Bonjour" : "Unknown"

        logger.lifecycle("Translation: " + translatedMessage)
        translatedFile.get().asFile.write(translatedMessage)
    }
}

tasks.register("translateGood", SimpleTranslationTask) {
    inputs.file(tasks.named("helloWorld", SimplePrintingTask).map { messageFile }) (1)
}
```

| **1** | **Register Implicit Task Dependency**: `translateGood` requires only one of the files that is produced by `helloWorld`. |
| --- | --- |

Gradle now understands that `translateGood` requires `helloWorld` to have run successfully first because it needs to create the `message.txt` file which is then used by the translation task. Gradle can use this information to optimize task scheduling. Using the `map` method avoids eagerly retrieving the `helloWorld` task until the output is needed to determine if `translateGood` should run.

The [`cacheIf`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html#cacheIf\(org.gradle.api.specs.Spec\)) and [`doNotCacheIf`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html#doNotCacheIf\(java.lang.String,org.gradle.api.specs.Spec\)) methods should only be used in situations where the [cacheability](https://docs.gradle.org/current/userguide/build_cache.html#build_cache) of a task varies between different task instances or cannot be determined until the task is executed by Gradle. You should instead favor annotating the task class itself with [`@CacheableTask`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/CacheableTask.html) annotation for any task that is *always* cacheable. Likewise, the [`@DisableCachingByDefault`](https://docs.gradle.org/current/javadoc/org/gradle/work/DisableCachingByDefault.html) should be used to always disable caching for all instances of a task type.

Annotating a task type will ensure that *each task instance* of that type is properly understood by Gradle to be cacheable (or not cacheable). This removes the need to remember to configure each of the task instances separately in build scripts.

Using the annotations also *documents* the intended cacheability of the task type within its own source, appearing in Javadoc and making the task’s behavior clear to other developers without requiring them to inspect each task instance’s configuration. It is also slightly more efficient than running a test to determine cacheability.

Remember that only tasks that produce reproducible and relocatable output should be marked as `@CacheableTask`.

If you want to reuse the output of a task, you shouldn’t do this:

```kotlin
KotlinGroovy
```
```groovy
abstract class BadCalculatorTask extends DefaultTask {
    @Input
    abstract Property<Integer> getFirst()

    @Input
    abstract Property<Integer> getSecond()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void run() {
        def result = first.get() + second.get()
        logger.lifecycle("Result: " + result)
        outputFile.get().asFile.write(result.toString())
    }
}

tasks.register("clean", Delete) {
    delete layout.buildDirectory
}

tasks.register("addBad1", BadCalculatorTask) {
    first = 10
    second = 25
    outputFile = layout.buildDirectory.file("badOutput.txt")
    outputs.cacheIf { true }
}

tasks.register("addBad2", BadCalculatorTask) {
    first = 3
    second = 7
    outputFile = layout.buildDirectory.file("badOutput2.txt")
}
```

| **1** | **Define a Task**: The `BadCalculatorTask` type is deterministic and produces relocatable output, but is not annotated. |
| --- | --- |
| **2** | **Mark the Task Instance as Cacheable**: This example shows how to mark a specific task instance as cacheable. |
| **3** | **Forget to Mark a Task Instance as Cacheable**: Unfortunately, the `addBad2` instance of the `BadCalculatorTask` type is not marked as cacheable, so it will not be cached, despite behaving the same as `addBad1`. |

As this task meets the criteria for cacheability (we can imagine a more complex calculation in the `@TaskAction` that would benefit from automatic work avoidance via caching), you should mark the *task type itself* as cacheable like this:

```kotlin
KotlinGroovy
```
```groovy
@CacheableTask (1)
abstract class GoodCalculatorTask extends DefaultTask {
    @Input
    abstract Property<Integer> getFirst()

    @Input
    abstract Property<Integer> getSecond()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void run() {
        def result = first.get() + second.get()
        logger.lifecycle("Result: " + result)
        outputFile.get().asFile.write(result.toString())
    }
}

tasks.register("clean", Delete) {
    delete layout.buildDirectory
}

tasks.register("addGood1", GoodCalculatorTask) {
    first = 10
    second = 25
    outputFile = layout.buildDirectory.file("goodOutput.txt")
}

tasks.register("addGood2", GoodCalculatorTask) { (2)
    first = 3
    second = 7
    outputFile = layout.buildDirectory.file("goodOutput2.txt")
}
```

| **1** | **Annotate the Task Type**: Applying the `@CacheableTask` to a task type informs Gradle that instances of this task should *always* be cached. |
| --- | --- |
| **2** | **Nothing Else Needs To Be Done**: When we register task instances, nothing else needs to be done - Gradle knows to cache them. |

When configuring tasks and extensions do not call [`get()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#get\(\)) on a provider, use [`map()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#map\(org.gradle.api.Transformer\)), or [`flatMap()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#flatMap\(org.gradle.api.Transformer\)) instead.

A provider should be evaluated as late as possible. Calling `get()` forces immediate evaluation, which can trigger unintended side effects, such as:

- The value of the provider becomes an input to configuration, causing potential configuration cache misses.
- The value may be evaluated too early, meaning you might not be using the final or correct value of the property. This may lead to painful and hard to debug ordering issues.
- It breaks Gradle’s ability to build dependencies and to track task inputs and outputs, making automatic task dependency wiring impossible. See [Working with task inputs and outputs](https://docs.gradle.org/current/userguide/lazy_configuration.html#working_with_task_dependencies_in_lazy_properties)

It is preferable to avoid explicitly evaluating a `Provider` at all, and deferring to `map` / `flatMap` to connect `Providers` to `Providers` implicitly.

Here is a task that writes an input `String` to a file:

```kotlin
KotlinGroovy
```
```groovy
abstract class MyTask extends DefaultTask {
    @Input
    abstract Property<String> getMyInput()

    @OutputFile
    abstract RegularFileProperty getMyOutput()

    @TaskAction
    void doAction() {
        def outputFile = myOutput.get().asFile
        def outputText = myInput.get() (1)
        println(outputText)
        outputFile.write(outputText)
    }
}

Provider<String> currentEnvironment = providers.gradleProperty("currentEnvironment").orElse("234") (2)
```

| **1** | Using `Provider.get()` in the task action |
| --- | --- |
| **2** | Gradle property that we wish to use as input |

You could call `get()` at configuration time to set up this task:

```kotlin
KotlinGroovy
```
```groovy
tasks.register("avoidThis", MyTask) {
    myInput = "currentEnvironment=${currentEnvironment.get()}"  (1)
    myOutput = new File(layout.buildDirectory.get().asFile, "output-avoid.txt")  (2)
}
```

| **1** | **Reading the value of `currentEnvironment` at configuration time**: This value might change by the time the task start executing. |
| --- | --- |
| **2** | **Reading the value of `buildDirectory` at configuration time**: This value might change by the time the task start executing. |

Instead, you should explicitly wire task inputs and outputs like this:

```kotlin
KotlinGroovy
```
```groovy
tasks.register("doThis", MyTask) {
    myInput = currentEnvironment.map { "currentEnvironment=$it" }  (1)
    myOutput = layout.buildDirectory.file("output-do.txt")  (2)
}
```

| **1** | **Using `map()` to transform `currentEnvironment`**: `map` transform runs only when the value is read. |
| --- | --- |
| **2** | **Using `file()` to create a new `Provider<RegularFile>`**: the value of the `buildDirectory` is only checked when the value of the provider is read. |

When defining custom task types or registering ad-hoc tasks, always set a clear `group` and `description`.

A good group name is short, lowercase, and reflects the purpose or domain of the task. For example: `documentation`, `verification`, `release`, or `publishing`.

Before creating a new group, look for an existing group name that aligns with your task’s intent. It’s often better to reuse an established category to keep the task output organized and familiar to users.

This information is used in the [Tasks Report](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:listing_tasks) (shown via `./gradlew tasks`) to group and describe available tasks in a readable format.

Providing a group and description ensures that your tasks are:

- Displayed clearly in the report
- Categorized appropriately
- Understandable to other users (and to your future self)

|  | Tasks with no group are hidden from the [Tasks Report](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:listing_tasks) unless `--all` is specified. |
| --- | --- |

Tasks without a group appear under the "other" category in `./gradlew tasks --all` output, making them harder to locate:

```kotlin
KotlinGroovy
```
```groovy
tasks.register('generateDocs') {
    // Build logic to generate documentation
}
```

```text
$ gradlew :app:tasks --all

> Task :app:tasks

------------------------------------------------------------
Tasks runnable from project ':app'
------------------------------------------------------------

Other tasks
-----------
compileJava - Compiles main Java source.
compileTestJava - Compiles test Java source.
generateDocs
processResources - Processes main resources.
processTestResources - Processes test resources.
startScripts - Creates OS specific scripts to run the project as a JVM application.
```

When defining custom tasks, always assign a clear `group` and `description`:

```kotlin
KotlinGroovy
```
```groovy
tasks.register('generateDocs') {
    group = 'documentation'
    description = 'Generates project documentation from source files.'
    // Build logic to generate documentation
}
```

```text
$ gradlew :app:tasks --all

> Task :app:tasks

------------------------------------------------------------
Tasks runnable from project ':app'
------------------------------------------------------------

Documentation tasks
-------------------
generateDocs - Generates project documentation from source files.
javadoc - Generates Javadoc API documentation for the 'main' feature.
```

`#tasks`

When working with Gradle’s file collection types, be careful to avoid triggering dependency resolution during the configuration phase.

Gradle’s [`Configuration`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/Configuration.html) and [`FileCollection`](https://docs.gradle.org/current/javadoc/org/gradle/api/file/FileCollection.html) types extend the JDK’s `Collection<File>` interface.

However, calling some available methods from this interface—such as `.size()`, `.isEmpty()`, `getFiles()`, `asPath()`, or `.toList()` —on these Gradle types will implicitly trigger resolution of their dependencies. The same is possible using Kotlin stdlib collection extension methods or Groovy GDK collection extensions. Converting a `Configuration` to a `Set<File>` also discards any implicit task dependencies it carries.

You should avoid using these methods when configuring your build. Instead, use the methods defined directly on the Gradle interfaces - this is a necessary *first step* towards preventing eager resolutions. Be sure to use [lazy types and APIs](https://docs.gradle.org/current/userguide/lazy_configuration.html#working_with_files_in_lazy_properties) that defer resolution to wire task dependencies and inputs correctly. Some methods that cause resolution are not obvious. Be sure to check the actual behavior when using configurations in an atypical way.

```kotlin
KotlinGroovy
```
```groovy
abstract class FileCounterTask extends DefaultTask {
    @InputFiles
    abstract ConfigurableFileCollection getCountMe();

    @TaskAction
    void countFiles() {
        logger.lifecycle("Count: " + countMe.files.size())
    }
}

tasks.register("badCountingTask", FileCounterTask) {
    if (!configurations.runtimeClasspath.isEmpty()) { (1)
        logger.lifecycle("Resolved: " + (configurations.runtimeClasspath.state == RESOLVED))
        countMe.from(configurations.runtimeClasspath)
    }
}

tasks.register("badCountingTask2", FileCounterTask) {
    def files = configurations.runtimeClasspath.files (2)
    countMe.from(files)
    logger.lifecycle("Resolved: " + (configurations.runtimeClasspath.state == RESOLVED))
}

tasks.register("badCountingTask3", FileCounterTask) {
    def files = configurations.runtimeClasspath + layout.projectDirectory.file("extra.txt") (3)
    countMe.from(files)
    logger.lifecycle("Resolved: " + (configurations.runtimeClasspath.state == RESOLVED))
}

tasks.register("badZippingTask", Zip) { (4)
    if (!configurations.runtimeClasspath.isEmpty()) {
        logger.lifecycle("Resolved: " + (configurations.runtimeClasspath.state == RESOLVED))
        from(configurations.runtimeClasspath)
    }
}
```

| **1** | **`isEmpty()` causes resolution**: Many seemingly harmless Collection API methods like `isEmpty()` cause Gradle to resolve dependencies. |
| --- | --- |
| **2** | **Accessing files directly**: Using `getFiles()` to access the files in a `Configuration` will also cause Gradle to resolve the file collection. |
| **3** | **Adding a file via plus operator**: Using the plus operator will force the `runtimeClasspath` configuration to be resolved implicitly. The implementation of `Configuration` doesn’t override the plus operator for regular files, therefore it falls back to using the eager API, which causes resolution. |
| **4** | **Be careful with indirect inputs**: Some built-in tasks, for example subtypes of `AbstractCopyTask` like `Zip`, allow adding inputs indirectly and can have the same problems. |

To avoid issues, always defer resolution until the execution phase. Use APIs that support lazy evaluation.

```kotlin
KotlinGroovy
```
```groovy
abstract class FileCounterTask extends DefaultTask {
    @InputFiles
    abstract ConfigurableFileCollection getCountMe();

    @TaskAction
    void countFiles() {
        logger.lifecycle("Count: " + countMe.files.size())
    }
}

tasks.register("goodCountingTask", FileCounterTask) {
    countMe.from(configurations.runtimeClasspath) (1)
    countMe.from(layout.projectDirectory.file("extra.txt")) (2)
    logger.lifecycle("Resolved: " + (configurations.runtimeClasspath.state == RESOLVED))
}
```

| **1** | **Add configurations to Task properties or Specs directly**: This will defer resolution until the task is executed. |
| --- | --- |
| **2** | **Add files to Specs separately**: This allows combining files with file collections without triggering implicit resolutions. |

Resolving configurations before the task execution phase can lead to incorrect results and slower builds.

Resolving a configuration - either directly via calling its [`resolve()`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/Configuration.html#resolve\(\)) method or indirectly via accessing its set of artifacts - returns a set of files that does not preserve references to the tasks that produced those files.

Configurations *are* file collections and can be added to `@InputFiles` properties on other tasks. It is important to do this correctly to avoid breaking automatic task dependency wiring between a consumer task and any tasks that are implicitly required to produce the artifacts being consumed. For example, if a configuration contains a project dependency, Gradle knows that consumers of the configuration must first run any tasks that produce that project’s artifacts.

In addition to correctness concerns, resolving configurations during the configuration phase can slow down the build, even when running unrelated tasks (e.g., `help`) that don’t require the resolved dependencies.

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    runtimeOnly(project(":lib")) (1)
}

abstract class BadClasspathPrinter extends DefaultTask {
    @InputFiles
    Set<File> classpath = [] as Set (2)

    protected int calculateDigest(File fileOrDirectory) {
        if (!fileOrDirectory.exists()) {
            throw new IllegalArgumentException("File or directory $fileOrDirectory doesn't exist")
        }
        return 0 // actual implementation is stripped
    }

    @TaskAction
    void run() {
        logger.lifecycle(
            classpath.collect { file ->
                def digest = calculateDigest(file) (3)
                "$file#$digest"
            }.join("\n")
        )
    }
}

tasks.register("badClasspathPrinter", BadClasspathPrinter) {
    classpath = configurations.named("runtimeClasspath").get().resolve() (4)
}
```

| **1** | **Add project dependency**: The `:lib` project must be built in order to resolve the runtime classpath successfully. |
| --- | --- |
| **2** | **Declare input property as Set of files**: A simple `Set` input doesn’t track task dependencies. |
| **3** | **Dependency artifacts are used to calculate digest**: Artifacts from the already resolved classpath are used to calculate the digest. |
| **4** | **Resolve runtimeClasspath**: The implicit task dependency on `:library:jar` task is lost here when the configuration is resolved prior to task execution. The `lib` project will not be built when the `:app:badClasspathPrinter` task is run, leading to a failure in `calculateDigest` because the `lib.jar` file will not exist. |

To avoid issues, always defer resolution to the execution phase by using lazy APIs like [FileCollection](https://docs.gradle.org/current/javadoc/org/gradle/api/file/FileCollection.html).

```kotlin
KotlinGroovy
```
```groovy
dependencies {
    runtimeOnly(project(":lib")) (1)
}

abstract class GoodClasspathPrinter extends DefaultTask {

    @InputFiles
    abstract ConfigurableFileCollection getClasspath() (2)

    protected int calculateDigest(File fileOrDirectory) {
        if (!fileOrDirectory.exists()) {
            throw new IllegalArgumentException("File or directory $fileOrDirectory doesn't exist")
        }
        return 0 // actual implementation is stripped
    }

    @TaskAction
    void run() {
        logger.lifecycle(
            classpath.collect { file ->
                def digest = calculateDigest(file) (3)
                "$file#$digest"
            }.join("\n")
        )
    }
}

tasks.register("goodClasspathPrinter", GoodClasspathPrinter) {
    classpath.from(configurations.named("runtimeClasspath")) (4)
}
```

| **1** | **Write to a file in the output directory**: This is the same. |
| --- | --- |
| **2** | **Declare input files property as ConfigurableFileCollection**: This lazy collection type will track task dependencies. |
| **3** | **Dependency artifacts are resolved to calculate digest**: The classpath will be resolved at execution time to calculate the digest. |
| **4** | **Configuration is passed to input property directly**: Using `from` causes the configuration to be lazily wired to the input proeprty. The configuration will be resolved when necessary, preserving task dependencies. The output reveals that the `lib` project is now built when the `:app:goodClasspathPrinter` task is run because of the implicit task dependency, and the `lib.jar` file is found when calculating the digest. |

Use [`@PathSensitivity.NONE`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/PathSensitivity.html#NONE) for file inputs and [`@PathSensitivity.RELATIVE`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/PathSensitivity.html#RELATIVE) for directory inputs.

Tasks should generally care about the **contents** of their input files, not their location on disk.

When annotating file-based input properties (for example, `@InputFile` or `@InputFiles` collections), use `@PathSensitivity.NONE`. This tells Gradle to ignore the path and only consider the file contents when determining whether a task is up-to-date.

For directory-based inputs (for example, `@InputDirectory` or `@InputFiles` collections), use `@PathSensitivity.RELATIVE`. This tells Gradle to also consider only the name of the directory (ignoring its absolute location) and to relativize the paths of all files within that directory to it when doing up-to-date checks.

Using `PathSensitivity.NAME_ONLY` or `@PathSensitivity.ABSOLUTE` is generally incorrect.

`PathSensitivity.NAME_ONLY` tells Gradle to consider a file’s name in addition to its contents, which is rarely useful.

`@PathSensitivity.ABSOLUTE` tells Gradle to consider a file’s complete absolute path. This prevents [Build Cache](https://docs.gradle.org/current/userguide/build_cache.html#build_cache) and [Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache) hits across different machines or checkout locations, making your build non-relocatable. It can also lead to confusing behavior where the same build produces different task outcomes when run from different directories. If no `@PathSensitive` annotation is provided, `PathSensitivity.ABSOLUTE` is the default.

```kotlin
KotlinGroovy
```
```groovy
abstract class AnimalSearchTask extends DefaultTask {
    @Input
    abstract Property<String> getFind()

    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE) (1)
    abstract RegularFileProperty getCandidatesFile()

    @OutputFile
    abstract RegularFileProperty getResultsFile()

    @TaskAction
    void search() {
        if (candidatesFile.get().getAsFile().readLines().contains(find.get())) {
            def msg = "Found a " + find.get() + "!"
            getLogger().lifecycle(msg)
            resultsFile.get().asFile.text = msg
        }
    }
}

def useAlternateInput = providers.gradleProperty("useAlternateInput").isPresent()

def copyTask = tasks.register("copy", Copy) {
    from(layout.projectDirectory.file("candidates.txt"))
    destinationDir = (useAlternateInput ? layout.buildDirectory.dir("alternateSearchInput") : layout.buildDirectory.dir("searchInput")).get().asFile (2)
}

tasks.register("search", AnimalSearchTask) {
    find = "cat"
    candidatesFile.fileProvider(copyTask.map { new File(it.destinationDir, "candidates.txt") }) (3)
    resultsFile = layout.buildDirectory.file("searchOutput/results.txt")
    dependsOn(copyTask)
}
```

| **1** | The `AnimalSearchTask` task type uses a file input property annotated with `@PathSensitivity.ABSOLUTE`. This means that the absolute path of the input file is used to determine if the task is `UP-TO-DATE` or if it can be loaded from cache. Yet the path is irrelevant for the operation of the task’s `@TaskAction`, which only cares about file contents. |
| --- | --- |
| **2** | The `copy` task will move the *exact same* `candidates.txt` to different destination directories, depending on if the `useAlternateInput` project property is set. |
| **3** | The `search` task is wired to use as input whatever the file the `copy` task moved to its `destinationDir`. Despite the contents of the file being the same, when enabling the `-PuseAlternateInput` after a successful build, the `search` task will be out-of-date due to its different directory, and the search will be rerun. |

```kotlin
KotlinGroovy
```
```groovy
@InputFile
@PathSensitive(PathSensitivity.NONE) (1)
abstract RegularFileProperty getCandidatesFile()
```

| **1** | Everything remains the same, except that the input property is now annotated with `@PathSensitivity.NONE`. Only the contents of this input file matter to this task. When the `search` task is rerun with `-PuseAlternateInput`, it remains `UP-TO-DATE`. |
| --- | --- |

Overlapping output files or directories cause tasks to rerun unnecessarily and waste work.

Gradle tracks all output files and directories declared by tasks to decide whether a task needs to be rerun. For example, if the contents of a task’s output directory change after its last execution, Gradle will rerun that task.

Ensuring that each task uses its own unique output files and directories, both within a project and across the entire build, prevents unnecessary work.

```kotlin
KotlinGroovy
```
```groovy
abstract class GreetingTask extends DefaultTask {
    @Input
    abstract Property<String> getType()
    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void run() {
        def outFileName = type.get() + ".txt"
        def message = "Hello " + type.get()
        outputDirectory.file(outFileName).get().asFile.text = message (1)
    }
}

abstract class ConsumerTask extends DefaultTask {
    @InputDirectory
    abstract DirectoryProperty getInputDirectory()
    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void run() {
        def message = inputDirectory.get().file("a.txt").asFile.text (2)
        outputFile.get().asFile.write(message)
    }
}

def greeterA = tasks.register("greeterA", GreetingTask) {
    type = "a"
    outputDirectory = layout.buildDirectory.dir("greetings") (3)
}
tasks.register("greeterB", GreetingTask) {
    type = "b"
    outputDirectory = layout.buildDirectory.dir("greetings") (4)
}

tasks.register("consumer", ConsumerTask) {
    inputDirectory = greeterA.flatMap { it.outputDirectory } (5)
    outputFile = layout.buildDirectory.file("consumerOutput.txt")
}
```

| **1** | **Write to a file in the output directory**: This task produces a single file in the `outputDirectory`, named based on the `type` input. |
| --- | --- |
| **2** | **Read a specific file in the input directory**: This task only needs to read a single `a.txt` file in the input directory. |
| **3** | **Set output directory**: Sets `outputDirectory` to a subdirectory in `buildDirectory`. |
| **4** | **Set output directory**: Same as above, using the same shared `greetings` directory. |
| **5** | **Wire `greeterA` to consumer**: Makes sure that `greeterA` runs and produces the output directory before it is used by `consumer`. |

With this setup, if you run the `consumer` task, then `greeterB`, the `consumer` task will be invalidated.

The next time `consumer` is run it will **not** be `UP-TO-DATE` and will have to run again despite not using the output from `greeterB`.

This happens because `greeterB` changes the contents of the shared output directory `greetings`, which is an output of `greeterA` that `consumer` depends on (despite `consumer` only actually using the unchanged `a.txt` file in that directory).

To avoid issues, avoid using shared task output directories and files.

Instead, tasks should only declare the exact outputs and consume the exact inputs that they actually produce and consume.

The *simplest* change to make here is to use distinct output directories for each `GreetingTask`. This alone is sufficient to fix the problem.

```kotlin
KotlinGroovy
```
```groovy
def greeterA = tasks.register("greeterA", GreetingTask) {
    type = "a"
    outputDirectory = layout.buildDirectory.dir("greetings")
}
tasks.register("greeterB", GreetingTask) {
    type = "b"
    outputDirectory = layout.buildDirectory.dir("greetings-2") (1)
}
```

| **1** | **Set unique output directories**: Each `GreetingTask` is assigned its own unique output directory based on the `type` input. |
| --- | --- |

Now when running `consumer` task, then `greeterB`, then `consumer` task remains `UP-TO-DATE` as Gradle knows that it is not using the output from `greeterB`, since `greeterA` and `greeterB` write to distinct output directories.

However, a more *complete and idiomatic* approach realizes that:

1. Tasks that produce single output files should make this clear from the type of their `@Output` properties.
2. Tasks that only consume single input files should make this clear from the type of their `@Input` properties.

```kotlin
KotlinGroovy
```
```groovy
abstract class GreetingTask extends DefaultTask {
    @Input
    abstract Property<String> getType()
    @OutputFile
    abstract RegularFileProperty getOutputFile() (1)

    @TaskAction
    void run() {
        def message = "Hello " + type.get()
        outputFile.get().asFile.text = message
    }
}

abstract class ConsumerTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInputFile() (2)
    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void run() {
        def message = inputFile.get().asFile.text
        outputFile.get().asFile.write(message)
    }
}

def greeterA = tasks.register("greeterA", GreetingTask) {
    type = "a"
    outputFile = layout.buildDirectory.dir("greetings").map { it.file("a.txt") } (3)
}
tasks.register("greeterB", GreetingTask) {
    type = "b"
    outputFile = layout.buildDirectory.dir("greetings").map { it.file("b.txt") }
}

tasks.register("consumer", ConsumerTask) {
    inputFile = greeterA.map { it.outputFile.get() } (4)
    outputFile = layout.buildDirectory.file("consumerOutput.txt")
}
```

| **1** | **Write to a specific output file**: This task produces a single file to a directly specified `outputFile` without registering an entire output directory. |
| --- | --- |
| **2** | **Read a specific file**: Unlike the previous example the input is a single directly specified `inputFile` file. |
| **3** | **Set output file**: Sets `outputFile` to a file that is inside a `shared` subdirectory of `buildDirectory`. |
| **4** | **Wire `greeterA` to consumer**: Makes sure that `greeterA` produces the output file before it is used by `consumer` by wiring task inputs to outputs directly. |

Now when running `consumer` task, then `greeterB`, the `consumer` task remains `UP-TO-DATE` as Gradle knows that it is not using the output from `greeterB`, since `greeterA` and `greeterB` *produce and consume distinct files* (that happen to be in created in the same directory).