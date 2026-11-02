plugins {
    alias(libs.plugins.android.library)
}

// Phase 2.7: this module exists for exactly one reason — it is the
// only place in the project allowed to reference the framework
// android.hardware.fingerprint.FingerprintManager at compile time.
//
// compileSdk 37 (the app module's) REMOVED the FingerprintManager
// stubs from the SDK (the class was dropped from the platform SDK
// in Android C), so the app module cannot compile against it; the
// Phase 2.5 availability probe already had to reach it reflectively
// for the same reason. Compiling this bridge against API 36, where
// the stubs still exist, gives us a type-safe direct call instead.
//
// Runtime safety: the app only invokes this module below API 28
// (the executor seam in BiometricUnlock.kt), where the framework
// class is present and public — it is the API Noma-era plugins use
// on the Galaxy J7 Prime.
android {
    namespace = "app.nota.legacyfingerprint"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
