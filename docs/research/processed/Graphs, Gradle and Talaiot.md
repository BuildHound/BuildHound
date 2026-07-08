---
title: "Graphs, Gradle and Talaiot"
source: "https://proandroiddev.com/graphs-gradle-and-talaiot-b0c02c50d2b1"
author:
  - "[[iñaki villar]]"
published: 2019-05-01
created: 2026-07-07
description: "Graphs, Gradle and Talaiot After covering the basics of Talaiot and then one of the default Publishers, the InfluxDbPublisher, now is the moment to go deeper in the new family of Publishers released …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-b0c02c50d2b1---------------------------------------)

Follow publication

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-b0c02c50d2b1---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*s8ThREg_e2gWi2_XgvpBfA.png)

After covering the [basics of Talaiot](https://proandroiddev.com/understanding-talaiot-5da62594b00c) and then one of the default Publishers, the [InfluxDbPublisher](https://proandroiddev.com/exploring-the-influxdbpublisher-in-talaiot-ae6c60a0b0ec), now is the moment to go deeper in the new family of Publishers released recently on Talaiot 0.2.0: the TaskDependencyGraphPublishers.

## Graphs?

Do you remember the Graph Theory? Maybe you remember some concepts from the university or if you have been preparing interviews for Google or Facebook. Dijkstra, DFS or BFS are some of the basic concepts. However, this is only the tip of the iceberg.

In mathematics, the Graph Theory is the study of graphs, which are mathematical structures used to model objects. In computer science, Graphs are used to represent networks of communication, data organization, computational devices, but it has other applications in other branches like Biology, Chemistry or Linguistics.

One graph can be represented as an ordered pair like `G=(V,E)` where V is the vertices (nodes) and E the edges(relationships). We have different types of graphs depending on different properties like the orientation of the edges, weights assigned to each edge or if the graph contains cycles.

Graphs are everywhere, from Garbage Collector/Memory Management to algorithms to rank web pages in a search engine(PageRank), but of course, we have examples in the Android/Kotlin world.

For example, in our loved UI testing framework Espresso, there is the `TreeIterables` class used as utility methods for iterating over tree-structured items. In this case, the tree structure will be the View Hierarchy of our UI views under test. The class includes DFS and BFS static traversal methods:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*MIqSBh2eKlK6GAl3Qtvuxg.png)

One usage found is in the `PositionAsserions` used for assert relative position of elements on the UI:

```c
Iterables.filter(breadthFirstViewTraversal(root),viewPredicate)
         .iterator()
```

One more advanced example is found in the last Kotlin Konf with the great talk “Kotlin/Native Concurrency Model” by Nikolay Igotti. In this session, Nikolay goes deeper into the concurrency problems and designing principles in the Kotlin Native platform. In one of the sections talking about the immutability, he exposes the problem of running complex reachability algorithms into complex graph objects relations. One way to solve it is by applying Graph Theory principles: any object graph can be condensed into a Directed Acyclic Graph with their stronger connected component avoiding to run the reachability algorithm for the entire graph:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*yDd5izaQ0m9oZ15ZwAbXug.png)

