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
    // Phase 3a: Firebase (identity only — spec §2). The plugin reads
    // app/google-services.json, which is NEVER committed (Noma
    // precedent): .gitignore excludes it, CI materialises it from the
    // GOOGLE_SERVICES_JSON_BASE64 secret, and local builds use the
    // parked copy placed next to this file by hand.
    alias(libs.plugins.google.services)
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
        versionCode = 30
        versionName = "0.17.0"
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

// --- Test execution bounds + CI sharding (Phase 3.6 close-out) ---
//
// The suite outgrew Gradle's defaults: with no explicit heap and
// forkEvery = 0, all 300+ tests ran in ONE worker JVM capped at the
// 512 MB default heap. The Robolectric + Compose-UI classes hold
// large sandbox classloaders, and by late in the suite the worker
// was spending its life in GC; on CI the test step kept dying at
// ~6 minutes with exit 143 (SIGTERM — the worker killed under
// memory pressure). Bounds below give each worker real headroom
// and cap how much state any one worker accumulates.
//
// CI also shards the run into two parallel jobs (.github/workflows/
// android.yml) via -PnotaTestShard=fast|ui: the Compose-UI classes
// (full editor composition, native graphics, gesture injection)
// run in their own job so neither job's test step lasts ~7 minutes.
// Add new Compose-UI test classes to uiTestClasses so they land in
// the UI shard. With no shard property (local runs) the full suite
// runs unfiltered.
val notaUiTestClasses = listOf(
    "app.nota.feature.editor.EditorBlockDeletionUiTest",
    "app.nota.feature.editor.EditorBlockDragUiTest",
    "app.nota.feature.editor.EditorReadModeUiTest",
    "app.nota.feature.editor.EditorRefinementUiTest",
    "app.nota.feature.notes.NotesSwipeGhostUiTest",
    "app.nota.feature.settings.BeginnerAiSetupUiTest",
    "app.nota.feature.export.NoteExportUiTest",
    "app.nota.feature.settings.SettingsScreenPolishUiTest",
)

tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    forkEvery = 25
    when (providers.gradleProperty("notaTestShard").orNull) {
        "fast" -> filter {
            notaUiTestClasses.forEach { excludeTestsMatching(it) }
        }
        "ui" -> filter {
            notaUiTestClasses.forEach { includeTestsMatching(it) }
        }
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
    // Phase 3a: the account avatar is the one remote image Nota loads
    // (the signed-in user's Google profile photo) — Coil needs its
    // network fetcher for http(s) models; note attachments stay
    // local files as before.
    implementation(libs.coil.network.okhttp)
    // Phase 3b: Drive authorization (drive.file) + REST + scheduled work.
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    // Phase 2a App Lock: BiometricPrompt (fingerprint convenience gate)
    // and ProcessLifecycleOwner (background/foreground re-lock tracking).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)

    // Phase 2.7: direct framework fingerprint bridge for API < 28
    // (the module compiles against API 36 because compileSdk 37
    // removed the framework FingerprintManager stubs).
    implementation(project(":legacyfingerprint"))

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    // Phase 3.5 (D1): Compose UI tests under Robolectric — the ghost-row
    // regression is a composition-layer defect, so its proof must drive
    // the real list composable, not the data layer.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Declares ComponentActivity in the debug manifest merge, which is
    // what Robolectric resolves activities against — without it the
    // compose test rule cannot launch its host activity in unit tests.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Phase 3a accounts: Firebase Auth (identity ONLY — notes never
    // leave the device except in the user's own Drive archive), and
    // Credential Manager for the Google sign-in flow (spec §2).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}
