/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

pluginManagement {
    includeBuild("build-logic")
    // BuildHound lives in this repository two levels up; the included build supplies
    // the `dev.buildhound` settings plugin without a published artifact.
    includeBuild("../..")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.buildhound")
}

buildhound {
    // Per-build HTML report under build/buildhound/ (on by default; explicit for the demo).
    htmlReport {
        enabled = true
    }
    // Local BuildHound stack from deploy/compose.yaml:
    //   docker compose -f ../../deploy/compose.yaml up --build
    server {
        url = "http://localhost:8080"
        // The committed local-dev token from deploy/compose.yaml (project "pilot").
        // Real deployments must supply the token via the environment only.
        token = providers.environmentVariable("BUILDHOUND_TOKEN")
            .orElse("buildhound-local-dev-token")
    }
    // Builds of this sample are LOCAL mode (no CI env). Uploads go to localhost only,
    // so the ~/.buildhound/optin marker is not required for the demo.
    localBuilds {
        enabled = true
        requireOptInFile = false
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "nowinandroid"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")
include(":app-nia-catalog")
include(":benchmarks")
include(":core:analytics")
include(":core:common")
include(":core:data")
include(":core:data-test")
include(":core:database")
include(":core:datastore")
include(":core:datastore-proto")
include(":core:datastore-test")
include(":core:designsystem")
include(":core:domain")
include(":core:model")
include(":core:navigation")
include(":core:network")
include(":core:notifications")
include(":core:screenshot-testing")
include(":core:testing")
include(":core:ui")

include(":feature:foryou:api")
include(":feature:foryou:impl")
include(":feature:interests:api")
include(":feature:interests:impl")
include(":feature:bookmarks:api")
include(":feature:bookmarks:impl")
include(":feature:topic:api")
include(":feature:topic:impl")
include(":feature:search:api")
include(":feature:search:impl")
include(":feature:settings:impl")
include(":lint")
include(":sync:work")
include(":sync:sync-test")
include(":ui-test-hilt-manifest")

// Upstream requires 17+; the BuildHound plugin (JVM 21 bytecode) raises the floor to 21.
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    """
    This sample requires JDK 21+ (BuildHound plugin floor) but it is currently using JDK ${JavaVersion.current()}.
    Java Home: [${System.getProperty("java.home")}]
    https://developer.android.com/build/jdks#jdk-config-in-studio
    """.trimIndent()
}
