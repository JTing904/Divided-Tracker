import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.dividendstream"
version = "1.0.18"

kotlin {
    jvmToolchain(21)
}

/**
 * The Android app and the desktop app are the same application.
 *
 * Rather than copy 37 files and let the two drift, the desktop build compiles the Android
 * module's sources directly. Only the handful of files that genuinely touch the Android
 * framework are excluded here and reimplemented in src/main/kotlin under the same package
 * and the same type names, so everything downstream compiles unchanged.
 *
 * If a file is added to the Android app that needs a desktop counterpart, it will fail to
 * compile here — which is the intended signal, not an inconvenience.
 *
 * The exclusions below are matched against the relative path inside *every* source directory,
 * not just the Android one. That is why each desktop replacement is named `Desktop*.kt` while
 * keeping the original package and type names: a file called `SessionStore.kt` in this module
 * would be excluded along with the Android one.
 */
val androidSources = file("../android/app/src/main/java")

sourceSets {
    main {
        kotlin.srcDir(androidSources)
        kotlin.exclude(
            // Android entry points; replaced by Main.kt and DesktopAppContainer.kt.
            "com/dividendstream/app/MainActivity.kt",
            "com/dividendstream/app/DividendStreamApp.kt",
            // DataStore-backed; replaced by file-backed equivalents.
            "com/dividendstream/app/data/local/SessionStore.kt",
            "com/dividendstream/app/data/local/SnapshotCache.kt",
            "com/dividendstream/app/data/local/PendingLedgerStore.kt",
            "com/dividendstream/app/data/local/PendingPurchaseStore.kt",
            "com/dividendstream/app/data/local/SettingsStore.kt",
            // Reaches the container through LocalContext.
            "com/dividendstream/app/ui/AppViewModelProvider.kt",
            // Credential Manager is Android-only; the desktop opens a browser instead.
            "com/dividendstream/app/ui/auth/GoogleSignInLauncher.kt",
            // Uses navigation-compose and a bottom bar; desktop gets a sidebar instead.
            "com/dividendstream/app/ui/navigation/DividendStreamRoot.kt",
            // Touches the Activity window to tint the status bar.
            "com/dividendstream/app/ui/theme/Theme.kt",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // JetBrains' multiplatform builds of the AndroidX libraries. These supply
    // androidx.lifecycle.ViewModel, viewModelScope and collectAsStateWithLifecycle, which is
    // why the eight ViewModels and nine screens compile without modification.
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // The backend, built from source via the composite build in settings.gradle.kts.
    // Its own dependencies are `implementation`, so they reach the runtime classpath but not
    // this module's compile classpath; the launcher needs Spring's API at compile time, hence
    // the explicit entries below, pinned to the same Boot version the backend builds against.
    implementation("com.dividendstream:dividend-stream-backend:0.1.0")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-context")

    // A real PostgreSQL, run in-process against the user's profile directory. On the backend
    // this is a test-only dependency; the desktop build is the one place it ships.
    implementation("io.zonky.test:embedded-postgres:2.2.2")
    implementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.10.0"))
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64")
}

compose.desktop {
    application {
        mainClass = "com.dividendstream.desktop.MainKt"

        // So the running app can report its own version without a third place to bump it.
        // Applies to `run` and to the packaged binary alike.
        jvmArgs += "-Ddividendstream.version=$version"

        // jlink and jpackage come from the JDK named here. The JetBrains runtime bundled with
        // Android Studio builds the code fine but ships no jpackage, so packaging is pointed
        // at a full JDK via -Pdividendstream.jpackage.home (see package-desktop.cmd).
        (project.findProperty("dividendstream.jpackage.home") as String?)?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Dividend Stream"
            packageVersion = "1.0.17"
            description = "Watch your expected dividends accumulate in real time"
            vendor = "Dividend Stream"

            // jlink strips everything unreferenced, and Spring finds its dependencies
            // reflectively, so the modules it needs have to be named explicitly.
            modules(
                "java.sql",
                "java.naming",
                "java.management",
                "java.instrument",
                "java.security.jgss",
                "java.desktop",
                "jdk.unsupported",
                // The loopback listener desktop Google sign-in redirects back to.
                "jdk.httpserver",
                "jdk.crypto.ec",
            )

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "9C1D5C6E-7B2A-4F51-9E3B-2A6D4F81C0A7"
            }
        }
    }
}
