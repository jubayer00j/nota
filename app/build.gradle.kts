import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Baseline Profiles infrastructure (Phase 1a). The plugin only wires
    // the packaging/generation tasks; actually GENERATING a profile still
    // requires a device/emulator session with a generator target, so no
    // profile is bundled yet and none is hand-written.
    alias(libs.plugins.androidx.baselineprofile)
}

// Release signing is NOT configured here: CI passes AGP injected-signing
// properties on the Gradle command line (the same mechanism Noma's CI uses
// for its debug keystore), fed from GitHub Actions secrets:
//   -Pandroid.injected.signing.store.file / .store.password /
//    .key.alias / .key.password
// Local release builds are therefore unsigned by default; the signed APK/AAB
// of record always comes from CI. (AGP 9's signing DSL also rejects plain
// password property assignment — injected signing sidesteps that entirely.)

android {
    namespace = "app.nota.notes"
    // compileSdk 37: the current libraries (Compose BOM 2026.09, navigation 2.10,
    // rich-editor 1.2.1) are built against API 37 and refuse less. This is a
    // compile-time floor only — targetSdk stays 36 per spec §1 (runtime
    // behaviour opt-ins unchanged).
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nota.notes"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // The rich-editor state touches a few Android stubs on the JVM;
            // default-returning stubs keep unit tests hermetic.
            isReturnDefaultValues = true
            // Robolectric (Phase 1b repository test) needs the merged
            // Android resources on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.bundles.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Phase 1a: persisted app preferences (theme choices in Settings).
    implementation(libs.androidx.datastore.preferences)

    // Phase 1a: installs any Baseline Profile bundled with the APK at
    // first launch (profile generation itself needs a device session).
    implementation(libs.androidx.profileinstaller)

    // Phase 0 editor spike (spec §5) — the go/no-go gate for the real editor.
    implementation(libs.richeditor.compose)

    // Phase 1b: note-card cover images from local attachment files only —
    // coil-compose without any network fetcher module.
    implementation(libs.coil.compose)

    // Phase 2a App Lock: BiometricPrompt (fingerprint convenience gate)
    // and ProcessLifecycleOwner (background/foreground re-lock tracking).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)

    // Phase 2.7: direct framework fingerprint bridge for API < 28
    // (the module compiles against API 36 because compileSdk 37
    // removed the framework FingerprintManager stubs).
    implementation(project(":legacyfingerprint"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
