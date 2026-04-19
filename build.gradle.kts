plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

group = "io.github.jdepends"
version = "1.0.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.fusesource.jansi:jansi:2.4.3")
}

application {
    mainClass.set("io.github.jdepends.MainKt")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.distZip {
    dependsOn(tasks.shadowJar)
}

tasks.distTar {
    dependsOn(tasks.shadowJar)
}

tasks.startScripts {
    dependsOn(tasks.shadowJar)
}

tasks.named("startShadowScripts") {
    dependsOn(tasks.jar)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.github.jdepends.MainKt"
    }
}
