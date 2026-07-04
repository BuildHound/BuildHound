plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":lib-a"))
    implementation(project(":lib-b"))
}

application {
    mainClass.set("dev.buildhound.overheadfixture.AppKt")
}

kotlin { jvmToolchain(21) }
