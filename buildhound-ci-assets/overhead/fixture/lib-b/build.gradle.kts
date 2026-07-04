plugins { kotlin("jvm") }

repositories { mavenCentral() }

dependencies {
    implementation(project(":lib-a"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
