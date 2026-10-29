import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing material is never committed. Provide it in local.properties or
// keystore.properties; without it, release builds are simply unsigned.
val signingProperties = Properties().apply {
    listOf("local.properties", "keystore.properties").forEach { name ->
        val file = rootProject.file(name)
        if (file.exists()) file.inputStream().use { load(it) }
    }
}

val releaseStorePath = signingProperties.getProperty("RELEASE_STORE_FILE")
val hasReleaseKey = !releaseStorePath.isNullOrBlank() && rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.accio.whisperbubble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.accio.whisperbubble"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = signingProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = signingProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
