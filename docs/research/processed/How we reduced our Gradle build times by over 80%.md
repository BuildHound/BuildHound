---
title: "How we reduced our Gradle build times by over 80%"
source: "https://proandroiddev.com/how-we-reduced-our-gradle-build-times-by-over-80-51f2b6d6b05b"
author:
  - "[[Adam Ahmed]]"
published: 2021-11-05
created: 2026-07-07
description: "Tips and advice on how we reduced our Android project Gradle build times"
tags:
  - "clippings"
---
[Mastodon](https://androiddev.social/@adam)

## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-51f2b6d6b05b---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-51f2b6d6b05b---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*89FxNOfLuZC4ZxjNbx0Rkg.png)

Image source: Gradle

When I joined my current company a few months ago, building our Android app took an average of 14 minutes on my machine. You can imagine how wildly unproductive that made me feel, so I went on a journey to speed it up.

It’s down to about 2 minutes now, so I’ll share some of the things I learned along the way, but please don’t apply these changes blindly to your project and instead use the [Gradle profiler](https://github.com/gradle/gradle-profiler) to ensure that they work for your existing setup. Different things work for different projects.

## Easy wins

1. **Use the latest versions of the tools and plugins you use in your project**

We were using AGP 4.2, and by updating to AGP 7, we were able to take advantage of the [performance improvmenets](https://docs.gradle.org/7.0.2/release-notes.html#performance-improvements) promised in Gradle 7, which takes us to my next point.

==**2\. Enable file-system watching**==

If you’re already using Gradle version 7, skip this section because it’s enabled by default, but if you’re stuck on version 6.7 (AGP versions 7 and 4.2), then you should manually enable [file-system watching](https://blog.gradle.org/introducing-file-system-watching). It allows Gradle to store information about which tasks’ inputs and outputs were changed between builds, so it can quickly figure out which ones to re-execute.

You can enable it by adding this to your `gradle.properties` script

```c
org.gradle.vfs.watch=true
```

**3\. Enable configuration on demand**

This is helpful for multi-module projects because it attempts to configure only modules that are relevant for the tasks you run instead of configuring your entire project for every task. However, it’s currently still an incubating feature, so it might not work for your project. You can read more about it [here](https://docs.gradle.org/current/userguide/multi_project_configuration_and_execution.html#sec:configuration_on_demand)

You can enable it by adding this to your `gradle.properties` script

```c
org.gradle.configureondemand=true
```

**4\. Enable parallel execution**

If you’re working on a multi-module project, then forcing Gradle to execute tasks in parallel is also an easy performance gain. This works with the caveat that your tasks in different modules should be independent, and not access shared state, which you shouldn’t be doing anyway.

You can enable it by adding this to your `gradle.properties` script

```c
org.gradle.parallel=true
```

**5\. Enable build caching**

This works by storing and reusing outputs produced by other builds if the inputs haven’t changed. One feature of this is task output caching. It leverages Gradle’s existing `UP_TO_DATE` checks, but instead of only reusing outputs from the most recent build on the same machine, it allows Gradle to reuse outputs from any earlier build in any location on the machine. When using a shared build cache for task output caching, this even works across developer machines and build agents, so you can share the same cache with your coworkers and your CI. [Nelson Osacky](https://twitter.com/nellyspageli) wrote a nice series about the benefits and caveats of using remote build caching [here](https://medium.com/swlh/how-fast-does-my-internet-need-to-be-to-use-the-gradle-remote-build-cache-part-1-4acaa6f9a2fa)

You can enable it by adding this to your `gradle.properties` script

```c
org.gradle.caching=true
```

## General advice

Before we move on to the next section, here’s a quick primer on the Gradle build lifecycle. This is important so we understand the savings we’ll get from some of the points below.

Every Gradle build has 3 phases:

- **Initialization**: this is where Gradle decides which modules are going to take part in the build, and creates a `project` instance for each of them.
- **Configuration**: this is when the project objects are configured, and all the build scripts of all projects which are part of the build are executed. This phase is where you need to pay the most attention because it’s executed with every build, so if you’re firing off a network request here for some reason, please don’t.
- **Execution**: like the name implies, this is where Gradle executes the tasks that were created and configured earlier.

Now on to some random bits of advice in no particular order of importance!

**6\. Think carefully about the plugins you add to your project**

Every plugin you add to your project adds time to the configuration phase, even if it doesn’t do anything. So go through your plugins and remove the ones you’re not using. You can find out how much time each plugin is adding to your build by looking at build scans.

## Get Adam Ahmed’s stories in your inbox

Join Medium for free to get updates from this writer.

**7\. If you’re using the Firebase Performance Monitoring plugin, disable it for debug builds**

We saw *massive* improvements by making this change.

```c
android {
……
    buildTypes {
        debug {
            FirebasePerformance {
                instrumentationEnabled false
            }
        }
    }
}
```

**8\. Convert build logic to static tasks**

If you have a lot of logic in your `build.gradle` script, consider converting it to static Gradle tasks so Gradle can cache their results and alleviate their effects on your project’s configuration phase time. For example, if you have code that determines the `versionName` based on the current Github branch, that’s a good candidate to start with. While we’re on this topic, always check and make sure your `build.gradle` scripts are as lean as possible. We removed a few legacy tasks/code snippets that didn’t make sense in the scope of our project anymore this way. You can read about the best practices for authoring maintainable builds [here](https://docs.gradle.org/current/userguide/authoring_maintainable_build_scripts.html)

**9\. Move scripts and tasks to your buildSrc directory**

This might not give you a performance improvement, but like we mentioned above, this cleans up your `build.gradle` scripts, and also allows you to easily reuse these tasks in different modules. You can read about the buildSrc directory [here](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html#sec:build_sources), writing scripts [here](https://docs.gradle.org/current/userguide/tutorial_using_tasks.html), and writing tasks [here](https://docs.gradle.org/current/userguide/custom_tasks.html).

**10\. Take advantage of configuration avoidance**

Now that you’re writing your own Gradle scripts, you should learn about [configuration avoidance](https://docs.gradle.org/current/userguide/task_configuration_avoidance.html). It allows Gradle to avoid creating/configuring tasks during the configuration phase when these tasks won’t be executed. For example, when running a compile task, other unrelated tasks, like code quality, testing and publishing tasks, will not be executed, so any time spent creating and configuring those tasks is unnecessary. You can start by registering your tasks instead of eagerly creating them, but there’s even more you can learn about effective task creation by reading the docs linked above. Registered tasks are known to the build, but they’re only configured when necessary for execution. Take a look at these two tasks:

```c
task mySlowTask {
    sleep 2000
    doLast {
        println "This task will add 2 seconds to your configuration phase every time you run any task"
    }
}tasks.register("mySlowTask") {
    sleep 2000
    doLast {
        println "This task won't add much time to your build unless you specifically execute it or any tasks that depend on it"
    }
}
```

**11\. Enable the parallel garbage collector if you’re using JDK 9+**

You might want to profile this change first before enabling it for your project, but it can be enabled by appending the string `-XX:+UseParallelGC` to your `org.gradle.jvmargs=` in the `gradle.properties` script, or just by adding the following line there if you haven’t customized these settings before.

```c
org.gradle.jvmargs=-XX:+UseParallelGC
```

**12\. Use the** [**gradle-doctor**](https://github.com/runningcode/gradle-doctor) **plugin**

I know I just said you should really think before adding plugins to your project, but this one’s only purpose is to improve your builds by giving you warnings about issues it finds in your project, and you can remove it once you’re done.

**13\. Enable non-transitive R classes**

Doing so helps prevent resource duplication by ensuring that each module’s R class only contains references to its own resources, without pulling references from its dependencies. This leads to more `UP_TO_DATE` builds and the corresponding benefits of compilation avoidance. [Many](https://twitter.com/n8ebel/status/1455347318199259137?s=21) people didn’t see any any performance improvement with this change though, so take it with a grain of salt.

Here’s an excellent blog [post](https://blog.blundellapps.co.uk/speed-up-your-build-non-transitive-r-files/) about this by [Blundell](https://twitter.com/blundell_apps)

**14\. Enable configuration caching**

This is an experimental feature, so proceed with caution, but I can confirm that it was absolutely magical for us. Remember the configuration phase we talked about above? Well, this feature caches its results and reuses them for subsequent builds, similar to how build caching caches and reuses task outputs. You can enable it by adding the line below to your `gradle.properties` script, but please read about it [here](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache) first.

```c
org.gradle.unsafe.configuration-cache=true
```

**15\. Disable Jetifier**

The jetifier tool basically replaces the `android.support` libraries with their equivalent `androidx` versions, but it also adds a ton of time to your configuration phase. If you don’t need it, it’s an easy performance gain.

This excellent blog [post](https://adambennett.dev/2020/08/disabling-jetifier/) by [Adam Bennett](https://twitter.com/iateyourmic) will help you determine whether or not you can remove the Jetifier from your project, as well as describing the improvements his team saw by doing so.

**16\. Disable the build variants you’re not using**

Gradle creates a build variant for every possible combination of the product flavors and build types that you configure, but there might be some variants that either you do not need or do not make sense in the context of your project. You can remove these build variant configurations by creating a variant filter in your module-level `build.gradle`. Here’s an example for disabling the mockRelease variant in case you never want to build that.

```c
android {
    ………
    buildTypes {
        debug { … }
        release { … }
    }
    productFlavors {
        mock { … }
        full { … }
    }
    variantFilter { variant -> 
        def names = variant.flavors*.name
        def isReleaseBuildType = variant.buildType.name == "release"
        if(names.contains("mock") && isReleaseBuildType) {
            setIgnore(true)
        }
    }
}
```

**17\. If you’re using Github Actions for your CI, consider the Gradle Build Action**

We were able to cut our total CI runtime for all consecutive pushes to the same branch in half by using the [Gradle Build Action](https://github.com/marketplace/actions/gradle-build-action) because it’s able to properly task cache results from previous runs, unlike the [Github Cache](https://github.com/marketplace/actions/cache) action which kinda didn’t work at all for us out of the box.

**Many thanks to** [**Adam Bennett**](https://twitter.com/iateyourmic) **and** [**Vladimir Jovanović**](https://twitter.com/VladimirWrites) **for their help with proof-reading this blog post!**

**That’s it! Please share this blog post to spread the knowledge**

**Add me on** [**Linkedin**](https://www.linkedin.com/in/oheyadam/) **and** [**Twitter**](https://twitter.com/oheyadam) **to talk about Gradle and Android!:)**[Android](https://medium.com/tag/android?source=post_page-----51f2b6d6b05b---------------------------------------)[Gradle](https://medium.com/tag/gradle?source=post_page-----51f2b6d6b05b---------------------------------------)

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.