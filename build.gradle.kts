plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.4.1"
    id("org.graalvm.buildtools.native") version "0.10.4"
    application
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

graalvmNative {
    binaries {
        named("main") {
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.github.jdepends.MainKt"
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("jdepends")
            mainClass.set("io.github.jdepends.MainKt")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-run-time=org.fusesource.jansi,org.fusesource.hawtjni"
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
