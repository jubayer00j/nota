import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

// Release signing comes from the environment in CI (GitHub Actions secrets):
//   NOTA_RELEASE_STORE_FILE / NOTA_RELEASE_STORE_PASSWORD /
//   NOTA_RELEASE_KEY_ALIAS  / NOTA_RELEASE_KEY_PASSWORD
// Locally, drop a gitignored keystore.properties in the repo root with the same
// keys (storeFile/storePassword/keyAlias/keyPassword). With neither present the
// release build stays unsigned so day-to-day local builds always work.
val ciStoreFile = System.getenv("NOTA_RELEASE_STORE_FILE")
val localProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val releaseStoreFile = ciStoreFile ?: localProps.getProperty("storeFile")
val releaseStorePassword = System.getenv("NOTA_RELEASE_STORE_PASSWORD")
    ?: localProps.getProperty("storePassword")
val releaseKeyAlias = System.getenv("NOTA_RELEASE_KEY_ALIAS")
    ?: localProps.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("NOTA_RELEASE_KEY_PASSWORD")
    ?: localProps.getProperty("keyPassword")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = <redacted>
                keyAlias = releaseKeyAlias
                keyPassword = <redacted>
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
