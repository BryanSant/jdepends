# jdepends

A fast, lightweight CLI tool to check for dependency and plugin updates in Java/Kotlin projects. It supports Gradle (Groovy & Kotlin DSL), Maven, and Version Catalogs.

`jdepends` scans your project configuration and queries Maven Central and the Gradle Plugin Portal to find the latest available versions, highlighting stable releases and showing recent versions.

## Features

- **Project Scanning**: Automatically detects dependencies in `build.gradle`, `build.gradle.kts`, `pom.xml`, and `gradle/libs.versions.toml`.
- **Transitive Support**: Use `-a` or `--all` to resolve and check the entire dependency tree (requires `gradle` or `mvn` to be installed).
- **Direct Queries**: Check any specific coordinate directly: `jdepends org.jetbrains.kotlinx:kotlinx-serialization-json`.
- **Plugin Support**: Detects and checks Gradle plugins.
- **Stable Version Detection**: Smartly identifies the latest stable release versus pre-release versions (RC, Milestone, Alpha/Beta).
- **Colorized Output**: Clear, colorized console output (via Jansi).
- **Native Executable**: Can be compiled to a native binary using GraalVM for near-instant startup.

## Installation

### Prerequisites

- Java 25 or higher (for building/running the JAR).
- (Optional) GraalVM for native compilation.

### Build from Source

```bash
git clone https://github.com/BryanSant/jdepends.git
cd jdepends
./gradlew build
```

The executable shadow JAR will be in `build/libs/jdepends-1.0.0.jar`.

### Native Compilation

To build a native executable:

```bash
./gradlew nativeCompile
```

The binary will be available at `build/native/nativeCompile/jdepends`.

## Usage

Run `jdepends` inside a Java/Kotlin project directory:

```bash
# Check declared dependencies and plugins
jdepends

# Check ALL dependencies (including transitive)
jdepends --all
```

Or check a specific dependency:

```bash
jdepends io.micronaut:micronaut-http-server-netty
```

### Output Example

```text
🔌 org.jetbrains.kotlin.jvm:2.1.0 [2.1.0]
📦 org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3 [1.8.0, 1.8.0-RC, 1.7.3, ...]
```

## License

This project is Open Source released under the [MIT License](LICENSE).
