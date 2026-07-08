---
title: "Talaiot at Scale"
source: "https://proandroiddev.com/talaiot-at-scale-8cb5259d3244"
author:
  - "[[iñaki villar]]"
published: 2019-08-20
created: 2026-07-07
description: "Talaiot at Scale Six months ago, Talaiot was released as a tool to help to analyze the builds and make measurables improvements in our Gradle projects. The idea behind was simple, track the …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-8cb5259d3244---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-8cb5259d3244---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

Six months ago, Talaiot was released as a tool to help to analyze the builds and make measurables improvements in our Gradle projects. The idea behind was simple, track the information of the build and publish the results:

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----8cb5259d3244---------------------------------------)

### Talaiot is a simple and extensible plugin targeting teams using Gradle Build System. It records the duration of your…

github.com

Since then, several releases happened(current version [1.0.4](https://bintray.com/cdsap/maven/talaiot)). We have included new Publishers like Elasticsearch, PushGateway or Timeline. We fixed bugs, and we tried to optimize the way we collect and report the data. Being an Open Source project, we have had contributions and feedback from the community. Check these articles to know more about Talaiot:

- [Understanding Internals Talaiot](https://proandroiddev.com/understanding-talaiot-5da62594b00c)
- E [xploring the InfluxDbPublisher in Talaiot](https://proandroiddev.com/exploring-the-influxdbpublisher-in-talaiot-ae6c60a0b0ec)
- [Graphs, Gradle and Talaiot](https://proandroiddev.com/graphs-gradle-and-talaiot-b0c02c50d2b1)

In this article, we are going to explore which direction is taking Talaiot since the changes on 1.x and how we can work with Talaiot in medium/big projects.

## Denormalizing Building Information

One of the significant changes coming in 1.x releases was the reworking on the primary entity that holds the information of the build and how is collected the data.

[Anton Malinskiy](https://medium.com/u/71457ef24fa1?source=post_page---user_mention--8cb5259d3244---------------------------------------)

made this excellent contribution, check this article where he explains the changes:

## [Gradle build analytics](https://medium.com/@Malinskiy/gradle-build-analytics-444e8870c8cf?source=post_page-----8cb5259d3244---------------------------------------)

### Working with any build system is fun and games until you reach a point where almost every single person complains about…

medium.com

Previously, we were including a collection of entries representing the execution of the tasks along with metrics (default and custom) defined in the main configuration. In environments like InfluxDb, this caused hitting the high cardinality problems quick. Sooner or later(depending on the design of the DB) factors like tasks and dynamic measurements will contribute to that, independent of changes like increasing values of `max-series-per-database`

Anton’s changes included a new main Entity `ExecutionReport` which denormalizes the information of the build from the tasks. This means that we can define two different dimensions of data to send our information about the build. Let’s explore deep. Firstly, I want to remind the main interface `Publisher`:

```c
interface Publisher {    
    fun publish(report: ExecutionReport)
}
```

`Publisher` represents an entity where the information will be published. The contract includes information about the build( `ExecutionReport` ). Once the build is finished, Talaiot will publish the results in all the publishers registered:

```c
publisherProvider.get().forEach {
    it.publish(report)
}
```

The new `ExecutionReport` entity contains the information of the build divided by different categories:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*LN49cEAciCTaln9x3EFkmg.png)

The build info section contains information related to the duration of the build itself. Environment properties are including different metrics of our environment during the build:

<iframe src="https://proandroiddev.com/media/54dcd5f07aaa004077717c7751c53c93" allowfullscreen="" frameborder="0" height="249" width="680" title="Fields"></iframe>

As Gradle Scan service is showing at the report, `ExecutionReport` is including the “Switches” category information:

<iframe src="https://proandroiddev.com/media/6f955947e65eeb0136c39ce4b72ed29c" allowfullscreen="" frameborder="0" height="203" width="680" title="Swtiches"></iframe>

The last category is Custom Metrics, where can add additional information to our builds like “team” or “version”.

Regarding the tasks, the information included keeps the same, tracking the task path, duration and task execution state.

## Get iñaki villar’s stories in your inbox

Join Medium for free to get updates from this writer.

With this approach, we can define different custom metrics for each dimension, allowing us being more specific on how we track the data:

```c
metrics {
    customBuildMetrics ("versionApp" to $version)
    customTaskMetrics ("team" to $team, 
                       "customProperty" to getCustomProperty())
}
```

These changes help us to structure better the data and give us more flexibility having to dimensions: build information and task information.

## Reducing the impact on big projects

We have seen that denormalizing the information of the main entity helps us to design better strategies to be consumed by the Publishers. However, in medium-big teams sometimes is not enough because we are generating massive amounts of data, and we need to evaluate what we want to track or investigate. Let’s take an example of one medium Android team of 30 developers working during a week:

```c
30 developers x 15 builds x 800 tasks x 5 days
```

This will generate 1800000 task entry points, and 2250 build entry points per week, without considering CI builds. If we are using as Publisher InfluxDb, we will reach limits on the default configuration on the 3rd day. Once we have decided to invest time and resources to analyze builds, the last thing we want is bothering our infra colleagues asking to allocate more resources. So we need to think first what we want to track in our build iteration.

Now we are going to see different techniques to reduce the amount of information depending on our requirements:

**1- Filtering**

Since version 0.3, thanks to Satyarth Sampathto, we can filter the configuration of our tasks depending on different entities. Filtering helps in tracking specific investigations on single modules or exclude tasks we don’t need to track. The available filters are tasks, modules and thresholds:

<iframe src="https://proandroiddev.com/media/5d6c3b1c0a6a88e1135dafa70fb8a1d5" allowfullscreen="" frameborder="0" height="309" width="680" title="Filtering"></iframe>

For `tasks` and `modules`, we can define inclusions and exclusions in our filters. In the case of `threshold` filters, we can define lower and upper values for our inputs.

Note that this doesn’t apply for all publishers, in case we want to use GraphPublishers, Talaiot won’t filter anything.

**2- Disabling Tasks/Build publishing**

In other scenarios, we may want to send only one of the dimensions to our publishers. For example, we are only interested in track build times and no tasks times, or if we are using another service like Gradle Enterprise, we want to track only tasks to follow up a specific problem but not the build information.

The main `PublisherConfiguration` offers the possibility to achieve this requirement. The specific dimension can be disabled with `publishBuildMetrics` or `publishTaskMetrics`.

Example:

```c
influxDbPublisher {
  dbName = "tracking"
  url = "http:influxdb.local"
  publishBuildMetrics = false
}
```

The publishers implementing this feature are `InfluxDbPublisher`, `PushGatewayPublisher`, `OutputPublisher` and `ElasticSearchPublisher`. If you are using custom publishers, you can implement your logic that fits in your requirements.

**3- Hybrid Publishers**

Another new publisher included in the last release is the `HybridPublisher`. The idea came from this [issue](https://github.com/cdsap/Talaiot/issues/89). Ivan asked about the possibility to choose a different publisher per dimension. And that is exactly the purpose of this new Publisher.

Giving more context, imagine you want to measure tasks and builds but you don’t want to mess with additional filters(all the info is valuable). Moreover, in your company, infrastructure offers different services. It makes sense to have the values with less cardinality in one environment like InfluxDb and the massive data provided by the tasks metrics in other environments more flexibles like Elasticsearch. With the `HybridPublisher`, we can choose publishers depending on the dimension:

<iframe src="https://proandroiddev.com/media/d82ff11a65cf45a55bf5b54d036901c4" allowfullscreen="" frameborder="0" height="287" width="680" title="Hybrid"></iframe>

In this case, the publisher will send the information related to the Build to `buildPublisher` InfluxDb, and the tasks data to the `taskPublisher` defined in the configuration, Elasticsearch. In the next iterations, we will allow defining custom publishers in the `HybridPublisher`, giving more flexibility.

## Final words

Measuring our changes and improvements in the build system should be the way to understand how we can improve our processes. Gradle Enterprise offers you excellent tools to understand better these bottlenecks, but in case you don’t have the service or you want to explore other possibilities with Talaiot, remember is open source and is waiting for contributions:

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----8cb5259d3244---------------------------------------)

### Talaiot is a simple and extensible plugin targeting teams using Gradle Build System. It records the duration of your…

github.com

Big thanks to

[Anton Malinskiy](https://medium.com/u/71457ef24fa1?source=post_page---user_mention--8cb5259d3244---------------------------------------)

, Satyarth Sampathto,

[Ivan Balaksha](https://medium.com/u/f6d5ddc01c6?source=post_page---user_mention--8cb5259d3244---------------------------------------)

and the Grab team for the help, feedback and new ideas.

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:96:96/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_info--8cb5259d3244---------------------------------------)The latest posts from Android Professionals and Google Developer Experts.

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.