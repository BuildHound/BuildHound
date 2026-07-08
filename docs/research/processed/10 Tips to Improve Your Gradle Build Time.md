---
title: "10 Tips to Improve Your Gradle Build Time"
source: "https://medium.com/@kenodoggy/10-tips-to-improve-your-gradle-build-time-1ddeca924759"
author:
  - "[[Brenda Cook]]"
published: 2016-04-16
created: 2026-07-07
description: "This article is tailored toward Android, but many of the tips are useful for any Gradle project."
tags:
  - "clippings"
---
*This article is tailored toward Android, but many of the tips are useful for any Gradle project.*

*Note: as of this writing instant run has been available as beta and has recently been released in Android Studio 2.0. I’ve seen people report many issues with it so far, so I’ve not jumped into that pool as of yet.*

Gradle is a powerful and wonderful build system, but it’s not perfect and build times are slower than other tools out there. In the past, I developed with Eclipse using Ant and Maven. Since I ended my relationship with Eclipse and moved on to Android Studio and IntelliJ using Gradle, I’ve noticed that I sit around a lot longer feeling, well, irritated when it came to running builds.

For too long, I just accepted this and quietly stewed — ok sometimes it wasn’t that quietly. I came to cope by finding other things to do during builds, such as browse the internet for cute cat photos (this is not a joke, sadly — or not sadly, depending on your perspective). However, a recent task required that I change EVERY single one of the TextViews, EditTexts, Buttons, etc. in a fairly large project from a 3rd party version that afforded backward compatibility with Roboto fonts to the standard AppCompat widgets. This meant changing millions (this may be a slight exaggeration) of xml files and Java class files, as well as checking to be sure that changes made still worked and looked the same once everything was said and done. As you could very well guess, this left me sitting around in build purgatory — repeatedly.

After a couple of days of doing this, I couldn’t stand it anymore, I decided there just had to be something I could do about this. Thankfully there was!! Here’s some information that I learned in my journey to faster build times that I thought at least one or two others would appreciate reading about.

For reference, my builds were taking about 1.5 to just under 2 minutes on average with install and launch on an Android emulator. In the end I’d whittled it down to about 30–40 seconds give or take a few seconds on any given build.

**1 Daemonize**

Shave off a little bit of time from Gradle startup time by using an existing daemon.

```c
org.gradle.daemon=trueor--daemon
```

**2 Parallelize**

Allows Gradle to build your projects in parallel. This is only useful for multi-project builds since tasks from the same project are never executed in parallel.

```c
org.gradle.parallel=trueor--parallel
```

The first option is for your.properties file, the second option is a command line option. (see item 4 for more on using a global gradle.properties file)

For example:

```c
./gradlew assembleDebug --parallel
```

This can also be set in Android Studio by checking the *Compile independent modules in parallel* box under **Preferences -> Build, Execution, Deployment -> Compiler**

If you want, you can choose to set the maximum number of threads by using the max workers option. If a max is not set, Gradle attempts to choose the right number of worker threads based on the number of available CPU cores.

```c
./gradlew assembleDebug --parallel --max-workers=numberOfMaxThreads
```

