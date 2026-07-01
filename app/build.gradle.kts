plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wici.androidalbumdemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wici.androidalbumdemo"
        minSdk = 33
        targetSdk = 34
        versionCode = 4
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Native "Sign in with Google" via Credential Manager. SupabaseAuth exchanges the
    // Google ID token with Supabase using the app's existing HttpURLConnection style.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
