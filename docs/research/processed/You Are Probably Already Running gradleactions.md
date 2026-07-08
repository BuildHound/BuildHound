---
title: "You Are Probably Already Running gradle/actions"
source: "https://blog.gradle.org/running-gradle-github-actions"
author:
  - "[[Laura Kassovic]]"
published: 2026-06-30
created: 2026-07-07
description: "There is a particular kind of software that you depend on heavily and have never once thought about. It does not have a landing page you have visited. It was not chosen at an architecture review. Nobody sat in a conference room and debated it against three competitors, and no ..."
tags:
  - "clippings"
---
How a corner of the JVM world quietly standardized on one GitHub Action, what it actually does for them, and why you can read all of this off other people's build files.

## Introduction

There is a particular kind of software that you depend on heavily and have never once thought about. It does not have a landing page you have visited. It was not chosen at an architecture review. Nobody sat in a conference room and debated it against three competitors, and no one wrote a blog post titled “Why We Migrated To It.” It simply showed up one day, did its job, and has been doing its job on every commit since.

A great deal of the build automation running on GitHub is this kind of software. And one of the most widely used examples, if you write anything that touches the Java Virtual Machine, is a small collection of GitHub Actions published by Gradle at [`gradle/actions`](https://github.com/gradle/actions).

![Gradle GitHub Action](https://blog.gradle.org/images/2026/running-gradle-github-actions/image3.png "Gradle GitHub Action")

You may not recognize the name `setup-gradle`. But it’s used by [more than 45,000 open source repositories](https://blog.gradle.org/github-actions-for-gradle-v6), and it’s the action baked into [GitHub’s own official starter workflow](https://docs.github.com/en/actions/automating-builds-and-tests/building-and-testing-java-with-gradle) for Gradle projects. When you click “Set up a workflow” on a fresh Gradle repo and accept the suggestion GitHub hands you, you are adopting `gradle/actions` without ever having formed an opinion about it. This is how the most consequential defaults always spread: not by persuasion but by presumption. It is the same mechanism by which much of Europe [enrolls organ donors](https://www.organdonation.nhs.uk/about-organ-donation/faq/what-is-the-opt-out-system/).

So: a primer on what the thing does, a tour of who actually runs it in production, and an argument about why the unglamorous parts are the ones worth caring about.

## What it actually is

`gradle/actions` is not one action. It is a small family of them, and in practice, almost everyone means the same one when they invoke it.

The centerpiece is **`setup-gradle`**. Its job is to make Gradle run well inside a GitHub Actions runner, which is a fresh, ephemeral machine that has never heard of your project and will be deleted the moment your build finishes. Left to its own devices, that machine re-downloads your dependencies, re-initializes Gradle, and re-does a pile of work it has no memory of having done five minutes ago on the previous commit. `setup-gradle` is what gives the ephemeral machine a memory.

The `setup-gradle` action also runs **`wrapper-validation`**, which checks that nobody has smuggled a tampered Gradle Wrapper into your repository. It sounds paranoid until you understand the attack, at which point it sounds prudent (more on this below). And the optional **`dependency-submission`** reports your project’s real, fully-resolved dependency graph to GitHub so that Dependabot can warn you about vulnerable libraries. The dependency story is genuinely good, and we’ve [written about it at length elsewhere](https://blog.gradle.org/gradle-github-partnership-supply-chain-security); I am going to wave at it and move on because the more interesting thing about this toolkit is not the feature anyone demos. It is the feature nobody notices.

## The unglamorous hard problem

Here is the naive version of CI caching, which most engineers reinvent at least once: you reach for [`actions/cache`](https://github.com/actions/cache), the general-purpose caching action GitHub ships, you point it at the directory where Gradle keeps its stuff, you pick a cache key, and you call it a day.

This kind of works. It works the way a paper towel taped over a leak works. The trouble is that “the directory where Gradle keeps its stuff”, the Gradle User Home, is not a tidy bag of downloaded JARs. It is a living, accreting pile of state: resolved dependencies, but also compiled build-script classes, downloaded Gradle distributions, daemon metadata, and assorted caches-of-caches that Gradle maintains for its own purposes. Cache too much of it, and you spend more time uploading and downloading the cache than you saved. Cache too little, and you miss the speedup entirely. Pick the wrong cache key, and you either never get a hit or, worse, get a stale hit that downloads a bunch of useless content but your build is still slow so you spend a Thursday afternoon debugging.

The person who built this part of the action put it plainly. Getting the caching right, [Gradle’s Daz DeBoer wrote](https://blog.gradle.org/gh-actions), is “not trivial,” because “it’s easy to save too much state” or “underspecify the cache key,” and “the action takes care of the details for you.” This is the entire value proposition of a great deal of good infrastructure, stated without varnish: *this is fiddly and easy to get subtly wrong, so we got it wrong a hundred times in private, and you don’t have to.* You write six lines of YAML and inherit someone else’s hundred bad Thursdays.

The payoff is the boring kind. One independent tutorial author (a longtime Jenkins user, not a Gradle partisan) [measured his sample build](https://tomgregory.com/gradle/build-gradle-projects-with-github-actions/) running “around 15 seconds faster with the cache enabled,” and concluded that “running Gradle is a breeze and it even has caching for fast performance.” Fifteen seconds is not a number that makes anyone gasp. But it is fifteen seconds multiplied by every push, every pull request, every contributor, every day, for years, and that integral is where developer time actually goes (and, on a private repo, a chunk of your GitHub bill too).

## You can read this off other people’s files

One of the quietly strange things about open source is that the build configuration is *right there*. Every public repository’s `.github/workflows` directory is world-readable, which means you can walk up to the most respected projects in your ecosystem and simply read how they run their CI. A thing that would be wildly invasive in any other context and is completely normal here. It is the closest the software industry comes to letting you watch how other people actually keep house.

So I went and read a lot of them. The pattern is hard to miss.

The frameworks you’d expect to have strong opinions use it. [**Spring Boot**](https://github.com/spring-projects/spring-boot) —the default way a very large fraction of the planet writes Java services—drives its Gradle builds through `setup-gradle`, pinned to an exact commit hash rather than a floating version tag, wired up to publish Build Scans, and toggling its cache between read-only and read-write depending on the job. [**Micronaut**](https://github.com/micronaut-projects/micronaut-core), the serverless-flavored JVM framework, runs it across a matrix of JDKs and a GraalVM native-image build, with predictive test selection layered on top. [**Ktor**](https://github.com/ktorio/ktor), JetBrains’ asynchronous Kotlin web framework, runs the `wrapper-validation` action as its own tidy, least-privilege workflow.

The libraries you’ve definitely shipped to production use it. [**OkHttp**](https://github.com/square/okhttp) and [**Retrofit**](https://github.com/square/retrofit) —which is to say, a meaningful share of every HTTP request ever made from an Android phone—run `setup-gradle` across genuinely intimidating build matrices: multiple JDK versions, several TLS providers, a span of Android API levels, Windows, GraalVM, the works. OkHttp also runs `wrapper-validation` as a dedicated gate. [**Moshi**](https://github.com/square/moshi) (JSON), [**Mockito**](https://github.com/mockito/mockito) (the mocking framework in approximately every Java test suite you’ve ever seen), and [**JUnit 5**](https://github.com/junit-team/junit5) (the thing actually *running* those tests) are all on it. JUnit 5 even encrypts its build cache and relies on predictive test selection; Mockito runs `wrapper-validation` in a dedicated job whose sole purpose is to refuse to proceed if the wrapper looks wrong.

And the developer tool makers use it, which is the tell that a piece of infrastructure has earned the respect of people who are professionally suspicious of infrastructure. [**detekt**](https://github.com/detekt/detekt), the Kotlin static-analysis tool, has the most security-conscious setup I saw: every action pinned to a commit hash, an encrypted cache key on every step. [**Koin**](https://github.com/InsertKoinIO/koin), the dependency-injection framework, bundles both `wrapper-validation` and `setup-gradle` into a single reusable composite step. [**OpenTelemetry’s Java SDK**](https://github.com/open-telemetry/opentelemetry-java) —the instrumentation a large chunk of the observability industry is built on—runs it across a JDK matrix that stretches from 8 to 26.

If you want a single observation to take away from an afternoon of reading other people’s CI files, it is this: these teams disagree about almost everything (build matrices, JDK ranges, how aggressively to cache, whether to pin to a hash or a tag), and they have quietly converged on `setup-gradle` as the place to express those disagreements. That convergence is not loyalty. Engineers of this caliber do not feel loyalty toward build tooling. It is the absence of a reason to do anything else, which is a much more durable thing.

## The small luxury

There is a feature in `setup-gradle` that costs nothing, that you will not notice you wanted until you have it, and that you will resent its absence forever after. When your build publishes a [Build Scan](https://gradle.com/develocity/product/build-scan/) ®, the action surfaces the link to it directly in the GitHub UI and the job summary, instead of leaving it buried somewhere in sixteen thousand lines of log output.

![GitHub Action](https://blog.gradle.org/images/2026/running-gradle-github-actions/image1.png "GitHub Action")

This is a small thing. It is the software equivalent of a hotel that puts the light switch where your hand expects it.

![Build Scan](https://blog.gradle.org/images/2026/running-gradle-github-actions/image2.png "Build Scan")

## Using it

The reason adoption-by-default works is that the default is genuinely small. A complete, sensible Gradle CI job is about this long:

```yaml
name: Build
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew build
```

That is the entire thing. Caching across runs, the Build Scan link surfaced in your job summary, wrapper validation on every push; all of it is on by that single `setup-gradle` line. There is a great deal more you *can* configure, and the projects in the tour above configure plenty of it, but the distance from zero to a build that is faster and safer than the one most people write by hand is six lines and about ninety seconds.

## The pitch, plainly

The pitch is unglamorous on purpose: add one line, get faster builds and a wrapper you can trust, and go spend the reclaimed attention on the part of your software that’s actually yours to worry about. That’s the whole offer. It’s a good one.