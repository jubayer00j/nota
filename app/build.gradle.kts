import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nota.notes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.bundles.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Phase 0 editor spike (spec §5) — the go/no-go gate for the real editor.
    implementation(libs.richeditor.compose)

    testImplementation(libs.junit)
}
