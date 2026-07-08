---
title: "Exploring the InfluxDbPublisher in Talaiot"
source: "https://proandroiddev.com/exploring-the-influxdbpublisher-in-talaiot-ae6c60a0b0ec"
author:
  - "[[iñaki villar]]"
published: 2019-04-15
created: 2026-07-07
description: "Exploring the InfluxDbPublisher in Talaiot A couple of weeks ago we checked the internals of Talaiot, learning how it works and what are the internal concepts(Publishers and Metrics). This time we …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-ae6c60a0b0ec---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-ae6c60a0b0ec---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*vDlE65-0mrtTI-oTgRf1aw.png)

A couple of weeks ago we [checked](https://proandroiddev.com/understanding-talaiot-5da62594b00c) the internals of Talaiot, learning how it works and what are the internal concepts([Publishers](https://github.com/cdsap/Talaiot#publishers) and [Metrics](https://github.com/cdsap/Talaiot#metrics)). This time we are going to learn more about one of the default publishers included in Talaiot: **InfluxDbPublisher**.

The idea is to publish the information of the build to the InfluxDb Server configured in the Talaiot plugin. Later, we will connect it with Grafana to create different dashboards to visualize the data.

## InfluxDb

First of all, we should explain more about [InfluxDb](https://www.influxdata.com/). InfluxDb is an open-source time series database developed by InfluxData. So what is a time series database? Following Wikipedia, [we have this definition](https://en.wikipedia.org/wiki/Time_series_database):

> A time series database (TSDB) is a software system that is optimized for handling time series data, arrays of numbers indexed by time (a datetime or a datetime range).

If still is not very clear what would be the difference with other DB Types like Relational or Key-Values Databases more details can be found [here](https://blog.timescale.com/what-the-heck-is-time-series-data-and-why-do-i-need-a-time-series-database-dcf3b1b18563/).

Once we know more about InfluxDb is the moment to start interacting with the database, for that we need to use a specific protocol:

**Line Protocol**

InfluxDB’s Line Protocol is the format used for writing points to the database. The format is:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*XcLwpDAJt-UDJMYi090y5A.png)

Where:

**Measurement**: is the name for the set of information that you are registering

**Tag**: additional information you want to include in your data point

**Field**: the value of the measurement, is required for every data point.

**Timestamp**: the timestamp for your data point. It is optional and by default uses the server’s local nanosecond timestamp in UTC.

The design of the scheme it could look easy when we have simple data points but is vital to think wisely the design in different scenarios to avoid problems like High Cardinality, check the [InfluxDB schema design and data layout](https://docs.influxdata.com/influxdb/v1.7/concepts/schema_and_data_layout/) for more information.

In Talaiot, once you have applied the plugin in your project and registered the InfluxDbPublisher, every task will generate one data point following the Line Protocol:

`tracking,state=EXECUTED,rootNode=false,task=:app:signingConfigWriterDebuf,branch=master value=25`

## Dashboards

Once we have our instance of InfluxDb and set-up Talaiot, builds will generate data points of the tasks in the Database. If you want to start querying your data you can use the [HTTP API](https://docs.influxdata.com/influxdb/v1.7/guides/querying_data/) provided by InfluxDb:

`curl -G 'http://localhost:8086/query?pretty=true' --data-urlencode "db=tracking" --data-urlencode "q=SELECT \"value\", \"task\" FROM \"tracking\""`

With this query you will receive data like:

```c
"values":[  
   [  
      "2019-04-09T10:19:54.365252852Z",
      8,
      ":app:checkDebugManifest"
   ],
   [  
      "2019-04-09T10:19:54.365252852Z",
      84749,
      ":app:assembleDebug"
   ],
   [  
      "2019-04-09T10:19:54.365252852Z",
      13,
      ":app:preBuild"
   ]
]
```

However, it doesn’t look very convenient to work at the time to analyze big chunks of data. We will use an external tool to create custom Dashboards: Grafana.

## Grafana

[Grafana](https://grafana.com/) is a powerful tool for data visualization and monitoring.

The main components are:

**Data Sources:** are storage backends for the time series data. Currently, the data sources supported are Graphite, InfluxDB, OpenTSDB, Prometheus, Elasticsearch, CloudWatch.

**Panel:** is the basic visualisation block in Grafana(for instance one graph). Each panel includes one tool called Query Editor

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*AsJIt0QklAP8___nDsneVg.png)

**Query Editor:** It allows using the Data Source defined in the settings. It includes a graphical user interface to help in the creation of the queries:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*hztekw_1u3-By8bOzw9mew.png)

You can use the SQL mode if you are fluently building the queries with the SQL syntax.

**DashBoard:** It is a set of Panels with data provided by Data Sources and modelled by the Query Editor. It combines all the previous concepts:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*Exccj3hyOOQiWhUNkgnpSw.png)

There are more exciting concepts in Grafana like the [Alerting Engine](https://grafana.com/docs/alerting/rules/) that allows you to attach rules to your dashboard panels or [Plugins](https://grafana.com/docs/plugins/installation/) where you can extend functionality from the core Grafana implementation

At this point, we will connect the Grafana’s data source with our InfluxDb instance and then we will be ready to start building our dashboards with the data registered with the InfluxDbPublisher.

## InfluxDbPublisher

In the Talaiot architecture, InfluxDbPublisher is one default publisher that delivers the information tracked during the build to the InfluxDb Instance, the overall picture is:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*rKIqbGfiJTv2ZJ3twEKGrA.png)

Here, we have included Grafana as an external system to connect to the InfluxDbServer.

## Get iñaki villar’s stories in your inbox

Join Medium for free to get updates from this writer.

The basic configuration required for the TalaiotConfiguration is:

<iframe src="https://proandroiddev.com/media/adab1039a4e683e154a0866c9d34c2fd" allowfullscreen="" frameborder="0" height="287" width="680" title="InfluxDbPublisher Conf"></iframe>

Remember that you can implement your [custom publishers](https://github.com/cdsap/Talaiot/wiki/Publishers#custompublisher) to extend the functionality under your infrastructure's requirements.

## Quick Setup with Docker

To have a quick setup to check the possibilities of Talaiot, we are providing a Docker image with Grafana and Inlfluxdb instances(based on [this](https://github.com/philhawthorne/docker-influxdb-grafana) great repo).

Additionally, the Docker image is creating a default database, a provisioned dashboard and the default data source for InfluxDb. The source is [here](https://github.com/cdsap/Talaiot/blob/master/docker/Dockerfile):

To run the Docker Image:

```c
docker run -d \
  -p 3003:3003 \
  -p 3004:8083 \
  -p 8086:8086 \
  -p 22022:22 \
  -v /var/lib/influxdb \
  -v /var/lib/grafana \
  cdsap/talaiot:latest
```

You can access to the local instance of Grafana:

`http://localhost:3003` root/root

## Examples

**Use case 1: Measuring improvements of R8**

Our project still is working with D8 and Proguard and we are scheduling a task to use R8 as the main compiler for dexing and shrinking/obfuscation/optimization. After finishing the task of migration and fixing the possible problems found in the process, is the moment to measure what is the impact on the build.

We will create a custom metric called `R8` and will assign the value depending on the configuration provided by the conf file or custom provider:

```c
talaiot {
    metrics {
        customMetrics("R8" to isR8Enabled())
    }
    publishers {
        influxDbPublisher {
            dbName = "tracking"
            url = "http://localhost:8086"
            urlMetric = "tracking"
        }
    }
}
```

Once the build is finished, for each task tracked we will have one data point like:

```c
tracking,state=EXECUTED,rootNode=true,task=:app:assembleRelease,gradleVersion=5.1,R8=true,user=inaki,project=TalaiotClientExample,os=MacOSX-10.14.4 value=45882
```

You can notice `R8=value` has been added in the measurement for every task.

Now we can create the panel in Grafana, first of all, the query should check the overall time of the main task(assembleRelease) and the property flag R8

```c
SELECT mean("value") FROM "tracking" WHERE ("R8" = 'true' AND "task" = ':consumer-android:app:assembleRelease') AND $timeFilter GROUP BY time($__interval) fill(null)
```

However, thanks to the Query Editor we can build the query without knowing the exact syntax of SQL:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*3HNpN2GJfC20tr7GIBRmAA.png)

Is up to us how we want to design the experimentation or regression process to compare both configurations. Here is the result of an automated process with 25 builds for each option:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*A-xlDrScASMD6oJQywi-dA.png)

R8 is speeding up our builds by 14%:)

**Uses Case 2: Build Process by Modules**

Nowadays, in the Android world [modularization](https://medium.com/androiddevelopers/a-patchwork-plaid-monolith-to-modularized-app-60235d9f212e) is becoming more important. Besides the known benefits in terms of coding and architecture, it is important to know if with modularization we are creating some bottleneck in the design of the modularization.

In this example, we will use Talaiot to show the distribution of time spent by a module in the build of an Android Project.

Within the data point generated, `module` is one property of the main Domain Entity of Talaiot included as a tag. The query should be:

```c
SELECT sum("value") FROM "tracking" WHERE $timeFilter GROUP BY time($__interval), "module" fill(null)
```

We are using a summary of the values(length) of the task grouped by module.

The panel for this query in a simple graph is:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*SIDTcFSpP8F9wUXbfuxuCw.png)

Where we are stacking the results in a percentage distribution. Maybe in a project with few modules is ok but if we have hundreds of modules, it will be hard to visualize the information properly.

To improve the visualization, we will use Pie Chart Plugin:

## [Pie Chart plugin for Grafana](https://grafana.com/plugins/grafana-piechart-panel?source=post_page-----ae6c60a0b0ec---------------------------------------)

### Pie chart panel for grafana

grafana.com

The results applying in the new panel included with the same query would be:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*5fjZhk_ZZYoMIWXwUyR5Cw.png)

## What’s next?

In the next article, we will explain more about one of the new categories of Publishers in Talaiot called GraphPublishers.

Thanks and Happy Tracking!

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:96:96/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_info--ae6c60a0b0ec---------------------------------------)[Last published 1 day ago](https://proandroiddev.com/how-i-found-a-deadlock-inside-art-that-art-itself-couldnt-dump-ff7b33b0334a?source=post_page---post_publication_info--ae6c60a0b0ec---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

## Responses (1)

Write a response[What are your thoughts?](https://medium.com/m/signin?operation=register&redirect=https%3A%2F%2Fproandroiddev.com%2Fexploring-the-influxdbpublisher-in-talaiot-ae6c60a0b0ec&source=---post_responses--ae6c60a0b0ec---------------------respond_sidebar------------------)

```c
Hi do you mind share the dashboard json file ?? i want to integrate metrix dashboard like your, but after trying for hour its still failed to create metrix like yours
```

Reply

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.