[https://www.youtube.com/watch?v=nw6YTfEyfO0&list=PLQ176FUIyIUbVvFMqDc2jhxS-t562uytr](https://www.youtube.com/watch?v=nw6YTfEyfO0&list=PLQ176FUIyIUbVvFMqDc2jhxS-t562uytr)

## Graphs in Gradle

Gradle is a build system based on tasks. A [Task](https://docs.gradle.org/current/dsl/org.gradle.api.Task.html) is a unit of work for a build, for example, some tasks are very familiar for us:`compileKotlin`, `test` or `assembleDebug`. A task may have dependencies on other tasks or might be scheduled to always run after another task. Gradle ensures that all task dependencies are executed after all of its dependencies. The composition of all the tasks and the dependencies are represented as a Directed Acyclic Graph([DAG](https://en.wikipedia.org/wiki/Directed_acyclic_graph)).

If we take for example the default [Java Plugin](https://docs.gradle.org/current/userguide/java_plugin.html) we can notice that the tasks included and their dependencies when executing the `build` task:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*o08xj8awr5J8KILFt2ASCw.png)

[https://docs.gradle.org/current/userguide/java\_plugin.html](https://docs.gradle.org/current/userguide/java_plugin.html)

The `jar` task only will be executed after the task `classes` has finished. This makes sense because we don’t want to package the classes generated by the compiler until the previous task is finished, on the same way we can notice that we need to execute first the tasks `compileJava` and `processResources` to execute the `classes` tasks

Maybe you are wondering how we could know which tasks are dependent on a given one. If we want to add one dependency to a given task we can use:`Task.dependsOn(java.lang.Object[])` but what about the tasks included in the different plugins?

For every task, in the evaluation phase, Gradle assembles the task execution graph for a build. This occurs after all the projects have been evaluated and before any task execution begins. This information will be accessible through `TaskDependency getTaskDependencies()` at the main Task interface.

Being the `TaskDepdency` type:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*0HOIoxQJfNKXPbI5rnvV3Q.png)

And is here, where Talaiot is collecting and providing the information of the dependencies on a given task. This data will be provided in the main aggregated task entity used later by the Publishers.

## TaskDependencyGraphPublisher

Is the new publisher released in the version 0.2.0. It includes three different outputs/publishers and additional configuration to include/avoid the execution depending on your requirements on CI/Local builds.

The basic configuration is:

<iframe src="https://proandroiddev.com/media/33079f6f28536c1bc95bd8d7ebfeee70" allowfullscreen="" frameborder="0" height="353" width="680" title="taskdependency"></iframe>

The default publishers included within the TaskDependencyGraphPublisher are HtmlPublisher, DotPublisher and GexfPublisher.

Once the build is finished, Talaiot will create the files defined through the publisher configuration in the `${project.rootDit}/$TALAIOT_OUTPUT_DIR`:

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*MZ4O4ERzU6iFCqONm-QbQw.png)

**HtmlPublisher**

The first Publisher included is the HtmlPublisher, it’s using the library [vis.js](http://visjs.org/) (thanks

[warat wongmaneekit](https://medium.com/u/1afe61aa115d?source=post_page---user_mention--b0c02c50d2b1---------------------------------------)

and

[Panjamapong Sermsawatsri](https://medium.com/u/4a9caa9e66d3?source=post_page---user_mention--b0c02c50d2b1---------------------------------------)

for the recommendation). It helps to display the task dependency graph:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*Jt27QQ_0nbQcbuUexOUr4w.png)

simple DAG for Kotlin module

The Publisher works well for a graph with low density, that means small modules or specific tasks of one module. In the case of high-density graphs the visualization becomes harder:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*5dltGElmypAOKdssQfsMAw.png)

However, thanks to the library you can zoom and group the different tasks. Results are categorized by module.

**DotPublisher**

DOT is the text file format of the suite [GraphViz](https://www.graphviz.org/). It has a fluent syntax that describes network data, including subgraphs and elements appearances:

```c
digraph graphname {
     assemble -> compileJava -> compileKotlin;
     compileJava -> classes;
 }
```

The usage nowadays is very extended, and multiples plugins and tools are using the format, check for example [this article](https://medium.com/@nvinayshetty/introducing-state-art-96e125b8bf5f) by

[Vinay Shetty](https://medium.com/u/f8c9fcbc196a?source=post_page---user_mention--b0c02c50d2b1---------------------------------------)

where he explains the plugin created to represent state machine diagram for the [State Machine Library](https://github.com/Tinder/StateMachine), a Kotlin DSL for finite state machine by Tinder.

If you prefer to check the different possibilities of the format, GraphViz offers a [web version](http://www.webgraphviz.com/) to start playing.

Let’s back to the Publisher, once we have the build finished, and our configuration of Talaiot using DotPublisher, a png will be generated with the Task Dependency Graph information:

![](https://miro.medium.com/v2/resize:fit:1360/format:webp/1*yAHvx6V2GWU6b2BR4hGbkQ.png)

core-domain assemble of https://github.com/cdsap/Kotlin-Client-Server

The publisher is using [graphviz-java](https://github.com/nidi3/graphviz-java) to create the graphviz models and convert them into nice graphics

**GexfPublisher**

## Get iñaki villar’s stories in your inbox

Join Medium for free to get updates from this writer.

GEXF format is a language for describing complex networks structures. It started in 2007 at the [Gephi](http://gephi.org/) project, an Open Graph Viz Platform. A simple graph would be:

```c
<?xml version="1.0" encoding="UTF-8"?>
<gexf xmlns="http://www.gexf.net/1.2draft" version="1.3">
    <meta>
        <creator>Talaiot</creator>
        <description>Graph Dependency</description>
    </meta>
    <graph mode="static" defaultedgetype="directed">
      <node id="0" label="compileKotlin"/>
      <node id="1" label="compileJava"/>
      <edge id="0" source="1" target="0" />
    </graph>
</gexf>
```

Where we define the main components of the graph `nodes` and `edges` as children of the node graph. Additionally, the format specification allows you to define more attributes for the graph. Talaiot is providing additional attributes, module and cached:

```c
<attributes class="node">
   <attribute id="0" title="module" type="string"/>
   <attribute id="1" title="cached" type="boolean">
         <default>false</default>
    </attribute>
</attributes>
<node id="4" label="preDebugBuild">
    <attvalues>
        <attvalue for="0" value=":core"/>
        <attvalue for="1" value="false"/>
    </attvalues>
</node>
```

To visualize the data, we will use [Gephi](http://gephi.org/):

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*tb2AZo9QNCec0uf1kFP_tg.png)

It’s a great tool to apply filters, layouts and different statistics with the data provided. You have three main components:

- Overview: You can visualize applying different layouts to the data provided. Additionally, you can apply filters
- Data Laboratory: You will have the information applied in the overview in a table format, where you can merge, export and filter the information
- Preview: Visualize and export the results of the current workspace.

## Use case: GexfPublisher with Plaid app

Now we are going to explore more options of the GexfPublisher and the integration with Gephi. In this example we are going to use the repository from Google Plaid:

## [android/plaid](https://github.com/android/plaid?source=post_page-----b0c02c50d2b1---------------------------------------)

### An Android app which provides design news & inspiration as well as being an example of implementing material design. …

github.com

Plaid was written as a showcase for material design in Android in a real application. Later Plaid was used to show the best practices related to common problems in the Android platform: architecture, testing and modularizing.

Once we have pulled the repository and configured Talaiot(using taskGraphDependencyPublisher with `gexf = true`) we need to execute the build `assembleDebug`. Once the build is finished, the.gexf file will be generated.

When we open the file in Gephi, a dialog will appear with the basic information of the graph provided:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*IkZ65u2QCr4l23tCVwgrxg.png)

We are loading a directed graph with 335 nodes and 1106 edged

As we mentioned before, we include the custom attributes module and cached task. With the help of Gephi we are going to use this data to group the graph and have better visualization:

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*RI81NyrTwLtSK8m5r4Yhbg.png)

Applying the attribute to the graph:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*r_bxu2siOf7P---1WnEfbw.png)

Plaid’s tasks dependency graph with Force Atlas2 distribution

We can notice a normal distribution with some tasks contiguous to the different modules. The test module(`:test-shared`) is more independent than the other modules.

Next example will be using combining two filters but in this case only the tasks in the `:app` module and those tasks that are cached:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*FyZ4KJhoagHOAaqVRaj4bQ.png)

Gephi also includes the possibility to apply different metrics to the graph, these metrics analyze the network, nodes and edged with different algorithms, let’s check some of them:

**Degree Centrality**

Measures the number of relationships of one node, this relationships can be either incoming or outcoming. The Degree Centrality algorithm can help us finding popular nodes in a graph. For the `assembleDebug` in the Plaid project we have this overall distribution:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*TsTjKBb5KjfmTNArSnp99Q.png)

In the Data Laboratory section, we can explore the data with the results of the algorithms applied in the graph:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*KGyCeVfekFworBKsk_q8OQ.png)

Weighted Degree

We notice the task `generateDebugFeatureTransitiveDep` of the `:app` module contains the highest value(50/2). If we decompose the total degree in in-degree and out-degree the results are:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*BblweiIE2mEg_8_Mjz0JQw.png)

Weighted Incoming Degree. Similar results to the total degree, with a predominance of pre-building configuration tasks

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*Yv84yrjeRbN39KguApAQpQ.png)

Weighted outcoming Degree. Compilation and packaging tasks are nodes with more influence

At the same time, we can apply a filter in the visualization of the graph, for example here a Weighted Degree with values higher than 27:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*5PEJT_UfFRITchOU7DeLzQ.png)

**Betweenness Centrality**

The Betweennessalgorithm is a way of detecting the amount of influence a node has over the flow of information in a graph. First calculates the shortest path between every pair of nodes. Each node receives a score, based on the number of these shortest paths that pass through the node. Nodes that most frequently lie on these shortest paths will have a higher betweenness centrality score.

In the Plaid project we have this distribution:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*QR0jyXgijroHTHj-uPNalw.png)

