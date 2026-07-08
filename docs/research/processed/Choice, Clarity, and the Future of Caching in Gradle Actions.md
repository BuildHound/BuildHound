---
title: "Choice, Clarity, and the Future of Caching in Gradle Actions"
source: "https://blog.gradle.org/choice-clarity-future-caching-gradle-actions"
author:
  - "[[Daz DeBoer]]"
published: 2026-04-03
created: 2026-07-07
description: "Since the release of gradle/actions v6, we’ve been listening closely to the feedback from the community. It’s clear that while we were focused on building the next generation of caching for Gradle, we missed the mark on how we communicated the changes to licensing that came wi..."
tags:
  - "clippings"
---
Version 6.1.0 of gradle/actions puts transparency and user choice at the forefront, with a Safe Harbor clause, a new open-source Basic Caching plan.

## Introduction

Since the [release of `gradle/actions` v6](https://blog.gradle.org/github-actions-for-gradle-v6), we’ve been listening closely to the [feedback from](https://blog.gradle.org/github-actions-for-gradle-v6) [the community](https://github.com/gradle/actions/issues/917). It’s clear that while we were focused on building the next generation of caching for Gradle, we missed the mark on how we communicated the changes to licensing that came with the release.

As the maintainer of this project, my goal is to ensure this action remains a tool the community trusts. Today, we are releasing [version 6.1.0](https://github.com/gradle/actions/releases/tag/v6.1.0), which puts transparency and user choice at the forefront.

### 1\. Data Ownership: The “Safe Harbor” Clause

The most significant concern we heard was that our new [license terms](https://gradle.com/legal/gradle-technologies-terms-of-use/) were overly broad, leading some to fear that Gradle might claim ownership of the cached data processed by the action.

**Let me be clear: Your code and artifacts belong to you. Period.** To formalize this, we have updated the [Gradle Technologies Terms of Use](https://gradle.com/legal/gradle-technologies-terms-of-use/) to include a [**Safe Harbor clause**](https://gradle.com/legal/gradle-technologies-terms-of-use/#:~:text=Exclusions%20from%20User%20Submissions) specifically for cached content. This clause explicitly confirms that Gradle claims no ownership of the content of your cache entries. We may collect metadata (such as cache keys) and usage metrics to facilitate and improve the caching service, but the actual cached data will not be inspected or retained.

For more details on licensing of `gradle/actions` and `gradle-actions-caching`, please see our new [Distribution & Licensing Guide](https://github.com/gradle/actions/blob/main/DISTRIBUTION.md).

### 2\. Restoring Choice: The “Basic” Caching Provider

In v6.0, we introduced *Enhanced Caching* via the proprietary `gradle-actions-caching` component as the only caching option with `setup-gradle`. We understand that for some, having a 100% open-source path is a requirement, not a preference.

In v6.1, we are introducing a *Basic Caching* provider. This is a 100% open-source thin wrapper over `actions/cache`. It provides reliable, path-based caching backed by GitHub.

**You now have a choice.** If you prefer to stay entirely within the MIT-licensed ecosystem, you can opt out of the proprietary library with one line of configuration:

```yaml
- uses: gradle/actions/setup-gradle@v6
  with:
    cache-provider: basic
```

### 3\. Why the Move to Proprietary Caching?

You might wonder why we are moving to a proprietary model if the current implementation is similar to the open-source caching logic in v5.

The answer is our roadmap. Advanced features like *Gradle Configuration Cache* support and *improved Gradle User Home cleanup* will rely on proprietary logic from the Develocity Artifact Cache. Moving the caching core logic into a closed-source library lays the foundation for those improvements.

*Enhanced Caching* is enabled by default because it is where our future innovation will live, but *Basic Caching* will remain a first-class, free, and open-source citizen of this repository.

### 4\. Transparency on Monetization

We want to be upfront about how we plan to sustain these tools:

- **Public Repositories:** Both *Basic Caching* and *Enhanced Caching* are **free forever**.
- **Private Repositories:** *Basic Caching* is **free forever**. *Enhanced Caching* is currently in a **Free Preview**.

While we haven’t finalized specific usage thresholds yet, our intent is to keep Enhanced Caching free for individual developers and small teams. We are currently in a learning phase to ensure that future restrictions are focused on large-scale commercial users of the product. No matter how these limits evolve, the open-source Basic Caching provider will always remain a free, functional alternative for everyone.

---

Thank you for your candid feedback. It has helped us find a better path forward—one that respects both the community’s values and our vision for the future of Gradle on CI.