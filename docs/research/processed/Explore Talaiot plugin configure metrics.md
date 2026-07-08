---
title: "Explore Talaiot plugin: configure metrics"
source: "https://medium.com/@mydogtom/explore-talaiot-plugin-configure-metrics-5ef3ae9a8c5d"
author:
  - "[[Svyatoslav Chatchenko]]"
published: 2020-03-21
created: 2026-07-07
description: "Talaiot is a Plugin written in Kotlin that helps to track the information of the Gradle tasks executed in your project during the build."
tags:
  - "clippings"
---
Talaiot is a Plugin written in Kotlin that helps to track the information of the Gradle tasks executed in your project during the build.

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----5ef3ae9a8c5d---------------------------------------)

### Talaiot is a simple and extensible plugin targeting teams using Gradle Build System. It records the duration of your…

github.com

Talaiot has notions of metrics (what to measure) and publishers (where to store/publish measurements [https://github.com/cdsap/Talaiot/#publishers](https://github.com/cdsap/Talaiot/#publishers)).

In this article, I will cover two topics:

- how to customise what is measured
- how to add additional metrics

## Initial setup

For the initial setup, I just follow [the readme](https://github.com/cdsap/Talaiot/#groovy) and use `JsonPublisher` which stores all measurements in the `build/reports/talaiot/data.json` file.

<iframe src="https://medium.com/media/54bb3ca6d07185ec1562b3f8a0883596" allowfullscreen="" frameborder="0" height="177" width="680" title=""></iframe>

Simple Talaiot configuration

After executing `/.gradlew tasks` `data.json` contains a lot of information like OS version, Java version, user name, etc.

<iframe src="https://medium.com/media/5e70cbab1201c9975b2e26b7c66ad5de" allowfullscreen="" frameborder="0" height="1541" width="680" title=""></iframe>

Generated report

## Customise what is measured

Depending on your use-case, you might need to disable some of the metrics. For example, `username` and `gitUser` are pretty much the same and you need only one of them. Or you simply might be not interested in some fields. Another example is when you store your measurement in InfluxDB/Prometheus and don’t want to blow up your storage with irrelevant data or fields with high cardinality.

Talaiot implements each measurement as a separate metric like `GitUserMetric`, `UserMetric`, `OsMetric` etc. Metrics are combined into groups: `default`, `git`, `environment`, `performance` and `gradleSwitches`. All that information is defined in `MetricsConfiguration`. By default all five groups are included. **Important**: default configuration is applied only if there is no customisation. It means that if you do customisation, then you have to specify all metrics that needs to be executed.

In our case, we want to include all metrics from groups `default` and `perfomance` and one specific metric `GitBranchMetric`. Talaiot configuration would look like

<iframe src="https://medium.com/media/866377eb282a4a864f4453604ca29f4a" allowfullscreen="" frameborder="0" height="221" width="680" title=""></iframe>

Talaiot config with specific metrics

After executing `/.gradlew tasks` `data.json` contains much fewer data.

<iframe src="https://medium.com/media/e1a5d0eebcbb54f68c4663c7188f513c" allowfullscreen="" frameborder="0" height="1321" width="680" title=""></iframe>

Generated report with subset of metrics

## Add custom metrics

Talaiot has a lot of different metrics, but sometimes you are interested to measure something specific to your project. For that you have two mechanisms:

- `customBuildMetrics` and `customTaskMetrics` are key-value maps that are defined at project configuration time [https://github.com/cdsap/Talaiot/#metrics](https://github.com/cdsap/Talaiot/#metrics)
- fully custom metrics, implemented in your project.

I’ll focus only on the second option because the first one is straightforward and well documented.

## Get Svyatoslav Chatchenko’s stories in your inbox

Join Medium for free to get updates from this writer.

Let’s track if the Gradle build was executed from IDE (Android Studio) or command line. As the second custom metric, we will identify the root folder of the project.

I implement custom metrics inside the `buildSrc` folder, but you can use [the composite build](https://docs.gradle.org/current/userguide/composite_builds.html) for that purpose too. It’s important to add the dependency on the Talaiot plugin.

<iframe src="https://medium.com/media/f889436958f56b429bc1f9b7a2392bfe" allowfullscreen="" frameborder="0" height="331" width="680" title=""></iframe>

buildSrc/build.gradle

Custom metric has to implement `Metric<T, in Context>` and has to define `provider` and `assigner` function. `provider` is a function that performs the calculation and `assigner` is a function that assigns the value to the `ExecutionReport`. There are several [predefined base metrics](https://github.com/cdsap/Talaiot/tree/master/talaiot/src/main/kotlin/com/cdsap/talaiot/metrics/base) like `GradleMetric`, `GradlePropertyMetric`, `CmdMetric`, `JvmArgsMetric` etc. In our case we will extend `*GradleMetric*` which defines that `provider` function receives Gradle project as aninput.

AndroidStudioMetric will look like

<iframe src="https://medium.com/media/9d564a65d033a553244d30208b062516" allowfullscreen="" frameborder="0" height="221" width="680" title=""></iframe>

Detect if build was executed from the IDE

I know that IDE adds `android.injected.invoked.from.ide` property to the build. Our metric simply checks if this property is present.

And ProjectFolderMetric will look like

<iframe src="https://medium.com/media/9d2b7b48ee220ea3468e48011cd9575d" allowfullscreen="" frameborder="0" height="133" width="680" title=""></iframe>

Identify project folder

It’s even simpler. We just retrieve [root directory of the project](https://docs.gradle.org/current/javadoc/org/gradle/api/Project.html#getRootDir--).

The final step is to adjust the Talaiot configuration

<iframe src="https://medium.com/media/6591a9ed9db0e696273828b84f13b18e" allowfullscreen="" frameborder="0" height="353" width="680" title=""></iframe>

Talaiot config with new metrics

After executing `./gradlew tasks` `data.json` contains two new fields under `customProperties.buildProperties`.

<iframe src="https://medium.com/media/d9bb543bffc974cb0dd5b24d7b35e5c4" allowfullscreen="" frameborder="0" height="1387" width="680" title=""></iframe>

Generated report with custom metrics

## Source code

Source code can be found [https://github.com/MyDogTom/ConfigureTalaiot](https://github.com/MyDogTom/ConfigureTalaiot)

Each step was added as a separate commit:

- initial setup [https://github.com/MyDogTom/ConfigureTalaiot/commit/63551db6f461befbeb7c46b90f126368a52357ae](https://github.com/MyDogTom/ConfigureTalaiot/commit/63551db6f461befbeb7c46b90f126368a52357ae)
- customise what is measured [https://github.com/MyDogTom/ConfigureTalaiot/commit/1fbf8a80cfa49441011f223e92837b8689d95816](https://github.com/MyDogTom/ConfigureTalaiot/commit/1fbf8a80cfa49441011f223e92837b8689d95816)
- add custom metrics [https://github.com/MyDogTom/ConfigureTalaiot/commit/e46978427ab1c72a10773b56e1942f98fa0a89d9](https://github.com/MyDogTom/ConfigureTalaiot/commit/e46978427ab1c72a10773b56e1942f98fa0a89d9)