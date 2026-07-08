---
title: "Gradle Remote Cache with Redis and Circle CI"
source: "https://proandroiddev.com/gradle-remote-cache-with-redis-and-circle-ci-d0f3f5ab14df"
author:
  - "[[iñaki villar]]"
published: 2020-04-14
created: 2026-07-07
description: "Gradle Remote Cache with Redis and Circle CI I would say Remote Cache is one of the top features to optimize the build speed across the Development Team using Gradle as Build System. Today, we have …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-d0f3f5ab14df---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-d0f3f5ab14df---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*n4qogcKRDWXzz35S1ylzlw.jpeg)

I would say Remote Cache is one of the top features to optimize the build speed across the Development Team using Gradle as Build System.

Today, we have different solutions and resources helping to create, manage and implement Remote Caching. [Gradle Enterprise Build Cache](https://docs.gradle.com/enterprise/tutorials/caching/) is the most powerful solution, offering a remote and replicated cache**,** we are using it at Tinder and we are thrilled with the results in a distributed team.

There are other solutions like Remote Cache in AWS S3 ([here](https://github.com/myniva/gradle-s3-build-cache) and [here](https://github.com/americanexpress/gradle-s3-build-cache)), or you can implement in your custom infrastructure with the official [Docker image](https://hub.docker.com/r/gradle/build-cache-node/) for a remote Node. For targeting specific caching systems like Redis, you also have different plugins ([here](https://plugins.gradle.org/plugin/com.github.tagantroy.gradle-redis-build-cache) and [here](https://github.com/tehlers/gradle-redis-build-cache)). And from the Gradle perspective, you can extend the build cache functionality with the [BuildCacheConfiguration](https://docs.gradle.org/current/javadoc/org/gradle/caching/configuration/BuildCacheConfiguration.html) API.

These days of massive working from home, new scenarios have raised due to this new reality.

[Nelson Osacky](https://medium.com/u/85419ad6ecd1?source=post_page---user_mention--d0f3f5ab14df---------------------------------------)

is writing an excellent [serie](https://medium.com/@runningcode/how-fast-does-my-internet-need-to-be-to-use-the-gradle-remote-build-cache-part-1-4acaa6f9a2fa) s of posts about the trade-of of using remote caching with slow connections

But this article is just a quick test to compare for a specific Cache System(Redis), which Kubernetes infrastructure provider([EKS](https://aws.amazon.com/eks/) / [GKE](https://cloud.google.com/kubernetes-engine)) suits better for a Circle CI.

## Simple Local Example

To validate the idea, we are going to use a simple project and Redis. We can create a Redis instance locally with Docker:

```c
docker run --name redis-build-cache -d redis
```

For communication with Redis, we will use one of the plugins mentioned before([https://github.com/tehlers/gradle-redis-build-cache](https://github.com/tehlers/gradle-redis-build-cache)). In your Gradle project, you need to include the plugin:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*wzQCXkd1MEAcue5RWR7oww.png)

And then, set up the RedisBuildCache Service in the `buildCache` configuration:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*_awrjFRuiSq7I7WGsV-fwA.png)

Finally, we can execute the target task adding the parameter `--scan` to retrieve the build scan. After the second execution, we double-check in the build scan generated the correct configuration(performance > build cache):

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*j8KqFQhBCFLtbA468OSvsA.png)

## Experimenting with K8s Providers

Now that we know the local configuration works, it’s time to move to the cloud.

**Project**

We are going to use a fork of [Tivi](https://github.com/chrisbanes/tivi), a project by

[Chris Banes](https://medium.com/u/9303277cb6db?source=post_page---user_mention--d0f3f5ab14df---------------------------------------)

to use as a showcase of different features of Android. The main debug task contains 30 projects and 903 tasks.

**CI Configuration**

Once we have forked the project, we have to set up the CircleCI configuration. We will create a new configuration file (`.circleci/config.yml`), including the main debug task:

```c
- run:
        name: build
        command: ./gradlew app:assembleDebug --scan
```

We are using the `--scan` parameter to generate the build scan like the local example. Don’t forget to add the `buildScan` configuration block to add the license acceptance.

**Deploying Redis Instance**

We are going to use a Redis Instance in Kubernetes with EKS and GKE. The creation of the cluster is out of the scope of this article, but in case you need some guidance, for GKE you have:

## [Quickstart | Kubernetes Engine Documentation | Google Cloud](https://cloud.google.com/kubernetes-engine/docs/quickstart?source=post_page-----d0f3f5ab14df---------------------------------------)

### In this quickstart, you deploy a simple web server containerized application to a Google Kubernetes Engine (GKE)…

cloud.google.com

And for EKS, I find that the Bitnami article is one of the best resources:

## [Get Started with the Amazon Elastic Container Service for Kubernetes (EKS)](https://docs.bitnami.com/aws/get-started-eks/?source=post_page-----d0f3f5ab14df---------------------------------------)

### Amazon Web Services (AWS) is a well-known provider of cloud services, while Kubernetes is quickly becoming the standard…

docs.bitnami.com

When you create a cluster, you need to choose a zone/region. To have better results, we need to select according to the CircleCI infrastructure. They mentioned on the website:

> CircleCI builds are currently run mainly from AWS East and West as well as Google Cloud Platform East.

In this example, we are going to base both infrastructures in the same zones (us-east).

## Get iñaki villar’s stories in your inbox

Join Medium for free to get updates from this writer.

Once you have your cluster up, it’s the moment to deploy the Redis instance. You can use the charts provided by Helm or the option that suits in your cluster. In case you want to apply something quick, you can execute in your cluster:

<iframe src="https://proandroiddev.com/media/cc8c65cc5d4af3b16994a1e723921dbd" allowfullscreen="" frameborder="0" height="904" width="680" title="Remote Cache with Redis and K8s"></iframe>

The last step is going to provide a public IP, this IP it’s linked with the Redis instance. To get the IP, you need to execute `kubectl get svc`:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*7DdFYwako-UYcZcwndx3MA.png)

Once the IP is ready(for GKE is not immediate), you can proceed to update the `build.gradle` configuration with the new URL in the same configuration explained in the previous point.

And that it is! Now is time to trigger the builds in Circle CI and collect the results.

## Results

**GKE**

Performance > Task Execution:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*H4a0Dro27w2ZKkPRkCIs5g.png)

Performance > Build Cache

![](https://miro.medium.com/v2/resize:fit:1120/format:webp/1*A5VzPS8ilXODUKQ8Th-esQ.png)

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*JM2s5N09Pt9xup_0plVZ0g.png)

**EKS**

Performance > Task Execution:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*vDtn8vzZzvHJJG9AoEs4Yw.png)

Performance > Build Cache

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*cluyTXS5SHHIXAk2wGSytA.png)

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*GJtF95gpbp-p_Iz-1pb8fw.png)

The execution of the builds in Circle CI was on April 12th around 8:00 pm(UTC -7). Previously I tested at different times and the results kept the same proportion with less download speed rate.

EKS is the winner of this experiment 🎉 🎉. We achieve better speed for storing and retrieving from the cache:

GKE: 685,2 kB/s — 867,6 kB/s

EKS: 756,8 kB/s — 1,2 MB/s

To retrieve the heaviest task output, `:app:mergeDebugJavaResource` with size 24.77 MB, we have 52.6 MB/s for EKS and 45.5 MB/s for GKE. Both are very good results, but slightly better for EKS.

## Final Words

Do you have more experiments in mind? Feel free to share suggestions and ideas.

Thank you very much for reading the article.

Happy Building and stay safe![Gradle](https://medium.com/tag/gradle?source=post_page-----d0f3f5ab14df---------------------------------------)[Redis](https://medium.com/tag/redis?source=post_page-----d0f3f5ab14df---------------------------------------)[Ci](https://medium.com/tag/ci?source=post_page-----d0f3f5ab14df---------------------------------------)

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:96:96/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_info--d0f3f5ab14df---------------------------------------)[Last published 1 day ago](https://proandroiddev.com/how-i-found-a-deadlock-inside-art-that-art-itself-couldnt-dump-ff7b33b0334a?source=post_page---post_publication_info--d0f3f5ab14df---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

## Responses (1)

Write a response[What are your thoughts?](https://medium.com/m/signin?operation=register&redirect=https%3A%2F%2Fproandroiddev.com%2Fgradle-remote-cache-with-redis-and-circle-ci-d0f3f5ab14df&source=---post_responses--d0f3f5ab14df---------------------respond_sidebar------------------)

==Don’t forget to add the buildScan configuration block to add the license acceptance.==

```c
Maybe a link to or an example of this?
```

Reply

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.