Also, the information on the Data Laboratory:

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*bwRDr_b4ou2mycY3DfhM2g.png)

The information shows a strong influence of the tasks placed on `:core` and `:app` modules. Those tasks showed before a high value on incoming degree community results.

Every time we are applying different algorithms to the graph, more data is available in the Data Laboratory. Here is the result of `:core:assembleDebug` that implies the execution of `:core` and `:bypass` modules:

<iframe src="https://proandroiddev.com/media/f7f468b6d0390f16dc24b81e00de68a7" allowfullscreen="" frameborder="0" height="1422" width="680" title="test"></iframe>

check the complete build output [here](https://gist.github.com/cdsap/d6a66432d4c6a2ef7089dcbc4ae3b086)

Thanks to Gephi we can go deeper in the graph analysis of our builds. The BFS/DFS/Shortest path theory is only the beginning of the real Graph Theory. If you want to explore more about Graph Algorithms, check this recently published book.

![](https://miro.medium.com/v2/resize:fit:1100/format:webp/1*vpDxqzE8ayPMqLebZK12eg.jpeg)

[https://neo4j.com/graph-algorithms-book/](https://neo4j.com/graph-algorithms-book/)

## Final words

Graph Theory is thrilling! In our case, we could use the knowledge of Graph Theory to understand better the Gradle builds. For example, understanding which tasks support more significant responsibility or maybe determine on which circumstances the number of CPUs is going to have a real impact in the parallelizing of the execution of tasks.

With the new release of Talaiot (0.2.0) you can generate your dot/html/gexf files with the task dependency graph of the build system. We want to explore more advanced integrations in the future with Neo4J or Spark and exploring different techniques of visualization for the HtmlPlublisher like clustering by module for example.

Thanks for reading and remember Talaiot is open-source and you can contribute and help with the development:

## [cdsap/Talaiot](https://github.com/cdsap/Talaiot?source=post_page-----b0c02c50d2b1---------------------------------------)

### Simple and extensible plugin to track task times in your Gradle Project. - cdsap/Talaiot

github.com

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:96:96/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_info--b0c02c50d2b1---------------------------------------)The latest posts from Android Professionals and Google Developer Experts.

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.