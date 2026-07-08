---
title: "Gradle Properties, BuildConfig, and Secrets Management (The Right Way)"
source: "https://proandroiddev.com/gradle-properties-buildconfig-and-secrets-management-the-right-way-8b1b161aaefd"
author:
  - "[[Prashant verma]]"
published: 2025-12-19
created: 2026-07-07
description: "Gradle Properties, BuildConfig, and Secrets Management (The Right Way) If your API keys live in your repo or APK, they’re already public. Almost every Android app needs configuration: API base …"
tags:
  - "clippings"
---
## [ProAndroidDev](https://proandroiddev.com/?source=post_page---publication_nav-c72404660798-8b1b161aaefd---------------------------------------)

Follow publication

1. [🧠 The 3 Configuration Layers (Mental Model)](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#fbf1 "🧠 The 3 Configuration Layers (Mental Model)")
2. [⚙️ 1. Gradle Properties — Build-Time Configuration](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#59d5 "⚙️ 1. Gradle Properties — Build-Time Configuration")
3. [Example: gradle.properties](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#e5b9 "Example: gradle.properties")
4. [⚠️ Important Reality Check](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#5261 "⚠️ Important Reality Check")
	1. [🧬 Variant-Specific BuildConfig (Best Practice)](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#1b22 "🧬 Variant-Specific BuildConfig (Best Practice)")
		2. [🔐 3. Secrets Management — What Is Actually Safe?](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#6c5a "🔐 3. Secrets Management — What Is Actually Safe?")
		3. [❌ What NOT to Do](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#1c3c "❌ What NOT to Do")
		4. [✅ What Production Apps Actually Do](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#08d1 "✅ What Production Apps Actually Do")
		5. [🥇 Option 1: Backend Token Exchange (Best)](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#550b "🥇 Option 1: Backend Token Exchange (Best)")
		6. [🥈 Option 2: Remote Config / Feature Flags](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#bb0e "🥈 Option 2: Remote Config / Feature Flags")
		7. [🥉 Option 3: CI-Injected Values (Use Carefully)](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#84af "🥉 Option 3: CI-Injected Values (Use Carefully)")
		8. [🧪 A Safe, Real-World Pattern](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#b68b "🧪 A Safe, Real-World Pattern")
		9. [🏢 How Real Companies Do This](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#8859 "🏢 How Real Companies Do This")
		10. [🧭 TL;DR](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#0025 "🧭 TL;DR")
		11. [🔮 Coming Next](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#7bc5 "🔮 Coming Next")
5. [💬 Final Word](https://proandroiddev.com/?source=post_page-----8b1b161aaefd---------------------------------------#d0ec "💬 Final Word")

[![ProAndroidDev](https://miro.medium.com/v2/resize:fill:76:76/1*qc2BOr4xE7A0QV8N5w4fow.png)](https://proandroiddev.com/?source=post_page---post_publication_sidebar-c72404660798-8b1b161aaefd---------------------------------------)

The latest posts from Android Professionals and Google Developer Experts.

Follow publication

> If your API keys live in your repo or APK, they’re already public.

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*VVVcoPz7JmpflpD_6PeyHA.png)

Almost every Android app needs configuration:

- API base URLs
- Feature flags
- Timeouts
- API keys or tokens

The real challenge isn’t *adding* these values — it’s **adding them safely and correctly**.

In this article, we’ll clearly separate:

- **Build-time configuration**
- **Compile-time constants**
- **Runtime secrets**

…and show how real production apps handle each.

### 🧠 The 3 Configuration Layers (Mental Model)

![](https://miro.medium.com/v2/resize:fit:1400/format:webp/1*oqAMoDKQC1vOC_Kf2hKMQg.png)

👉 Problems happen when these layers are mixed.

### ⚙️ 1. Gradle Properties — Build-Time Configuration

Gradle properties are meant for **build behavior**, not app logic.

## Get Prashant verma’s stories in your inbox

Join Medium for free to get updates from this writer.

They live in:

- `gradle.properties`
- `local.properties`
- Environment variables (CI)

## Example: gradle.properties

```c
ENABLE_LOGGING=true
API_TIMEOUT=30
```

Access in Gradle:

```c
val loggingEnabled =
    providers.gradleProperty("ENABLE_LOGGING").get().toBoolean()
```

📌 **Key point**  
Gradle properties:

- Affect how the app is built
- Are NOT directly accessible in Kotlin/Java code
- Are evaluated during **configuration phase**

### 🧪 local.properties — Local & Dev-Only

```c
MAPS_API_KEY=debug_key_123
```

Why this file exists:

- Ignored by Git
- Different per developer
- Safe for **local testing only**

Access:

```c
val mapsKey =
    providers.gradleProperty("MAPS_API_KEY").orNull
```

⚠️ Still not secure for production secrets.

### 🧩 2. BuildConfig — Compile-Time Constants

`BuildConfig` exposes Gradle values to **application code**.

```c
android {
    defaultConfig {
        buildConfigField(
            "String",
            "BASE_URL",
            "\"https://api.example.com\""
        )
    }
}
```

Usage:

```c
val baseUrl = BuildConfig.BASE_URL
```

## ⚠️ Important Reality Check

Anything in `BuildConfig`:

- Is baked into the APK
- Can be extracted using APK tools
- Is **not secure**

> *Obfuscation does not equal security.*

### 🧬 Variant-Specific BuildConfig (Best Practice)

```c
productFlavors {
    dev {
        buildConfigField(
            "String",
            "BASE_URL",
            "\"https://dev.api.example.com\""
        )
    }
    prod {
        buildConfigField(
            "String",
            "BASE_URL",
            "\"https://api.example.com\""
        )
    }
}
```

Benefits:

- Zero runtime branching
- Clear environment separation
- Works perfectly with CI

### 🔐 3. Secrets Management — What Is Actually Safe?

### ❌ What NOT to Do

```c
const val STRIPE_SECRET = "sk_live_XXXX"
```

Even if:

- It’s in `BuildConfig`
- It’s in native (NDK) code
- It’s obfuscated

➡️ **It can still be extracted.**

### ✅ What Production Apps Actually Do

### 🥇 Option 1: Backend Token Exchange (Best)

- App authenticates user
- Backend issues short-lived token
- App never sees real secrets

This is how:

- Payments
- Analytics
- Auth
- Premium APIs

are handled in real products.

### 🥈 Option 2: Remote Config / Feature Flags

- Fetch values at runtime
- Rotate without app updates
- Kill switches for emergencies

Used for:

- Feature toggles
- Non-critical keys
- Experimentation

### 🥉 Option 3: CI-Injected Values (Use Carefully)

CI:

```c
export API_KEY=staging_key_xxx
```

Gradle:

```c
buildConfigField(
    "String",
    "API_KEY",
    "\"${System.getenv("API_KEY")}\""
)
```

⚠️ Still visible in APK — acceptable only for:

- Staging
- Non-sensitive keys
- Internal builds

### 🧪 A Safe, Real-World Pattern

```c
android {
    defaultConfig {
        buildConfigField(
            "Boolean",
            "ENABLE_LOGGING",
            project.findProperty("ENABLE_LOGGING")?.toString() ?: "false"
        )
    }
}
```

`gradle.properties`

```c
ENABLE_LOGGING=true
```

Result:

- Configurable per environment
- No code changes
- CI-friendly
- Zero secrets leaked

### 🏢 How Real Companies Do This

- ❌ No production secrets in app
- ✅ Environment-based BuildConfig
- ✅ Backend-controlled tokens
- ✅ CI injects only non-critical values
- ✅ `local.properties` for dev convenience

> *Security is not hiding secrets —* ***it’s never shipping them.***

### 🧭 TL;DR

- `gradle.properties` → build behavior
- `BuildConfig` → compile-time constants
- `local.properties` → dev-only values
- Real secrets must live on the backend

### 🔮 Coming Next

👉 **Speeding Up Your Gradle Builds: Caching, Parallel Execution, and Incremental Builds**

We’ll make Gradle **fast**, not just correct.

👏 *If this helped you avoid leaking keys, clap and follow the “Gradle for Android” series.*

## 💬 Final Word

By the end of this [Demystifying Gradle](https://medium.com/@er.vprashant/understanding-gradle-in-android-the-complete-roadmap-for-developers-17e5d09040f6) series, you’ll no longer be “just editing build.gradle.”  
You’ll **own it** — confidently creating, debugging, and optimizing Gradle builds that work for *you*, not against you.

So grab your coffee, hit that **Follow** button, and let’s decode Gradle — one build at a time. ⚙️💚

To make Medium work, we log user data. By using Medium, you agree to our [Privacy Policy](https://policy.medium.com/medium-privacy-policy-f03bf92035c9), including cookie policy.