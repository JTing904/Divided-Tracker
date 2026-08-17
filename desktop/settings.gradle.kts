pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // JetBrains' multiplatform lifecycle artifacts depend on the real androidx
        // annotation/arch-core modules, which are only published to Google's repository.
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "dividend-stream-desktop"

// The desktop app runs the backend inside its own JVM, so it builds the backend from source
// rather than depending on a published artifact.
includeBuild("../backend")
