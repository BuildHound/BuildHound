---
title: "Measure, don’t guess: Making observability the key to performance tuning your software delivery"
source: "https://develocity.ai/blog/observability-is-the-key-to-software-delivery-performance/"
author:
  - "[[Trisha Gee]]"
published: 2025-11-06
created: 2026-07-07
description: "If you’re serious about performance, productivity, and compliance in your large software organization, you need to start by measuring what is actually there."
tags:
  - "clippings"
---
This blog post is inspired by my DPE Summit 2025 presentation: [*Measure, Don’t Guess: Observability as the Key to Performance Tuning Software Delivery*](https://dpe.org/sessions/trisha-gee/measure-dont-guess-observability-as-the-key-to-performance-tuning-software-delivery/).

![](https://www.youtube.com/watch?v=6009QYXU710)

If you’re senior engineering staff or leadership in a large software organization, you know the drill. We are constantly seeking ways to make our engineering teams faster, more efficient, and generally less miserable. But how often do we actually stop and measure before we start tuning?

I’ve asked engineers this question before: Have you ever tried to performance-tune an application without benchmarking or profiling first? We all nod—we’ve done it. But the real question is, have you ever done it *successfully*?

The risk of optimizing without data is severe. It’s an old quote, but it still rings true today:

*“More computing sins are committed in the name of efficiency without necessarily achieving it than for any other single reason, including blind stupidity.”* (A Case Against GoTo, William Wulf, 1972)

My old boss, [Martin Thompson](https://www.linkedin.com/in/martinjthompson/) (creator of the high-throughput, low-latency technology [Aeron](https://aeron.io/)), used to tell me, "Measure, don't guess". **And that principle—taking measurements first to establish a base state before attempting to improve performance—applies not just to optimizing application performance in production, but to optimizing the entire developer experience.**

Let's apply that powerful performance engineering mindset to our internal processes and our path to production.

## The pain of the path to production

Before we fix anything, we need to acknowledge the pain points frustrating our development teams. These aren't just minor irritations; they’re major productivity killers that overload our continuous delivery pipelines.

The pain often manifests in the following ways (and we know this firsthand, because we see it at Gradle):

- **Waiting for builds:** We spend too much time waiting for builds to pass. Or worse, to fail. “Fail fast” is key to high performance.
- **Troubleshooting failures:** We waste developer time by manually digging through logs to pinpoint the root cause of a failure.
- **Flaky tests:** This is one of my pet peeves. Flaky tests drive me insane.
- **Security debt:** We leave security until late in the process, hoping someone else will flag what needs to be fixed, by which time it’s time consuming, difficult, and expensive to fix.

When faced with slow delivery cycles, long queues, and frustrated engineers, leadership often turns to the "quick fix." So let’s talk about what happens next.

### The myth of throwing hardware at the problem

We’ve all been there: the CI pipeline is clogged, builds are slow, and the instantaneous solution is simply to throw more hardware at the problem.

![Blog image](https://develocity.ai/_next/image/?url=%2Fassets%2Fimages%2Fblog%2Fobservability-is-the-key-to-software-delivery-performance%2Fdd7d80c7-c45f-4568-b3d4-8678b17c0d1d.jpg&w=3840&q=95)

Blog image

Jeff Atwood, co-founder of Stack Overflow, once argued:

*“…when does it make sense to throw hardware at a programming problem? As a general rule, I'd say almost always.”* (Jeff Atwood, [Hardware is Cheap, Programmers are Expensive](https://blog.codinghorror.com/hardware-is-cheap-programmers-are-expensive/), 2008)

He may have had a valid point in the pre-cloud era—buying a server was cheaper than wasting developer time. But in the age of hyperscale cloud infrastructure, throwing hardware at the problem is almost too easy. We have to ask: did it solve the problem? Was it cost-effective? And most critically, **how can you tell?**

The data often shows that resource constraints aren't the real issue. [Hans Dockter’s keynote at last year’s DPE Summit](https://youtu.be/qbcyjnO5910?si=OPCsDccFc33XXY9s&t=655) revealed a staggering truth: **90% of CPUs in CI are unused, but we still have queuing issues**. If 90% of your resources are sitting idle, your bottleneck isn't hardware! It's efficiency or process, and you need visibility to be able to determine which

We’re living in an era where software complexity has outpaced our understanding. As [Charity Majors](https://www.linkedin.com/in/charity-majors/), CTO and founder of observability platform [Honeycomb.io](http://honeycomb.io/), noted:

*“We are way behind where we ought to be as an industry. We are shipping code we don't understand, to systems we have never understood.”* (Charity Majors, [Observability is a Many-Splendored Definition](https://charity.wtf/2020/03/03/observability-is-a-many-splendored-thing/), 2020)

**This lack of understanding is precisely why we need to stop relying on guesswork and instead adopt observability across the entire continuous delivery pipeline.**

## Moving beyond monitoring with observability

We generally monitor performance in production—that’s where we focus our metrics, looking at application performance and user impact. But monitoring production only covers a small portion of the deployment loop, and our modern CD pipelines are enormously complicated matrices running on many different agents.

**We need to observe the path to production as well in order to improve productivity. Any time code is sitting around, waiting to be delivered, it’s simply [waste](https://www.6sigma.us/lean-six-sigma-articles/lean-the-8-wastes/) in [Lean](https://en.wikipedia.org/wiki/Lean_software_development) terms.**

This is where the distinction between monitoring and observability becomes vital. As Charity Majors states in her [Observability Manifesto](https://www.honeycomb.io/blog/observability-a-manifesto):

- **Monitoring is about known unknowns.** We already know there’s an issue, or potential for an issue, so we’re going to check in on that particular thing by setting actionable alerts.
- **Observability is about unknown unknowns**. We don’t know where the issues are because we’ve never been able to look deeply enough to find them. This empowers you to ask brand new questions and then explore wherever the data takes you.

With true observability, you don't need to know the questions ahead of time—instead, you can use the data you’ve gathered to explore and discover hidden inefficiencies.

## The scientific approach to developer experience

The solution to our pipeline pains is straightforward: **If you cannot measure it, you cannot improve it**. We need to use the scientific method to improve developer experience, recognizing that the ability to experiment is critical for innovation and business value.

![Blog image](https://develocity.ai/_next/image/?url=%2Fassets%2Fimages%2Fblog%2Fobservability-is-the-key-to-software-delivery-performance%2Fd986e912-7ffc-4c90-aa1a-0da847fa350d.jpg&w=3840&q=95)

Blog image

The scientific approach for performance tuning your pipeline is simple:

1. **Measure:** Establish your base state.
2. **Form a hypothesis:** Based on your measurements, hypothesize the bottleneck.
3. **Implement a change:** Crucially, implement *one change*. If you change five things at once, you won't know which one was actually effective.

### How do we measure?

Tools like [Develocity](https://develocity.ai/develocity/product/develocity-360/) offer the ability to track metrics across your continuous delivery pipeline. This includes tracking build counts, trends in build time, failure rates over time, and identifying those frustrating flaky tests. More importantly, you gain visibility across multiple projects, helping technical management spot issues like unused or rogue dependencies in CI projects you didn't even realize existed.

We’re also moving into an era where [Agentic AI can query observability data](https://develocity.ai/develocity/product/develocity-360/#agentic-ai), turning complex build failure data into actionable explanations—an incredible boost to troubleshooting speed.

## Seeing what the data reveals

Once you begin measuring, you start seeing startling things that immediately contradict some of your long-held assumptions:

### 1\. The build is "just big"

When large, complicated enterprise applications have slow builds, the assumption is often: "It’s just big. That's why it takes a long time".

But when you measure at a granular level, you often find that dependency resolution is actually dominating your build time.

![Blog image](https://develocity.ai/_next/image/?url=%2Fassets%2Fimages%2Fblog%2Fobservability-is-the-key-to-software-delivery-performance%2Fc69f2195-de2c-46e5-b575-9926261fcd4c.jpg&w=3840&q=95)

Blog image

Data shows that **30% to 40% of all CI build time at large organizations is spent downloading dependencies**. This is a massive chunk of time dedicated to a task that should be relatively easy to speed up by implementing effective caching.

To fix the problem, you first have to actually understand the nature of the problem itself. Better observability is what delivers that understanding. Otherwise, you’re writing it off as “just big”, or you’re wasting efforts trying to solve the wrong problem.

### 2\. Flaky tests are "random"

Another frustrating assumption is that flaky tests are simply random failures—an unavoidable, unpredictable consequence of a complex system. So we just shrug and rerun the build, right?

No! Observability proves otherwise:

- Measurement can reveal that **a small number of tests are responsible for most flakiness**, giving you a focused target for repair.
- Failures might align not with randomness, but with **increased load** or specific, faulty infrastructure.

Flaky tests are hard to fix precisely because you have to find the underlying problem. Unfortunately, the human consequence of chronic flakiness is far worse than the wasted cycles. **A study on developer experience found that developers who experience flaky tests more often are more likely to take no action in response to them.**

*“…our analysis revealed that rerunning the failing build and attempting to repair the flaky tests were the most common actions. Our findings also suggested that developers who experience flaky tests more often are more likely to take no action in response to them.”* ([Surveying the Developer Experience of Flaky Tests](https://ieeexplore.ieee.org/document/9793965), Owain Parry; Gregory M. Kapfhammer; Michael Hilton; Phil McMinn, 2022)

When developers lose trust in the tests themselves, you're then investing significant effort in running tests that are ultimately ignored, so what’s the point?

### 3\. Security is "fine"

![Blog image](https://develocity.ai/_next/image/?url=%2Fassets%2Fimages%2Fblog%2Fobservability-is-the-key-to-software-delivery-performance%2F362cdab8-b632-4374-a246-2533f5a04537.jpg&w=3840&q=95)

Blog image

Finally, we often assume our pipeline is secure until disaster strikes. Without proper visibility, you won't know if an unexpected dependency is sneaking into a project that you didn't even realize was being built in CI.

The critical truth is that **without observability, you can't know your pipeline is secure**. Observability allows you to verify that dependencies are what you expect, providing the kind of pervasive security required today.

## Observability is the foundation for DORA metrics

Ultimately, all this measurement relates directly to business outcomes that engineering teams care deeply about—specifically, DORA metrics.

Observability of your continuous delivery pipeline can help you improve all [four key DORA indicators](https://dora.dev/guides/dora-metrics-four-keys/):

- Shorter builds lead to faster **Lead Time**.
- Reliable pipelines lead to higher **Deployment Frequency**.
- Catching and fixing flakiness and failures lowers your **Change Failure Rate**.
- Faster troubleshooting lowers your **Mean Time to Recover (MTTR)**.
- Complete visibility into your builds, tests, and dependencies delivers the [**Pervasive Security**](https://dora.dev/capabilities/pervasive-security/) and audit readiness we need.

On the Develocity team here at Gradle, we used to talk about acceleration and troubleshooting as pillars of improvement, but we now understand that **observability is the essential foundation** for everything.

![Blog image](https://develocity.ai/_next/image/?url=%2Fassets%2Fimages%2Fblog%2Fobservability-is-the-key-to-software-delivery-performance%2Ff4d4802a-d3df-496e-acc3-194ae8ad7042.png&w=3840&q=95)

Blog image

## Start measuring today

If you’re serious about performance, productivity, and compliance in your large software organization, you need to start by measuring what is actually there. So if you’re not already doing it, my key takeaway is this: **Start measuring things that matter to your path to production today**.

Stop guessing and start measuring. It all starts with observability.

Learn more about our observability platform: [Develocity 360](https://develocity.ai/develocity/product/develocity-360/).

[Learn More](https://develocity.ai/develocity/product/develocity-360/)


full transcript of the youtube video
00:00:01 So my talk is titled, Measure, Don't Guess. Observability is the key to  
00:00:04 performance tuning software delivery. I have a question for you. Have you  
00:00:09 ever done performance tuning on an application without benchmarking and  
00:00:14 profiling? Anyone? Come on, we've all done performance tuning without benchmarking  
00:00:21 and profiling. My real question is, have you ever successfully done performance  
00:00:25 tuning without doing the benchmarking and profiling first? I found a quote  
00:00:30 from the famous Case Against GoTo from back in 1972, and the quote  
00:00:35 is, more computing sins are committed in the name of efficiency without  
00:00:39 necessarily achieving it than for any other single reason, including blind  
00:00:44 stupidity. Now, I know this quote is getting on a little bit,  
00:00:47 but I would still say it's fairly accurate, even these days.  
00:00:52 I used to work for Financial Exchange in London. My boss was Martin  
00:00:56 Thompson, and he always used to say to us, measure, don't guess.  
00:01:00 If you're going to try and tune the performance of your application,  
00:01:03 you have to first take measurements to figure  
00:01:06 out what your base state is and how to improve the performance.  
00:01:11 Now, he's not the only one who says this. I  
00:01:14 stole, sorry, I mean borrowed with consent this picture from Holly Cummins,  
00:01:18 who works at IBM. She gives a whole bunch of presentations about performance  
00:01:22 too, and she always says, measure, don't guess. And she says she stole  
00:01:25 it from Kirk Pepperdine, who's another performance tuning expert.  
00:01:29 So what if we applied the same performance engineering mindset from optimizing  
00:01:34 application performance to optimizing the developer experience? Firstly,  
00:01:40 let's take a look at the pain of the developer  
00:01:44 experience. From a Gradle point of view, I work for Gradle.  
00:01:46 From a Gradle point of view, we're often talking about the pain of  
00:01:49 waiting for builds to pass or not pass. We're talking about the pain  
00:01:54 of troubleshooting any failures that might happen. We're talking about the  
00:01:58 pain of flaky tests. This is my particular favorite. Sorry, what's the opposite  
00:02:02 of favorite? This is my particular not favorite. Flaky tests drive me insane.  
00:02:09 If you want more information about flaky tests and you didn't go to  
00:02:12 Brian's talk in the other room, have a look at it on the  
00:02:14 YouTube because they did do extensive coverage of flaky tests.  
00:02:19 A lot of these pains tend to lead to an overloaded continuous delivery  
00:02:23 pipeline. I actually got ChatGPT to do this and it's not dreadful.  
00:02:27 So you're going to overload your continuous delivery pipeline if you've  
00:02:30 got long Builds, if you can't troubleshoot your build, if you've got flakiness  
00:02:33 in your tests, it's going to cause you trouble. And on top of  
00:02:37 that, there's always security. As a developer, I'm aware that I need to  
00:02:41 care about security, but it's always something that kind of comes a little  
00:02:45 bit later on and in the hope that someone else can tell me  
00:02:47 what I need to do for security. There is a quick fix to  
00:02:50 a lot of these problems and I'm sure we've all done this too.  
00:02:54 The quick fix is to throw hardware at the problem.  
00:02:57 So I wanted to find some statistics or some useful information about how  
00:03:01 often we should throw hardware at the problem. I found a quote from  
00:03:05 2008 from Jeff Atwood, co-founder of Stack Overflow. He said, when does  
00:03:10 it make sense to throw hardware at a programming problem? As a general  
00:03:14 rule, I'd say almost always. So he was actually talking about in the  
00:03:18 olden days, before cloud, buying a server, plugging it into a rack,  
00:03:24 and that would cost significantly less money than developer time wasted.  
00:03:29 So his argument is it's always better to use hardware to speed up  
00:03:33 a performance problem. Probably you might even argue in the age of cloud,  
00:03:37 this is even more appropriate than before because it's so much easier.  
00:03:41 But the question then is, did it fix the problem?  
00:03:46 Was it cost effective? And most importantly, how can you tell?  
00:03:52 In Hans' keynote at DPE Summit last year, he had this slide.  
00:03:57 90% of CPUs in CI are unused, but still have queuing issues.  
00:04:03 This is clearly not a hardware problem if you have 90%  
00:04:06 of your CPUs sat around doing absolutely nothing.  
00:04:09 So we need to be able to find out where the problem really  
00:04:13 is. Found a quote from Charity Majors in the more recent past.  
00:04:16 She knows a bit about observability because she runs a company that does  
00:04:19 observability in production. We're way behind where we ought to be as an  
00:04:23 industry. We're shipping code we don't understand to systems we have never  
00:04:28 understood. This resonates quite strongly with me. I've worked on a bunch  
00:04:32 of systems like this. And I think in the age of Gen AI,  
00:04:35 when we're not even writing the code ourselves, this might become even more  
00:04:39 important to consider. Fortunately, when we look at the continuous delivery/DevOps  
00:04:44 loop, we do have monitoring as part of this. It is important to  
00:04:49 be able to see what we're doing and how we're doing it.  
00:04:52 But often, when we look at the continuous deployment pipeline, when we talk  
00:04:58 about monitoring, we're talking about monitoring performance in production.  
00:05:03 That's what we care about. What are our apps doing in  
00:05:06 production? How does that impact the users? And we don't often actually  
00:05:09 look at all of the rest of the deployment pipeline. And as I  
00:05:14 mentioned in the keynote this morning, it's not just a case of like  
00:05:17 a few boxes and a few lines. Our CD pipelines are enormously complicated,  
00:05:23 running on lots of different agents in some weird and wonderful  
00:05:27 matrix. And yet we have very little visibility over what's happening there.  
00:05:31 So what we're talking about here is we're talking about measuring the path  
00:05:33 to production in terms of developer productivity. The developers have worked  
00:05:37 on code. How quickly can you get it into production? Because otherwise,  
00:05:41 in lean terms, it's just waste. It's just sat around doing nothing.  
00:05:46 And more specifically, we're talking about observability, not monitoring.  
00:05:50 Now, until I did my research for this presentation, I actually didn't know  
00:05:54 what the difference was. And again, I have to go to  
00:05:56 Charity Majors, who said, monitoring is about known unknowns and actionable  
00:06:01 alerts. Observability is about unknown unknowns and empowering you to ask  
00:06:05 arbitrary new questions and explore where the cookie crumbs take you.  
00:06:10 This is a really important difference. With observability, you don't have  
00:06:14 to know the questions you're going to ask of your continuous delivery pipeline  
00:06:17 or your application or whatever. You can just use it to look around  
00:06:21 and see what the data tells you. So what is the solution to  
00:06:25 our pains in terms of deployment and developer experience?  
00:06:31 I've already mentioned it. If you cannot measure it, you cannot improve  
00:06:34 it. So my quotes are getting even older now. This is from Lord  
00:06:37 Kelvin in 1847. Actually, he didn't say that at all. He said something  
00:06:41 much more long-winded and more difficult to quote. But the internet turned  
00:06:45 it into this. So now this is clearly the truth.  
00:06:48 But the point is, when it comes to science, if you can't measure  
00:06:52 it, you can't improve it. So let's use  
00:06:56 science. Let's go ahead and use scientific method to improve developer experience.  
00:07:00 In fact, in Accelerate, they talk about the ability to experiment is a  
00:07:05 critical part of innovation and creating business value. We need to be able  
00:07:09 to use experimental approaches to improve the way that we work.  
00:07:13 And the scientific approach is as simple as, simple, measure,  
00:07:18 form a hypothesis, and implement a change. Implement one change, by the  
00:07:24 way, change one thing. Because I know what we all do.  
00:07:27 We get some data, we look at it and go, oh,  
00:07:29 I think I'm going to change this and this and this.  
00:07:31 And then you don't know which changes were effective. So how do you  
00:07:35 measure? That's what we're talking about, observability and measurement.  
00:07:38 Now, I work for Gradle, so I'm going to say use Dev-Velocity. Also,  
00:07:42 I hope our head of design isn't in here because if you're really  
00:07:44 upset about what I've done with the logo.  
00:07:47 Use Dev-Velocity. Dev-Velocity will tell you a bunch of information about  
00:07:50 your builds throughout your continuous deployment pipeline. For example,  
00:07:54 number of builds, trends in build time, that kind of thing.  
00:07:58 Be able to tell you how many failures you've had over time.  
00:08:01 It talked about flaky tests, identify your flaky  
00:08:04 tests. And you can do a lot of stuff across projects,  
00:08:06 which is really helpful for your technical management. You'll be able to  
00:08:10 see things like, are there some dodgy dependencies which are used in some  
00:08:15 projects that you didn't even know were being built in CI somewhere?  
00:08:18 This can expose a whole bunch of information to you you didn't even  
00:08:21 know that you needed. And of course, because it's 2025, we're also going  
00:08:27 to talk about using Gen AI and LLMs and be able to query  
00:08:31 the MCP server to find out information like, why did my build fail?  
00:08:37 And in fact, if you were in Laurent's talk, he talked a lot  
00:08:38 about this kind of thing. So science is a good thing to use.  
00:08:42 Your best and wisest refuge from all your troubles is in your science.  
00:08:46 And that was Ada Lovelace, who was the first programmer. So obviously,  
00:08:49 she was correct when she said this. So now you've done your monitoring  
00:08:54 observability measurement. What do you see? So let's talk a little bit about  
00:08:58 the sorts of things you might be able to see if you start  
00:09:00 looking in places like where your builds are happening and in your deployment  
00:09:05 pipeline. Sometimes we look at a great big build, particularly when it comes  
00:09:09 to enterprise applications, which are large, complicated, gnarly, and often  
00:09:16 a little bit older than we would like to see. And we just  
00:09:19 assume the build is going to take a long time because it's just  
00:09:22 a lot of code. So our assumption is it's just  
00:09:26 big. That's why it takes a long time.  
00:09:29 But in actual fact, when you measure it and when you look at  
00:09:32 the details, you'll find that dependency resolution, maybe, for example,  
00:09:37 is dominating your build time. This is also from  
00:09:42 Hans' keynote last year. We found that 30% to 40% of all CI  
00:09:45 build time at large organizations is spent downloading dependencies.  
00:09:49 And that is a lot of time to do a task which should  
00:09:53 be fairly easy to speed up. The solution is quite straightforward.  
00:09:57 Usually, it's usually add a cache. If you're interested in our caching technologies,  
00:10:01 Etienne and Mirko are going to be talking about caching tomorrow.  
00:10:06 I don't remember which time, but they'll be able to talk about it  
00:10:08 in more detail. Another thing that people might comment upon is that flaky  
00:10:12 tests are occurring. And the assumption is that they just randomly fail.  
00:10:17 We don't really, we can't predict why they fail. We just have flakiness  
00:10:20 in the system. But if you measure it, you might be able to  
00:10:24 identify one of many different sources of flakiness. This is kind of one  
00:10:28 of the problems with flaky tests is that it could be any number  
00:10:31 of things. You might be able to find things like it's just a  
00:10:34 small number of tests which are responsible for most flakiness. That gives  
00:10:38 you some focus. You can fix those tests and address those specific problems.  
00:10:43 It might be that the tests randomly fail, but the failures align with  
00:10:47 things like increased load or something which is outside of the code base.  
00:10:53 Or it might be you'll find that you get more flakiness on certain  
00:10:56 infrastructures. The thing is that you can't identify any of these problems  
00:11:01 if you're not looking for them. Flaky tests in particular are hard to  
00:11:06 fix because you have to find the problem.  
00:11:08 Most of the time you don't even know you have flakiness.  
00:11:10 You just go, well, I'll just rerun it and it's probably fine.  
00:11:13 There was a study done on the impact of flaky tests on developer  
00:11:18 experience, and it said, our analysis revealed that rerunning the failing  
00:11:21 build and attempting to repair the flaky tests were the most common actions.  
00:11:25 Okay, so far so good. What was more interesting is they found that  
00:11:30 our findings also suggested that developers who experience flaky tests more  
00:11:35 often are more likely to take no action in response to them.  
00:11:39 So as soon as you have any flakiness in your build system,  
00:11:43 you end up losing trust in that whole build system. You lose trust  
00:11:47 in your tests, in which case you're spending a lot of time writing  
00:11:51 running tests for less value because your developers are ignoring the results  
00:11:56 of those tests. And of course, we always have security, the security stuff.  
00:12:02 So our assumption is that our pipeline is  
00:12:05 secure, it's fine, until you actually measure and take a look and you  
00:12:07 find out you have some dependency you didn't know about sneaking into some  
00:12:11 project. You might not even know that that project was being built.  
00:12:15 You can't know these things without having some kind of observability there.  
00:12:18 Without observability, you can't know that your pipeline is secure. You  
00:12:23 need to be able to see that the dependencies are the ones you  
00:12:26 expect and you don't have some weird things happening.  
00:12:29 I also wanted to bring this back a little bit to Dora because  
00:12:31 Dora is our way to allow us to measure to see if we're  
00:12:35 actually effectively delivering the software that we say that we're delivering.  
00:12:39 So shorter builds will lead to faster lead time.  
00:12:43 Reliable pipelines will lead to higher deployment frequency.  
00:12:46 Catching your flaky tests will lower your change failure rate.  
00:12:51 Faster troubleshooting will lower your MTTR. And on top of that,  
00:12:57 the complete visibility into your build pipelines will give you the pervasive  
00:13:01 security that you need so that you're not running loads of security tests  
00:13:04 at the end and then finding out that actually you've got a big  
00:13:06 security hole or worse, you don't find that out. So observability is a  
00:13:11 foundation for performance, productivity, and compliance. You have to start  
00:13:16 by measuring what's there. And we used to talk about the three pillars  
00:13:21 of observability, acceleration, and troubleshooting, but now we sort of  
00:13:24 realize that you can't have any of the other things if you don't  
00:13:27 have observability first. So my take-homes are if you're not already doing  
00:13:31 it, and to be honest, a lot of people in this audience probably  
00:13:34 are already doing it, but if you're not already doing it,  
00:13:36 start measuring things that matter to you today, particularly when it comes  
00:13:41 to your path to production. Because without observability, you're not able  
00:13:45 to see the state of where you started,  
00:13:49 identify potential bottlenecks, and at the end, when you've made your improvements,  
00:13:54 show that you've made positive changes. You need to start with observability.  
00:13:59 Thank you very much.