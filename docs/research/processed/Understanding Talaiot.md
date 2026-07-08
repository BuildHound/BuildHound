---
title: "Understanding Talaiot"
source: "https://proandroiddev.com/understanding-talaiot-5da62594b00c"
author:
  - "[[iñaki villar]]"
published: 2019-04-03
created: 2026-07-07
description: "Understanding Talaiot Talaiot is a simple Plugin written in Kotlin that helps to track the information of the Gradle tasks executed in your project during the build: cdsap/Talaiot Simple and …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-5da62594b00c---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-5da62594b00c---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

Talaiot is a simple Plugin written in Kotlin that helps to track the information of the Gradle tasks executed in your project during the build:

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----5da62594b00c---------------------------------------)

### Simple and extensible plugin to track task times in your Gradle Project. — cdsap/Talaiot

github.com

## Motivation

Making a plugin to help to understand your builds is not a new idea. In the past we were using this awesome plugin by Pascal Hartig:

## [passy/build-time-tracker-plugin](https://github.com/passy/build-time-tracker-plugin?source=post_page-----5da62594b00c---------------------------------------)

### Gradle plugin to continuously track and report your build times — passy/build-time-tracker-plugin

github.com

Thanks to this plugin we understood better these annoying tasks, consuming time and resources. Another cool Plugin is Kurinometer by Pedro Gómez:

## [pedrovgs/Kuronometer](https://github.com/pedrovgs/Kuronometer?source=post_page-----5da62594b00c---------------------------------------)

### Gradle plugin to measure build times. Let’s measure how long developers around the world are compiling software. …

github.com

It was developed with Scala and FP concepts.

However, we wanted to go further, for medium and big companies understanding the building process is critical to detect the bottlenecks and trying to keep the productivity and motivation of developers. [Build Scan](https://scans.gradle.com/) is excellent to understand all the details, and you can aggregate the results if you are using Gradle Enterprise.

## [Gradle Enterprise | Improve Build Speed, Reliability and Debugging](https://gradle.com/?source=post_page-----5da62594b00c---------------------------------------)

### Gradle Enterprise improves developer productivity by speeding up slow builds, improving build reliability, and…

gradle.com

But not all the companies are using Gradle Enterprise and is here where Talaiot could help teams of developers. Talaiot provides information about the build tasks and allows to extend and customize the information we want to analyze.

## Internals of the Plugin

The base implementation is straight forward. We need to hook our tracker in the build process to collect information of the two main Gradle lifecycles involved:

— The task LifeCycle

— The build LifeCycle

The Talaiot Plugin provides one Listener that combine both interfaces information:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*MG61v2jT76Wy5aiGTi5ukQ.png)

During the build, we will record information about the tasks like the length or status:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*1ulCA8Gi-QqyIWplKgxPMA.png)

For instance, we found missing configurations on developer’s machines where tasks were not cached.

Finally, the information will be combined with two more components of the plugin:

## Get iñaki villar’s stories in your inbox

Join Medium for free to get updates from this writer.

— Metrics

— Publishers

## Metrics

For every measurement done, Talaiot adds metrics to help you analyze data and detect problems due to different environments on the developer’s machines. Metrics are categorized by different configurations and can be disabled by groups. The Default Configuration of Metrics includes:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*CgxQZyy4OhCnYFsVNHBnpg.png)

You can extend it with custom metrics. For instance, if we want to add the version of the app and a custom property we have to include the new metrics in the Talaiot configuration:

<iframe src="https://proandroiddev.com/media/3c7ad59ca1b9db1c86052b33bad1652a" allowfullscreen="" frameborder="0" height="177" width="680" title="Custom Metrics"></iframe>

## Publishers

Once the build is finished is the moment to publish the results. Talaiot includes two default publishers:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*9lV_EucBWmQXf7IE54BzDg.png)

In the next post, we will explore more about InfluxDbPublisher. In case you have different systems or requirements Talaiot allows creating custom Publishers. You only have to extend the `Publisher` interface:

<iframe src="https://proandroiddev.com/media/434b823b8fece2850381bfb9851933af" allowfullscreen="" frameborder="0" height="177" width="680" title="JsonPublisher"></iframe>

And then include it in the Talaiot configuration in your Gradle file:

<iframe src="https://proandroiddev.com/media/f2ff2065311991e91726d2d95dea9ff1" allowfullscreen="" frameborder="0" height="155" width="680" title="Custom Publisher"></iframe>

## Final words

You can start using Talaiot today including the plugin in the classpath:

```c
classpath("com.cdsap:talaiot:<latest_version>")
```

And applying the plugin:

```c
plugins {
    id("talaiot")
}
```

You will find more information about different configurations in the repository and wiki pages:

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----5da62594b00c---------------------------------------)

### Simple and extensible plugin to track task times in your Gradle Project. — cdsap/Talaiot

github.com

Don’t miss the next episode explaining the InfluxDbPublisher.

Thanks for reading and Happy Tracking!

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:96:96/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_info--5da62594b00c---------------------------------------)The latest posts from Android Professionals and Google Developer Experts.

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.