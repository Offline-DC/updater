import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The self-update "updater": a separate, tiny APK whose one screen stays in
// front while the launcher's own package is being replaced (nothing in the
// launcher's package can draw then). See
// .agents/planning/2026-10-28-self-update-no-dark-screen/plan.md.
//
// Deliberately no Compose, no AndroidX, no dependencies: plain Views keep the
// APK in the tens of KB, since it will ship inside the launcher's APK.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.offlineinc.dumbdownlauncher.updater"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.offlineinc.dumbdownlauncher.updater"
        // Same floor as :app. Fonts load from assets, not res/font, so nothing
        // here needs API 26.
        minSdk = 24
        targetSdk = 36
        // The launcher will check this before using the updater (plan, Phase 2).
        // Bump it whenever the screen or its intent contract changes.
        // 2: backstop once the launcher has been replaced (2026-10-29).
        // 4: WarmReceiver, so the launcher can start this process while the
        //    update downloads (2026-11-09). Older updaters still work: the
        //    broadcast goes nowhere and the screen cold-starts as before, so
        //    the launcher's MIN_UPDATER_VERSION_CODE does not move.
        versionCode = 4
        versionName = "4"
    }

    // Same release key as :app, read from the same environment variables, so
    // both APKs are signed alike.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
