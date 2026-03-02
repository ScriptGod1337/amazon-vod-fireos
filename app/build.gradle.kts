plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.scriptgod.fireos.avod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scriptgod.fireos.avod"
        minSdk = 25
        targetSdk = 34
        versionCode = (findProperty("versionCodeOverride") as? String)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionNameOverride") as? String) ?: "1.0-dev"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("RELEASE_STORE_FILE")
                ?: "/home/vscode/amazon-vod-android/release.keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "firetv_store"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "firetv"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "firetv_store"
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        getByName("debug") {
            isDebuggable = true
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.shimmer)
    testImplementation(libs.junit4)
}
