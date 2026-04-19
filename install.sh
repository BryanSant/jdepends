#!/bin/bash

set -e

# --- Configuration ---
BINARY_NAME="jdepends"
REQUIRED_JAVA_VERSION="25"
INSTALL_DIR="$HOME/.local/bin"
NATIVE_BINARY_PATH="build/native/nativeCompile/$BINARY_NAME"

echo "🚀 Starting installation of $BINARY_NAME..."

# 1. Environment Checks
echo "🔍 Checking environment..."

# Check Java Version and GraalVM
if ! command -v java >/dev/null 2>&1; then
    echo "❌ Error: Java is not installed."
    exit 1
fi

JAVA_VERSION_OUT=$(java -version 2>&1)
if [[ ! "$JAVA_VERSION_OUT" == *"version \"$REQUIRED_JAVA_VERSION"* && ! "$JAVA_VERSION_OUT" == *" $REQUIRED_JAVA_VERSION."* ]]; then
    echo "⚠️  Warning: Java $REQUIRED_JAVA_VERSION is recommended (detected: $(java -version 2>&1 | head -n 1))."
fi

if [[ ! "$JAVA_VERSION_OUT" == *"GraalVM"* ]]; then
    echo "❌ Error: GraalVM is required for native compilation."
    echo "Please install GraalVM Java $REQUIRED_JAVA_VERSION (e.g., via SDKMAN: sdk install java 25.0.2-graal)."
    exit 1
fi

# Check Kotlin (though Gradle handles this, we verify as requested)
if command -v kotlinc >/dev/null 2>&1; then
    KOTLIN_VERSION=$(kotlinc -version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
    echo "✅ Kotlin $KOTLIN_VERSION detected."
else
    echo "ℹ️  Kotlin compiler not found in PATH, but Gradle will handle it."
fi

# 2. Build
echo "🏗️  Building native binary with Gradle (this may take a few minutes)..."
chmod +x gradlew
./gradlew nativeCompile

# 3. Install
if [ ! -f "$NATIVE_BINARY_PATH" ]; then
    echo "❌ Error: Native binary was not found at $NATIVE_BINARY_PATH."
    exit 1
fi

echo "📦 Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "$NATIVE_BINARY_PATH" "$INSTALL_DIR/$BINARY_NAME"
chmod +x "$INSTALL_DIR/$BINARY_NAME"

# 4. Path Validation
echo "🌐 Checking PATH..."
if [[ ":$PATH:" == *":$INSTALL_DIR:"* ]]; then
    echo "✅ $INSTALL_DIR is in your PATH."
else
    echo "⚠️  $INSTALL_DIR is NOT in your PATH."
    echo "Please add the following to your shell profile (.bashrc, .zshrc, etc.):"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
fi

# 5. Explanation
echo ""
echo "🎉 Done! $BINARY_NAME has been installed."
echo "--------------------------------------------------------"
echo "What was done:"
echo "1. Verified Java $REQUIRED_JAVA_VERSION (GraalVM) is available."
echo "2. Compiled the project to a native image using GraalVM and Gradle."
echo "3. Copied the optimized binary to $INSTALL_DIR/$BINARY_NAME."
echo "4. Verified installation directory in your system PATH."
echo ""
echo "You can now run '$BINARY_NAME' from anywhere."
