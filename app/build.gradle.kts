plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.abhinavxt.newsforge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.abhinavxt.newsforge"
        minSdk = 30
        targetSdk = 37
        // Overridden by the release workflow from the tag (v1.4.2 -> 1.4.2, 10402), so a
        // release build always says which tag it came from. Local builds keep these.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment, never from the repository: CI decodes the
    // keystore from a secret into a file and points these at it. Without them a release
    // build is left unsigned, which keeps local `assembleRelease` working as before.
    val keystorePath = providers.environmentVariable("NEWSFORGE_KEYSTORE").orNull
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("NEWSFORGE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NEWSFORGE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NEWSFORGE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    // Export the schema so future migrations have a reference point to diff against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    // Declared explicitly rather than relied on transitively via lifecycle/room-ktx:
    // FeedFetcher uses suspendCancellableCoroutine directly.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    // Reader mode: pulls the article body out of a publisher page.
    implementation(libs.jsoup)
    // Article images in the reader. The OkHttp fetcher is picked up automatically.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}