If you choose to use the parallel option, you should ensure that yours is a “ [decoupled project](https://docs.gradle.org/current/userguide/multi_project_builds.html#sec:decoupled_projects) ”.

**3 Configure on Demand**

If you have a multi-project build this one may be useful to you.

From the Gradle documentation: Configuration on demand mode attempts to configure only projects that are relevant for requested tasks, i.e. it only executes the build.gradle file of projects that are participating in the build. This way, the configuration time of a large multi-project build can be reduced. In the long term, this mode will become the default mode, possibly the only mode for Gradle build execution.

This incubating feature should be used with [decoupled projects](https://docs.gradle.org/current/userguide/multi_project_builds.html#sec:decoupled_projects).

```c
org.gradle.configureondemand=trueor--configure-on-demand
```

**4 Globalize**

This is more about convenience, such as combining 1–3.

Locally configure global Gradle settings for all projects by modifying or creating a gradle.properties file.

The gradle-wrapper.properties file is different than the gradle.properties file. In projects that use a Gradle wrapper such as Android, the gradle-wrapper.properties file exists in the project’s root directory and is typically source control tracked. The Gradle Wrapper exists to ensure that the correct Gradle version is being used even if the user has not installed Gradle previously or has a different version installed than the one required by the project. On the other hand, your global gradle.properties file exists in your top level USER\_HOME/.gradle directory (or wherever your GRADLE\_USER\_HOME points to) and shouldn’t be source control affected. Even if your sub projects have gradle.properties files, the gradle.properties file located in your user home directory will have precedence. If you don’t find a gradle.properties file in your.gradle directory, just create one.

Example:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*ZwX9oMRRylpEnzs_NXmczw.png)

See the whole [list](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_configuration_properties) of potential gradle.properties settings.

**5 Minimize**

Let’s say that you want to run build instead of assemble, but you don’t need lint or tests to run. You can exclude any task you want with

```c
-x nameOfTaskToExcludeor--exclude-task nameOfTaskToExclude
```

Example:

```c
./gradlew build -x test -x lint
```

**6 Leverage ART by Setting minSdkVersion at Build Time**

## Get Brenda Cook’s stories in your inbox

Join Medium for free to get updates from this writer.

This one is a big one and it yields significant performance gain.

Modify your Android app’s build.gradle file to allow setting the minSdkVersion at build by using a Gradle Property.

```c
// allows the developer to set minSdk at build in order to take
// advantage of faster build times leveraging ART in API 21+ without
// losing much needed lint checks
def minSdk = hasProperty('minSdk') ? minSdk : 16android {
    compileSdkVersion 23
    buildToolsVersion "23.0.2"    dexOptions {
        javaMaxHeapSize "4g"
        jumboMode true
    }    defaultConfig {
        applicationId "com.myapplication.app"        minSdkVersion minSdk
        targetSdkVersion 23
```

Once you’ve made these changes, you can invoke command line builds like so:

```c
./gradlew assembleDebug -PminSdk=23
```

What this does is set the minSdkVersion to 23 (or whatever you choose) rather than the regularly desired minSdkVersion. This produces faster builds since dexing for Dalvik is no longer a consideration as long as you choose 21+. If no minSdk property is found, then it falls back to the minSdkVersion that you’ve set for the else condition.

This has two advantages over the [Android documentation suggested approach](http://developer.android.com/tools/building/multidex.html#dev-build). The first is that it does not eliminate much needed lint checks. The second is that it allows individual team members to decide what their minSdkVersion will be so that they can match their emulator of choice without having it imposed on them. I have to give a shout out to Cesar Ferreira for this useful tip. See the Gist [here](https://gist.github.com/cesarferreira/8480ea6fd0b95ba57f98).

If you prefer to build using Android Studio don’t fret, you’re in luck. Simply modify your build settings by going to **Preferences -> Build, Execution, Deployment -> Compiler** and then in the box next to **Command-line Options** enter

```c
-PminSdk=theVersionYouWant
```

From then on, when building from Android Studio, the options you’ve set here will be applied until you change them. This does not afford the flexibility of changing your build options on the fly, but it’s still a nice option to have!

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*HTZfgcTSYoJeyR80vIVEqw.png)

**7 Go Offline**

If you find that Gradle is trying to access network resources and you know that these costly network connections are unnecessary, disable them by using the offline option. Offline specifies that the build should operate without accessing network resources.

```c
--offline
```

**8 Update Your Gradle Distribution to 2.4**

If you haven’t already, you might consider updating to 2.4. According to this official [Gradle Blog post](https://gradle.org/gradle-2-4-the-fastest-yet/), 2.4 is the fastest yet and is significantly faster than any of its predecessors.

**9 Move to jCenter**

This doesn’t affect incremental builds as much as it does fresh builds where you need to fetch dependencies.

You can learn more about why Android chose jCenter over mavenCentral

1. here: [https://blog.bintray.com/2015/02/09/android-studio-migration-from-maven-central-to-jcenter/](https://blog.bintray.com/2015/02/09/android-studio-migration-from-maven-central-to-jcenter/)
2. here: [https://code.google.com/p/android/issues/detail?id=72061](https://code.google.com/p/android/issues/detail?id=72061)
3. and here: [https://blog.bintray.com/2014/08/04/feel-secure-with-ssl-think-again/](https://blog.bintray.com/2014/08/04/feel-secure-with-ssl-think-again/)

**10 Profile!**

If you’ve done everything you think you can to improve build times and it’s still slow, figure out why, profile your Gradle build to see which tasks are taking the longest. If they’re non-essential you can improve your build performance by excluding them. Profiling your Gradle build couldn’t be easier, simply run your build with the profile option.

```c
--profile
```

Here’s example output. The first two screenshots show a build with the daemon option on and nothing else. The second two screenshots show a build run with daemon and -PminSdk=22

Notice the giant reduction in the time it took to complete the dex task when minSdk was set to 22.

Gradle 2.4 -daemon -profile

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*KboqoKRMzkoHikYTLvy6SA.png)

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*6Q5btPqXpi9YfMCZYefc-A.png)

Gradle 2.4 -daemon -PminSdk=22 -profile

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*dQg5b2PxkYwdkXaS8iYnhg.png)

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*FPs79RdLep3OrnT-u7jk6g.png)

Holy smokes! That’s more than a minute faster!

You can find your profile output in html format in your project under build/reports/profile

Happy building!

For further reading check out the official documentation and the command line info/cheat sheet.

[https://docs.gradle.org/](https://docs.gradle.org/current/userguide/gradle_command_line.html)

[https://docs.gradle.org/current/userguide/gradle\_command\_line.html](https://docs.gradle.org/current/userguide/gradle_command_line.html)